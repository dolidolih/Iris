package party.qwer.iris

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
import org.json.JSONException
import org.json.JSONObject
import party.qwer.iris.model.ApiResponse
import party.qwer.iris.model.CommonErrorResponse
import party.qwer.iris.model.ConfigRequest
import party.qwer.iris.model.ConfigResponse
import party.qwer.iris.model.DashboardStatusResponse
import party.qwer.iris.model.DecryptRequest
import party.qwer.iris.model.DecryptResponse
import party.qwer.iris.model.QueryRequest
import party.qwer.iris.model.QueryResponse
import party.qwer.iris.model.ReplyRequest
import party.qwer.iris.model.ReplyType


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
                get("/dashboard") {
                    val html = PageRenderer.renderDashboard()
                    call.respondText(html, ContentType.Text.Html)
                }

                get("/config") {
                    call.respond(
                        ConfigResponse(
                            bot_name = Configurable.botName,
                            bot_http_port = Configurable.botSocketPort,
                            web_server_endpoint = Configurable.webServerEndpoint,
                            db_polling_rate = Configurable.dbPollingRate,
                            message_send_rate = Configurable.messageSendRate,
                            bot_id = Configurable.botId,
                        )
                    )
                }

                get("/dashboard/status") {
                    call.respond(
                        DashboardStatusResponse(
                            isObserving = dbObserver.isObserving,
                            statusMessage = if (dbObserver.isObserving) {
                                "Observing database"
                            } else {
                                "Not observing database"
                            },
                            lastLogs = observerHelper.lastChatLogs
                        )
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
                            val value = req.rate ?: throw Exception("missing or invalid value")

                            Configurable.dbPollingRate = value
                        }

                        "sendrate" -> {
                            val value = req.rate ?: throw Exception("missing or invalid value")

                            Configurable.messageSendRate = value
                        }

                        "botport" -> {
                            val value = req.port ?: throw Exception("missing or invalid value")

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
                        ReplyType.TEXT -> Replier.sendMessage(
                            notificationReferer, roomId, replyRequest.data.jsonPrimitive.content,
                        )

                        ReplyType.IMAGE -> Replier.sendPhoto(
                            roomId, replyRequest.data.jsonPrimitive.content
                        )

                        ReplyType.IMAGE_MULTIPLE -> Replier.sendMultiplePhotos(
                            roomId,
                            replyRequest.data.jsonArray.map { it.jsonPrimitive.content })
                    }

                    call.respond(ApiResponse(success = true, message = "success"))
                }

                post("/query") {
                    val queryRequest = call.receive<QueryRequest>()

                    try {
                        val rows = kakaoDB.executeQuery(queryRequest.query,
                            (queryRequest.bind?.map { it.content } ?: listOf()).toTypedArray())

                        rows.map {
                            decryptRow(it.toMutableMap())
                        }

                        call.respond(QueryResponse(data = rows))
                    } catch (e: Exception) {
                        throw Exception("Query 오류: query=${queryRequest.query}, err=${e.message}")
                    }
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

    private fun decryptRow(row: Map<String, String?>): MutableMap<String, String?> {
        @Suppress("NAME_SHADOWING") val row = row.toMutableMap()

        try {
            if (row.contains("message") || row.contains("attachment")) {
                val vStr = row.getOrDefault("v", "")
                if (vStr?.isNotEmpty() == true) {
                    try {
                        val vJson = JSONObject(vStr)
                        val enc = vJson.optInt("enc", 0)
                        val userId = row.get("user_id")?.toLongOrNull() ?: Configurable.botId

                        if (row.contains("message")) {
                            val encryptedMessage = row.getOrDefault("message", "")
                            if (encryptedMessage?.isNotEmpty() == true && encryptedMessage != "{}") {
                                try {
                                    row["message"] =
                                        KakaoDecrypt.decrypt(enc, encryptedMessage, userId)
                                } catch (e: Exception) {
                                    System.err.println("Decryption error for message: $e")
                                }
                            }
                        }
                        if (row.contains("attachment")) {
                            val encryptedAttachment = row.getOrDefault("attachment", "")
                            if (encryptedAttachment?.isNotEmpty() == true && encryptedAttachment != "{}") {
                                try {
                                    row["attachment"] =
                                        KakaoDecrypt.decrypt(enc, encryptedAttachment, userId)
                                } catch (e: Exception) {
                                    System.err.println("Decryption error for attachment: $e")
                                }
                                row["attachment"] =
                                    KakaoDecrypt.decrypt(enc, encryptedAttachment, userId)
                            }
                        }
                    } catch (e: JSONException) {
                        System.err.println("Error parsing 'v' for decryption: $e")
                    }
                }
            }

            val botId = Configurable.botId
            val enc = row["enc"]?.toIntOrNull() ?: 0

            if (row.contains("nickname")) {
                try {
                    val encryptedNickname = row.get("nickname")!!
                    row["nickname"] = KakaoDecrypt.decrypt(enc, encryptedNickname, botId)
                } catch (e: Exception) {
                    System.err.println("Decryption error for nickname: $e")
                }
            }

            val urlKeys =
                arrayOf("profile_image_url", "full_profile_image_url", "original_profile_image_url")

            for (urlKey in urlKeys) {
                if (row.contains(urlKey)) {
                    val encryptedUrl = row[urlKey]!!
                    if (encryptedUrl.isNotEmpty()) {
                        try {
                            row[urlKey] = KakaoDecrypt.decrypt(enc, encryptedUrl, botId)
                        } catch (e: Exception) {
                            System.err.println("Decryption error for $urlKey: $e")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            System.err.println("JSON processing error during decryption: $e")
        }

        return row
    }
}
