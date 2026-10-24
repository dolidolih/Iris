package party.qwer.iris

import net.zetetic.database.sqlcipher.SQLiteDatabase
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.locks.ReentrantReadWriteLock

/**
 * Shared snapshot machinery for the SQLCipher readers (crypto_database /
 * crypto_user_database). One instance per database file.
 *
 * Why this exists (ISSUE-02 / ISSUE-31 / ISSUE-32 all live in the two readers):
 *  - **Freshness (ISSUE-02)**: the old `if (db != null) return true` meant the first
 *    decrypted working copy was used for the whole life of the process. `use()` re-checks
 *    staleness (source mtime advanced, or TTL expired) and re-opens so nicknames and
 *    `chat_log_search` stay current on a long-lived daemon.
 *  - **Location (ISSUE-31)**: working copies go to a *private* dir — the app's own
 *    `filesDir`, or, for the root daemon (no app Context), a mode-0700 dir we own. A
 *    world-accessible location is refused outright instead of being used for decrypted data.
 *  - **Close/query race (ISSUE-32)**: a read-write lock lets a refresh/close wait for
 *    in-flight queries instead of nulling `db` out from under them (`already closed object`).
 *
 * Thread-safety: `use()` is safe from any thread; refresh/close are serialized by their
 * own locks so a slow key derivation never blocks a reader on a healthy snapshot.
 */
