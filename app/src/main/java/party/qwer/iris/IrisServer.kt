package party.qwer.iris

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.KotlinxWebsocketSerializationConverter
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.send
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import party.qwer.iris.model.AotResponse
import party.qwer.iris.model.BulkQueryResponse
import party.qwer.iris.model.ApiResponse
import party.qwer.iris.model.CommonErrorResponse
import party.qwer.iris.model.ConfigRequest
import party.qwer.iris.model.ConfigResponse
import party.qwer.iris.model.DashboardStatusResponse
import party.qwer.iris.model.DecryptRequest
import party.qwer.iris.model.DecryptResponse
import party.qwer.iris.model.JsonPayloadResponse
import party.qwer.iris.model.ProcessCommandRequest
import party.qwer.iris.model.ProcessStatusResponse
import party.qwer.iris.model.QueryRequest
import party.qwer.iris.model.QueryResponse
import party.qwer.iris.model.RoomInfo
import party.qwer.iris.model.RoomListResponse
import party.qwer.iris.model.SearchHit
import party.qwer.iris.model.SearchRequest
import party.qwer.iris.model.SearchResponse
import party.qwer.iris.model.SqlCipherStatus
import java.io.File

/**
 * IrisServer — `app_process` 데몬이 여는 HTTP 서버.
 *
 * 이벤트 브로드캐스트(`/ws`)·답장(`/reply`)·조회(`/query`, `/chat`, `/room`, `/user`,
 * `/search`, `/reactions`)·설정(`/config`)·상태(`/process-status`, `/dashboard/status`)를
 * 한 포트에서 제공한다. GUI 가 없는 데몬이므로 상태·설정 화면은 `/dashboard` HTML 한 장이
 * 대신한다.
 *
 * 스레드 규약:
 *  - start/stop 은 Main, HTTP 핸들러(`/process-command`), shutdown hook 어디서나 올 수 있다
 *    — 모든 변경부는 @Synchronized 로 직렬화하고 공개 필드는 @Volatile 로 발행한다.
 *  - /ws 전달은 non-blocking fanout — 느린 클라이언트 하나가 DB 폴링을 막을 수 없다.
 */
class IrisServer(private val kakaoDB: KakaoDB) {

    private val wsFanout = WsFanout("IrisServer")

    /** /chat 조회 경로용 ObserverHelper — poller 와 별개로 읽기만 한다. */
    private val readHelper: ObserverHelper = ObserverHelper.forReads(kakaoDB)

    @Volatile
    private var engineRef: EmbeddedServer<*, *>? = null

    @Volatile
    private var _isRunning = false

    /** KakaoTalk 의 NotificationReferer — shared_prefs XML 에서 추출 (답장 시 필요). */
    @Volatile
    private var notificationReferer: String = "Iris"

    /**
     * stop 시 데몬 워스(DB poller, 이미지 정리, 송신기)까지 함께 내리기 위한 훅 — Main 이 등록한다.
     * null 이면 기존 동작(엔진만 정리)과 동일.
     */
    @Volatile
    var workerStopHook: Runnable? = null

    /**
     * /reply 핸들러용 CoroutineScope. stop() 가 cancel 하므로 start() 는 매번 새 scope 을
     * 만든다 — 재사용하면 restart 이후 답장이 조용히 전부 폐기된다.
     */
    @Volatile
    private var serverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** WS 이벤트 전달 (non-blocking). @return 버퍼 만충으로 폐기한 건수. */
    fun broadcastToClients(message: String): Long = wsFanout.publish(message)

    /** ws 에 붙어있는 클라이언트 수. */
    val wsSubscribers: Int get() = wsFanout.subscriberCount

    val isRunning: Boolean
        get() = _isRunning && engineRef != null

    /** 직전 기동 실패 원인(포트 점유 등) — 정지 시 초기화. */
    var lastError: String? = null
        private set

    /** 기동 실패 원인이 포트 점유인지 판단. */
    val isPortInUseError: Boolean
        get() = lastError?.let {
            val l = it.lowercase()
            l.contains("address already in use") || l.contains("bindexception") ||
                l.contains("already in use")
        } == true

