package party.qwer.iris

import android.database.sqlite.SQLiteException
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import party.qwer.iris.model.ApiResponse
import party.qwer.iris.model.CommonErrorResponse
import party.qwer.iris.model.DecryptRequest
import party.qwer.iris.model.DecryptResponse
import party.qwer.iris.model.ReplyRequest
import party.qwer.iris.model.ReplyType
import party.qwer.iris.model.ConfigRequest


class HttpServerKt(
    val kakaoDB: KakaoDB,
    val dbObserver: DBObserver,
    val observerHelper: ObserverHelper,
    val notificationReferer: String
) {
    fun startServer() {
        embeddedServer(Netty, port = Configurable.botSocketPort) {
            install(ContentNegotiation) {
                json()
            }

            install(StatusPages) {
                exception<Throwable> { call, cause ->
                    call.respond(
                        HttpStatusCode.InternalServerError, CommonErrorResponse(
                            message = cause.message ?: "unknown error"
                        )
                    )
                }
            }

            routing {
                get("/config") {
                    val html = ConfigPageDocumentProvider.renderPage()
                    call.respondText(html, ContentType.Text.Html)
                }

                // TODO: 리팩
                get("/config/dbstatus") {
                    val statusJson = JSONObject()
                    try {
                        val isObserving = dbObserver.isObserving
                        statusJson.put("isObserving", isObserving)
                        if (isObserving) {
                            statusJson.put("statusMessage", "Observing database")
                        } else {
                            statusJson.put("statusMessage", "Not observing database")
                        }
                        val lastLogsList = observerHelper.lastChatLogs
                        val lastLogsJsonArray = JSONArray(lastLogsList)
                        statusJson.put("lastLogs", lastLogsJsonArray)
                    } catch (e: JSONException) {
                        throw Exception("Failed to serialize DB status to JSON: $e")
                    }

                    call.respondText(
                        JSONObject(
                            mapOf(
                                "success" to true, "message" to statusJson
                            )
                        ).toString(), contentType = ContentType.Application.Json
                    )
                }

                get("/config/info") {
                    val configJson = JSONObject()

                    try {
                        configJson.put("bot_name", Configurable.botName)
                        configJson.put("bot_http_port", Configurable.botSocketPort)
                        configJson.put("web_server_endpoint", Configurable.webServerEndpoint)
                        configJson.put("db_polling_rate", Configurable.dbPollingRate)
                        configJson.put("message_send_rate", Configurable.messageSendRate)
                        configJson.put("bot_id", Configurable.botId)
                    } catch (e: JSONException) {
                        throw Exception("Failed to serialize DB status to JSON: $e")
                    }

                    call.respondText(
                        configJson.toString(), contentType = ContentType.Application.Json
                    )
                }

                post("/config/{name}") {
                    val name = call.parameters["name"]
                    val req = call.receive<ConfigRequest>()

                    when (name) {
                        "endpoint" -> {
                            val value = req.endpoint
                            if (value.isNullOrBlank()) {
                                throw Exception("missing or empty value")
                            }
                            Configurable.webServerEndpoint = value
                        }

                        "botname" -> {
                            val value = req.botname
                            if (value.isNullOrBlank()) {
                                throw Exception("missing or empty value")
                            }
                            Configurable.botName = value
                        }

                        "dbrate" -> {
                            val value = req.rate
                                ?: throw Exception("missing or invalid value")

                            Configurable.dbPollingRate = value
                        }

                        "sendrate" -> {
                            val value = req.rate
                                ?: throw Exception("missing or invalid value")

                            Configurable.messageSendRate = value
                        }

                        "botport" -> {
                            val value  = req.port
                                ?: throw Exception("missing or invalid value")

                            if (value < 1 || value > 65535) {
                                throw Exception("Invalid port number. Port must be between 1 and 65535.")
                            }

                            Configurable.botSocketPort = value
                        }

                        else -> {
                            throw Exception("Unknown config $name")
                        }
                    }

                    call.respond(ApiResponse(success = true, message = "success"))
                }

                post("/reply") {
                    val replyRequest = call.receive<ReplyRequest>()
                    val roomId = replyRequest.room.toLong()

                    when (replyRequest.type) {
                        ReplyType.TEXT -> Replier.SendMessage(
                            notificationReferer, roomId, replyRequest.data.jsonPrimitive.content
                        )

                        ReplyType.IMAGE -> Replier.SendPhoto(roomId, replyRequest.data.jsonPrimitive.content)
                        ReplyType.IMAGE_MULTIPLE -> Replier.SendMultiplePhotos(
                            roomId,
                            replyRequest.data.jsonArray.map { it.jsonPrimitive.content })
                    }

                    call.respond(ApiResponse(success = true, message = "success"))
                }

                // TODO: 리팩
                post("/query") {
                    val body = call.receive<String>()

                    call.respondText(
                        handleQueryFunction(JSONObject(body)),
                        contentType = ContentType.Application.Json
                    )
                }

                post("/decrypt") {
                    val decryptRequest = call.receive<DecryptRequest>()
                    val plaintext = KakaoDecrypt.decrypt(
                        decryptRequest.enc,
                        decryptRequest.b64_ciphertext,
                        decryptRequest.user_id ?: Configurable.botId
                    )

                    call.respond(DecryptResponse(plain_text = plaintext))
                }
            }
        }.start(wait = true)
    }

    private fun createQuerySuccessResponse(queryResult: Any): String {
        try {
            val responseJson = JSONObject()
            responseJson.put("success", true)
            val dataArray = JSONArray()

            if (queryResult is List<*>) {
                val resultList = queryResult
                if (!resultList.isEmpty() && resultList[0] is List<*>) {
                    val bulkResults = queryResult as List<List<Map<String?, Any?>>>
                    for (singleQueryResult in bulkResults) {
                        val singleQueryDataArray = JSONArray()
                        for (rowMap in singleQueryResult) {
                            val rowJson = JSONObject(rowMap)
                            processDecryptionForResponse(rowJson)
                            singleQueryDataArray.put(rowJson)
                        }
                        dataArray.put(singleQueryDataArray)
                    }
                } else {
                    val singleQueryResult = queryResult as List<Map<String?, Any?>>
                    for (rowMap in singleQueryResult) {
                        val rowJson = JSONObject(rowMap)
                        processDecryptionForResponse(rowJson)
                        dataArray.put(rowJson)
                    }
                }
            }
            responseJson.put("data", dataArray)
            return responseJson.toString()
        } catch (e: JSONException) {
            throw Exception("Failed to create query success JSON response.")
        }
    }

    private fun processDecryptionForResponse(rowJson: JSONObject) {
        try {
            if (rowJson.has("message") || rowJson.has("attachment")) {
                val vStr = rowJson.optString("v")
                if (!vStr.isEmpty()) {
                    try {
                        val vJson = JSONObject(vStr)
                        val enc = vJson.optInt("enc", 0)
                        val userId = rowJson.optLong("user_id", Configurable.botId)
                        if (rowJson.has("message")) {
                            val encryptedMessage = rowJson.getString("message")
                            if (encryptedMessage.isEmpty() || encryptedMessage == "{}") {
                            } else {
                                rowJson.put(
                                    "message", KakaoDecrypt.decrypt(enc, encryptedMessage, userId)
                                )
                            }
                        }
                        if (rowJson.has("attachment")) {
                            val encryptedAttachment = rowJson.getString("attachment")
                            if (encryptedAttachment.isEmpty() || encryptedAttachment == "{}") {
                            } else {
                                rowJson.put(
                                    "attachment",
                                    KakaoDecrypt.decrypt(enc, encryptedAttachment, userId)
                                )
                            }
                        }
                    } catch (e: JSONException) {
                        System.err.println("Error parsing 'v' for decryption: $e")
                    } catch (e: java.lang.Exception) {
                        System.err.println("Decryption error for message/attachment: $e")
                    }
                }
            }

            val botId = Configurable.botId
            val enc = rowJson.optInt("enc", 0)
            if (rowJson.has("nickname")) {
                try {
                    val encryptedNickname = rowJson.getString("nickname")
                    rowJson.put("nickname", KakaoDecrypt.decrypt(enc, encryptedNickname, botId))
                } catch (e: java.lang.Exception) {
                    System.err.println("Decryption error for nickname: $e")
                }
            }
            val urlKeys =
                arrayOf("profile_image_url", "full_profile_image_url", "original_profile_image_url")
            for (urlKey in urlKeys) {
                if (rowJson.has(urlKey)) {
                    val encryptedUrl = rowJson.optString(urlKey, "")
                    if (encryptedUrl.isNotEmpty()) {
                        try {
                            rowJson.put(urlKey, KakaoDecrypt.decrypt(enc, encryptedUrl, botId))
                        } catch (e: java.lang.Exception) {
                            System.err.println("Decryption error for $urlKey: $e")
                        }
                    }
                }
            }
        } catch (e: java.lang.Exception) {
            System.err.println("JSON processing error during decryption: $e")
        }
    }

    private fun handleQueryFunction(obj: JSONObject): String {
        try {
            if (obj.has("queries")) {
                val queriesArray = obj.getJSONArray("queries")
                val bulkResults: MutableList<List<Map<String, Any>>> = ArrayList()
                for (i in 0 until queriesArray.length()) {
                    val queryObj = queriesArray.getJSONObject(i)
                    val query = queryObj.getString("query")
                    val bindValues: List<Any> = jsonArrayToList(queryObj.getJSONArray("bind"))
                    val bindArgs = arrayOfNulls<String>(bindValues.size)
                    for (j in bindValues.indices) {
                        bindArgs[j] = bindValues[j].toString()
                    }
                    val queryResult: List<Map<String, Any>> = kakaoDB.executeQuery(query, bindArgs)
                    bulkResults.add(queryResult)
                }
                return createQuerySuccessResponse(bulkResults)
            } else {
                val query = obj.getString("query")
                val bindJsonArray = obj.optJSONArray("bind")
                val bindValues =
                    if ((bindJsonArray != null)) jsonArrayToList(bindJsonArray) else emptyList<Any>()
                val bindArgs = arrayOfNulls<String>(bindValues.size)
                for (i in bindValues.indices) {
                    bindArgs[i] = bindValues[i].toString()
                }
                val queryResult: List<Map<String, Any>> = kakaoDB.executeQuery(query, bindArgs)
                return createQuerySuccessResponse(queryResult)
            }
        } catch (e: JSONException) {
            throw Exception("Invalid 'query' or 'queries' field for query function. $e")
        } catch (e: ClassCastException) {
            throw Exception("Invalid 'query' or 'queries' field for query function. $e")
        } catch (e: SQLiteException) {
            throw Exception("Database query error: $e")
        } catch (e: java.lang.Exception) {
            throw Exception("Error executing query: $e")
        }
    }

    private fun jsonArrayToList(jsonArray: JSONArray?): List<Any> {
        val list: MutableList<Any> = ArrayList()
        if (jsonArray != null) {
            for (i in 0 until jsonArray.length()) {
                list.add(jsonArray[i])
            }
        }
        return list
    }
}
