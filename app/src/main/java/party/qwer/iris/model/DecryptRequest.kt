package party.qwer.iris.model

data class DecryptRequest(
    val b64_ciphertext: String,
    val user_id: Long?,
    val enc: Int,
)