    /**
     * KakaoTalk shared_prefs XML 에서 NotificationReferer 추출.
     * 실패해도 답장 자체는 동작하므로 기본값으로 넘어간다.
     */
    private fun extractNotificationReferer(): String {
        try {
            val appPath = PathUtils.getAppPath()
            val prefsFile = File("${appPath}shared_prefs/KakaoTalk.hw.perferences.xml")
            if (prefsFile.exists() && prefsFile.canRead()) {
                val data = prefsFile.readText()
                val regex = Regex("""<string name="NotificationReferer">(.*?)</string>""")
                val match = regex.find(data)
                if (match != null && match.groups[1] != null) {
                    val referer = match.groups[1]!!.value
                    println("IrisServer: NotificationReferer extracted: $referer")
                    return referer
                }
            }
        } catch (e: Exception) {
            println("IrisServer: Failed to extract NotificationReferer: ${e.message}")
        }
        return "Iris"
    }

    @Synchronized
    fun start(): Boolean {
        if (engineRef != null) return true
        try {
            // 이전 stop() 가 cancel 한 scope 이 있으면 새 scope 으로 교체한다.
            val previous = serverScope
            serverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            if (previous.coroutineContext[Job]?.isActive == true) {
                runCatching { previous.cancel() }
            }

            notificationReferer = extractNotificationReferer()
            val lenientJson = Json { ignoreUnknownKeys = true }

            // 기본 바인드는 0.0.0.0 — 원격 클라이언트가 기기 IP:포트로 접속한다.
            engineRef = embeddedServer(Netty, host = "0.0.0.0", port = Configurable.botSocketPort) {
                install(WebSockets) {
                    contentConverter = KotlinxWebsocketSerializationConverter(lenientJson)
                    // 우리 /ws 핸들러는 send 만 하고 recv 하지 않아서, peer 가 FIN 을 보내도
                    // 핸들러 코루틴이 깨어나지 못한다. ping 이 없으면 끊긴 구독자가
                    // ws_subscribers 에 계속 쌓이고(죽은 채널에 trySend 성공 → drop 도 안 잡힘)
                    // fanout 버퍼만 축낸다. 30s ping / 90s timeout 으로 죽은 쪽을 치운다.
                    pingPeriodMillis = 30_000L
                    timeoutMillis = 90_000L
                }
                install(ContentNegotiation) { json(lenientJson) }
                install(StatusPages) {
                    exception<Throwable> { call, cause ->
                        call.respond(
                            HttpStatusCode.InternalServerError,
                            CommonErrorResponse(message = cause.message ?: "unknown error")
                        )
                    }
                }

                routing {
                    // ── WebSocket ──────────────────────────────
                    webSocket("/ws") {
                        // 연결별 수신 채널 — 느린 클라이언트 하나가 다른 connection/
                        // polling thread 를 막지 못한다.
                        val sub = wsFanout.subscribe()
                        try {
                            for (msg in sub.channel) {
                                send(msg)
                            }
                        } finally {
                            wsFanout.unsubscribe(sub)
                        }
                    }

                    // ── 답장 ───────────────────────────────────
                    post("/reply") {
                        val req = call.receive<party.qwer.iris.model.ReplyRequest>()
                        val roomId = req.room

                        when (req.type) {
                            "text" -> handleTextReply(roomId, req.data.jsonPrimitive.content, req.threadId)
                            "image", "image_multiple" ->
                                handleMediaReply(roomId, MediaPayload.parseImages(req.data))
                            "media", "video", "audio", "file" -> {
                                val kind = when (req.type) {
                                    "video" -> MediaKind.VIDEO
                                    "audio" -> MediaKind.AUDIO
                                    else -> MediaKind.FILE
                                }
                                val item = MediaPayload.parseSingle(req.data, kind)
                                if (item == null) {
                                    call.respond(ApiResponse(success = false, message = "Invalid media payload"))
                                    return@post
                                }
                                handleMediaReply(roomId, listOf(item))
                            }
                            else -> {
                                call.respond(
                                    ApiResponse(success = false, message = "Unknown reply type: ${req.type}")
                                )
                                return@post
                            }
                        }
                        call.respond(ApiResponse(success = true, message = "Enqueued"))
                    }

                    // ── 설정 ───────────────────────────────────
                    route("/config") {
                        get {
                            call.respond(
                                ConfigResponse(
                                    bot_name = Configurable.botName,
                                    bot_http_port = Configurable.botSocketPort,
                                    web_server_endpoint = Configurable.webServerEndpoint,
                                    db_polling_rate = Configurable.dbPollingRate,
                                    message_send_rate = Configurable.messageSendRate,
                                    bot_id = Configurable.botId,
                                    broadcast_types = Configurable.broadcastTypes,
                                    include_system_events = Configurable.includeSystemEvents,
                                    enable_extension = Configurable.enableExtension
                                )
                            )
                        }

                        post("{name}") {
                            val name = call.parameters["name"]
                            val req = call.receive<ConfigRequest>()

                            when (name) {
                                "endpoint" -> Configurable.webServerEndpoint = req.endpoint ?: ""

                                "botname" -> {
                                    val value = req.botname
                                    if (value.isNullOrBlank()) throw Exception("missing or empty value")
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
                                    // 실행 중인 엔진은 이전 바인딩을 유지한다. 조용히 넘어가는
                                    // "성공" 응답 대신 재시작 필요를 명시한다.
                                    return@post call.respond(
                                        ApiResponse(
                                            success = true,
                                            message = "saved; engine keeps port ${Configurable.botSocketPort} until restart"
                                        )
                                    )
                                }

                                "types" -> {
                                    val value = req.types ?: throw Exception("missing value")
                                    if (value.isNotEmpty()) {
                                        val invalid = value.filter { it !in KakaoMessageType.VALID_FILTERS }
                                        if (invalid.isNotEmpty())
                                            throw Exception("Invalid broadcast type(s): $invalid")
                                    }
                                    // 빈 list = 필터 해제(null, 현행 동작)
                                    Configurable.broadcastTypes = value.ifEmpty { null }
                                }

                                "extension" -> {
                                    val value = req.enable ?: throw Exception("missing or invalid value")
                                    Configurable.enableExtension = value
                                }

                                "systemevents" -> {
                                    val value = req.enable ?: throw Exception("missing or invalid value")
                                    Configurable.includeSystemEvents = value
                                }

                                else -> throw Exception("Unknown config $name")
                            }

                            call.respond(ApiResponse(success = true, message = "success"))
                        }
                    }

                    // ── 최근 채팅방 목록 (한글 방이름 해석 포함) ──
                    get("/rooms") {
                        try {
                            val limit = (call.request.queryParameters["limit"]?.toIntOrNull()
                                ?: 30).coerceIn(1, 100)
                            val rooms = kakaoDB.getRecentRooms(limit).map {
                                RoomInfo(
                                    id = it["id"] ?: "",
                                    name = it["name"],
                                    updated_at = it["updated_at"]
                                )
                            }
                            call.respond(RoomListResponse(rooms = rooms))
                        } catch (e: Exception) {
                            call.respond(RoomListResponse(rooms = emptyList()))
                        }
                    }

                    // ── path 기반 read-only 조회 API ────────────
                    route("/user") {
                        get("/name/{id}") {
                            val id = call.parameters["id"]?.toLongOrNull()
                                ?: return@get call.respond(ApiResponse(false, "invalid user id"))
                            try {
                                val name = kakaoDB.getUserProfile(id).first
                                call.respond(JsonPayloadResponse(payload = buildJsonObject {
                                    put("name", name?.let { JsonPrimitive(it) } ?: JsonNull)
                                }))
                            } catch (e: Exception) {
                                call.respond(JsonPayloadResponse(payload = JsonNull, error = e.message))
                            }
                        }
                        get("/{id}") {
                            val id = call.parameters["id"]?.toLongOrNull()
                                ?: return@get call.respond(ApiResponse(false, "invalid user id"))
                            try {
                                val (name, url) = kakaoDB.getUserProfile(id)
                                call.respond(JsonPayloadResponse(payload = buildJsonObject {
                                    put("id", JsonPrimitive(id))
                                    put("name", name?.let { JsonPrimitive(it) } ?: JsonNull)
                                    put("profile_image_url", url?.let { JsonPrimitive(it) } ?: JsonNull)
                                }))
                            } catch (e: Exception) {
                                call.respond(JsonPayloadResponse(payload = JsonNull, error = e.message))
                            }
                        }
                    }

                    route("/room") {
                        get("/name/{id}") {
                            val id = call.parameters["id"]?.toLongOrNull()
                                ?: return@get call.respond(ApiResponse(false, "invalid room id"))
                            try {
                                val name = kakaoDB.getChatRoomMeta(id)["name"] as? String
                                call.respond(JsonPayloadResponse(payload = buildJsonObject {
                                    put("name", name?.let { JsonPrimitive(it) } ?: JsonNull)
                                }))
                            } catch (e: Exception) {
                                call.respond(JsonPayloadResponse(payload = JsonNull, error = e.message))
                            }
                        }
                        get("/{id}") {
                            val id = call.parameters["id"]?.toLongOrNull()
                                ?: return@get call.respond(ApiResponse(false, "invalid room id"))
                            try {
                                call.respond(JsonPayloadResponse(payload = toJsonElement(kakaoDB.getChatRoomMeta(id))))
                            } catch (e: Exception) {
                                call.respond(JsonPayloadResponse(payload = JsonNull, error = e.message))
                            }
                        }
                        get("/{id}/members") {
                            val id = call.parameters["id"]?.toLongOrNull()
                                ?: return@get call.respond(ApiResponse(false, "invalid room id"))
                            try {
                                val members = kakaoDB.getRoomMembers(id)
                                call.respond(JsonPayloadResponse(payload = buildJsonObject {
                                    put("members", toJsonElement(members))
                                }))
                            } catch (e: Exception) {
                                call.respond(JsonPayloadResponse(payload = JsonNull, error = e.message))
                            }
                        }
                        get("/{id}/links") {
                            val id = call.parameters["id"]?.toLongOrNull()
                                ?: return@get call.respond(ApiResponse(false, "invalid room id"))
                            try {
                                val linkId = kakaoDB.linkIdOfChat(id)
                                call.respond(
                                    JsonPayloadResponse(
                                        payload = toJsonElement(linkId?.let { kakaoDB.getOpenLink(it) })
                                    )
                                )
                            } catch (e: Exception) {
                                call.respond(JsonPayloadResponse(payload = JsonNull, error = e.message))
                            }
                        }
                    }

                    get("/reactions/{id}") {
                        val id = call.parameters["id"]?.toLongOrNull()
                            ?: return@get call.respond(ApiResponse(false, "invalid log id"))
                        try {
                            val rx = kakaoDB.getReactions(id)
                            call.respond(JsonPayloadResponse(payload = buildJsonObject {
                                put("reactions", toJsonElement(rx))
                            }))
                        } catch (e: Exception) {
                            call.respond(JsonPayloadResponse(payload = JsonNull, error = e.message))
                        }
                    }

                    // ── /chat — 채팅 로그 조회 (/ws 프레임과 동일한 형식) ──
                    // id 는 확장자의 log_id(스노우플레이크)와 같은 계열 — /reactions/{id} 와 동일.
                    route("/chat") {
                        // 방(chat_id) 기준 메시지 페이지네이션. item[] 은 /ws 프레임과 동일.
                        get("/{room}/messages") {
                            val room = call.parameters["room"]?.toLongOrNull()
                                ?: return@get call.respond(ApiResponse(false, "invalid room id"))
                            val q = call.request.queryParameters
                            for (name in listOf(
                                "limit", "before", "after", "around", "user_id",
                                "from_created_at", "to_created_at"
                            )) {
                                if (q[name] != null && q[name]!!.toLongOrNull() == null)
                                    return@get call.respond(ApiResponse(false, "invalid '$name'"))
                            }
                            val anchor = listOfNotNull(
                                q["before"]?.toLongOrNull()?.let { "before" to it },
                                q["after"]?.toLongOrNull()?.let { "after" to it },
                                q["around"]?.toLongOrNull()?.let { "around" to it }
                            )
                            if (anchor.size > 1) return@get call.respond(
                                ApiResponse(false, "before/after/around are mutually exclusive")
                            )
                            val (cursorVal, cursorOp, orderDesc) = when (anchor.firstOrNull()?.first) {
                                "before" -> Triple(anchor.first().second, "<", true)
                                "after" -> Triple(anchor.first().second, ">", false)
                                "around" -> Triple(anchor.first().second, "<=", true)
                                else -> Triple(Long.MAX_VALUE, "<", true)
                            }
                            val types = q["types"]?.split(",")?.map { it.trim() }
                                ?.filter { it.isNotEmpty() }
                            if (types != null) {
                                val invalid = types.filter { it !in KakaoMessageType.VALID_FILTERS }
                                if (invalid.isNotEmpty())
                                    return@get call.respond(ApiResponse(false, "Invalid type(s): $invalid"))
                            }
                            val limit = (q["limit"]?.toIntOrNull() ?: 50).coerceIn(1, 100)
                            try {
                                val (items, nextCursor, hasMore) = readHelper.messageFramesPage(
                                    room, cursorVal, cursorOp, limit, orderDesc,
                                    q["user_id"]?.toLongOrNull(), types,
                                    q["from_created_at"]?.toLongOrNull(),
                                    q["to_created_at"]?.toLongOrNull()
                                )
                                call.respond(JsonPayloadResponse(payload = buildJsonObject {
                                    putJsonArray("items") { items.forEach { add(toJsonElement(it)) } }
                                    put(
                                        "next_cursor",
                                        nextCursor?.let { JsonPrimitive(it.toString()) } ?: JsonNull
                                    )
                                    put("has_more", hasMore)
                                }))
                            } catch (e: Exception) {
                                call.respond(JsonPayloadResponse(payload = JsonNull, error = e.message))
                            }
                        }
                        get("/{id}") {
                            val id = call.parameters["id"]?.toLongOrNull()
                                ?: return@get call.respond(ApiResponse(false, "invalid log id"))
                            try {
                                val frame = readHelper.frameForLogId(id)
                                    ?: return@get call.respond(ApiResponse(false, "log not found"))
                                call.respond(JsonPayloadResponse(payload = toJsonElement(frame)))
                            } catch (e: Exception) {
                                call.respond(JsonPayloadResponse(payload = JsonNull, error = e.message))
                            }
                        }
                        // 삭제된 original 메시지 회수 조회. 삭제 마크 id 와 원본 id 어느 쪽을
                        // 넣어도 동일하게 동작한다.
                        get("/{id}/deleted") {
                            val id = call.parameters["id"]?.toLongOrNull()
                                ?: return@get call.respond(ApiResponse(false, "invalid log id"))
                            try {
                                val out = readHelper.deletedRecoveryFor(id)
                                    ?: return@get call.respond(ApiResponse(false, "not a deleted message"))
                                call.respond(JsonPayloadResponse(payload = toJsonElement(out)))
                            } catch (e: Exception) {
                                call.respond(JsonPayloadResponse(payload = JsonNull, error = e.message))
                            }
                        }
                        get("/{id}/prev/{n}") {
                            val (id, n) = parseChatOffset(call)
                                ?: return@get call.respond(ApiResponse(false, "invalid id or offset"))
                            respondRelative(call, id, -n)
                        }
                        get("/{id}/before/{n}") {
                            val (id, n) = parseChatOffset(call)
                                ?: return@get call.respond(ApiResponse(false, "invalid id or offset"))
                            respondRelative(call, id, -n)
                        }
                        get("/{id}/next/{n}") {
                            val (id, n) = parseChatOffset(call)
                                ?: return@get call.respond(ApiResponse(false, "invalid id or offset"))
                            respondRelative(call, id, n)
                        }
                        get("/{id}/after/{n}") {
                            val (id, n) = parseChatOffset(call)
                                ?: return@get call.respond(ApiResponse(false, "invalid id or offset"))
                            respondRelative(call, id, n)
                        }
                        // days: 소수 허용(0.5 == 12시간). days 보다 오래된 chat_logs 삭제.
                        delete("/logs") {
                            try {
                                val raw = call.request.queryParameters["days"]
                                val days = raw?.toDoubleOrNull()
                                    ?: return@delete call.respond(ApiResponse(false, "missing 'days'"))
                                if (days <= 0.0) return@delete call.respond(
                                    ApiResponse(false, "'days' must be positive")
                                )
                                val deleted = readHelper.purgeChatLogsOlderThan(days)
                                call.respond(JsonPayloadResponse(payload = buildJsonObject {
                                    put("deleted", JsonPrimitive(deleted))
                                    put("cutoff_days", JsonPrimitive(days))
                                }))
                            } catch (e: Exception) {
                                call.respond(JsonPayloadResponse(payload = JsonNull, error = e.message))
                            }
                        }
                    }

                    // ── SQL 쿼리 (단일 + 벌크) ──────────────────
                    route("/query") {
                        post {
                            val req = call.receive<QueryRequest>()
                            try {
                                val bulk = req.queries?.takeIf { it.isNotEmpty() }
                                if (bulk != null) {
                                    val data = bulk.map { one ->
                                        queryOne(one.query, one.bind)
                                    }
                                    call.respond(BulkQueryResponse(data = data))
                                } else {
                                    val sql = req.query ?: throw Exception("missing query")
                                    call.respond(
                                        QueryResponse(data = queryOne(sql, req.bind))
                                    )
                                }
                            } catch (e: Exception) {
                                call.respond(QueryResponse(data = emptyList(), error = e.message))
                            }
                        }
                    }

                    // ── crypto_database 全文 검색 ───────────────
                    route("/search") {
                        post {
                            val req = call.receive<SearchRequest>()
                            try {
                                val hasFilter = req.room != null || req.user_id != null ||
                                    !req.types.isNullOrEmpty() || req.from_created_at != null ||
                                    req.to_created_at != null
                                val window = if (hasFilter) minOf(req.limit * 8, 500) else req.limit
                                // chat_log_search(crypto_database) 에는 방/보낸이/시각 컬럼이 없고
                                // db1 SQL 로 조인도 불가 → id 만 뽑아 db1.chat_logs 에서 되읽는다.
                                val raw = CryptoDatabaseReader.searchMessages(req.query, window)
                                val hits = if (!hasFilter) {
                                    raw.map { SearchHit(id = it.first, preview = it.second) }
                                } else {
                                    readHelper.enrichChatHits(raw)
                                        .filter { row ->
                                            val ts = (row["created_at"] as? String)?.toLongOrNull() ?: 0L
                                            (req.room == null || row["chat_id"] == req.room.toString()) &&
                                                (req.user_id == null || row["user_id"] == req.user_id.toString()) &&
                                                (req.types.isNullOrEmpty() || row["type_name"] in req.types!!) &&
                                                (req.from_created_at == null || ts >= req.from_created_at!!) &&
                                                (req.to_created_at == null || ts <= req.to_created_at!!)
                                        }
                                        .take(req.limit)
                                        .map { row ->
                                            SearchHit(
                                                id = row["id"] as Long,
                                                preview = row["preview"] as String,
                                                chat_id = row["chat_id"] as String?,
                                                user_id = row["user_id"] as String?,
                                                created_at = row["created_at"] as String?,
                                                type_name = row["type_name"] as String?,
                                                room_name = row["room_name"] as String?,
                                                sender_name = row["sender_name"] as String?
                                            )
                                        }
                                }
                                call.respond(SearchResponse(hits = hits))
                            } catch (e: Exception) {
                                call.respond(SearchResponse(hits = emptyList(), error = e.message))
                            }
                        }
                    }

                    // ── 복호화 ─────────────────────────────────
                    route("/decrypt") {
                        post {
                            val req = call.receive<DecryptRequest>()
                            try {
                                val plain = KakaoDecrypt.decrypt(
                                    req.enc,
                                    req.b64_ciphertext,
                                    req.user_id ?: Configurable.botId
                                )
                                call.respond(DecryptResponse(plain_text = plain))
                            } catch (e: Exception) {
                                call.respond(DecryptResponse(plain_text = "Error: ${e.message}"))
                            }
                        }
                    }

                    // ── AOT 토큰 ───────────────────────────────
                    get("/aot") {
                        try {
                            val aot = AuthProvider.getToken()
                            call.respond(
                                AotResponse(success = true, aot = Json.parseToJsonElement(aot.toString()))
                            )
                        } catch (e: Exception) {
                            call.respond(AotResponse(success = false, aot = JsonNull))
                        }
                    }

                    // ── API 명세 (Swagger UI / OpenAPI JSON) ────
                    get("/openapi.json") {
                        call.respondText(
                            OpenApiSpec.render(Configurable.botSocketPort, host = call.request.headers[HttpHeaders.Host]),
                            ContentType.Application.Json
                        )
                    }
                    get("/swagger") {
                        call.respondText(OpenApiSpec.html(), ContentType.Text.Html)
                    }

                    // ── 데몬 상태 / 제어 ───────────────────────
                    get("/process-status") {
                        call.respond(
                            ProcessStatusResponse(
                                server_running = _isRunning,
                                port = Configurable.botSocketPort,
                                db_observing = AppState.isObserving,
                                bot_id = Configurable.botId,
                                bot_id_resolved = Configurable.botIdResolved,
                                bot_name = Configurable.botName,
                                bot_http_port = Configurable.botSocketPort,
                                web_server_endpoint = Configurable.webServerEndpoint,
                                db_polling_rate = Configurable.dbPollingRate,
                                message_send_rate = Configurable.messageSendRate,
                                uptime_ms = System.currentTimeMillis() - AppState.startedAtMs,
                                logs = RuntimeLog.snapshot(limit = 60),
                                last_logs = AppState.lastChatLogs,
                                sqlcipher = sqlCipherStatus(),
                                ws_subscribers = wsFanout.subscriberCount
                            )
                        )
                    }

                    post("/process-command") {
                        val req = call.receive<ProcessCommandRequest>()
                        when (req.command) {
                            "stop" -> {
                                // 자기 엔진의 요청 스레드에서 stop() 하면 in-flight(자신 포함)
                                // 대기 + 응답 미전달 → 클라이언트가 "실패"로 오인한다.
                                // 먼저 응답하고 내려온다.
                                call.respond(ApiResponse(success = true, message = "Process stopping"))
                                scheduleLifecycle { stop() }
                            }
                            "restart" -> {
                                call.respond(ApiResponse(success = true, message = "Process restarting"))
                                scheduleLifecycle {
                                    stop()
                                    // engine.stop() 내부 join 으로 포트는 반납되지만 SO_REUSE
                                    // 편승을 위해 짧게 대기한다.
                                    runCatching { Thread.sleep(300) }
                                    start()
                                }
                            }
                            else -> call.respond(
                                ApiResponse(success = false, message = "Unknown command: ${req.command}")
                            )
                        }
                    }

                    // ── 대시보드 (GUI 가 없는 데몬의 유일한 설정/상태 화면) ──
                    route("/dashboard") {
                        get {
                            call.respondText(PageRenderer.renderDashboard(), ContentType.Text.Html)
                        }
                        get("status") {
                            val observing = AppState.isObserving
                            call.respond(
                                DashboardStatusResponse(
                                    isObserving = observing,
                                    statusMessage = if (observing) "Observing database" else "Not observing database",
                                    lastLogs = AppState.lastChatLogs
                                )
                            )
                        }
                    }
                }
            }.start(wait = false)

            println("IrisServer: Started on port ${Configurable.botSocketPort}, referer=$notificationReferer")
            _isRunning = true
            return true
        } catch (e: Exception) {
            System.err.println("IrisServer failed to start: ${e.message}")
            e.printStackTrace()
            engineRef = null
            _isRunning = false
            lastError = e.message ?: e.javaClass.simpleName
            return false
        }
    }

