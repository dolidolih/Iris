@file:Suppress("PropertyName")

package party.qwer.iris.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import party.qwer.iris.RuntimeLog

// ── path 기반 read-only 조회 API (info provision) ────────
// payload 는 실제 JSON 객체를 그대로 싣기 위해 JsonElement 를 쓴다
// (Map<String, Any?> 는 kotlinx.serialization 불가).

@Serializable
data class JsonPayloadResponse(
    val payload: JsonElement,
    val error: String? = null
)

// ── 방 목록 ────────────────────────────────────────────

@Serializable
data class RoomInfo(
    val id: String,
    val name: String? = null,
    val updated_at: String? = null
)

@Serializable
data class RoomListResponse(
    val rooms: List<RoomInfo> = emptyList()
)

// ── crypto_database 全文 검색 API ────────────────────────

@Serializable
data class SearchRequest(
    val query: String,
    val limit: Int = 50,
    // 선택 필터 — 미지정 시 응답은 {"query","limit"} 만 보낸 현행과 동일.
    val room: Long? = null,
    val user_id: Long? = null,
    val types: List<String>? = null,
    val from_created_at: Long? = null,
    val to_created_at: Long? = null
)

@Serializable
data class SearchHit(
    val id: Long,
    val preview: String,
    // 필터 적용 시에만 채워지는 enrichment 필드 (미지정 경로는 null 유지 → 기존 클라이언트 무영향).
    val chat_id: String? = null,
    val user_id: String? = null,
    val created_at: String? = null,
    val type_name: String? = null,
    val room_name: String? = null,
    val sender_name: String? = null
)

@Serializable
data class SearchResponse(
    val hits: List<SearchHit>,
    val error: String? = null
)

// ── 데몬 상태 / 제어 API ────────────────────────────────

@Serializable
data class ProcessStatusResponse(
    // 상태 필드는 전부 nullable + 기본값 — 필수 필드가 하나 빠져도 전체 디코딩이 실패해
    // "살아있는 데몬"을 "정지"로 오탐하는 일을 막기 위해.
    val server_running: Boolean? = null,
    val port: Int? = null,
    val db_observing: Boolean? = null,
    val bot_id: Long? = null,
    val bot_id_resolved: Boolean? = null,
    val bot_name: String? = null,
    val bot_http_port: Int? = null,
    val web_server_endpoint: String? = null,
    val db_polling_rate: Long? = null,
    val message_send_rate: Long? = null,
    val uptime_ms: Long? = null,
    /** 데emon stdout/stderr 로그 (최신 선행). */
    val logs: List<RuntimeLog.Entry> = emptyList(),
    /** 최근 DB 채팅 로그 (raw 행, 최신 선행). */
    val last_logs: List<Map<String, String?>> = emptyList(),
    /** SQLCipher 네이티브 로딩 상태 — crypto DB 조회가 조용히 죽은 경우의 1 차 진단. */
    val sqlcipher: SqlCipherStatus? = null,
    /** 지금 붙어있는 /ws 구독자 수. 이벤트가 안 나오는데 이 값이 0 이면 클라이언트 쪽 문제. */
    val ws_subscribers: Int? = null
)

@Serializable
data class SqlCipherStatus(
    val loaded: Boolean,
    val path: String? = null,
    /** 어디서 load 됐나: loadLibrary | own-package | apk-extract | package-scan | foreign-package */
    val source: String? = null,
    val foreign: Boolean? = null,
    val crypto_database_readable: Boolean? = null,
    val crypto_user_database_readable: Boolean? = null
)

@Serializable
data class ProcessCommandRequest(
    /** "stop" | "restart" */
    val command: String
)

/** /query 벌크 응답 — data 는 질의 순서대로 유지된다. */
@Serializable
data class BulkQueryResponse(
    val data: List<List<Map<String, String?>>>,
    val error: String? = null
)
