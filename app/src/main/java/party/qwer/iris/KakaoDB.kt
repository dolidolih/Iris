package party.qwer.iris

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonPrimitive
import org.json.JSONException
import org.json.JSONObject

class KakaoDB {
    lateinit var connection: SQLiteDatabase

    init {
        try {
            connection = SQLiteDatabase.openDatabase(
                "$DB_PATH/KakaoTalk.db", null, SQLiteDatabase.OPEN_READWRITE
            )
            connection.execSQL("ATTACH DATABASE '$DB_PATH/KakaoTalk2.db' AS db2")
            connection.execSQL("ATTACH DATABASE '$DB_PATH/multi_profile_database.db' AS db3")
            Configurable.botId = botUserId
        } catch (e: SQLiteException) {
            System.err.println("SQLiteException: " + e.message)
            System.err.println("You don't have a permission to access KakaoTalk Database.")
            System.exit(1)
        }
    }

    val botUserId: Long
        get() {
            return connection.rawQuery(
                "SELECT user_id FROM chat_logs WHERE v LIKE '%\"isMine\":true%' ORDER BY _id DESC LIMIT 1;",
                null
            ).use { cursor ->
                if (cursor.moveToFirst()) {
                    val botUserId = cursor.getLong(0)
                    println("Bot user_id is detected: $botUserId")

                    botUserId
                } else {
                    System.err.println("Warning: Bot user_id not found in chat_logs with isMine:true. Decryption might not work correctly.")

                    0
                }
            }
        }


    fun getNameOfUserId(userId: Long): String? {
        val stringUserId = arrayOf(userId.toString())
        val sql = if (checkNewDb()) {
            "WITH info AS (SELECT ? AS user_id) " + "SELECT COALESCE(open_chat_member.nickname, friends.name) AS name, " + "COALESCE(open_chat_member.enc, friends.enc) AS enc " + "FROM info " + "LEFT JOIN db2.open_chat_member ON open_chat_member.user_id = info.user_id " + "LEFT JOIN db2.friends ON friends.id = info.user_id;"
        } else {
            "SELECT name, enc FROM db2.friends WHERE id = ?"
        }

        return connection.rawQuery(sql, stringUserId).use { cursor ->
            if (cursor.moveToNext()) {
                val encryptedName = cursor.getString(cursor.getColumnIndexOrThrow("name"))
                val enc = cursor.getInt(cursor.getColumnIndexOrThrow("enc"))

                try {
                    KakaoDecrypt.decrypt(enc, encryptedName, Configurable.botId)
                } catch (e: Exception) {
                    System.err.println("Decryption error in getNameOfUserId: $e");
                    encryptedName
                }
            } else {
                null
            }
        }
    }


    fun getChatInfo(chatId: Long, userId: Long): Array<String?> {
        val sender = if (userId == Configurable.botId) {
            Configurable.botName
        } else {
            getNameOfUserId(userId)
        }

        connection.rawQuery(
            "SELECT private_meta FROM chat_rooms WHERE id = ?", arrayOf(chatId.toString())
        ).use { cursor ->
            if (cursor.moveToNext()) {
                val value = cursor.getString(0)
                if (!value.isNullOrEmpty()) {
                    try {
                        val meta = Json.decodeFromString<Map<String, JsonElement>>(value)
                        val name = meta["name"]

                        if (name != null) {
                            return arrayOf(name.jsonPrimitive.content, sender)
                        }
                    } catch (_: Exception) {
                    }
                }
            }
        }

        connection.rawQuery(
            "SELECT name FROM db2.open_link WHERE id = (SELECT link_id FROM chat_rooms WHERE id = ?)",
            arrayOf(chatId.toString())
        ).use { cursor ->
            if (cursor.moveToNext()) {
                return arrayOf(cursor.getString(0), sender)
            }
        }

        return arrayOf(sender, sender)
    }

