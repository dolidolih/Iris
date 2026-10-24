package party.qwer.iris

import java.io.File
import java.io.RandomAccessFile

/**
 * Reads `userDbPassPhraseSalt` (Long) from KakaoTalk's DataStore protobuf file.
 *
 * KakaoTalk stores the passphrase seed for crypto_user_database as a Feature_DataStore
 * PreferenceMap entry:
 *   PreferenceMap    { repeated PreferenceEntry map = 1 }
 *   PreferenceEntry  { string key = 1; PreferenceValue value = 2 }
 *   PreferenceValue  { ... int64 (varint, wiretype 0) ... }
 *
 * Verified against the live device: the stored Long is what derives K1
 * (a84bf2c8…c192de), which decrypts crypto_user_database.
 */
object UserDbSaltStore {
    private const val KEY = "userDbPassPhraseSalt"
    private const val FILE = "Feature_DataStore.pref.preferences_pb"
    private val CANDIDATES = listOf("files/datastore/$FILE", "files/$FILE", "datastore/$FILE")

    /** Re-stat the source file after this long even when it looks unchanged (ISSUE-02). */
    private const val SEED_TTL_MS = 5 * 60 * 1000L

    @Volatile
    private var cachedSeed: Long? = null

    @Volatile
    private var loadedAtMs: Long = 0

    @Volatile
    private var sourceFile: File? = null

    @Volatile
    private var sourceMtime: Long = -1

    /** cached seed, reading the DataStore file if absent. null if unavailable. */
    fun getSeed(): Long? {
        val cached = cachedSeed
        if (cached != null && !expired()) return cached
        return loadSeed()?.also { cachedSeed = it }
    }

    /**
     * Drops the cached seed. Called when an encrypted-DB open fails so the next attempt
     * re-reads the DataStore (a rotated seed would otherwise be cached forever).
     */
    fun invalidateCache() {
        cachedSeed = null
        loadedAtMs = 0
        sourceFile = null
        sourceMtime = -1
    }

    /** True when the cached seed must be re-read (TTL expiry or the source file changed). */
    private fun expired(): Boolean {
        val f = sourceFile
        if (f != null && f.lastModified() != sourceMtime) return true
        return System.currentTimeMillis() - loadedAtMs > SEED_TTL_MS
    }

    fun loadSeed(): Long? {
        val base = runCatching { PathUtils.getAppPath() }.getOrNull() ?: return null
        for (rel in CANDIDATES) {
            val f = File(base, rel)
            if (!f.isFile) continue
            val parsed = runCatching { parseLong(f) }
            parsed.onFailure { System.err.println("UserDbSaltStore: ${f.path} parse error: $it") }
            val value = parsed.getOrNull()
            if (value != null) {
                sourceFile = f
                sourceMtime = f.lastModified()
                loadedAtMs = System.currentTimeMillis()
                return value
            }
        }
        System.err.println("UserDbSaltStore: '$KEY' not found under $base")
        return null
    }

    /** Walk PreferenceMap entries; return the varint held by the matching entry. */
    private fun parseLong(file: File): Long? {
        val b = ByteArray(file.length().toInt()).also {
            RandomAccessFile(file, "r").use { raf -> raf.readFully(it) }
        }
        val n = b.size
        var i = 0
        while (i < n) {
            val tag = readVarint(b, i) ?: return null
            val t = tag.first.toInt()
            i = tag.second
            // PreferenceMap.map is field 1, length-delimited.
            if ((t ushr 3) != 1 || (t and 0x7) != 2) {
                i = skip(b, i, t and 0x7) ?: return null
                continue
            }
            val size = readVarint(b, i) ?: return null
            val entryLen = size.first.toInt()
            val entryStart = size.second
            val entryEnd = entryStart + entryLen
            if (entryEnd > n) return null

            var key: String? = null
            var value: Long? = null
            var j = entryStart
            while (j < entryEnd) {
                val t2 = readVarint(b, j) ?: return null
                val f2 = t2.first.toInt()
                j = t2.second
                val w2 = f2 and 0x7
                if (f2 ushr 3 == 1 && w2 == 2) {
                    val kl = readVarint(b, j) ?: return null
                    val klen = kl.first.toInt()
                    if (kl.second + klen > n) return null
                    key = String(b, kl.second, klen, Charsets.UTF_8)
                    j = kl.second + klen
                } else if (w2 == 2) {
                    // PreferenceValue: nested message whose varint field carries the Long.
                    val vl = readVarint(b, j) ?: return null
                    val vlen = vl.first.toInt()
                    val vend = vl.second + vlen
                    if (vend > n) return null
                    var k = vl.second
                    while (k < vend) {
                        val t3 = readVarint(b, k) ?: return null
                        val w3 = t3.first and 0x7
                        k = t3.second
                        if ((w3.toInt()) == 0) {
                            val v = readVarint(b, k) ?: return null
                            value = v.first
                            k = v.second
                        } else {
                            k = skip(b, k, w3.toInt()) ?: return null
                        }
                    }
                    j = vend
                } else {
                    j = skip(b, j, w2) ?: return null
                }
            }
            // A legitimate seed can be 0 — accept any present value, do not filter it out.
            if (key == KEY && value != null) return value
            i = entryEnd
        }
        return null
    }

    /** (value, next offset) or null if malformed. Value is the raw unsigned varint. */
    private fun readVarint(b: ByteArray, from: Int): Pair<Long, Int>? {
        var x = 0L
        var shift = 0
        var i = from
        while (i < b.size) {
            val byte = b[i].toInt() and 0xff
            i++
            x = x or ((byte and 0x7f).toLong() shl shift)
            if (byte and 0x80 == 0) return x to i
            shift += 7
            if (shift >= 64) return null
        }
        return null
    }

    /** Skip a field of the given wire type; return the next offset. */
    private fun skip(b: ByteArray, from: Int, wire: Int): Int? = when (wire) {
        0 -> readVarint(b, from)?.second
        1 -> if (from + 8 <= b.size) from + 8 else null
        5 -> if (from + 4 <= b.size) from + 4 else null
        2 -> {
            val len = readVarint(b, from) ?: return null
            val end = len.second + len.first.toInt()
            if (end <= b.size) end else null
        }
        else -> null
    }
}
