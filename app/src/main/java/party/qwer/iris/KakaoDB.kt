package party.qwer.iris

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException

class KakaoDB {
    lateinit var connection: SQLiteDatabase

    init {
        try {
            connection = SQLiteDatabase.openDatabase(
                DB_PATH + "/KakaoTalk.db", null, SQLiteDatabase.OPEN_READWRITE
            )
            connection.execSQL("ATTACH DATABASE '$DB_PATH/KakaoTalk2.db' AS db2")
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

        var room = sender

        connection.rawQuery(
            "SELECT name FROM db2.open_link WHERE id = (SELECT link_id FROM chat_rooms WHERE id = ?)",
            arrayOf(chatId.toString())
        ).use { cursor ->
            if (cursor.moveToNext()) {
                room = cursor.getString(0)
            }
        }

        return arrayOf(room, sender)
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
        private const val DB_PATH = "/data/data/com.kakao.talk/databases"
    }
}