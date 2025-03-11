package party.qwer.iris.model

import kotlinx.serialization.Serializable

@Serializable
data class ConfigValues(
    var botName: String = "Iris",
    var botHttpPort: Int = 3000,
    var webServerEndpoint: String = "http://172.17.0.1:5000/db",
    var dbPollingRate: Long = 100,
    var messageSendRate: Long = 50,
    var botId: Long = 0L
)