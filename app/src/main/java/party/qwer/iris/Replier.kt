package party.qwer.iris

import android.app.RemoteInput
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

// SendMsg : ye-seola/go-kdb

class Replier {
    companion object {
        private val messageChannel = Channel<SendMessageRequest>(Channel.CONFLATED)
        private val coroutineScope = CoroutineScope(Dispatchers.IO)
        private var messageSenderJob: Job? = null
        private val mutex = Mutex()

        init {
            startMessageSender()
        }

        fun startMessageSender() {
            coroutineScope.launch {
                if (messageSenderJob?.isActive == true) {
                    messageSenderJob?.cancelAndJoin()
                }
                messageSenderJob = launch {
                    for (request in messageChannel) {
                        val deferred = request.result
                        val result = try {
                            mutex.withLock {
                                request.send()
                                delay(Configurable.messageSendRate)
                            }
                            Result.success(Unit)
                        } catch (e: CancellationException) {
                            deferred?.completeExceptionally(e)
                            throw e
                        } catch (e: Throwable) {
                            System.err.println("Error sending message from channel: $e")
                            Result.failure(e)
                        }

                        deferred?.complete(result)
                    }
                }
            }
        }

        fun restartMessageSender() {
            startMessageSender()
        }

        private fun sendMessageInternal(referer: String, chatId: Long, msg: String) {
            val replyKey = "reply_message"
            val intent = Intent().apply {
                component = ComponentName(
                    "com.kakao.talk",
                    "com.kakao.talk.notification.NotificationActionService"
                )
                action = "com.kakao.talk.notification.REPLY_MESSAGE"
                putExtra("chat_id", chatId)
                putExtra("noti_referer", referer)
                putExtra("is_chat_thread_notification", false)

                val results = Bundle().apply {
                    putCharSequence(replyKey, msg)
                }

                val remoteInput = RemoteInput.Builder(replyKey).build()
                RemoteInput.addResultsToIntent(arrayOf(remoteInput), this, results)
            }

            AndroidHiddenApi.startService(intent)
        }

        suspend fun sendMessage(referer: String, chatId: Long, msg: String): Result<Unit> {
            return enqueueSendRequest {
                sendMessageInternal(
                    referer, chatId, msg
                )
            }
        }


        suspend fun sendPhoto(room: Long, base64ImageDataString: String): Result<Unit> {
            return enqueueSendRequest {
                sendPhotoInternal(
                    room, base64ImageDataString
                )
            }
        }

        suspend fun sendMultiplePhotos(room: Long, base64ImageDataStrings: List<String>): Result<Unit> {
            return enqueueSendRequest {
                sendMultiplePhotosInternal(
                    room, base64ImageDataStrings
                )
            }
        }

        private fun sendPhotoInternal(room: Long, base64ImageDataString: String) {
            sendMultiplePhotosInternal(room, listOf(base64ImageDataString))
        }

        private fun sendMultiplePhotosInternal(room: Long, base64ImageDataStrings: List<String>) {
            val picDir = File(IMAGE_DIR_PATH).apply {
                if (!exists()) {
                    mkdirs()
                }
            }

            val uris = base64ImageDataStrings.mapIndexed { idx, base64ImageDataString ->
                val decodedImage = Base64.decode(base64ImageDataString, Base64.DEFAULT)
                val timestamp = System.currentTimeMillis().toString()

                val imageFile = File(picDir, "${timestamp}_${idx}.png").apply {
                    writeBytes(decodedImage)
                }

                val imageUri = Uri.fromFile(imageFile)
                mediaScan(imageUri)
                imageUri
            }

            if (uris.isEmpty()) {
                System.err.println("No image URIs created, cannot send multiple photos.")
                return
            }

            val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                setPackage("com.kakao.talk")
                type = "image/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                putExtra("key_id", room)
                putExtra("key_type", 1)
                putExtra("key_from_direct_share", true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }

            try {
                AndroidHiddenApi.startActivity(intent)
            } catch (e: Exception) {
                System.err.println("Error starting activity for sending multiple photos: $e")
                throw e
            }
        }

        private suspend fun enqueueSendRequest(block: suspend () -> Unit): Result<Unit> {
            val deferred = CompletableDeferred<Result<Unit>>()
            messageChannel.send(SendMessageRequest(block, deferred))
            return deferred.await()
        }

        internal data class SendMessageRequest(
            val send: suspend () -> Unit,
            val result: CompletableDeferred<Result<Unit>>?
        )

        private fun mediaScan(uri: Uri) {
            val mediaScanIntent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE).apply {
                data = uri
            }
            AndroidHiddenApi.broadcastIntent(mediaScanIntent)
        }
    }
}