package party.qwer.iris

class Db2NicknameObserver(
    private val kakaoDb: KakaoDB
) {
    private data class CacheEntry(
        val linkId: Long,
        val userId: Long,
        val nicknameRaw: String?,
        val enc: Int,
        val profileLinkId: Long?
    ) {
        val cacheKey: String
            get() = "$linkId:$userId"
    }

    @Volatile
    private var pollingThread: Thread? = null

    private var lastSnapshot: Map<String, CacheEntry> = emptyMap()
    private val roomNameCache: MutableMap<Long, String?> = HashMap()
    private var lastDataVersion: Long? = null
    private var hasBaseline = false
    private var activePollingRateMs: Long = currentConfiguredRate()

    fun startPolling() {
        if (pollingThread?.isAlive == true) {
            println("DB2 nickname observer thread is already running.")
            return
        }

        NicknameObserverRegistry.updateStatus(
            isObserving = true,
            statusMessage = "Starting db2 nickname observer"
        )

        pollingThread = Thread(
            {
                while (!Thread.currentThread().isInterrupted) {
                    try {
                        activePollingRateMs = currentConfiguredRate()
                        pollOnce()
                        if (activePollingRateMs > 0) {
                            Thread.sleep(activePollingRateMs)
                        } else {
                            Thread.sleep(25)
                        }
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    } catch (e: Exception) {
                        NicknameObserverRegistry.updateStatus(
                            isObserving = false,
                            statusMessage = "Nickname observer error: ${e.message ?: "unknown"}"
                        )
                        System.err.println("Error during db2 nickname polling: $e")
                        if (activePollingRateMs > 0) {
                            Thread.sleep(activePollingRateMs)
                        } else {
                            Thread.sleep(25)
                        }
                    }
                }
            },
            "DB2-Nickname-Polling-Thread"
        ).also {
            it.isDaemon = true
            it.start()
        }

        println("DB2 nickname observer thread started.")
    }

    fun stopPolling() {
        pollingThread?.interrupt()
        pollingThread = null
        NicknameObserverRegistry.updateStatus(
            isObserving = false,
            statusMessage = "Nickname observer stopped"
        )
        println("DB2 nickname observer thread stopped.")
    }

    val isPollingThreadAlive: Boolean
        get() = pollingThread?.isAlive == true

    private fun pollOnce() {
        val currentDataVersion = kakaoDb.getDb2DataVersion()
        if (hasBaseline && currentDataVersion != null && lastDataVersion != null && currentDataVersion == lastDataVersion) {
            return
        }

        val currentSnapshot = loadSnapshot()
        if (!hasBaseline) {
            hasBaseline = true
            lastSnapshot = currentSnapshot
            lastDataVersion = currentDataVersion
            NicknameObserverRegistry.updateStatus(
                isObserving = true,
                statusMessage = buildStatusMessage(
                    mode = observationMode(currentDataVersion),
                    cachedRows = currentSnapshot.size,
                    detail = "Baseline loaded"
                )
            )
            return
        }

        val previousSnapshot = lastSnapshot
        var nicknameChanges = 0
        var membershipJoined = 0
        var membershipLeft = 0

        for ((cacheKey, currentEntry) in currentSnapshot) {
            val previousEntry = previousSnapshot[cacheKey]
            if (previousEntry == null) {
                membershipJoined += 1
                continue
            }

            if (
                currentEntry.nicknameRaw == previousEntry.nicknameRaw &&
                currentEntry.enc == previousEntry.enc &&
                currentEntry.profileLinkId == previousEntry.profileLinkId
            ) {
                continue
            }

            val oldNickname = kakaoDb.decryptOpenChatMemberNickname(previousEntry.nicknameRaw, previousEntry.enc)
            val newNickname = kakaoDb.decryptOpenChatMemberNickname(currentEntry.nicknameRaw, currentEntry.enc)

            if (oldNickname == newNickname) {
                continue
            }

            emitNicknameUpdate(currentEntry, oldNickname, newNickname)
            nicknameChanges += 1
        }

        for (cacheKey in previousSnapshot.keys) {
            if (!currentSnapshot.containsKey(cacheKey)) {
                membershipLeft += 1
            }
        }

        lastSnapshot = currentSnapshot
        lastDataVersion = currentDataVersion

        val detail = when {
            nicknameChanges > 0 -> "Detected $nicknameChanges nickname change(s)"
            membershipJoined > 0 || membershipLeft > 0 -> "Membership sync +$membershipJoined/-$membershipLeft"
            else -> "No nickname changes"
        }

        NicknameObserverRegistry.updateStatus(
            isObserving = true,
            statusMessage = buildStatusMessage(
                mode = observationMode(currentDataVersion),
                cachedRows = currentSnapshot.size,
                detail = detail
            )
        )
    }

    private fun loadSnapshot(): Map<String, CacheEntry> {
        val rawRows = kakaoDb.getOpenChatMemberSnapshotRaw()
        val snapshot = LinkedHashMap<String, CacheEntry>(rawRows.size.coerceAtLeast(16))

        for (row in rawRows) {
            val entry = CacheEntry(
                linkId = row.linkId,
                userId = row.userId,
                nicknameRaw = row.nicknameRaw,
                enc = row.enc,
                profileLinkId = row.profileLinkId
            )
            snapshot[entry.cacheKey] = entry
        }

        return snapshot
    }

    private fun emitNicknameUpdate(
        currentEntry: CacheEntry,
        oldNickname: String?,
        newNickname: String?
    ) {
        val roomName = resolveRoomName(currentEntry.linkId)
        val ts = System.currentTimeMillis() / 1000

        val eventMap = linkedMapOf(
            "ts" to ts.toString(),
            "type" to "open_chat_member.updated",
            "source" to "db2.open_chat_member",
            "room_name" to roomName,
            "link_id" to currentEntry.linkId.toString(),
            "user_id" to currentEntry.userId.toString(),
            "field" to "nickname",
            "old_nickname" to oldNickname,
            "new_nickname" to newNickname,
            "profile_link_id" to currentEntry.profileLinkId?.toString()
        )

        NicknameObserverRegistry.recordEvent(eventMap)
    }

    private fun resolveRoomName(linkId: Long): String? {
        val freshRoomName = kakaoDb.getOpenLinkRoomNameForLink(linkId)
        if (freshRoomName != null || !roomNameCache.containsKey(linkId)) {
            roomNameCache[linkId] = freshRoomName
        }
        return roomNameCache[linkId]
    }

    private fun observationMode(currentDataVersion: Long?): String {
        return if (currentDataVersion == null) {
            "snapshot"
        } else {
            "data_version"
        }
    }

    private fun buildStatusMessage(
        mode: String,
        cachedRows: Int,
        detail: String
    ): String {
        val rateLabel = if (activePollingRateMs == 0L) {
            "0ms-config/25ms-sleep"
        } else {
            "${activePollingRateMs}ms"
        }
        return "Watching db2.open_chat_member via $mode ($detail, $cachedRows cached, $rateLabel)"
    }

    private fun currentConfiguredRate(): Long {
        return Configurable.nicknameObserverRate.coerceAtLeast(0)
    }
}
