package party.qwer.iris

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import party.qwer.iris.model.ConfigValues


object Configurable {
    private const val CONFIG_FILE_PATH = "/data/local/tmp/config.json"
    private var configValues : ConfigValues = ConfigValues()

    private val json = Json {
        encodeDefaults = true
    }

    init {
        loadConfig()
    }

    private fun loadConfig() {
        val configFile = File(CONFIG_FILE_PATH)
        if (!configFile.exists()) {
            println("config.json not found, creating default config.")
            saveConfig()
            return
        }

        try {
            val jsonString = configFile.readText()
            println("jsonString from file: $jsonString")
            configValues = json.decodeFromString(ConfigValues.serializer(), jsonString)
        } catch (e: IOException) {
            println("Error reading config.json, creating default config: ${e.message}")
            saveConfig() // save default config to file
        } catch (e: SerializationException) {
            System.err.println("JSON parsing error in config.json, creating default config: ${e.message}")
            saveConfig()
        }
    }

    private fun saveConfig() {
        try {
            println("saveConfig: configValues before serialization: $configValues")

            val jsonString = json.encodeToString(ConfigValues.serializer(), configValues)
            println("saveConfig: jsonString: $jsonString")

            File(CONFIG_FILE_PATH).writeText(jsonString)
        } catch (e: IOException) {
            System.err.println("Error writing config to file: ${e.message}")
        } catch (e: SerializationException) {
            System.err.println("JSON error while saving config: ${e.message}")
        }
    }

    var botId: Long
        get() = configValues.botId
        set(value) {
            configValues.botId = value
            saveConfig()
            println("Bot Id is updated to: $botId")
        }

    var botName: String
        get() = configValues.botName
        set(value) {
            configValues.botName = value
            saveConfig()
            println("Bot name updated to: $botName")
        }

    var botSocketPort: Int
        get() = configValues.botHttpPort
        set(value) {
            configValues.botHttpPort = value
            saveConfig()
            println("Bot port updated to: $botSocketPort")
        }

    var webServerEndpoint: String
        get() = configValues.webServerEndpoint
        set(value) {
            configValues.webServerEndpoint = value
            saveConfig()
            println("WebServerEndpoint updated to: $webServerEndpoint")
        }

    var dbPollingRate: Long
        get() = configValues.dbPollingRate
        set(value) {
            configValues.dbPollingRate = value
            saveConfig()
            println("DbPollingRate updated to: $dbPollingRate")
        }

    var messageSendRate: Long
        get() = configValues.messageSendRate
        set(value) {
            configValues.messageSendRate = value
            saveConfig()
            println("MessageSendRate updated to: $messageSendRate")
        }
}