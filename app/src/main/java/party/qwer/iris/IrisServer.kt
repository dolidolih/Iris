package party.qwer.iris

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.KotlinxWebsocketSerializationConverter
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
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonObject
import party.qwer.iris.model.AotResponse
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


class IrisServer(
    private val kakaoDB: KakaoDB,
    private val dbObserver: DBObserver,
    private val observerHelper: ObserverHelper,
    private val notificationReferer: String,
    private val wsBroadcastFlow: MutableSharedFlow<String>
) {
@@ -144,79 +153,134 @@ class IrisServer(
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
                }

                get("/aot") {
                    val aotToken = AuthProvider.getToken()

                    call.respond(
                        AotResponse(success = true, aot = Json.parseToJsonElement(aotToken.toString()).jsonObject)
                    )
                }

                post("/reply") {
                    val replyRequest = call.receive<ReplyRequest>()
                    handleReplyRequest(replyRequest)

                    call.respond(ApiResponse(success = true, message = "success"))
                }

                post("/query") {
                    val queryRequest = call.receive<QueryRequest>()

                    try {
                        val rows = kakaoDB.executeQuery(queryRequest.query,
                            (queryRequest.bind?.map { it.content } ?: listOf()).toTypedArray())

                        call.respond(QueryResponse(data = rows.map {
                            KakaoDB.decryptRow(it)
                        }))
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

                webSocket("/ws") {
                    val sendMutex = Mutex()
                    val broadcastJob = launch {
                        sharedFlow.collect { msg ->
                            sendMutex.withLock {
                                send(msg)
                            }
                        }
                    }

                    try {
                        for (frame in incoming) {
                            if (frame is Frame.Text) {
                                val payload = frame.readText()
                                if (payload.isBlank()) {
                                    continue
                                }

                                try {
                                    val replyRequest = Json.decodeFromString<ReplyRequest>(payload)
                                    handleReplyRequest(replyRequest)
                                    sendMutex.withLock {
                                        send(
                                            Json.encodeToString<ApiResponse<String>>(ApiResponse(
                                                success = true,
                                                message = "success",
                                            ))
                                        )
                                    }
                                } catch (e: SerializationException) {
                                    println("Failed to parse WebSocket payload as ReplyRequest: ${e.message}")
                                    sendMutex.withLock {
                                        send(
                                            Json.encodeToString<ApiResponse<String>>(ApiResponse(
                                                success = false,
                                                message = "Invalid payload",
                                            ))
                                        )
                                    }
                                } catch (e: Exception) {
                                    println("Failed to handle WebSocket reply request: ${e.message}")
                                    sendMutex.withLock {
                                        send(
                                            Json.encodeToString<ApiResponse<String>>(ApiResponse(
                                                success = false,
                                                message = e.message ?: "unknown error",
                                            ))
                                        )
                                    }
                                }
                            }
                        }
                    } finally {
                        broadcastJob.cancelAndJoin()
                    }
                }
            }
        }.start(wait = true)
    }

    private fun handleReplyRequest(replyRequest: ReplyRequest) {
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
    }
}