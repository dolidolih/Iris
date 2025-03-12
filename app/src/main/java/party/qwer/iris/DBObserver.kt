package party.qwer.iris

import party.qwer.iris.Configurable.dbPollingRate
import kotlin.concurrent.Volatile

class DBObserver(private val kakaoDb: KakaoDB, private val observerHelper: ObserverHelper) {
    private var pollingThread: Thread? = null

    @Volatile
    private var isObserving: Boolean = false

    fun startPolling() {
        if (pollingThread == null || !pollingThread!!.isAlive) {
            pollingThread = Thread {
                isObserving = true
                while (true) {
                    observerHelper.checkChange(kakaoDb)
                    try {
                        val pollingInterval = dbPollingRate
                        if (pollingInterval > 0) {
                            Thread.sleep(pollingInterval)
                        } else {
                            Thread.sleep(1000)
                        }
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        System.err.println("Polling thread interrupted: $e")
                        isObserving = false
                        break
                    }
                }
                isObserving = false
            }
            pollingThread!!.name = "DB-Polling-Thread"
            pollingThread!!.start()
            println("DB Polling thread started.")
        } else {
            println("DB Polling thread is already running.")
        }
    }

    fun stopPolling() {
        if (pollingThread != null && pollingThread!!.isAlive) {
            pollingThread!!.interrupt()
            pollingThread = null
            isObserving = false
            println("DB Polling thread stopped.")
        }
    }

    val isPollingThreadAlive: Boolean
        get() = pollingThread != null && pollingThread!!.isAlive
}