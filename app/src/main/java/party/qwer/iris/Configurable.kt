package party.qwer.iris

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import party.qwer.iris.model.ConfigValues

/**
 * Iris 데몬 설정의 단일 저장소 — `app_process` 환경에서는 SharedPreferences 를 쓸 수 없어
 * JSON 파일 하나로 저장/읽기 한다.
 *
 * 저장 규칙 (IrisGUI 의 daemon-config 와 동일한 이유): 하나의 파일을 여러 스레드(HTTP
 * 핸들러 + polling thread)가 동시에 보므로
 *  - 저장은 tmp → rename 원자적 기록 + `.bak` 스냅샷. 읽기에 실패해도 파일을 기본값으로
 *    덮어쓰지 않는다 (중간 크래시 후 설정 전체가 조용히 사라지던 경로 차단).
 *  - 모든 setter 는 잠금 하에 read-modify-write 이고 `revision` 을 올린다. 무엇이 언제
 *    바뀌었는지 로그로 추적할 수 있다.
 *  - 알 수 없는 키(구버전/신버전 혼용) 때문에 기본값으로 되돌아가지 않도록 느슨하게 읽는다.
 */
class Configurable {
    companion object {
        private const val DEFAULT_CONFIG_PATH = "/data/local/tmp/config.json"

        private val json = Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
            coerceInputValues = true
        }

        private val writeLock = Any()

        @Volatile
        private var configValues: ConfigValues = ConfigValues()

        /** 파일이 실제로 존재했는가 — 기본값을 기존 파일로 덮어쓰지 않는 판정에 쓴다. */
        @Volatile
        private var loadedFromDisk: Boolean = false

        /**
         * botId 를 DB 에서 확인했는가. false 인데 botId 가 0 이면 salt 가 통째로 0 이라
         * 봇 자신의 메시지 복호가 조용히 깨진다 — 상태 응답에서 표시할 수 있게 남긴다.
         */
        @Volatile
        var botIdResolved: Boolean = false
            private set

        fun markBotIdResolved(resolved: Boolean) {
            botIdResolved = resolved
        }

        val CONFIG_FILE_PATH: String = System.getenv("IRIS_CONFIG_PATH") ?: DEFAULT_CONFIG_PATH

        init {
            loadConfig()
        }

        /** 현재 저장된 이력 번호 — 설정 드리프트 진단용. */
        val revision: Long get() = configValues.revision

        /** 현재 값을 한 줄로 — "바꿨는데 그대로" 추적의 발판. */
        fun describe(): String =
            "Configurable{rev=$revision port=$botSocketPort sendRate=$messageSendRate " +
                "dbRate=$dbPollingRate botId=$botId${if (botIdResolved) "" else " (unresolved!) "}" +
                "botName=$botName endpoint=\"$webServerEndpoint\"}"

        /**
         * config 파일 경로. File.canWrite() 는 존재하지 않는 경로에 대해 false 를 반환하므로,
         * 파일 유무와 무관하게 "디렉터리에 새로 만들 수 있는가"까지 본다 (신규 설치 후 첫
         * 설정 변경이 보존되려면 필요하다).
         */
        private fun configFile(): File = File(CONFIG_FILE_PATH)

        private fun configFileExists(): Boolean = configFile().isFile

        private fun loadConfig() {
            val file = configFile()
            if (!file.isFile) {
                // 파일이 없을 때만 기본값으로 만든다. 읽기에 실패한 경우에는 절대 기존 파일을
                // 기본값으로 덮어쓰지 않는다 (크래시 후 설정 유실 방지).
                println("config.json not found at $CONFIG_FILE_PATH, creating default config.")
                mutate { it }
                return
            }
            try {
                val jsonString = file.readText()
                println("jsonString from file: $jsonString")
                val parsed = json.decodeFromString(ConfigValues.serializer(), jsonString)
                synchronized(writeLock) { configValues = parsed }
                loadedFromDisk = true
            } catch (e: IOException) {
                System.err.println("Error reading config.json from $CONFIG_FILE_PATH: ${e.message} — keeping file as-is, using defaults")
            } catch (e: SerializationException) {
                // `.bak` 이 있으면 먼저 되돌린다 — main 파일이 잘려도 설정이 통째로 사라지지 않게.
                val bak = File("$CONFIG_FILE_PATH.bak")
                val restored = runCatching {
                    json.decodeFromString(ConfigValues.serializer(), bak.readText())
                }.getOrNull()
                if (restored != null) {
                    synchronized(writeLock) { configValues = restored }
                    loadedFromDisk = true
                    println("Configurable: main config 읽기 실패 — ${bak.name} 로 되돌림")
                } else {
                    System.err.println("JSON parsing error in config.json from $CONFIG_FILE_PATH: ${e.message} — keeping file as-is, using defaults")
                }
            }
        }