    /** SQLCipher 네이티브/암호화 DB 접근 상태 — 조용한 폴백의 1 차 진단. */
    private fun sqlCipherStatus(): SqlCipherStatus {
        val loaded = runCatching { SqlCipherNative.ensureLoaded() }.getOrDefault(false)
        return SqlCipherStatus(
            loaded = loaded,
            path = SqlCipherNative.loadedPath,
            source = SqlCipherNative.source,
            foreign = runCatching { SqlCipherNative.isForeignLibrary() }.getOrNull(),
            crypto_database_readable = if (loaded) runCatching { CryptoDatabaseReader.open() }.getOrNull() else false,
            crypto_user_database_readable = if (loaded) runCatching { CryptoUserDatabaseReader.open() }.getOrNull() else false
        )
    }

    /** /query 의 단일 질의 — decryptRow 적용까지. */
    private fun queryOne(sql: String?, bind: List<JsonPrimitive>?): List<Map<String, String?>> {
        if (sql.isNullOrEmpty()) throw Exception("missing query")
        val bindArgs = bind?.map { it.content as String? }?.toTypedArray() ?: emptyArray()
        return kakaoDB.executeQuery(sql, bindArgs).map { KakaoDB.decryptRow(it) }
    }

    /**
     * stop/restart 는 엔진 자신의 요청 스레드에서 수행하면 자기 엔진의 grace timeout 을
     * 태우고 응답 자체가 미전달된다. 응답을 먼저 내보낸 뒤 detached 스레드에서 지연 실행한다.
     */
    private fun scheduleLifecycle(action: () -> Unit) {
        Thread {
            runCatching { Thread.sleep(150) }
            runCatching { action() }
                .onFailure { println("IrisServer: deferred lifecycle action failed: ${it.message}") }
        }.apply { isDaemon = true; name = "IrisServer-lifecycle" }.start()
    }

