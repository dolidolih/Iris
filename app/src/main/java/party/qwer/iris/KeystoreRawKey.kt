package party.qwer.iris

import android.database.sqlite.SQLiteDatabase
import java.io.File
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * KakaoTalk의 SQLCipher DB 키를 AndroidKeyStore HMAC 키로부터 유도한다.
 *
 * 키 유도 (실측 검증):
 *   K = HMAC-SHA256(rawKey, alias)
 *   crypto_database           alias "crypto_db_passphrase_key", message "crypto_database"
 *   cecall_history_database.db alias = message = filename
 *
 * rawKey 출처: root 셸/daemon(uid 0)에서 `/data/misc/keystore/persistent.sqlite`의
 * `keyentry`(namespace=카톡 uid, alias) → `blobentry`(subcomponent_type=0) blob.
 * blob은 software-backed KM blob("PKMblob" 매직 + 4B type + LE uint32 keyLen + rawKey).
 *
 * HAYUL(shared-uid) 경로: persistent.sqlite 는 keystore uid 영역 — root 없이 읽을 수
 * 없다. 대신 uid 를 공유하므로 AndroidKeyStore 프레임워크 API 로 카톡의 alias 를
 * 그대로 사용할 수 있다 (getEntry → HMAC 도는 rawKey 추출/사용 가능). hmacKeyFor 는
 * blob → keystore-API 순서로 시도하므로 모드와 무관하게 동작한다.
 *
 * namespace = 실제 카톡 uid — 예전 하드코딩(10089)은 기기마다 다른 값이었으므로
 * context 가능 시 packageManager 로 해석하고, daemon 은 env(KAKAOTALK_APP_UID) 우선,
 * 그 다음 root 로 읽을 수 있는 `/data/system/packages.list` 를 탄다.
 *
 * 주의: hardware-backed(TZB/TEE) keystore 기기에서는 blob에 평문 rawKey가 없어 null을
 * 반환한다. 그 경우 카톡 uid 도메인에서 `Mac.doFinal`을 수행하는 probe가 필요 (§10).
 * null이면 호출측은 조용히 폴백한다 (crash/오류 없음).
 */
object KeystoreRawKey {
    private const val PERSISTENT = "/data/misc/keystore/persistent.sqlite"
    private const val PACKAGES_LIST = "/data/system/packages.list"
    private const val KAKAO_PACKAGE = "com.kakao.talk"
    private const val KAKAO_UID_FALLBACK = 10089
    private const val BLOB_MAGIC = "PKMblob"

    /**
     * keystore namespace — 카톡의 실제 uid.
     *
     * app_process 에는 Context 가 없어 packageManager 를 탈 수 없다. env → (있으면) 앱
     * Context → root 로 읽는 packages.list 순서로 해석하고, 마지막 수단으로 과거
     * 하드코딩 값을 쓰되 "틀릴 수 있다" 는 로그를 남긴다 (잘못된 namespace 는 blob 부재와
     * 구별되지 않아 "key error" 로 오인되기 쉽다).
     */
    internal fun kakaoUid(): Int {
        System.getenv("KAKAOTALK_APP_UID")?.trim()?.toIntOrNull()?.let {
            if (it in 0..200_000) return it
            System.err.println("KeystoreRawKey: ignoring out-of-range KAKAOTALK_APP_UID=$it")
        }
        fromAppContext()?.let { return it }
        fromPackagesList()?.let { return it }
        System.err.println(
            "KeystoreRawKey: 카톡 uid 를 해석하지 못해 $KAKAO_UID_FALLBACK 사용 — " +
                "crypto DB 키 유도가 실패할 수 있습니다. env KAKAOTALK_APP_UID 로 지정하세요."
        )
        return KAKAO_UID_FALLBACK
    }

    private fun fromAppContext(): Int? = runCatching {
        val app = Class.forName("android.app.ActivityThread")
            .getMethod("currentApplication").invoke(null) as? android.app.Application
        app?.packageManager?.getPackageInfo(KAKAO_PACKAGE, 0)?.applicationInfo?.uid
    }.getOrNull()

    /** `/data/system/packages.list` 의 "<pkg> <uid> …" 라인 (root daemon 의 non-Context 경로). */
    private fun fromPackagesList(): Int? = runCatching {
        val f = File(PACKAGES_LIST)
        if (!f.isFile) return null
        f.bufferedReader().useLines { lines ->
            for (line in lines) {
                val parts = line.trim().split(Regex("\\s+"))
                if (parts.isNotEmpty() && parts[0] == KAKAO_PACKAGE) {
                    val uid = parts.getOrNull(1)?.toIntOrNull()
                    if (uid != null) return@runCatching uid
                }
            }
            null
        }
    }.getOrNull()

    /** alias의 raw HMAC key(32B), 없거나 파싱 불가 시 null. */
    fun hmacRawKey(alias: String, uid: Int = kakaoUid()): ByteArray? =
        runCatching { parseFromPersistent(alias, uid) }.getOrNull()

