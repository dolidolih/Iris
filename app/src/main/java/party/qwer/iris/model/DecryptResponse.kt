package party.qwer.iris.model

import kotlinx.serialization.Serializable

@Serializable
data class DecryptResponse(
    val plain_text: String
)