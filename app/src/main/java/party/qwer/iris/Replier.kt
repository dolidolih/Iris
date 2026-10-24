package party.qwer.iris

import android.app.RemoteInput
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import party.qwer.iris.Configurable
import party.qwer.iris.Replier.Companion.SendMessageRequest

// SendMsg : ye-seola/go-kdb

internal const val IMAGE_DIR_PATH = "/sdcard/Android/data/com.kakao.talk/files"

class Replier {
    companion object {
        private val messageChannel = Channel<SendMessageRequest>(Channel.UNLIMITED)
        private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        @Volatile private var messageSenderJob: Job? = null
        private val mutex = Mutex()

        /** ISSUE-21: start/stop/restart 직렬화 — 경쟁하면 중복 consumer 가 뜬다. */
        private val lifecycleLock = ReentrantLock()

        @Volatile
        private var running = false

        /** pendingobservability: submitted 는 enqueue 성공, sent 는 consumer 통과. */
        private val submitted = AtomicLong()
        private val sent = AtomicLong()

        /** 보내지지 않은 채 채널에 남아있는 요청 수 (재시작 churn 진단용). */
        val pendingCount: Long get() = (submitted.get() - sent.get()).coerceAtLeast(0)

        /** sender 가 실제로 도는 중인가 — stop hook 에서 확인 가능한 상태. */
        val isRunning: Boolean get() = running

        /** in-flight send 가 mutex 를 놓기를 기다리는 상한 — rate 가 크ても 넘기지 않는다. */
        private const val QUIESCE_BUDGET_MS = 3_000L

        init {
            startMessageSender()
        }

        /**
         * sender 재시작 — 진행 중이던 send 를 도중에 잘라내지 않는다 (그게 churn 중
         * 답장을 유실시켰다). 먼저 mutex 를 넘겨받아 현재 send 의 완료를 보장받고, join 한
         * 뒤 새 consumer 를 세운다. `restartMessageSender` 와 같은 동작 (rate 변경 콜백).
         */
        fun startMessageSender() {
            // 기동 시 설정 스냅샷 handshake 로그 — "레이트를 바꿨는데 그대로" 류를
            // stdout 한 줄로 추적하기 위한 발판.
            runCatching { println("Iris: daemon config ${Configurable.describe()}") }
            lifecycleLock.lock()
            try {
                val previous = messageSenderJob
                if (previous != null && previous.isActive) {
                    runBlocking {
                        withTimeoutOrNull(QUIESCE_BUDGET_MS) { mutex.withLock { } }
                        previous.cancelAndJoin()
                    }
                }
                running = true
                messageSenderJob = coroutineScope.launch {
                    for (request in messageChannel) {
                        try {
                            mutex.withLock {
                                request.send()
                                sent.incrementAndGet()
                                delay(Configurable.messageSendRate)
                            }
                        } catch (e: Exception) {
                            System.err.println("Error sending message from channel: $e")
                        }
                    }
                }
            } finally {
                lifecycleLock.unlock()
            }
        }

        fun restartMessageSender() {
            startMessageSender()
        }

        /**
         * ISSUE-05 후속: daemon stop hook 에서 호출할 sender 정지.
         *
         * 채널은 닫지 *않는다* — 닫으면 재기동 후 `enqueue` 가 ClosedSendChannelException
         * 이 되어 churn 중 답장을 통째로 잃는다 (ISSUE-21 의 원인). 대기 항목은 채널에 그대로
         * 남아 다음 start 시 소모되고, in-flight send 는 cancel 전에 mutex 를 넘겨받아 끝난다.
         */
        fun stop() {
            lifecycleLock.lock()
            try {
                running = false
                val job = messageSenderJob
                messageSenderJob = null
                if (job?.isActive == true) {
                    runBlocking {
                        val quiesced = withTimeoutOrNull(QUIESCE_BUDGET_MS) {
                            mutex.withLock { }
                            true
                        }
                        if (quiesced != true) {
                            println("Replier: in-flight send 대기 상한(${QUIESCE_BUDGET_MS}ms) 초과 — 취소만 적용")
                        }
                        job.cancelAndJoin()
                    }
                }
                if (pendingCount > 0) {
                    println("Replier: sender stopped with $pendingCount request(s) queued")
                } else {
                    println("Replier: message sender stopped")
                }
            } finally {
                lifecycleLock.unlock()
            }
        }

        private fun sendMessageInternal(
            referer: String,
            chatId: Long,
            msg: String,
            threadId: Long?
        ) {
            val intent = Intent().apply {
                component = ComponentName(
                    "com.kakao.talk", "com.kakao.talk.notification.NotificationActionService"
                )
                putExtra("noti_referer", referer)
                putExtra("chat_id", chatId)

                putExtra("is_chat_thread_notification", threadId != null)
                if (threadId != null) {
                    putExtra("thread_id", threadId)
                }

                action = "com.kakao.talk.notification.REPLY_MESSAGE"

                val results = Bundle().apply {
                    putCharSequence("reply_message", msg)
                }

                val remoteInput = RemoteInput.Builder("reply_message").build()
                RemoteInput.addResultsToIntent(arrayOf(remoteInput), this, results)
            }

            dispatchStartService(intent)
        }

        fun sendMessage(referer: String, chatId: Long, msg: String, threadId: Long?) {
            submitted.incrementAndGet()
            coroutineScope.launch {
                messageChannel.send(SendMessageRequest {
                    sendMessageInternal(
                        referer, chatId, msg, threadId
                    )
                })
            }
        }


        fun sendPhoto(room: Long, base64ImageDataString: String) {
            submitted.incrementAndGet()
            coroutineScope.launch {
                messageChannel.send(SendMessageRequest {
                    sendMediaInternal(
                        room, listOf(decodeLegacyImage(base64ImageDataString))
                    )
                })
            }
        }

        fun sendMultiplePhotos(room: Long, base64ImageDataStrings: List<String>) {
            submitted.incrementAndGet()
            coroutineScope.launch {
                messageChannel.send(SendMessageRequest {
                    sendMediaInternal(
                        room, base64ImageDataStrings.map { decodeLegacyImage(it) }
                    )
                })
            }
        }

        /**
         * 미디어 답장 (image/video/audio/file 공통) — 항목마다 원본 파일명으로
         * IMAGE_DIR 에 적는다. Kakao 는 URI DISPLAY_NAME 을 그대로 쓰므로 이게 곧
         * "파일명이 보여야 한다" 의 구현이다.
         */
        fun sendMedia(room: Long, items: List<MediaItem>) {
            submitted.incrementAndGet()
            coroutineScope.launch {
                messageChannel.send(SendMessageRequest {
                    sendMediaInternal(room, items)
                })
            }
        }

        /**
         * Iris 는 app_process(root, Context 없음) 에서만 도므로 dispatch 는 항상
         * system_server binder 리플랙션 경로다. (IrisGUI 의 HAYUL/shared-uid 모드는
         * 앱 Context 로 직접 startService/startActivity/sendBroadcast 를 탄다.)
         */
        private fun dispatchStartService(intent: Intent) = AndroidHiddenApi.startService(intent)

        private fun dispatchStartActivity(intent: Intent) = AndroidHiddenApi.startActivity(intent)

        private fun dispatchBroadcast(intent: Intent) = AndroidHiddenApi.broadcastIntent(intent)

        private fun decodeLegacyImage(base64: String): MediaItem =
            MediaItem("image.png", "image/png", MediaKind.IMAGE,
                Base64.decode(base64, Base64.DEFAULT))

        private fun sendMediaInternal(room: Long, items: List<MediaItem>) {
            val picDir = File(IMAGE_DIR_PATH)
            // ISSUE-21: mkdirs() 결과와 쓰기 가능 여부를 확인한다. 기존 코드는 결과를 버리고
            // writeBytes 가 던진 예외를 그냥 넘겼고, Android 11+ 비root 경로에서는 파일 이미지가
            // 아예 못 만들어져도 사진 답장이 실패로 안 남았다.
            if (!picDir.exists() && !picDir.mkdirs()) {
                throw IOException("cannot create image dir ${IMAGE_DIR_PATH} (check storage permission)")
            }
            if (!picDir.canWrite()) {
                throw IOException("image dir not writable: ${IMAGE_DIR_PATH}")
            }

            val used = mutableSetOf<String>()
            val uris = items.map { item ->
                val file = MediaPayload.writeTo(picDir, item, used)
                val uri = Uri.fromFile(file)
                mediaScan(uri)
                uri
            }

            if (uris.isEmpty()) {
                System.err.println("No media URIs created, cannot send media reply.")
                return
            }

            val intent = MediaPayload.buildSendIntent(items, ArrayList(uris), room, grantRead = false)

            try {
                dispatchStartActivity(intent)
            } catch (e: Exception) {
                System.err.println("Error starting activity for sending media: $e")
                throw e
            }
        }


        internal fun interface SendMessageRequest {
            suspend fun send()
        }

        private fun mediaScan(uri: Uri) {
            val mediaScanIntent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE).apply {
                data = uri
            }
            dispatchBroadcast(mediaScanIntent)
        }
    }
}