    /** `/chat/{id}/prev/{n}` 류 경로 파라미터 파싱. 실패 시 null. */
    private fun parseChatOffset(call: ApplicationCall): Pair<Long, Int>? {
        val id = call.parameters["id"]?.toLongOrNull() ?: return null
        val n = call.parameters["n"]?.toIntOrNull() ?: return null
        if (n <= 0) return null
        return id to n
    }

    /** 동일 채팅방 내 n번째 전후(offset 음수=이전) 메시지를 /ws 프레임 포맷으로 응답. */
    private suspend fun respondRelative(call: ApplicationCall, id: Long, offset: Int) {
        try {
            val frame = readHelper.frameRelative(id, offset)
            if (frame == null) {
                call.respond(ApiResponse(false, "message not found"))
            } else {
                call.respond(JsonPayloadResponse(payload = toJsonElement(frame)))
            }
        } catch (e: Exception) {
            call.respond(JsonPayloadResponse(payload = JsonNull, error = e.message))
        }
    }

    private fun toJsonElement(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is String -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Int -> JsonPrimitive(value)
        is Long -> JsonPrimitive(value)
        is Short -> JsonPrimitive(value.toInt())
        is Double -> JsonPrimitive(value)
        is Float -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is Map<*, *> -> buildJsonObject {
            for ((k, v) in value) put(k.toString(), toJsonElement(v))
        }
        is List<*> -> buildJsonArray {
            for (item in value) add(toJsonElement(item))
        }
        else -> JsonPrimitive(value.toString())
    }

