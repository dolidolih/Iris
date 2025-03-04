// SendMsg : ye-seola/go-kdb
// Kakaodecrypt : jiru/kakaodecrypt

package party.qwer.iris;

import android.os.IBinder;
import android.os.ServiceManager;
import android.app.IActivityManager;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.File;
import java.io.FileReader;
import java.util.concurrent.TimeUnit;


public class Main {

    private static final IBinder binder = ServiceManager.getService("activity");
    private static final IActivityManager activityManager = IActivityManager.Stub.asInterface(binder);
    public static final String NOTI_REF;
    private static final String CONFIG_FILE_PATH = "/data/local/tmp/config.json";
    private static final String DB_PATH = "/data/data/com.kakao.talk/databases";
    public static final String IMAGE_DIR_PATH = "/sdcard/Android/data/com.kakao.talk/files";


    static {
        String notiRefValue = null;
        File prefsFile = new File("/data/data/com.kakao.talk/shared_prefs/KakaoTalk.hw.perferences.xml");
        BufferedReader prefsReader = null;
        try {
            prefsReader = new BufferedReader(new FileReader(prefsFile));
            String line;
            while ((line = prefsReader.readLine()) != null) {
                if (line.contains("<string name=\"NotificationReferer\">")) {
                    int start = line.indexOf(">") + 1;
                    int end = line.indexOf("</string>");
                    notiRefValue = line.substring(start, end);
                    break;
                }
            }
        } catch (IOException e) {
            System.err.println("Error reading preferences file: " + e);
            notiRefValue = "default_noti_ref";
        } finally {
            if (prefsReader != null) {
                try {
                    prefsReader.close();
                } catch (IOException e) {
                    System.err.println("Error closing preferences file reader: " + e);
                }
            }
        }

        if (notiRefValue == null || notiRefValue.equals("default_noti_ref")) {
            System.err.println("NotificationReferer not found in preferences file or error occurred, using default or potentially failed to load.");
        } else {
            System.out.println("NotificationReferer loaded: " + notiRefValue);
        }
        NOTI_REF = (notiRefValue != null) ? notiRefValue : "default_noti_ref";
    }

    public static void main(String[] args) {
        Configurable.getInstance().loadConfig(CONFIG_FILE_PATH);
        System.out.println("Config file has been loaded.");

        Replier.startMessageSender();
        System.out.println("Message sender thread started");

        KakaoDB kakaoDb = new KakaoDB();
        ObserverHelper observerHelper = new ObserverHelper(); // observerHelper should be initialized after Configurable

        DBObserver dbObserver = new DBObserver(kakaoDb, observerHelper);
        dbObserver.startPolling();
        System.out.println("DBObserver started");

        ImageDeleter imageDeleter = new ImageDeleter(IMAGE_DIR_PATH, TimeUnit.HOURS.toMillis(1));
        imageDeleter.startDeletion();
        System.out.println("ImageDeleter started, and will delete images older than 1 hour.");

        HttpServer httpServer = new HttpServer(kakaoDb, dbObserver, observerHelper);
        httpServer.startServer();
        System.out.println("HTTP Server started");

        kakaoDb.closeConnection();
    }

}