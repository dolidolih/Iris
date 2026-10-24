package party.qwer.iris.model

import kotlinx.serialization.Serializable

@Serializable
data class ConfigValues(
    var botName: String = "Iris",
    var botHttpPort: Int = 3000,
    var webServerEndpoint: String = "",
    var dbPollingRate: Long = 100,
    var messageSendRate: Long = 50,
    var botId: Long = 0L,
    /** /ws 브로드캐스트 이벤트 필터. null = 필터 없음(현행 동작). */
    var broadcastTypes: List<String>? = null,
    /** true(기본) = origin 기반 system 이벤트를 브로드캐스트에 포함. */
    var includeSystemEvents: Boolean = true,
    /** true 면 /ws 프레임에 `extension` 필드를 추가한다 (기본 false = wire 규격 불변). */
    var enableExtension: Boolean = false,
    /** 바꿀 때마다 올라가는 이력 번호 — 저장소 드리프트 진단용. */
    var revision: Long = 0
)
