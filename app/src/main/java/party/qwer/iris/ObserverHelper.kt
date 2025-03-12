package party.qwer.iris

import android.database.Cursor
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.LinkedList
import java.util.concurrent.Executors


class ObserverHelper(
    val db: KakaoDB
) {
    private var lastLogId: Long = 0
    private val lastDecryptedLogs = LinkedList<Map<String, String?>>()
    private val httpRequestExecutor = Executors.newFixedThreadPool(8)

    fun checkChange(db: KakaoDB) {
        if (lastLogId == 0L) {
            lastLogId = getLastLogIdFromDB()
            println("Initial lastLogId: $lastLogId")
            return
        }

        val newLogCount = getNewLogCountFromDB()

        if (newLogCount > 0) {
            println("Detected $newLogCount new log(s). Processing...")

            db.connection.rawQuery(
                "SELECT * FROM chat_logs WHERE _id > ? ORDER BY _id ASC",
                arrayOf(lastLogId.toString())
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val columnNames = cursor.columnNames
                    val currentLogId = cursor.getLong(columnNames.indexOf("_id"))

                    if (currentLogId > lastLogId) {
                        val v = JSONObject(cursor.getString(columnNames.indexOf("v")))
                        val enc = v.getInt("enc")
                        val origin = v.getString("origin")

                        if (origin == "SYNCMSG" || origin == "MCHATLOGS") {
                            continue
                        }

                        val chatId = cursor.getLong(columnNames.indexOf("chat_id"))
                        val userId = cursor.getLong(columnNames.indexOf("user_id"))

                        var message = cursor.getString(columnNames.indexOf("message"))
                        var attachment = cursor.getString(columnNames.indexOf("attachment"))

                        try {
                            if (message.isNotEmpty() && message != "{}")
                                message = KakaoDecrypt.decrypt(enc, message, userId)
                        } catch (e: Exception) {
                            println("failed to decrypt message: $e")
                        }

                        try {
                            if (attachment.isNotEmpty() && attachment != "{}")
                                attachment = KakaoDecrypt.decrypt(enc, attachment, userId)
                        } catch (e: Exception) {
                            println("failed to decrypt attachment: $e")
                        }

                        storeDecryptedLog(cursor, message)

                        // update log id
                        lastLogId = currentLogId

                        val raw = mutableMapOf<String, String?>()
                        for ((idx, columnName) in columnNames.withIndex()) {
                            if (columnName == "message") {
                                raw[columnName] = message
                            } else if (columnName == "attachment") {
                                raw[columnName] = attachment
                            } else {
                                raw[columnName] = cursor.getString(idx)
                            }
                        }

                        val chatInfo = db.getChatInfo(chatId, userId)

                        httpRequestExecutor.execute {
                            sendPostRequest(
                                JSONObject(
                                    mapOf(
                                        "msg" to message,
                                        "room" to chatInfo[0],
                                        "sender" to chatInfo[1],
                                        "json" to raw
                                    )
                                ).toString()
                            )
                        }
                    }
                }
            }
        }
    }

    private fun getLastLogIdFromDB(): Long {
        val lastLog = db.logToDict(0)
        return lastLog["_id"]?.toLongOrNull() ?: 0;
    }

    private fun getNewLogCountFromDB(): Int {
        val res = db.executeQuery(
            "select count(*) as cnt from chat_logs where _id > ?", arrayOf(lastLogId.toString())
        )
        return res[0]["cnt"]?.toIntOrNull() ?: 0
    }

    @Synchronized
    private fun storeDecryptedLog(cursor: Cursor, decryptedMessage: String?) {
        val logEntry: MutableMap<String, String?> = HashMap()
        logEntry["_id"] = cursor.getString(cursor.getColumnIndexOrThrow("_id"))
        logEntry["chat_id"] = cursor.getString(cursor.getColumnIndexOrThrow("chat_id"))
        logEntry["user_id"] = cursor.getString(cursor.getColumnIndexOrThrow("user_id"))
        logEntry["message"] = decryptedMessage
        logEntry["created_at"] = cursor.getString(cursor.getColumnIndexOrThrow("created_at"))

        lastDecryptedLogs.addFirst(logEntry)
        if (lastDecryptedLogs.size > MAX_LOGS_STORED) {
            lastDecryptedLogs.removeLast()
        }
    }

    private fun sendPostRequest(jsonData: String) {
        val url = Configurable.webServerEndpoint
        println("Sending HTTP POST request to: $url")
        println("JSON Data being sent: $jsonData")

        val con = URL(url).openConnection() as HttpURLConnection

        try {
            // send
            con.requestMethod = "POST"
            con.setRequestProperty("Content-Type", "application/json")
            con.setRequestProperty("Accept", "application/json")
            con.connectTimeout = 5000
            con.readTimeout = 5000
            con.doOutput = true

            con.outputStream.use { os ->
                val input = jsonData.toByteArray(StandardCharsets.UTF_8)
                os.write(input, 0, input.size)
            }

            // check
            val responseCode = con.responseCode
            println("HTTP Response Code: $responseCode")

            try {
                val responseBody = con.inputStream.bufferedReader().use {
                    it.readText()
                }

                println("HTTP Response Body: $responseBody")
            } catch (e: IOException) {
                System.err.println("Error reading HTTP response body: " + e.message)
            }
        } catch (e: IOException) {
            System.err.println("Error sending POST request: " + e.message)
        } finally {
            con.disconnect()
        }
    }


    val lastChatLogs: List<Map<String, String?>>
        get() = lastDecryptedLogs

    companion object {
        private const val MAX_LOGS_STORED = 50
    }
}