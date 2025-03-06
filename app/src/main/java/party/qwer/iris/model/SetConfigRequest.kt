package party.qwer.iris.model

import kotlinx.serialization.Serializable

@Serializable
data class SetConfigRequest(
    val endpoint: String? = null,
    val botname: String? = null,
    val rate: String? = null,
    val port: String? = null
)