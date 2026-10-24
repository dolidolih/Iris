package party.qwer.iris.model

import kotlinx.serialization.Serializable

@Serializable
data class DecryptRequest(
    val enc: Int,
    val b64_ciphertext: String,
    /** 복호화 시드 — 생략하면 봇 user_id(Configurable.botId) 를 쓴다. */
    val user_id: Long? = null
)