        /** tmp → rename 원자적 저장 + `.bak` 스냅샷. */
        private fun persist() {
            val target = configFile()
            target.parentFile?.mkdirs()
            val payload = json.encodeToString(ConfigValues.serializer(), configValues)
            val tmp = File("$CONFIG_FILE_PATH.tmp")
            tmp.writeText(payload)
            runCatching {
                tmp.setReadable(false, false)
                tmp.setReadable(true, true)
                tmp.setWritable(false, false)
                tmp.setWritable(true, true)
            }
            if (target.isFile) {
                runCatching { target.copyTo(File("$CONFIG_FILE_PATH.bak"), overwrite = true) }
            }
            if (!tmp.renameTo(target)) {
                // rename 불가(크로스 파일시스템/퍼미션)일 때만 인플레이스 — 원자적이 아니어도
                // 되는 예외 경로지만, 차라리 이 편이 아무것도 못 쓰는 것보다 낫다.
                target.writeText(payload)
                tmp.delete()
            }
            loadedFromDisk = true
        }

        /** 모든 변경의 단일 입구 — 잠금 하에 read-modify-write, revision 증가. */
        private inline fun mutate(block: (ConfigValues) -> ConfigValues) {
            val changed: ConfigValues
            synchronized(writeLock) {
                val current = configValues
                val next = block(current).copy(revision = current.revision + 1)
                configValues = next
                changed = next
            }
            try {
                persist()
            } catch (e: Exception) {
                System.err.println("Error writing config to $CONFIG_FILE_PATH: ${e.message}")
            }
            println("Configurable: config saved (revision ${changed.revision})")
        }

        /** 변경을 명시하지 않은 호출용 (파일 최초 생성, 또는 재저장 강제). */
        fun save() = mutate { it }

        // ── 접근자 ─────────────────────────────────────────────

        var botId: Long
            get() = configValues.botId
            set(value) {
                if (value == configValues.botId) return
                mutate { it.copy(botId = value) }
                println("Bot Id is updated to: $botId")
            }

        var botName: String
            get() = configValues.botName
            set(value) {
                if (value == configValues.botName) return
                mutate { it.copy(botName = value) }
                println("Bot name updated to: $botName")
            }

        var botSocketPort: Int
            get() = configValues.botHttpPort
            set(value) {
                if (value == configValues.botHttpPort) return
                mutate { it.copy(botHttpPort = value) }
                println("Bot port updated to: $botSocketPort")
            }

        var webServerEndpoint: String
            get() = configValues.webServerEndpoint
            set(value) {
                if (value == configValues.webServerEndpoint) return
                mutate { it.copy(webServerEndpoint = value) }
                println("WebServerEndpoint updated to: $webServerEndpoint")
            }

        var dbPollingRate: Long
            get() = configValues.dbPollingRate
            set(value) {
                if (value == configValues.dbPollingRate) return
                mutate { it.copy(dbPollingRate = value) }
                println("DbPollingRate updated to: $dbPollingRate")
            }

        var messageSendRate: Long
            get() = configValues.messageSendRate
            set(value) {
                if (value == configValues.messageSendRate) return
                mutate { it.copy(messageSendRate = value) }
                println("MessageSendRate updated to: $messageSendRate")
                Replier.restartMessageSender()
            }

        /**
         * 브로드캐스트할 이벤트 종류 필터. null/비워둠 = 필터 없음(기존 동작 유지).
         * 값: text, photo, video, audio, file, contact, photo_animation, gif, emoticon, list,
         * app, app_feed, feed, feed_share, talk_memo, long_app, current_user, mchatlog, syncmsg,
         * system, unknown, deleted. ("deleted" = 메시지 삭제 마크 — SYNCDLMSG(작성자)/SYNCMODMSG(방장).)
         */
        var broadcastTypes: List<String>?
            get() = configValues.broadcastTypes
            set(value) {
                if (value == configValues.broadcastTypes) return
                mutate { it.copy(broadcastTypes = value) }
                println("Broadcast types set to: ${value ?: "<no filter>"}")
            }

        /** true(기본) = system(origin) 이벤트를 브로드캐스트에 포함. false = 제외. */
        var includeSystemEvents: Boolean
            get() = configValues.includeSystemEvents
            set(value) {
                if (value == configValues.includeSystemEvents) return
                mutate { it.copy(includeSystemEvents = value) }
                println("includeSystemEvents = $value")
            }

        /** true 면 /ws 프레임에 `extension`(타입/리액션/OpenChat비트) 필드가 추가된다. 기본 false. */
        var enableExtension: Boolean
            get() = configValues.enableExtension
            set(value) {
                if (value == configValues.enableExtension) return
                mutate { it.copy(enableExtension = value) }
                println("enableExtension = $value")
            }
    }
}
