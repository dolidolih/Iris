package party.qwer.iris.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class ReplyType {
    @SerialName("text")
    TEXT,

    @SerialName("image")
    IMAGE,

    @SerialName("image_multiple")
    IMAGE_MULTIPLE,
}