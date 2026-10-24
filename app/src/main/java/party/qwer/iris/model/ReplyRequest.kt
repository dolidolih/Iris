package party.qwer.iris.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * `type` 은 wire 상 문자열 그대로 받는다 (enum 보드보다 확장 안전 — media/video/audio/file
 * 처럼 추가된 종류도 역직렬화 실패 없이 핸들러 분기에서 처리한다).
 * 지원: text / image / image_multiple / media / video / audio / file
 */
@Serializable
data class ReplyRequest(
    val type: String = "text",
    val room: String,
    val data: JsonElement,
    val threadId: String? = null
)
