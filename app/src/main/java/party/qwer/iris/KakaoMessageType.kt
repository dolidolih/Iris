package party.qwer.iris

/**
 * chat_logs.type / v.origin 분류 헬퍼.
 *
 * `type` 단일 필드가 아니라 `type` + `v.origin` + OpenChat 비트(16384)의 조합으로
 * 이벤트 종류를 결정한다 (docs/db-events-and-info-provision.md §2).
 *
 * - OpenChat 로그 = base + 16384 (예: 16385 → base 1 = text in OpenChat).
 * - `origin`은 브로드캐스트 필터에 사용한다. SYNCMSG/MCHATLOGS는 기존 규격대로 제외하고,
 *   origin=MSG가 실질 메시지, origin=NEWMEM/DELMEM/FEED/… 는 시스템(멤버변경·피드) 이벤트.
 */
object KakaoMessageType {
    /** OpenChat 로그 판별 비트. */
    const val OPEN_CHAT_BIT = 16384

    /** 원본 type → OpenChat 비트를 제거한 base 타입. */
    fun baseType(type: Int): Int = type and OPEN_CHAT_BIT.inv()

    fun isOpenChat(type: Int): Boolean = (type and OPEN_CHAT_BIT) != 0

    /** origin 만으로 삭제 계열인지 본다 (deleted_at 은 별개의 확인). */
    fun isDeletionOrigin(origin: String): Boolean = origin == "SYNCDLMSG" || origin == "SYNCMODMSG"

    /** origin기반 "삭제 마크" 판별: SYNCDLMSG=작성자 삭제, SYNCMODMSG=방장(어드민) 삭제.
     *  deleted_at>0 이면서 origin 만 삭제 마크에 해당한다 (SYNCREWR 등 다른 deleted_at 행은 제외). */
    fun isDeletionEvent(origin: String, deletedAt: Long): Boolean =
        isDeletionOrigin(origin) && deletedAt > 0L

    /** type/origin을 "사람이 알아볼 문자열"로 매핑 (extension.type_name, 필터용).
     *  삭제 마크행이면 type/origin 을 보지 않고 "deleted" 로 분류한다. */
    fun classify(type: Int, origin: String): String = classify(type, origin, 0L)

    fun classify(type: Int, origin: String, deletedAt: Long): String {
        // ISSUE-35: 삭제 마크 origin(SYNCDLMSG/SYNCMODMSG) 은 deleted_at 컬럼을 넘기지 않은
        // 호출에서도 "deleted" 로 확정된다 — 2-arg classify 가 이를 놓치면 "deleted" 필터를
        // 쓴 설정이 삭제 이벤트를 system 으로잘못 처리 한다. deleted_at 는 flag 로만 남긴다.
        if (isDeletionOrigin(origin)) return "deleted"
        if (origin.isEmpty()) return "unknown"
        // 시스템(origin 기반)
        if (origin != "MSG" && origin != "MCHATLOGS" && origin != "SYNCMSG" && origin != "WRITE") {
            return "system"
        }
        // WRITE(origin)는 self 발신(내 메시지 저장) -> current_user로 분류
        if (origin == "WRITE") return "current_user"
        if (origin == "MCHATLOGS") return "mchatlog"
        if (origin == "SYNCMSG") return "syncmsg"
        // origin=MSG: base 타입별 분류
        return when (baseType(type)) {
            1 -> "text"
            2 -> "photo"
            3 -> "video"
            5 -> "audio"
            6 -> "file"
            7 -> "contact"
            11 -> "photo_animation"
            12 -> "contact"
            16 -> "long_app"
            18 -> "gif"
            20 -> "emoticon"
            23 -> "talk_memo"
            25 -> "app"
            26 -> "app_feed"
            27 -> "list"
            61 -> "in_link"
            71 -> "feed"
            72 -> "feed_share"
            93 -> "current_user"
            else -> "unknown"
        }
    }

    /**
     * 브로드캐스트 포함 여부 결정.
     *
     * filter = null (현행 동작 유지): origin ∉ {SYNCMSG, MCHATLOGS} 인 로그만 브로드캐스트.
     *   단 origin=NEWMEM/DELMEM/… 시스템 이벤트도 기존 규격대로 포함된다.
     * filter != null: classify 결과가 필터에 속한 로그만 브로드캐스트.
     *   단 SYNCMSG는 항상 제외. MCHATLOGS/system("system")은 필터에 명시된 경우에만 포함.
     * deletedAt 인자를 받지 않는 호출은 삭제 마크 여부를 보지 않으므로 0 을 넘긴다.
     */
    /** deleted_at 값은 classify/필터판정이 필요 없는 호출에서는 0 으로 넘긴다. */
    fun shouldBroadcast(type: Int, origin: String, filter: List<String>?, includeSystem: Boolean): Boolean =
        shouldBroadcast(type, origin, 0L, filter, includeSystem)

    /**
     * 삭제 이벤트(origin=SYNCDLMSG/SYNCMODMSG 이고 deleted_at>0)는 systemOrigin 과 동일하게
     * includeSystem 으로만 제어한다 — 기존 wire 동작을 바꾸지 않기 위함. filter가 지정된 경우엔
     * "deleted" 로 분류되므로 필터에 "deleted" 를 포함해야 전송된다.
     */
    fun shouldBroadcast(
        type: Int,
        origin: String,
        deletedAt: Long,
        filter: List<String>?,
        includeSystem: Boolean
    ): Boolean {
        if (origin == "SYNCMSG") return false
        val deletionMark = isDeletionOrigin(origin)
        val deletion = deletionMark && deletedAt > 0L
        if (!deletionMark && origin == "MCHATLOGS" && (filter == null || filter.isEmpty())) return false
        val systemOrigin = (origin != "MSG" && origin != "MCHATLOGS" && origin != "SYNCMSG" && origin != "WRITE")
        if (systemOrigin && !includeSystem) return false
        if (filter == null || filter.isEmpty()) return true
        // ISSUE-35: 삭제 마크 계열은 deleted_at 값과 무관하게 "deleted" 필터를 따른다
        // (이전엔 deleted_at==0 이면 "system" 으로빠져서 deleted 필터 설정이 조용히 실패했다).
        if (deletionMark) return "deleted" in filter
        if (origin == "MCHATLOGS") return "mchatlog" in filter
        if (origin == "WRITE") return "current_user" in filter
        if (systemOrigin) return "system" in filter
        return classify(type, origin, deletedAt) in filter
    }

    /** 필터 문자열 유효성 검사 (config /config/{name} 검증용). */
    val VALID_FILTERS = setOf(
        "text", "photo", "video", "audio", "file", "contact", "photo_animation", "gif",
        "emoticon", "list", "app", "app_feed", "feed", "feed_share", "talk_memo",
        "long_app", "current_user", "mchatlog", "syncmsg", "system", "unknown", "deleted"
    )
}
