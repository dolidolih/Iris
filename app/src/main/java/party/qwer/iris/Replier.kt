package party.qwer.iris

import android.app.IActivityManager
import android.app.RemoteInput
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.os.ServiceManager
import android.util.Base64
import party.qwer.iris.Replier.Companion.SendMessageRequest
import java.io.File
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue


// SendMsg : ye-seola/go-kdb

class Replier {
    companion object {
        private val binder: IBinder = ServiceManager.getService("activity")
        private val activityManager: IActivityManager = IActivityManager.Stub.asInterface(binder)
        private val messageQueue: BlockingQueue<SendMessageRequest> = LinkedBlockingQueue()

        fun startMessageSender() {
            Thread {
                while (true) {
                    try {
                        val request = messageQueue.take()
                        request.send()
                        Thread.sleep(Configurable.messageSendRate)
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        System.err.println("Message sender thread interrupted: $e")
                        break
                    } catch (e: Exception) {
                        System.err.println("Error sending message from queue: $e")
                    }
                }
            }.start()
        }

        private fun sendMessageInternal(referer: String, chatId: Long, msg: String) {
            val intent = Intent()
            intent.setComponent(
                ComponentName(
                    "com.kakao.talk", "com.kakao.talk.notification.NotificationActionService"
                )
            )

            intent.putExtra("noti_referer", referer)
            intent.putExtra("chat_id", chatId)
            intent.setAction("com.kakao.talk.notification.REPLY_MESSAGE")

            val results = Bundle()
            results.putCharSequence("reply_message", msg)

            val remoteInput = RemoteInput.Builder("reply_message").build()
            val remoteInputs = arrayOf(remoteInput)
            RemoteInput.addResultsToIntent(remoteInputs, intent, results)

            startService(intent)
        }

        fun sendMessage(referer: String, chatId: Long, msg: String) {
            messageQueue.offer { sendMessageInternal(referer, chatId, msg) }
        }


        fun sendPhoto(room: Long, base64ImageDataString: String) {
            messageQueue.offer(SendMessageRequest {
                sendPhotoInternal(
                    room, base64ImageDataString
                )
            })
        }

        fun sendMultiplePhotos(room: Long, base64ImageDataStrings: List<String>) {
            messageQueue.offer(SendMessageRequest {
                sendMultiplePhotosInternal(
                    room, base64ImageDataStrings
                )
            })
        }

        private fun sendPhotoInternal(room: Long, base64ImageDataString: String) {
            sendMultiplePhotosInternal(room, listOf(base64ImageDataString))
        }

        private fun sendMultiplePhotosInternal(room: Long, base64ImageDataStrings: List<String>) {
            val picDir = File(IMAGE_DIR_PATH)
            if (!picDir.exists()) {
                picDir.mkdirs()
            }

            val uris = base64ImageDataStrings.map {
                val decodedImage = Base64.decode(it, Base64.DEFAULT)
                val timestamp = System.currentTimeMillis().toString()

                val imageFile = File(picDir, "$timestamp.png")
                imageFile.writeBytes(decodedImage)

                val imageUri = Uri.fromFile(imageFile)
                mediaScan(imageUri)

                imageUri
            }

            if (uris.isEmpty()) {
                System.err.println("No image URIs created, cannot send multiple photos.")
                return
            }

            val intent = Intent(Intent.ACTION_SEND_MULTIPLE)
            intent.setPackage("com.kakao.talk")
            intent.setType("image/*")
            intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            intent.putExtra("key_id", room)
            intent.putExtra("key_type", 1)
            intent.putExtra("key_from_direct_share", true)

            try {
                startActivity(intent)
            } catch (e: Exception) {
                System.err.println("Error starting activity for sending multiple photos: $e")
                throw e
            }
        }


        internal fun interface SendMessageRequest {
            fun send()
        }

        private fun mediaScan(uri: Uri) {
            val mediaScanIntent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
            mediaScanIntent.setData(uri)
            broadcastIntent(mediaScanIntent)
        }

        private fun broadcastIntent(intent: Intent) {
            activityManager.broadcastIntent(
                null, intent, null, null, 0, null, null, null, -1, null, false, false, -2
            )
        }

        private fun startActivity(intent: Intent) {
            activityManager.startActivityAsUserWithFeature(
                null,
                "com.android.shell",
                null,
                intent,
                intent.type,
                null,
                null,
                0,
                0,
                null,
                null,
                -2
            )
        }

        private fun startService(intent: Intent) {
            activityManager.startService(
                null, intent, intent.type, false, "com.android.shell", null, -2
            )
        }
    }
}