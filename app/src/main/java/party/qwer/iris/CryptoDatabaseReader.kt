package party.qwer.iris


/**
 * Reader for KakaoTalk's `crypto_database` (SQLCipher).
 *
 * Key derivation (offline + live verified):
 *   rawKey = AndroidKeyStore HMAC key held under alias "crypto_db_passphrase_key"
 *            (software-backed KM blob — read via root dump in ROOT_ADB, or via the
 *             shared-uid framework KeyStore API in HAYUL mode — see KeystoreRawKey)
 *   K      = HMAC-SHA256(rawKey, "crypto_database")   // 32 bytes
 *   Open with sqlite3_key_v2(db,"main",K,32) — passphrase mode, SQLCipher-4 defaults.
 *   Feeding K as a raw key (`PRAGMA key="x'…'"`) fails with "file is not a database".
 *
 * The whole file is encrypted at the page level, so once opened every column is
 * plaintext — there is no per-field `enc` step (unlike db1.chat_logs / db2.open_chat_member).
 *
 * The table `chat_log_search` holds the same snowflake `id` as db1.chat_logs.id plus
 * `searchable_text` = the decrypted full-text of each message. This makes full-text
 * message lookup possible: match on chat_log_search, then tie back to chat_logs.id.
 * `room_master_table` is Room's migration-check table (single row).
 *
 * Graceful: if the keystore blob is unreadable (hardware-backed keystore) or the DB is
 * absent, open() returns false and callers fall back silently — no crash.
 *
 * Snapshot refresh / private working copy / close-race guarding are delegated to
 * [CryptoDbSnapshot] (ISSUE-02, ISSUE-31, ISSUE-32).
 */
object CryptoDatabaseReader {
    private const val DB_NAME = "crypto_database"
    private const val KEY_ALIAS = "crypto_db_passphrase_key"

    private val snapshot = CryptoDbSnapshot(
        label = "CryptoDatabaseReader",
        dbName = DB_NAME,
        subDir = "crypto_db"
    )

    /** Derives the 32-byte passphrase key K from the keystore raw key for a given alias. */
    @Throws(Exception::class)
    fun deriveKey(): ByteArray {
        val key = KeystoreRawKey.hmacKeyFor(KEY_ALIAS, DB_NAME)
            ?: throw IllegalStateException("keystore raw key unavailable")
        return key
    }

    /** Opens crypto_database read-only, deriving the key from the AndroidKeyStore dump. */
    fun open(): Boolean = snapshot.ensure(::keyProvider)

    private fun keyProvider(): ByteArray? =
        runCatching { deriveKey() }.getOrElse {
            System.err.println("CryptoDatabaseReader: ${it.message}")
            null
        }


    /**
     * Full-text lookup over `chat_log_search`: returns the matched log ids (==
     * db1.chat_logs.id snowflake) with a preview of searchable_text, most recent first.
     * Empty when the DB can't be opened or the term matches nothing.
     */
    fun searchMessages(query: String, limit: Int = 50): List<Pair<Long, String>> {
        val pattern = "%${escapeLike(query)}%"
        return runCatching {
            snapshot.use(::keyProvider) { database ->
                database.rawQuery(
                    "SELECT id, substr(searchable_text, 1, 120) FROM chat_log_search" +
                        " WHERE searchable_text LIKE ? ESCAPE '\\' ORDER BY id DESC LIMIT ?",
                    arrayOf(pattern, limit.toString())
                ).use { c ->
                    val out = ArrayList<Pair<Long, String>>()
                    while (c.moveToNext()) {
                        val id = c.getLong(0)
                        val text = c.getString(1) ?: ""
                        if (id != 0L) out.add(id to text)
                    }
                    out
                }
            } ?: emptyList()
        }.getOrElse {
            System.err.println("CryptoDatabaseReader: searchMessages failed: $it")
            emptyList()
        }
    }

    /** ISSUE-32: LIKE 의 `%`/`_` 를 escape 하지 않으면 질의에 포함된 와일드카드가
     *  의도치 않게 매치 셋을 넓힌다 (한 문자 질의가 전체를 훑는 식). */
    private fun escapeLike(term: String): String =
        term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    /** Names resolvable from crypto_database (chat_log_search is not a name source; kept
     *  for API symmetry with CryptoUserDatabaseReader — always null). */
    fun queryUserName(userId: Long): String? = null

    fun close() = snapshot.close()
}