    fun logToDict(logId: Long): Map<String, String?> {
        val dict: MutableMap<String, String?> = HashMap()

        connection.rawQuery("SELECT * FROM chat_logs ORDER BY _id DESC LIMIT 1", null)
            .use { cursor ->
                if (cursor.moveToNext()) {
                    val columnNames = cursor.columnNames
                    for (columnName in columnNames) {
                        val columnIndex = cursor.getColumnIndexOrThrow(columnName)
                        dict[columnName] = cursor.getString(columnIndex)
                    }
                }
            }

        return dict
    }


    fun checkNewDb(): Boolean {
        return connection.rawQuery(
            "SELECT name FROM db2.sqlite_master WHERE type='table' AND name='open_chat_member'",
            null
        ).use { cursor ->
            cursor.count > 0
        }
    }

    fun closeConnection() {
        if (connection.isOpen) {
            connection.close()
            println("Database connection closed.")
        }
    }

    fun executeQuery(
        sqlQuery: String, bindArgs: Array<String?>?
    ): List<Map<String, String?>> {
        val resultList: MutableList<Map<String, String?>> = ArrayList()
        connection.rawQuery(sqlQuery, bindArgs).use { cursor ->
            val columnNames = cursor.columnNames
            while (cursor.moveToNext()) {
                val row: MutableMap<String, String?> = HashMap()
                for (columnName in columnNames) {
                    val columnIndex = cursor.getColumnIndexOrThrow(columnName)
                    row[columnName] = cursor.getString(columnIndex)
                }
                resultList.add(row)
            }
        }
        return resultList
    }

    companion object {
        private val DB_PATH = "${PathUtils.getAppPath()}databases"
        fun decryptRow(row: Map<String, String?>): Map<String, String?> {
            @Suppress("NAME_SHADOWING") var row = row.toMutableMap()

            try {
                if (row.contains("message") || row.contains("attachment")) {
                    val vStr = row.getOrDefault("v", "")
                    if (vStr?.isNotEmpty() == true) {
                        try {
                            val vJson = JSONObject(vStr)
                            val enc = vJson.optInt("enc", 0)
                            val userId = row.get("user_id")?.toLongOrNull() ?: Configurable.botId
                            val keysToDecrypt = arrayOf("message", "attachment")
                            row = decryptRowValues(row, enc, userId, keysToDecrypt)
                        } catch (e: JSONException) {
                            System.err.println("Error parsing 'v' for decryption: $e")
                        }
                    }
                }

                val keywords = listOf("name", "nick_name", "nickname", "profile_image_url", "full_profile_image_url", "original_profile_image_url", "status_message", "contact_name", "board_v")

                if (row.contains("enc") && keywords.any { row.contains(it) }) {
                    val botId = Configurable.botId
                    val enc = row["enc"]?.toIntOrNull() ?: 0
                    val keysToDecrypt = arrayOf("nick_name", "name", "nickname", "profile_image_url", "full_profile_image_url", "original_profile_image_url", "status_message","contact_name","v","board_v")
                    row = decryptRowValues(row, enc, botId, keysToDecrypt)
                }
            } catch (e: Exception) {
                System.err.println("JSON processing error during decryption: $e")
            }

            return row
        }
        private fun decryptRowValues(row: MutableMap<String, String?>, enc: Int, botId: Long, keysToDecrypt: Array<String>): MutableMap<String, String?> {
            for (key in keysToDecrypt) {
                if (row.containsKey(key)) {
                    try {
                        val encryptedValue = row.getOrDefault(key, "") as? String
                        if (encryptedValue != "{}" && encryptedValue != "[]"){
                            encryptedValue?.let {
                                row[key] = KakaoDecrypt.decrypt(enc, it, botId)
                            }
                        }
                    } catch (e: Exception) {
                        System.err.println("Decryption error for $key: $e")
                    }
                }
            }
            return row
        }
    }
}