package party.qwer.iris



/**
 * Reader for KakaoTalk's `crypto_user_database` (SQLCipher).
 *
 * Key derivation (verified against the live device and reproducing the captured key
 * a84bf2c8…c192de exactly, see docs / daily log 2026-09-13):
 *   salt = zero-pad(("se" + seed + "ed") in UTF-8) to 16 bytes
 *   K1   = PBKDF2WithHmacSHA256(pass = char[16]{4,15,81,123,77,5,23,99,2,111,10,31,54,29,109,97},
 *                              salt, iters = 4096, keyBits = 256)
 *   seed = Long held under key `userDbPassPhraseSalt` in
 *          files/datastore/Feature_DataStore.pref.preferences_pb  (see UserDbSaltStore)
 *
 * K1 is a *passphrase*, not a raw page key: the app passes it to
 * net.zetetic.database.sqlcipher.SQLiteDatabase.openDatabase(path, K1, …) which invokes
 * sqlite3_key_v2 → passphrase mode with SQLCipher-4 defaults (kdf_iter 256000, HMAC sha512).
 * Feeding K1 as a raw key (PRAGMA key="x'…'") fails with "file is not a database".
 *
 * that have no `user` row (name only). Returns null if unknown or the DB cannot be opened.
 * Snapshot refresh / private working copy / close-race guarding are delegated to
 * [CryptoDbSnapshot] (ISSUE-02, ISSUE-31, ISSUE-32).
 */
object CryptoUserDatabaseReader {
    private const val DB_NAME = "crypto_user_database"
    private val SALT_CHARS = charArrayOf(4.toChar(),15.toChar(),81.toChar(),123.toChar(),77.toChar(),5.toChar(),23.toChar(),99.toChar(),2.toChar(),111.toChar(),10.toChar(),31.toChar(),54.toChar(),29.toChar(),109.toChar(),97.toChar())

    private val snapshot = CryptoDbSnapshot(
        label = "CryptoUserDatabaseReader",
        dbName = DB_NAME,
        subDir = "crypto_user_db"
    )

    /** Derives the 32-byte passphrase key K1 for a given `userDbPassPhraseSalt` seed. */
    @Throws(Exception::class)
    fun deriveKey(seed: Long): ByteArray {
        val raw = ("se" + seed + "ed").toByteArray(Charsets.UTF_8)
        val salt = ByteArray(16)
        System.arraycopy(raw, 0, salt, 0, minOf(16, raw.size))
        val spec = javax.crypto.spec.PBEKeySpec(SALT_CHARS, salt, 4096, 256)
        return javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(spec).encoded
    }

    /** Opens crypto_user_database read-only, deriving the key from the stored seed. */
    fun open(): Boolean {
        val ok = snapshot.ensure(::keyProvider)
        if (!ok) {
            // A stale/moved seed file is the usual cause of "file is not a database":
            // drop the cached seed so the next attempt re-reads the DataStore (ISSUE-02).
            UserDbSaltStore.invalidateCache()
        }
        return ok
    }

    private fun keyProvider(): ByteArray? {
        val seed = UserDbSaltStore.getSeed() ?: run {
            System.err.println("CryptoUserDatabaseReader: seed unavailable")
            return null
        }
        return runCatching { deriveKey(seed) }.getOrElse {
            UserDbSaltStore.invalidateCache()
            System.err.println("CryptoUserDatabaseReader: key derivation failed: $it")
            null
        }
    }

    /**
     * Resolves a plaintext profile name for a KakaoTalk user id. Two tables in the
     * decrypted DB carry names: `user` (friends) and `talk_channel` (plus-friend /
     * Kakao-channel / talk accounts that have no `user` row). Both key the name by the
     * member's user id — in `talk_channel` that id is also linked to the room's chat_id.
     * Returns null when the user is unknown or the encrypted DB cannot be opened.
     */
    fun queryUserName(userId: Long): String? = lookupName(userId)

    private fun lookupName(userId: Long): String? =
        snapshot.use(::keyProvider) { database ->
            database.rawQuery(
                "SELECT nickname FROM user WHERE id = ? AND nickname <> '' LIMIT 1",
                arrayOf(userId.toString())
            ).use { c -> if (c.moveToFirst()) c.getString(0) else null }
                ?: database.rawQuery(
                    "SELECT name FROM talk_channel WHERE id = ? AND name <> '' LIMIT 1",
                    arrayOf(userId.toString())
                ).use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }

    /**
     * Resolves a user's plaintext profile (name + profile image URL) for a KakaoTalk user id.
     *
     * The `user` table stores both `nickname` and `profile_image_url` in plaintext (measured:
     * 322 live rows). Falls back to `talk_channel` for plus-friend / Kakao-channel / talk accounts
     * that have no `user` row (name only). Returns null if unknown or the DB cannot be opened.
     */
    fun getUserProfile(userId: Long): Pair<String?, String?>? =
        snapshot.use(::keyProvider) { database ->
            val fromUser = database.rawQuery(
                "SELECT nickname, profile_image_url FROM user WHERE id = ? LIMIT 1",
                arrayOf(userId.toString())
            ).use { c ->
                if (c.moveToFirst()) {
                    val name = c.getString(0)
                    val url = c.getString(1)
                    if (!name.isNullOrEmpty() || !url.isNullOrEmpty()) name to url else null
                } else null
            }
            fromUser ?: database.rawQuery(
                "SELECT name FROM talk_channel WHERE id = ? AND name <> '' LIMIT 1",
                arrayOf(userId.toString())
            ).use { c -> if (c.moveToFirst()) c.getString(0) to null else null }
        }

    /** Bulk map of user id → nickname, for priming a name cache. Note: only `user`
     *  rows; unlike queryUserName it omits talk_channel names. Not used by the
     *  hot path, which calls queryUserName per-id instead. */
    fun queryAllNicknames(limit: Int = 4096): Map<Long, String> =
        runCatching {
            snapshot.use(::keyProvider) { database ->
                database.rawQuery(
                    "SELECT id, nickname FROM user WHERE nickname IS NOT NULL AND nickname <> '' LIMIT ?",
                    arrayOf(limit.toString())
                ).use { cursor ->
                    val out = LinkedHashMap<Long, String>()
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(0)
                        val name = cursor.getString(1)
                        if (id != 0L && !name.isNullOrEmpty()) out[id] = name
                    }
                    out
                }
            } ?: emptyMap()
        }.getOrElse {
            System.err.println("CryptoUserDatabaseReader: queryAllNicknames failed: $it")
            emptyMap()
        }

    fun close() = snapshot.close()
}