    /** K = HMAC-SHA256(rawKey(alias), message). rawKey 불가 시 null. */
    fun hmacKeyFor(alias: String, message: String, uid: Int = kakaoUid()): ByteArray? {
        val raw = hmacRawKey(alias, uid)
        if (raw != null) {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(raw, "HmacSHA256"))
            return mac.doFinal(message.toByteArray(Charsets.UTF_8))
        }
        // root 없이 alias 가 reachable 한 상태(HAYUL shared-uid) — 프레임워크 keystore 로
        // 직접 HMAC 을 탄다 (동일 수식: K = Mac_rawKey(message)).
        return hmacViaFrameworkKeyStore(alias, message)
    }

    /** AndroidKeyStore 의 SecretKeyEntry(alias) 로 HMAC-SHA256(message) — 실패 시 null. */
    private fun hmacViaFrameworkKeyStore(alias: String, message: String): ByteArray? =
        runCatching {
            val ks = java.security.KeyStore.getInstance("AndroidKeyStore")
            ks.load(null)
            val key = (ks.getEntry(alias, null) as? java.security.KeyStore.SecretKeyEntry)?.secretKey
                ?: return null
            Mac.getInstance("HmacSHA256").apply { init(key) }
                .doFinal(message.toByteArray(Charsets.UTF_8))
        }.onFailure { System.err.println("KeystoreRawKey: framework keystore HMAC unavailable: ${it.message}") }
            .getOrNull()

    /**
     * offset 하드코딩 대신 KM blob 헤더를 따라간다: 매직("PKMblob" NUL-terminated) 뒤
     * type(4B), keyLen(LE uint32 @ +9), rawKey(+13, keyLen bytes).
     */
    private fun parseFromPersistent(alias: String, uid: Int): ByteArray? {
        val dbFile = File(PERSISTENT)
        if (!dbFile.isFile) {
            System.err.println("KeystoreRawKey: not found: $PERSISTENT")
            return null
        }
        SQLiteDatabase.openDatabase(
            dbFile.absolutePath, null,
            SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS
        ).use { db ->
            // ISSUE-32: 키 로테이션이 있으면 alias 당 blob이 여러 개 쌓인다. `k.id` 순서는
            // keyentry 의 갱신 시각과 무관해서 (같은 key row 를 그대로 쓸 수 있다) 오래된
            // 구형 blob을 마지막이라고 잘못 집는다 — 그래서 "file is not a database" 가 키
            // 문제인지 파일 문제인지 알 수 없게 된다. insertion order(blobentry.rowid) 를
            // 최신 기준으로 읽어 newest-first 로 시도한다.
            val blobs = ArrayList<ByteArray>(2)
            db.rawQuery(
                "SELECT b.blob FROM blobentry b JOIN keyentry k ON b.keyentryid = k.id" +
                    " WHERE k.namespace = ? AND k.alias = ? AND b.subcomponent_type = 0" +
                    " ORDER BY b.rowid DESC",
                arrayOf(uid.toString(), alias)
            ).use { c ->
                while (c.moveToNext() && blobs.size < 4) blobs += c.getBlob(0)
            }
            if (blobs.isEmpty()) {
                System.err.println("KeystoreRawKey: no blob for alias=$alias uid=$uid")
                return null
            }
            if (blobs.size > 1) {
                println(
                    "KeystoreRawKey: $alias has ${blobs.size} registered blob(s) - using the newest " +
                        "(rowid-desc); older ones are ignored"
                )
            }
            return blobs.first().let { extractRawKey(it) }
        }
    }

    private fun extractRawKey(blob: ByteArray): ByteArray? {
        val magic = BLOB_MAGIC.toByteArray(Charsets.US_ASCII)
        if (blob.size < 13) return null
        // 매직은 NUL terminator 포함이라字节 단위로 앞에 붙어 있을 수 있다 — 뒤에紧跟하는
        // "PKMblob" 위치를 찾고, 없으면 offset 0으로 간주한다.
        var off = indexOf(blob, magic)
        if (off < 0) off = 0
        val keyLenOff = off + 9
        if (keyLenOff + 4 > blob.size) return null
        val keyLen = (blob[keyLenOff].toInt() and 0xff) or
            ((blob[keyLenOff + 1].toInt() and 0xff) shl 8) or
            ((blob[keyLenOff + 2].toInt() and 0xff) shl 16) or
            ((blob[keyLenOff + 3].toInt() and 0xff) shl 24)
        val start = off + 13
        if (keyLen <= 0 || start + keyLen > blob.size) {
            System.err.println("KeystoreRawKey: unexpected KM blob (len=$keyLen, size=${blob.size})")
            return null
        }
        return blob.copyOfRange(start, start + keyLen)
    }

    private fun indexOf(hay: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..hay.size - needle.size) {
            for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}
