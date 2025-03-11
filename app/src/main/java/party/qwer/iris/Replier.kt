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
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue

// SendMsg : ye-seola/go-kdb

object Replier {
    private val binder: IBinder = ServiceManager.getService("activity")
    private val activityManager: IActivityManager = IActivityManager.Stub.asInterface(binder)
    private val NOTI_REF = Main.NOTI_REF
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

    @Throws(Exception::class)
    private fun sendMessageInternal(notiRef: String, chatId: Long, msg: String) {
        val intent = Intent()
        intent.setComponent(
            ComponentName(
                "com.kakao.talk",
                "com.kakao.talk.notification.NotificationActionService"
            )
        )

        intent.putExtra("noti_referer", notiRef)
        intent.putExtra("chat_id", chatId)
        intent.setAction("com.kakao.talk.notification.REPLY_MESSAGE")

        val results = Bundle()
        results.putCharSequence("reply_message", msg)

        val remoteInput = RemoteInput.Builder("reply_message").build()
        val remoteInputs = arrayOf(remoteInput)
        RemoteInput.addResultsToIntent(remoteInputs, intent, results)
        activityManager.startService(
            null,
            intent,
            intent.type,
            false,
            "com.android.shell",
            null,
            -2
        )
    }

    fun sendMessage(notiRef: String, chatId: Long, msg: String) {
        messageQueue.offer { sendMessageInternal(notiRef, chatId, msg) }
    }


    @Throws(Exception::class)
    private fun sendPhotoInternal(room: Long, base64ImageDataString: String) {
        val decodedImage = Base64.decode(base64ImageDataString, Base64.DEFAULT)
        val timestamp = System.currentTimeMillis().toString()
        val picDir = File(Main.IMAGE_DIR_PATH)
        if (!picDir.exists()) {
            picDir.mkdirs()
        }
        val imageFile = File(picDir, "$timestamp.png")
        var fos: FileOutputStream? = null
        try {
            fos = FileOutputStream(imageFile)
            fos.write(decodedImage)
            fos.flush()
        } catch (e: IOException) {
            System.err.println("Error saving image to file: $e")
            throw e
        } finally {
            if (fos != null) {
                try {
                    fos.close()
                } catch (e: IOException) {
                    System.err.println("Error closing FileOutputStream: $e")
                }
            }
        }

        val imageUri = Uri.fromFile(imageFile)

        val mediaScanIntent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
        mediaScanIntent.setData(imageUri)
        try {
            activityManager.broadcastIntent(
                null,
                mediaScanIntent,
                null,
                null,
                0,
                null,
                null,
                null,
                -1,
                null,
                false,
                false,
                -2
            )
            println("Media scanner broadcast intent sent for: $imageUri")
        } catch (e: Exception) {
            System.err.println("Error broadcasting media scanner intent: $e")
            throw e
        }

        val intent = Intent()
        intent.setAction(Intent.ACTION_SENDTO)
        intent.setType("image/png")
        intent.putExtra(Intent.EXTRA_STREAM, imageUri)
        intent.putExtra("key_id", room)
        intent.putExtra("key_type", 1)
        intent.putExtra("key_from_direct_share", true)
        intent.setPackage("com.kakao.talk")

        try {
            activityManager.startActivityAsUserWithFeature(
                null,
                "com.android.shell",
                null,
                intent,
                intent.type,
                null, null, 0, 0,
                null,
                null,
                -2
            )
        } catch (e: Exception) {
            System.err.println("Error starting activity for sending image: $e")
            throw e
        }
    }

    fun sendPhoto(room: Long, base64ImageDataString: String) {
        messageQueue.offer(SendMessageRequest { sendPhotoInternal(room, base64ImageDataString) })
    }

    @Throws(Exception::class)
    private fun sendMultiplePhotosInternal(room: Long, base64ImageDataStrings: List<String>) {
        val uris: MutableList<Uri> = ArrayList()
        for (base64ImageDataString in base64ImageDataStrings) {
            val decodedImage = Base64.decode(base64ImageDataString, Base64.DEFAULT)
            val timestamp = System.currentTimeMillis().toString()
            val picDir = File(Main.IMAGE_DIR_PATH)
            if (!picDir.exists()) {
                picDir.mkdirs()
            }
            val imageFile = File(picDir, "$timestamp.png")
            var fos: FileOutputStream? = null
            try {
                fos = FileOutputStream(imageFile)
                fos.write(decodedImage)
                fos.flush()
            } catch (e: IOException) {
                System.err.println("Error saving image to file: $e")
                continue
            } finally {
                if (fos != null) {
                    try {
                        fos.close()
                    } catch (e: IOException) {
                        System.err.println("Error closing FileOutputStream: $e")
                    }
                }
            }
            val imageUri = Uri.fromFile(imageFile)
            val mediaScanIntent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
            mediaScanIntent.setData(imageUri)
            try {
                activityManager.broadcastIntent(
                    null,
                    mediaScanIntent,
                    null,
                    null,
                    0,
                    null,
                    null,
                    null,
                    -1,
                    null,
                    false,
                    false,
                    -2
                )
                println("Media scanner broadcast intent sent for: $imageUri")
            } catch (e: Exception) {
                System.err.println("Error broadcasting media scanner intent: $e")
                continue
            }
            uris.add(imageUri)
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
            activityManager.startActivityAsUserWithFeature(
                null,
                "com.android.shell",
                null,
                intent,
                intent.type,
                null, null, 0, 0,
                null,
                null,
                -2
            )
        } catch (e: Exception) {
            System.err.println("Error starting activity for sending multiple photos: $e")
            throw e
        }
    }


    fun sendMultiplePhotos(room: Long, base64ImageDataStrings: List<String>) {
        messageQueue.offer(SendMessageRequest {
            sendMultiplePhotosInternal(
                room,
                base64ImageDataStrings
            )
        })
    }

    internal fun interface SendMessageRequest {
        @Throws(Exception::class)
        fun send()
    }
}