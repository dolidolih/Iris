//notification parsing logic from https://github.com/mooner1022/StarLight/blob/nightly/app/src/main/java/dev/mooner/starlight/listener/specs/AndroidRParserSpec.kt
package party.qwer.iris

import android.app.Notification
import android.app.Person
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.service.notification.StatusBarNotification
import kotlin.concurrent.thread

class NotificationPoller {
    private val cachedSenderIds = mutableSetOf<String>()
    private val processedNotifications = mutableMapOf<String, Long>()

    fun startPolling() {
        thread(start = true) {
            while (true) {
                try {
                    pollNotifications()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                Thread.sleep(3000)
            }
        }
    }

    private fun pollNotifications() {
        val sbns = getActiveNotifications()

        val currentActiveKeys = mutableSetOf<String>()

        for (sbn in sbns) {
            if (sbn.packageName != "com.kakao.talk") continue

            val key = sbn.key
            val postTime = sbn.postTime
            currentActiveKeys.add(key)

            val lastProcessedTime = processedNotifications[key]

            if (lastProcessedTime == postTime) {
                continue
            }

            val notification = sbn.notification
            val extras = notification.extras ?: continue

            val rawTitle = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            val rawText = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()

            if (rawTitle == null && rawText == null) {
                processedNotifications[key] = postTime
                continue
            }

            val senderName = rawTitle ?: ""
            val subText = extras.getString(Notification.EXTRA_SUB_TEXT)
            val summaryText = extras.getString(Notification.EXTRA_SUMMARY_TEXT)
            val room = subText ?: summaryText ?: senderName

            var senderId = ""
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
                    if (!messages.isNullOrEmpty()) {
                        val messageBundle = messages[0] as? Bundle
                        val person = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            messageBundle?.getParcelable("sender_person", Person::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            messageBundle?.getParcelable("sender_person") as? Person
                        }
                        if (person != null) senderId = person.key ?: ""
                    }
                }
            } catch (e: Exception) {
                // pass
            }

            processedNotifications[key] = postTime

            if (senderId.isNotEmpty() && !cachedSenderIds.contains(senderId)) {
                NamesDB.saveName(senderId, senderName, room)
                cachedSenderIds.add(senderId)
            }
        }

        processedNotifications.keys.retainAll(currentActiveKeys)

        if (cachedSenderIds.size > 5000) {
            cachedSenderIds.clear()
        }
    }

    private fun getActiveNotifications(): Array<StatusBarNotification> {
        try {
            val serviceManager = Class.forName("android.os.ServiceManager")
            val getService = serviceManager.getMethod("getService", String::class.java)
            val binder = getService.invoke(null, "notification") as IBinder

            val stub = Class.forName("android.app.INotificationManager\$Stub")
            val inpm = stub.getMethod("asInterface", IBinder::class.java).invoke(null, binder)

            val methods = inpm.javaClass.methods

            val getAppActiveMethod = methods.find {
                it.name == "getAppActiveNotifications" && it.parameterTypes.size == 2 && it.parameterTypes[0] == String::class.java
            }
            if (getAppActiveMethod != null) {
                val result = getAppActiveMethod.invoke(inpm, "com.kakao.talk", 0)
                if (result is Array<*>) return result.filterIsInstance<StatusBarNotification>().toTypedArray()
            }

            val getActiveMethod = methods.find {
                it.name == "getActiveNotifications" && it.parameterTypes.size == 1 && it.parameterTypes[0] == String::class.java
            }
            if (getActiveMethod != null) {
                val result = getActiveMethod.invoke(inpm, "com.android.shell")
                if (result is Array<*>) return result.filterIsInstance<StatusBarNotification>().toTypedArray()
            }

        } catch (e: Exception) {
            e.printStackTrace()
        }
        return emptyArray()
    }
}