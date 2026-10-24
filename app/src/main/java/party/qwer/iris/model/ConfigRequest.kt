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
    val port: Int? = null,
    /** /config/types — 브로드캐스트 이벤트 필터. 빈 배열 = 필터 해제. */
    val types: List<String>? = null,
    /** /config/extension — /ws `extension` 필드 on/off. */
    val enable: Boolean? = null
)
