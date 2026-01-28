package party.qwer.iris

import android.database.Cursor
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.LinkedList
import java.util.concurrent.Executors
import kotlin.collections.set


class ObserverHelper(
    private val db: KakaoDB, private val wsBroadcastFlow: MutableSharedFlow<String>
) {
    private var lastLogId: Long = 0
    private val lastDecryptedLogs = LinkedList<Map<String, String?>>()
    private val httpRequestExecutor = Executors.newFixedThreadPool(8)
    private val okHttpClient = OkHttpClient()

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
                            lastLogId = currentLogId
                            continue
                        }

                        val chatId = cursor.getLong(columnNames.indexOf("chat_id"))
                        val userId = cursor.getLong(columnNames.indexOf("user_id"))

                        var message = cursor.getString(columnNames.indexOf("message"))
                        var attachment = cursor.getString(columnNames.indexOf("attachment"))
                        val messageType = cursor.getString(columnNames.indexOf("type"))

                        val threadId: String? = if (cursor.columnNames.indexOf("thread_id") != -1) {
                            cursor.getString(columnNames.indexOf("thread_id"))
                        } else {
                            null
                        }

                        var supplement = "{}"
                        try {
                            // supplement가 null이면 exception 발생함.
                            supplement = cursor.getString(columnNames.indexOf("supplement"))
                            if(supplement.isNotEmpty() && supplement != "{}")
                                supplement = KakaoDecrypt.decrypt(enc, supplement, userId)
                        } catch(_: Exception) {}

                        try {
                            if (message.isNotEmpty() && message != "{}") message =
                                KakaoDecrypt.decrypt(enc, message, userId)
                        } catch (e: Exception) {
                            println("failed to decrypt message: $e")
                        }

                        try {
                            if ((message.contains("선물") && messageType == "71") or (attachment == null)) {
                                attachment = "{}"
                            } else if (attachment.isNotEmpty() && attachment != "{}") {
                                attachment =
                                    KakaoDecrypt.decrypt(enc, attachment, userId)
                            }
                        } catch (e: Exception) {
                            println("failed to decrypt attachment: $e")
                        }

                        storeDecryptedLog(cursor, message)

                        // update log id
                        lastLogId = currentLogId

                        val raw = mutableMapOf<String, String?>()
                        val advancedPlainSerialized = mutableMapOf<String, MutableMap<String, Any?>?>()

                        for ((idx, columnName) in columnNames.withIndex()) {
                            if (columnName == "message") {
                                raw[columnName] = message
                            } else if (columnName == "attachment") {
                                raw[columnName] = attachment
                                advancedPlainSerialized[columnName] = getStringJsonToMap(attachment)
                                advancedPlainSerialized[columnName]!!["src_isThread"] = false
                            } else if (columnName == "supplement") {
                                raw["supplement"] = supplement
                                advancedPlainSerialized[columnName] = getStringJsonToMap(supplement)
                            } else {
                                raw[columnName] = cursor.getString(idx)
                            }
                        }

                        if (
                            threadId == null &&
                            advancedPlainSerialized["supplement"]!!.getOrDefault("threadId", "") != ""
                            &&
                            advancedPlainSerialized["attachment"]!!.getOrDefault("src_logId", "") == ""
                            &&
                            messageType == "1"
                            ) {
                            advancedPlainSerialized["attachment"]!!["src_logId"] =
                                advancedPlainSerialized["supplement"]!!.getOrDefault("threadId", "")
                            advancedPlainSerialized["attachment"]!!["src_isThread"] = true
                            raw["attachment"] = JSONObject(advancedPlainSerialized["attachment"]!!).toString()
                        } else if(threadId != null && messageType == "1") {
                            advancedPlainSerialized["attachment"]!!["src_logId"] = threadId.toULong()
                            advancedPlainSerialized["attachment"]!!["src_isThread"] = true
                            raw["attachment"] = JSONObject(advancedPlainSerialized["attachment"]!!).toString()
                        }

                        val chatInfo = db.getChatInfo(chatId, userId)
                        val data = JSONObject(
                            mapOf(
                                "msg" to message,
                                "room" to chatInfo[0],
                                "sender" to chatInfo[1],
                                "json" to raw
                            )
                        ).toString()

                        runBlocking {
                            wsBroadcastFlow.emit(data)
                        }

                        if (Configurable.webServerEndpoint.isNotEmpty()) {
                            httpRequestExecutor.execute {
                                sendPostRequest(data)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun getLastLogIdFromDB(): Long {
        val lastLog = db.logToDict(0)
        return lastLog["_id"]?.toLongOrNull() ?: 0
    }

    private fun getStringJsonToMap(data: String?): MutableMap<String, Any?> {
        if(data == null) return HashMap()
        val object_ = JSONObject(data)
        val map: MutableMap<String, Any?> = HashMap()

        val keys: MutableIterator<String> = object_.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value: Any? = object_.get(key)
            map[key] = value
        }

        return map
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

        val mediaType = "application/json; charset=utf-8".toMediaType()
        val requestBody = jsonData.toRequestBody(mediaType)

        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .build()

        try {
            okHttpClient.newCall(request).execute().use { response ->
                val responseCode = response.code
                println("HTTP Response Code: $responseCode")

                if (response.isSuccessful) {
                    val responseBody = response.body?.string()
                    println("HTTP Response Body: $responseBody")
                } else {
                    System.err.println("HTTP Error Response: $responseCode - ${response.message}")
                    val errorBody = response.body?.string()
                    if (errorBody != null) {
                        System.err.println("Error Body: $errorBody")
                    }
                }
            }
        } catch (e: IOException) {
            System.err.println("Error sending POST request: " + e.message)
        }
    }


    val lastChatLogs: List<Map<String, String?>>
        get() = lastDecryptedLogs

    companion object {
        private const val MAX_LOGS_STORED = 50
    }
}