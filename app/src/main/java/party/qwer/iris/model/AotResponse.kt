package party.qwer.iris.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class AotResponse(
    val success: Boolean,
    val aot: JsonObject
)