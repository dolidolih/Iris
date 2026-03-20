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
import java.security.MessageDigest
import java.util.LinkedList
import java.util.concurrent.Executors
import kotlin.collections.set

class ObserverHelper(
    private val db: KakaoDB, private val wsBroadcastFlow: MutableSharedFlow<String>
) {
    private val checkpointTracker = LogCheckpointTracker()
    private val lastDecryptedLogs = LinkedList<Map<String, String?>>()
    private val httpRequestExecutor = Executors.newFixedThreadPool(8)
    private val okHttpClient = OkHttpClient()

    fun checkChange(db: KakaoDB) {
        if (checkpointTracker.checkpoint() == 0L) {
            val initialLogId = getLastLogIdFromDB()
            checkpointTracker.reset(initialLogId)
            println("Initial lastLogId: $initialLogId")
            return
        }

        val newLogCount = getNewLogCountFromDB()
        if (newLogCount <= 0) {
            return
        }

        println("Detected $newLogCount new log(s). Processing...")

        db.connection.rawQuery(
            "SELECT * FROM chat_logs WHERE _id > ? ORDER BY _id ASC",
            arrayOf(checkpointTracker.checkpoint().toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                processCursorRow(cursor)
            }
        }
    }

    private fun processCursorRow(cursor: Cursor) {
        val columnNames = cursor.columnNames
        val currentLogId = cursor.getLong(columnNames.indexOf("_id"))

        if (checkpointTracker.shouldSkip(currentLogId)) {
            return
        }

        val chatId = cursor.getLongOrNull(columnNames, "chat_id")
        val userId = cursor.getLongOrNull(columnNames, "user_id")
        val messageType = cursor.getStringOrNull(columnNames, "type")
        val message = cursor.getStringOrNull(columnNames, "message")
        val attachment = cursor.getStringOrNull(columnNames, "attachment")
        val supplement = cursor.getStringOrNull(columnNames, "supplement")
        val metadata = RowMetadata(
            logId = currentLogId,
            chatId = chatId,
            userId = userId,
            messageType = messageType,
            messageLength = message?.length ?: 0,
            attachmentLength = attachment?.length ?: 0,
            supplementLength = supplement?.length ?: 0
        )

        try {
            processRow(cursor, columnNames, metadata)
            checkpointTracker.markCompleted(currentLogId)
        } catch (e: Exception) {
            logRowFailure(metadata, "row", e)
        }
    }

    private fun processRow(cursor: Cursor, columnNames: Array<String>, metadata: RowMetadata) {
        val vRaw = cursor.getStringOrNull(columnNames, "v") ?: "{}"
        val v = try {
            JSONObject(vRaw)
        } catch (e: Exception) {
            logRowFailure(metadata, "v_parse", e)
            JSONObject("{}")
        }
        val enc = v.optInt("enc", 0)
        val origin = v.optString("origin", "")

        if (origin == "SYNCMSG" || origin == "MCHATLOGS") {
            checkpointTracker.markCompleted(metadata.logId)
            return
        }

        val chatId = metadata.chatId ?: 0L
        val userId = metadata.userId ?: 0L
        val messageType = metadata.messageType ?: ""

        var message = cursor.getStringOrNull(columnNames, "message") ?: ""
        var attachment = cursor.getStringOrNull(columnNames, "attachment") ?: "{}"
        val threadId = cursor.getStringOrNull(columnNames, "thread_id")

        var supplement = "{}"
        try {
            supplement = cursor.getStringOrNull(columnNames, "supplement") ?: "{}"
            if (supplement.isNotEmpty() && supplement != "{}") {
                supplement = KakaoDecrypt.decrypt(enc, supplement, userId)
            }
        } catch (e: Exception) {
            logRowFailure(metadata, "supplement_decrypt", e)
            supplement = "{}"
        }

        try {
            if (message.isNotEmpty() && message != "{}") {
                message = KakaoDecrypt.decrypt(enc, message, userId)
            }
        } catch (e: Exception) {
            logRowFailure(metadata, "message_decrypt", e)
        }

        try {
            if ((message.contains("선물") && messageType == "71") || attachment == "null") {
                attachment = "{}"
            } else if (attachment.isNotEmpty() && attachment != "{}") {
                attachment = KakaoDecrypt.decrypt(enc, attachment, userId)
            }
        } catch (e: Exception) {
            logRowFailure(metadata, "attachment_decrypt", e)
            attachment = "{}"
        }

        storeDecryptedLog(cursor, message)

        val raw = mutableMapOf<String, String?>()
        val attachmentMap = getStringJsonToMap(attachment, metadata, "attachment_parse").apply {
            this["src_isThread"] = false
        }
        val supplementMap = getStringJsonToMap(supplement, metadata, "supplement_parse")

        for ((idx, columnName) in columnNames.withIndex()) {
            when (columnName) {
                "message" -> raw[columnName] = message
                "attachment" -> raw[columnName] = attachment
                "supplement" -> raw[columnName] = supplement
                else -> raw[columnName] = cursor.getString(idx)
            }
        }

        if (
            threadId.isNullOrEmpty() &&
            supplementMap.getOrDefault("threadId", "") != "" &&
            attachmentMap.getOrDefault("src_logId", "") == "" &&
            messageType == "1"
        ) {
            attachmentMap["src_logId"] = supplementMap.getOrDefault("threadId", "")
            attachmentMap["src_isThread"] = true
        } else if (!threadId.isNullOrEmpty() && messageType == "1") {
            attachmentMap["src_logId"] = threadId.toLongOrNull() ?: threadId
            attachmentMap["src_isThread"] = true
        }

        raw["attachment"] = JSONObject(attachmentMap as Map<*, *>).toString()
        raw["supplement"] = JSONObject(supplementMap as Map<*, *>).toString()

        val (roomName, senderName) = resolveChatInfo(chatId, userId, metadata)
        val data = JSONObject(
            mapOf(
                "msg" to message,
                "room" to roomName,
                "sender" to senderName,
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

    private fun resolveChatInfo(chatId: Long, userId: Long, metadata: RowMetadata): Pair<String, String> {
        var roomName: String? = null
        var senderName: String? = null

        try {
            val chatInfo = db.getChatInfo(chatId, userId)
            roomName = chatInfo.getOrNull(0)
            senderName = chatInfo.getOrNull(1)
        } catch (e: Exception) {
            logRowFailure(metadata, "chat_info_lookup", e)
        }

        if (senderName.isNullOrEmpty()) {
            try {
                val rawKey = "person_${chatId}:${userId}"
                val md = MessageDigest.getInstance("SHA-256")
                val hashedId = md.digest(rawKey.toByteArray()).joinToString("") { "%02x".format(it) }

                val fallbackInfo = NamesDB.getName(hashedId)
                if (fallbackInfo != null) {
                    senderName = fallbackInfo.first
                    if (roomName.isNullOrEmpty()) {
                        roomName = fallbackInfo.second
                    }
                }
            } catch (e: Exception) {
                logRowFailure(metadata, "namesdb_lookup", e)
            }
        }

        val fallbackSender = senderName?.takeIf { it.isNotBlank() } ?: "<unknown:$userId>"
        val fallbackRoom = roomName?.takeIf { it.isNotBlank() }
            ?: senderName?.takeIf { it.isNotBlank() }
            ?: "chat:$chatId"

        return fallbackRoom to fallbackSender
    }

    private fun getLastLogIdFromDB(): Long {
        val lastLog = db.logToDict(0)
        return lastLog["_id"]?.toLongOrNull() ?: 0
    }

    private fun getStringJsonToMap(
        data: String?,
        metadata: RowMetadata,
        stage: String
    ): MutableMap<String, Any?> {
        if (data.isNullOrBlank() || data == "{}") {
            return HashMap()
        }

        return try {
            val object_ = JSONObject(data)
            val map: MutableMap<String, Any?> = HashMap()

            val keys: MutableIterator<String> = object_.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value: Any? = object_.get(key)
                map[key] = value
            }

            map
        } catch (e: Exception) {
            logRowFailure(metadata, stage, e)
            HashMap()
        }
    }

    private fun getNewLogCountFromDB(): Int {
        val res = db.executeQuery(
            "select count(*) as cnt from chat_logs where _id > ?",
            arrayOf(checkpointTracker.checkpoint().toString())
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

    @Synchronized
    fun getRecentDecryptedLogs(limit: Int): List<Map<String, String?>> {
        return lastDecryptedLogs.take(limit)
    }

    val lastChatLogs: List<Map<String, String?>>
        @Synchronized get() = lastDecryptedLogs

    private fun sendPostRequest(jsonData: String) {
        try {
            val requestBody = jsonData.toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder()
                .url(Configurable.webServerEndpoint)
                .post(requestBody)
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    println("Failed to send POST request. Response code: ${response.code}")
                }
            }
        } catch (e: IOException) {
            println("Error sending POST request: ${e.message}")
        }
    }

    private fun logRowFailure(metadata: RowMetadata, stage: String, error: Exception) {
        System.err.println(
            "Row processing failed" +
                " _id=${metadata.logId}" +
                " chat_id=${metadata.chatId ?: "null"}" +
                " user_id=${metadata.userId ?: "null"}" +
                " type=${metadata.messageType ?: "null"}" +
                " message_len=${metadata.messageLength}" +
                " attachment_len=${metadata.attachmentLength}" +
                " supplement_len=${metadata.supplementLength}" +
                " stage=$stage" +
                " error=${error.javaClass.simpleName}:${error.message}"
        )
    }

    private data class RowMetadata(
        val logId: Long,
        val chatId: Long?,
        val userId: Long?,
        val messageType: String?,
        val messageLength: Int,
        val attachmentLength: Int,
        val supplementLength: Int
    )

    companion object {
        private const val MAX_LOGS_STORED = 50
    }
}

private fun Cursor.getStringOrNull(columnNames: Array<String>, columnName: String): String? {
    val index = columnNames.indexOf(columnName)
    if (index == -1 || isNull(index)) {
        return null
    }
    return getString(index)
}

private fun Cursor.getLongOrNull(columnNames: Array<String>, columnName: String): Long? {
    val index = columnNames.indexOf(columnName)
    if (index == -1 || isNull(index)) {
        return null
    }
    return getLong(index)
}