internal class CryptoDbSnapshot(
    private val label: String,
    private val dbName: String,
    private val subDir: String
) {
    @Volatile private var db: SQLiteDatabase? = null
    @Volatile private var openedAtMs: Long = 0
    @Volatile private var copiedSourceMtime: Long = 0

    private val rw = ReentrantReadWriteLock()
    private val refreshLock = ReentrantLock()

    companion object {
        /** Re-check the source after this long even if its mtime looks unchanged. */
        private const val STALE_TTL_MS = 30_000L

        /** 평문 working copy 가 남아도 되는 최대 — 넘기면 sweep 으로 지운다. */
        private const val MAX_WORKING_COPY_AGE_MS = 6 * 60 * 60 * 1000L
    }

    /** True when the open snapshot needs to be re-copied/re-opened. */
    private fun stale(): Boolean =
        db == null || System.currentTimeMillis() - openedAtMs > STALE_TTL_MS

    /**
     * Opens (or refreshes) the snapshot and runs [block] on it. Returns null when the
     * database/key/working copy is unavailable — callers then fall back as before.
     */
    fun <T> use(keyProvider: () -> ByteArray?, block: (SQLiteDatabase) -> T): T? {
        var attempt = 0
        while (attempt < 2) {
            attempt++
            if (stale()) refresh(keyProvider)
            if (db == null) return null
            rw.readLock().lock()
            try {
                val current = db ?: return null
                return block(current)
            } catch (e: Exception) {
                // A concurrent close/refresh can still win the race against the handle we
                // just read. Discard the snapshot once and retry; a second failure propagates.
                if (attempt > 1) throw e
                System.err.println("$label: query failed (${e.javaClass.simpleName}: ${e.message}) — refreshing snapshot")
                close()
            } finally {
                rw.readLock().unlock()
            }
        }
        return null
    }

    /** Opens the snapshot without running a query (kept for `open()`-style callers). */
    fun ensure(keyProvider: () -> ByteArray?): Boolean {
        if (!stale()) return db != null
        refresh(keyProvider)
        return db != null
    }

    /** Drop the snapshot; the next use() re-derives the key and re-copies. */
    fun close() {
        refreshLock.lock()
        try {
            rw.writeLock().lock()
            try {
                db?.close()
            } catch (t: Throwable) {
                System.err.println("$label: close failed: $t")
            } finally {
                db = null
                openedAtMs = 0
                copiedSourceMtime = 0
                rw.writeLock().unlock()
            }
        } finally {
            refreshLock.unlock()
        }
    }

    /** Key derivation + working-copy refresh, serialized so only one thread pays for it. */
    private fun refresh(keyProvider: () -> ByteArray?): SQLiteDatabase? {
        refreshLock.lock()
        try {
            // Another thread may already have refreshed while we waited for the lock.
            if (db != null && !stale()) return db
            if (!SqlCipherNative.ensureLoaded()) {
                System.err.println("$label: libsqlcipher unavailable")
                return null
            }
            val key = runCatching(keyProvider).getOrElse {
                System.err.println("$label: key derivation failed: $it")
                return null
            } ?: return null
            val workFile = copyToWorkingCopy() ?: return null
            val opened = try {
                SQLiteDatabase.openDatabase(
                    workFile.absolutePath, key, null,
                    SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
                    null
                )
            } catch (e: Throwable) {
                // Most likely a rotated-out key ("file is not a database") — say so, the
                // message that used to appear here was indistinguishable from a missing file.
                System.err.println("$label: open failed (key mismatch or unreadable db): $e")
                return null
            }
            // ISSUE-32: wrong key 와 corrupted file 이 모두 "file is not a database" 로 나온다.
            // catalog 를 읽어 미리 판별하면 이후 caller 질의가 이상한 오류로 헤매는 대신
            // "this key does not open it" 로그에 남는다.
            if (!verifyReadable(opened, workFile)) {
                runCatching { opened.close() }
                return null
            }
            rw.writeLock().lock()
            try {
                runCatching { db?.close() }
                db = opened
                openedAtMs = System.currentTimeMillis()
                return opened
            } finally {
                rw.writeLock().unlock()
            }
        } finally {
            refreshLock.unlock()
        }
    }

    /**
     * Snapshots the live (WAL-mode) database into [workingDir] and returns the main file.
     * Kakao keeps writing to the live files, so read-only access without the -shm/-wal is
     * unsafe; a copy is the proven path. The copy is redone when the source timestamp
     * advances, so repeated queries on an unchanged source cost nothing.
     */
    private fun copyToWorkingCopy(): File? {
        return try {
            val srcMain = File(PathUtils.getAppPath(), "databases/$dbName")
            if (!srcMain.isFile) {
                System.err.println("$label: source not found: $srcMain")
                return null
            }
            val workDir = workingDir() ?: return null
            val dstMain = File(workDir, dbName)
            val dstWal = File(workDir, "$dbName-wal")
            val srcWal = File(srcMain.parentFile, "$dbName-wal")
            val sourceMtime = maxOf(srcMain.lastModified(), srcWal.lastModified())
            if (!dstMain.isFile || sourceMtime > copiedSourceMtime) {
                copyInto(srcMain, dstMain)
                if (srcWal.isFile) copyInto(srcWal, dstWal) else dstWal.delete()
                copiedSourceMtime = sourceMtime
            }
            dstMain
        } catch (e: Throwable) {
            System.err.println("$label: copyToWorkingCopy failed: $e")
            null
        }
    }

    /**
     * Private working-copy directory. Never returns a world-accessible dir: the copies
     * are fully decrypted nickname/chat data and `/data/local/tmp` is writable by every
     * unprivileged app on several ROMs. Iris is always the root `app_process` daemon, so
     * there is no app `filesDir` branch — [WorkDirs] enforces owner-only (0700).
     */
    private fun workingDir(): File? {
        val hadContents = WorkDirs.root.isDirectory && runCatching {
            WorkDirs.root.listFiles()?.isNotEmpty() == true
        }.getOrDefault(false)
        val created = !WorkDirs.root.exists()
        val dir = WorkDirs.sub(subDir) ?: run {
            System.err.println("$label: cannot create private working dir under ${WorkDirs.root}")
            return null
        }
        if (created || hadContents || hasLeftovers(dir)) sweepStaleWorkingFiles(dir)
        return dir
    }

    /** 남겨둔 잔여물이 있는 지시점 (sweep 은 있을때만 돈다). */
    private fun hasLeftovers(dir: File): Boolean = runCatching { dir.listFiles()?.isNotEmpty() == true }.getOrDefault(false)


    /**
     * ISSUE-31: working copy 는 전부 평문으로 디crypt된 데이터다. 설치/업그레이드가 남긴
     * 잔여물이 수일간 그자리에 남아 읽히거나(공유 디바이스) 다른 DB의 사본과 같이 두지 않도록,
     * working dir 를 새로 만들거나 비어있지 않을때 한 번 sweep 한다. 우리 DB 파일과 `-wal`
     * /`-shm`/`.tmp`/`.bak` 만 남기고 지운다.
     */
    private fun sweepStaleWorkingFiles(dir: File) {
        val keepPrefixes = listOf(dbName, "$dbName-wal", "$dbName-shm", "$dbName.tmp", "$dbName.bak")
        val sweepStart = System.currentTimeMillis() - MAX_WORKING_COPY_AGE_MS
        runCatching {
            dir.listFiles()?.forEach { f ->
                if (!f.isFile) return@forEach
                val ours = keepPrefixes.any { f.name.startsWith(it) }
                if (!ours || f.lastModified() < sweepStart) {
                    if (f.delete()) {
                        println("$label: swept stale working copy ${f.name}")
                    }
                }
            }
        }.onFailure { println("$label: working copy sweep failed: $it") }
    }

    /** working copy 를 즉시 지운다 (shutdown wiring 는 Main.kt 소유라 아직 호출자 없음). */
    fun deleteWorkingCopy() {
        runCatching {
            val dir = workingDir() ?: return
            dir.listFiles()?.forEach { if (it.isFile) it.delete() }
        }
    }

    /**
     * Opening succeeds even with the wrong key (SQLCipher does not know yet) — the first page
     * decrypt is what fails. Read the catalog once so the failure is attributed now, at the
     * one place that knows the key came from (keystore blob / seed file).
     */
    private fun verifyReadable(database: SQLiteDatabase, workFile: File): Boolean = try {
        database.rawQuery("SELECT count(*) FROM sqlite_master", null).use { it.moveToFirst() }
        true
    } catch (e: Throwable) {
        System.err.println(
            "$label: ${workFile.name} opened but its catalog is unreadable with the derived key " +
                "(${e.message}) — wrong/rotated key or a copy taken mid-write"
        )
        false
    }

    private fun copyInto(src: File, dst: File) {
        RandomAccessFile(src, "r").use { raf ->
            dst.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                val size = raf.length()
                var pos = 0L
                while (pos < size) {
                    val n = raf.read(buf, 0, minOf(buf.size.toLong(), size - pos).toInt())
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    pos += n
                }
            }
        }
        dst.setLastModified(src.lastModified())
        // Decrypted content — keep the copy owner-only even if the dir slipped through.
        runCatching {
            dst.setReadable(false, false); dst.setReadable(true, true)
            dst.setWritable(false, false); dst.setWritable(true, true)
        }
    }
}
