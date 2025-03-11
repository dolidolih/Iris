package party.qwer.iris.util

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

object IntAsStringSerializer : KSerializer<Int?> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("StringToInt", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): Int? {
        val stringValue = decoder.decodeString()
        return stringValue.toIntOrNull()
    }

    override fun serialize(encoder: Encoder, value: Int? ) {
        encoder.encodeString(value?.toString() ?: "")
    }
}
