package party.qwer.iris.model

import kotlinx.serialization.Serializable

@Serializable
data class BulkQueryRequest(
    val queries: List<QueryRequest>
)