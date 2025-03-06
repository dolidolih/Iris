package party.qwer.iris.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive

@Serializable
data class ReplyRequest(
    val type: ReplyType = ReplyType.TEXT,
    val room: String,
    val data: JsonPrimitive,
)
