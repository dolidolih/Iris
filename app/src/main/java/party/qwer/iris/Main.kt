// SendMsg : ye-seola/go-kdb
// Kakaodecrypt : jiru/kakaodecrypt
package party.qwer.iris

import android.os.IBinder
import android.os.ServiceManager
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.io.IOException
import java.util.concurrent.TimeUnit

object Main {
    private val binder: IBinder = ServiceManager.getService("activity")
    @JvmField
    val NOTI_REF: String
    const val IMAGE_DIR_PATH: String = "/sdcard/Android/data/com.kakao.talk/files"


    init {
        var notiRefValue: String? = null
        val prefsFile = File("/data/data/com.kakao.talk/shared_prefs/KakaoTalk.hw.perferences.xml")
        var prefsReader: BufferedReader? = null
        try {
            prefsReader = BufferedReader(FileReader(prefsFile))
            var line: String
            while ((prefsReader.readLine().also { line = it }) != null) {
                if (line.contains("<string name=\"NotificationReferer\">")) {
                    val start = line.indexOf(">") + 1
                    val end = line.indexOf("</string>")
                    notiRefValue = line.substring(start, end)
                    break
                }
            }
        } catch (e: IOException) {
            System.err.println("Error reading preferences file: $e")
            notiRefValue = "default_noti_ref"
        } finally {
            if (prefsReader != null) {
                try {
                    prefsReader.close()
                } catch (e: IOException) {
                    System.err.println("Error closing preferences file reader: $e")
                }
            }
        }

        if (notiRefValue == null || notiRefValue == "default_noti_ref") {
            System.err.println("NotificationReferer not found in preferences file or error occurred, using default or potentially failed to load.")
        } else {
            println("NotificationReferer loaded: $notiRefValue")
        }
        NOTI_REF = if ((notiRefValue != null)) notiRefValue else "default_noti_ref"
    }

    @JvmStatic
    fun main(args: Array<String>) {

        Replier.startMessageSender()
        println("Message sender thread started")

        val kakaoDb = KakaoDB()
        val observerHelper = ObserverHelper(kakaoDb)

        val dbObserver = DBObserver(kakaoDb, observerHelper)
        dbObserver.startPolling()
        println("DBObserver started")

        val imageDeleter = ImageDeleter(IMAGE_DIR_PATH, TimeUnit.HOURS.toMillis(1))
        imageDeleter.startDeletion()
        println("ImageDeleter started, and will delete images older than 1 hour.")

        val httpServer = HttpServerKt(kakaoDb, dbObserver, observerHelper, NOTI_REF)
        httpServer.startServer()
        println("HTTP Server started")

        kakaoDb.closeConnection()
    }
}