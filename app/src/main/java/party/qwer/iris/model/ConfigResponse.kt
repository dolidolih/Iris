package party.qwer.iris.model

import kotlinx.serialization.Serializable

@Serializable
data class ConfigResponse(
    // 모든 필드에 기본값 — 부분 응답/필드 누락에서도 디코딩이 실패하지 않게 하기 위해.
    val bot_name: String = "",
    val bot_http_port: Int = 0,
    val web_server_endpoint: String = "",
    val db_polling_rate: Long = 0,
    val message_send_rate: Long = 0,
    val bot_id: Long = 0,
    val broadcast_types: List<String>? = null,
    val include_system_events: Boolean = true,
    val enable_extension: Boolean = false
)
