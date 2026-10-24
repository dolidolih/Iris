package party.qwer.iris

import java.io.File

/**
 * KakaoTalk 데이터 경로 헬퍼.
 *
 * ISSUE-31: `KAKAOTALK_APP_UID` 는 root 컨텍스트에서 SQL/경로에 끼워들어가는 유일한
 * 환경변수라서, 반드시 숫자만 통과시킨다. (숫자 검증 없으면 `'; DROP …` 류의 값을
 * `ATTACH DATABASE '<path>/KakaoTalk.db'` 문자열에 그대로 심게 된다.)
 *
 * 모드와 경로: ROOT_ADB daemon(uid 0) 은 env uid 로 /data_mirror 트리를 먼저 탄다 —
 * 다른 사용자/네임스페이스에서 CE 데이터를 보는 안전한 시점이라. HAYUL(shared-uid)
 * 은 앱 프로세스가 곧 카톡 uid 이므로 mirror 필요 없이 /data/data/com.kakao.talk 가
 * 곧 제자리 — env 는 unset 이고 defaultPath 가 정확히 그 경로를 탄다.
 *
 * ISSUE-40: 경로는 처음 해석될 때 캐시하고(60s TTL) 이후 stat 을 피한다. 해석 실패를
 * 첫 질의 스레드에서 조용히 던지지 않도록 `precompute()` 로 start-up 에 확인하고,
 * 캐시된 값은 `describe()` 로 로그에 남긴다.
 */
object PathUtils {
    /** env 에 들어있을 때만 허용되는 값: 순수 non-negative uid. */
    private fun sanitizedUid(): String? {
        val raw = System.getenv("KAKAOTALK_APP_UID")?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val uid = raw.toLongOrNull()
        if (uid == null) {
            System.err.println("PathUtils: ignoring non-numeric KAKAOTALK_APP_UID='$raw'")
            return null
        }
        if (uid < 0 || uid > 200_000L) {
            System.err.println("PathUtils: ignoring out-of-range KAKAOTALK_APP_UID=$uid")
            return null
        }
        return uid.toString()
    }

    @Volatile
    private var cachedPath: String? = null

    @Volatile
    private var cachedAtMs: Long = 0

    private const val RESOLVE_TTL_MS = 60_000L

    /** 설치 유무를 판단해 KakaoTalk data Dir를 반환. 없으면 null. */
    fun getAppPathOrNull(): String? {
        val now = System.currentTimeMillis()
        val cached = cachedPath
        if (cached != null && now - cachedAtMs < RESOLVE_TTL_MS) return cached
        val resolved = resolve()
        cachedPath = resolved
        cachedAtMs = now
        return resolved ?: cached
    }

    private fun resolve(): String? {
        val androidUid = sanitizedUid()
        val mirrorPath = if (androidUid != null)
            "/data_mirror/data_ce/null/$androidUid/com.kakao.talk/" else null
        val defaultPath = "/data/data/com.kakao.talk/"

        return when {
            mirrorPath != null && File(mirrorPath).isDirectory ->
                mirrorPath.also { println("PathUtils: using mirrorPath $it") }

            File(defaultPath).isDirectory ->
                defaultPath.also { println("PathUtils: using defaultPath $it") }

            else -> null
        }
    }

    /**
     * 데몬/서버 start-up 에서 호출하는 확인 — 첫 질의 스레드가 lazy exception 으로
     * 죽는 대신 startup 로그에 원인이 남는다. 없으면 null 을 반환하고 로그만 남긴다.
     */
    fun precompute(): String? {
        val path = getAppPathOrNull()
        if (path == null) {
            System.err.println(describeMissing())
        } else {
            println("PathUtils: resolved $path")
        }
        return path
    }

    /** 상태/디버그 로그용 — 어느 트리에서 읽고 있는지 한 줄로 드러나도록. */
    fun describe(): String =
        "PathUtils{path=${cachedPath ?: "<unresolved>"} envUid=${System.getenv("KAKAOTALK_APP_UID")?.takeIf { it.isNotBlank() } ?: "<unset>"}}"

    /** 경로를 못 찾았을 때의 설명 (start-up/상태 로그에서 재사용). */
    fun describeMissing(): String =
        "PathUtils: KakaoTalk data path not found (env KAKAOTALK_APP_UID=" +
            "${System.getenv("KAKAOTALK_APP_UID") ?: "<unset>"}, " +
            "checked /data_mirror/data_ce/null/<uid>/com.kakao.talk and /data/data/com.kakao.talk)"

    /**
     * ISSUE-40: 캐시된 경로를 버리고 다시 해석한다. 미러↔기본 트리 전환처럼 데이터 위치가
     * 바뀐 뒤 이전 커넥션이 다른 트리를 보지 않도록, 경로를 바꾸려는 쪽에서는
     * `invalidate()` 후 새 핸들을 만들어야 한다.
     */
    fun invalidate() {
        cachedPath = null
        cachedAtMs = 0
    }

    fun getAppPath(): String =
        getAppPathOrNull() ?: throw IllegalStateException(describeMissing())
}
