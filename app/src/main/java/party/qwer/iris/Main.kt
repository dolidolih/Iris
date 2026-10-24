// SendMsg : ye-seola/go-kdb
// Kakaodecrypt : jiru/kakaodecrypt
package party.qwer.iris

import java.util.concurrent.TimeUnit

/**
 * Iris daemon entry point — `app_process / party.qwer.iris.Main [port]` 로 실행된다.
 *
 * GUI 가 없는 순수 데몬: 설정은 JSON 파일(Configurable), 답장은 system_server binder
 * 리플랙션(Replier), 이벤트는 chat_logs 폴링(ObserverHelper) 으로 동작한다.
 */
class Main {
    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            try {
                // 데몬 stdout/stderr 전체를 로그 버퍼에 담아 /process-status 로 노출한다.
                RuntimeLog.captureStdout("Iris")

                // 실행 인자(args[0])로 서버 포트를 지정할 수 있다. 기동 스크립트가 포트를
                // config 파일에 쓸 수 없는 경우의 유일한 전달 경로.
                args.firstOrNull()?.trim()?.toIntOrNull()?.let { p ->
                    if (p in 1..65535) {
                        Configurable.botSocketPort = p
                        println("Iris: server port set from argument = $p")
                    } else {
                        System.err.println("Iris: invalid port argument ignored: ${args.firstOrNull()}")
                    }
                }
                println("Iris: ${Configurable.describe()}")

                // KakaoTalk 데이터 트리를 기동 시점에 확인한다 — 첫 질의 스레드가 lazy
                // exception 으로 죽는 대신 startup 로그에 원인이 남는다.
                PathUtils.precompute()

                Replier.startMessageSender()
                println("Message sender thread started")

                val kakaoDb = KakaoDB()

                val server = IrisServer(kakaoDb)
                // ISSUE-15: poller → WS 브로드캐스트는 non-blocking — ObserverHelper 가
                // polling thread 에서 직접 연결별 fanout 으로 넘긴다.
                val observerHelper = ObserverHelper(kakaoDb) { msg -> server.broadcastToClients(msg) }

                val dbObserver = DBObserver(kakaoDb, observerHelper)
                dbObserver.startPolling()
                println("DBObserver started")

                val imageDeleter = ImageDeleter(IMAGE_DIR_PATH, TimeUnit.HOURS.toMillis(1))
                imageDeleter.startDeletion()
                println("ImageDeleter started, and will delete images older than 1 hour.")

                // /process-command stop 은 데몬 워스(DB poller, 이미지 정리, 송신기)까지
                // 내려야 truly stop — "정지"인데 관찰/답장이 도는 반-정지 상태를 막는다.
                server.workerStopHook = Runnable {
                    runCatching { dbObserver.stopPolling() }
                    runCatching { imageDeleter.stopDeletion() }
                    runCatching { Replier.stop() }
                }

                if (!server.start()) {
                    System.err.println("Iris: HTTP server failed to start — process stays up for diagnostics")
                }
                println("Iris Server started")

                Runtime.getRuntime().addShutdownHook(object : Thread("Iris-Shutdown") {
                    override fun run() {
                        println("Iris: Shutting down...")
                        runCatching { dbObserver.stopPolling() }
                        runCatching { imageDeleter.stopDeletion() }
                        runCatching { server.stop() }
                        runCatching { kakaoDb.closeConnection() }
                        println("Iris: Shutdown complete.")
                    }
                })

                // 메인 스레드에서 블록 — app_process 프로세스가 종료되지 않도록
                Thread.sleep(Long.MAX_VALUE)
            } catch (e: Exception) {
                System.err.println("Iris Error")
                e.printStackTrace()
            }
        }
    }
}
