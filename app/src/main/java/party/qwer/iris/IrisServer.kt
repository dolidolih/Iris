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
import io.ktor.websocket.send
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.util.LinkedList
import party.qwer.iris.model.AotResponse
import party.qwer.iris.model.ApiResponse
import party.qwer.iris.model.CommonErrorResponse
import party.qwer.iris.model.ConfigRequest
import party.qwer.iris.model.ConfigResponse
import party.qwer.iris.model.DashboardStatusResponse
import party.qwer.iris.model.DecryptRequest
import party.qwer.iris.model.DecryptResponse
import party.qwer.iris.model.NicknameObserverStatusResponse
import party.qwer.iris.model.QueryRequest
import party.qwer.iris.model.QueryResponse
import party.qwer.iris.model.ReplyRequest
import party.qwer.iris.model.ReplyType

object NicknameObserverRegistry {
    private const val MAX_EVENTS_STORED = 50
    private val recentEvents = LinkedList<Map<String, String?>>()

    @Volatile
    var isObserving: Boolean = false
        private set

    @Volatile
    var statusMessage: String = "Nickname observer idle"
        private set

    fun updateStatus(isObserving: Boolean, statusMessage: String) {
        this.isObserving = isObserving
        this.statusMessage = statusMessage
    }

    fun recordEvent(event: Map<String, String?>) {
        synchronized(recentEvents) {
            recentEvents.addFirst(normalizeEvent(event))
            while (recentEvents.size > MAX_EVENTS_STORED) {
                recentEvents.removeLast()
            }
        }
    }

    private fun normalizeEvent(event: Map<String, String?>): Map<String, String?> {
        val normalized = LinkedHashMap(event)
        val oldNickname = event["old_nickname"] ?: event["old"]
        val newNickname = event["new_nickname"] ?: event["nickname"] ?: event["new"]

        if (!normalized.containsKey("old_nickname")) {
            normalized["old_nickname"] = oldNickname
        }
        if (!normalized.containsKey("new_nickname")) {
            normalized["new_nickname"] = newNickname
        }
        if (!normalized.containsKey("field") && (oldNickname != null || newNickname != null)) {
            normalized["field"] = "nickname"
        }

        return normalized
    }

    fun snapshotEvents(): List<Map<String, String?>> {
        synchronized(recentEvents) {
            return recentEvents.toList()
        }
    }
}

class IrisServer(
    private val kakaoDB: KakaoDB,
    private val dbObserver: DBObserver,
    private val observerHelper: ObserverHelper,
    private val notificationReferer: String,
    private val wsBroadcastFlow: MutableSharedFlow<String>
) {
    val sharedFlow = wsBroadcastFlow.asSharedFlow()

    fun startServer() {
        embeddedServer(Netty, port = Configurable.botSocketPort) {
            install(WebSockets) {
                contentConverter = KotlinxWebsocketSerializationConverter(Json)
            }

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
                route("/dashboard") {
                    get {
                        val html = PageRenderer.renderDashboard()
                        call.respondText(html, ContentType.Text.Html)
                    }

                    get("status") {
                        call.respond(
                            DashboardStatusResponse(
                                isObserving = dbObserver.isPollingThreadAlive,
                                statusMessage = if (dbObserver.isPollingThreadAlive) {
                                    "Observing database"
                                } else {
                                    "Not observing database"
                                },
                                lastLogs = observerHelper.lastChatLogs
                            )
                        )
                    }

                    get("nickname-status") {
                        call.respond(
                            NicknameObserverStatusResponse(
                                isObserving = NicknameObserverRegistry.isObserving,
                                statusMessage = NicknameObserverRegistry.statusMessage,
                                recentNicknameEvents = NicknameObserverRegistry.snapshotEvents()
                            )
                        )
                    }
                }

                route("/config") {
                    get {
                        call.respond(
                            ConfigResponse(
                                bot_name = Configurable.botName,
                                bot_http_port = Configurable.botSocketPort,
                                web_server_endpoint = Configurable.webServerEndpoint,
                                db_polling_rate = Configurable.dbPollingRate,
                                nickname_observer_rate = Configurable.nicknameObserverRate,
                                message_send_rate = Configurable.messageSendRate,
                                bot_id = Configurable.botId,
                            )
                        )
                    }

                    post("{name}") {
                        val name = call.parameters["name"]
                        val req = call.receive<ConfigRequest>()

                        when (name) {
                            "endpoint" -> {
                                var value = req.endpoint
                                if (value == null) {
                                    value = ""
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

                            "nickobserverrate" -> {
                                val value = req.rate ?: throw Exception("missing or invalid value")
                                if (value < 0) {
                                    throw Exception("nickname observer rate must be greater than or equal to 0")
                                }

                                Configurable.nicknameObserverRate = value
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
                }

                get("/aot") {
                    val aotToken = AuthProvider.getToken()

                    call.respond(
                        AotResponse(
                            success = true,
                            aot = Json.parseToJsonElement(aotToken.toString()).jsonObject
                        )
                    )
                }

                post("/reply") {
                    val replyRequest = call.receive<ReplyRequest>()
                    val roomId = replyRequest.room.toLong()
                    val threadId = replyRequest.threadId?.toLong()

                    when (replyRequest.type) {
                        ReplyType.TEXT -> Replier.sendMessage(
                            notificationReferer,
                            roomId,
                            replyRequest.data.jsonPrimitive.content,
                            threadId
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
                        val rows = kakaoDB.executeQuery(
                            queryRequest.query,
                            (queryRequest.bind?.map { it.content } ?: listOf()).toTypedArray()
                        )

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
                    sharedFlow.collect { msg ->
                        send(msg)
                    }
                }
            }
        }.start(wait = true)
    }
}
