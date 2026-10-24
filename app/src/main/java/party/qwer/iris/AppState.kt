package party.qwer.iris

/**
 * AppState — 데emon(process) 전역의 관측 상태.
 *
 * Iris 는 GUI 가 없는 app_process 전용이라 Compose 의 mutableStateOf/mutableStateListOf 가
 * 필요 없다. 화면 대신 HTTP(/process-status, /dashboard/status) 에서 읽으므로, UI 스레드
 * 규약 없이도 안전하게 읽을 수 있는 동기화 집합이면 충분하다.
 *
 * ObserverHelper.polling thread 가 쓰고 HTTP handler 스레드가 읽는다.
 */
object AppState {
    /** DB 폴러가 실제로 관찰 중인가 (DBObserver 가 true/false 를 관리한다). */
    @Volatile
    var isObserving: Boolean = false

    /**
     * 최근 복호화된 채팅 로그 (최신 선행) — polling thread 가 매 건마다 통째로 교체하는
     * 불변 목록. 읽는 쪽은 항상 완성된 목록만 본다.
     */
    @Volatile
    private var lastLogs: List<Map<String, String?>> = emptyList()

    fun setLastChatLogs(logs: List<Map<String, String?>>) {
        lastLogs = logs
    }

    val lastChatLogs: List<Map<String, String?>>
        get() = lastLogs

    /** 데몬 기동 시각 — uptime/재기동 추적용. */
    val startedAtMs: Long = System.currentTimeMillis()
}
