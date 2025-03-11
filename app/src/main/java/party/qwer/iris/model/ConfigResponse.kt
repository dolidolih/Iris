package party.qwer.iris.model

import kotlinx.serialization.Serializable

@Serializable
data class ConfigResponse(
    val bot_name: String,
    val bot_http_port: Int,
    val web_server_endpoint: String,
    val db_polling_rate: Long,
    val message_send_rate: Long,
    val bot_id: Long,
)
