package party.qwer.iris

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase

object NamesDB {
    private const val DB_PATH = "/data/local/tmp/names.db"
    private var db: SQLiteDatabase? = null

    init {
        try {
            db = SQLiteDatabase.openOrCreateDatabase(DB_PATH, null)
            db?.execSQL("CREATE TABLE IF NOT EXISTS names (sender_id TEXT PRIMARY KEY, sender_name TEXT, room_name TEXT)")
        } catch (e: Exception) {
            println("NamesDB Initialization Error: ${e.message}")
        }
    }

    fun saveName(senderId: String, senderName: String, roomName: String) {
        try {
            val values = ContentValues().apply {
                put("sender_id", senderId)
                put("sender_name", senderName)
                put("room_name", roomName)
            }
            db?.insertWithOnConflict("names", null, values, SQLiteDatabase.CONFLICT_REPLACE)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun getName(senderId: String): Pair<String, String>? {
        try {
            db?.rawQuery("SELECT sender_name, room_name FROM names WHERE sender_id = ?", arrayOf(senderId))?.use { cursor ->
                if (cursor.moveToFirst()) {
                    return Pair(cursor.getString(0), cursor.getString(1))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }
}