package party.qwer.iris.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.LongAsStringSerializer
import party.qwer.iris.util.IntAsStringSerializer

@Serializable
data class ConfigRequest(
    val endpoint: String? = null,
    val botname: String? = null,
    @Serializable(with = LongAsStringSerializer::class)
    val rate: Long? = null,
    @Serializable(with = IntAsStringSerializer::class)
    val port: Int? = null
)
