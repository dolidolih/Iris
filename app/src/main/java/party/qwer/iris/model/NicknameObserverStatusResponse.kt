package party.qwer.iris.model

import kotlinx.serialization.Serializable

@Serializable
data class NicknameObserverStatusResponse(
    val isObserving: Boolean,
    val statusMessage: String,
    val recentNicknameEvents: List<Map<String, String?>>
)
