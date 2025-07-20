package party.qwer.iris

import java.io.File

object PathUtils {
    fun getAppPath(): String {
        val androidUid = System.getenv("KAKAOTALK_APP_UID") ?: "0"
        val mirrorPath = "/data_mirror/data_ce/null/$androidUid/com.kakao.talk/"
        val defaultPath = "/data/data/com.kakao.talk/"

        return when {
            File(mirrorPath).exists() -> mirrorPath.also { println("Returning mirrorPath: $it") }
            File(defaultPath).exists() -> defaultPath.also { println("Returning defaultPath: $it") }
            else -> throw RuntimeException("KakaoTalk app path not found")
        }
    }
}