    // ── 답장 dispatch ────────────────────────────────────

    /**
     * 텍스트 답장 — notificationReferer 를 Replier 에 전달.
     * irispy-client 규약상 /ws 는 `{msg, room, sender, json}` 형태만 허용하므로 답장 ACK 은
     * 브로드캐스트하지 않고 로그만 남긴다.
     */
    private fun handleTextReply(roomId: String, text: String, threadId: String?) {
        val referer = notificationReferer
        serverScope.launch {
            try {
                val chatId = roomId.toLongOrNull()
                if (chatId != null) {
                    Replier.sendMessage(referer, chatId, text, threadId?.toLongOrNull())
                }
                val safeText = text.replace("\n", "\\n")
                println("IrisServer: text reply sent room=$roomId len=${safeText.length}")
            } catch (e: Exception) {
                System.err.println("IrisServer Replier error: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    /** image / image_multiple 답장. */
    private fun handleMediaReply(roomId: String, items: List<MediaItem>) {
        serverScope.launch {
            try {
                val chatId = roomId.toLongOrNull()
                if (chatId != null) {
                    Replier.sendMedia(chatId, items)
                }
                println("IrisServer: media reply sent room=$roomId count=${items.size}")
            } catch (e: Exception) {
                System.err.println("IrisServer Replier media error: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    /**
     * 리소스 정리. 먼저 수신을 끊는다(new requests 차단, in-flight join) — handler 가
     * DB/scope 에 접근하지 않는 상태가 된 뒤에 워스를 정리한다.
     */
    @Synchronized
    fun stop() {
        engineRef?.let {
            try {
                it.stop(1000L, 2000L)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        engineRef = null
        _isRunning = false
        lastError = null
        serverScope.cancel()
        runCatching { workerStopHook?.run() }
            .onFailure { println("IrisServer: worker stop hook failed: ${it.message}") }
    }
}
