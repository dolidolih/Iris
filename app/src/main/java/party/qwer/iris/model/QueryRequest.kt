package party.qwer.iris.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive

@Serializable
data class QueryRequest(
    val query: String? = null,
    // 원본 Iris 규격과 동일하게 JsonPrimitive 리스트로 받는다. 클라이언트는 bind 에 따옴표
    // 없는 숫자(방 id, 사용자 id)를 보내므로 List<String> 으로 받으면 역직렬화가 실패한다.
    val bind: List<JsonPrimitive>? = null,
    val queries: List<QueryRequest>? = null
)
