// SendMsg : ye-seola/go-kdb
// Kakaodecrypt : jiru/kakaodecrypt

import android.os.IBinder;
import android.os.ServiceManager;
import android.app.IActivityManager;
import android.content.ComponentName;
import android.content.Intent;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.File;
import java.io.FileReader;
import java.util.concurrent.TimeUnit;


public class Iris {

    private static IBinder binder = ServiceManager.getService("activity");
    private static IActivityManager activityManager = IActivityManager.Stub.asInterface(binder);
    public static final String NOTI_REF; // Changed to public static final
    private static final String CONFIG_FILE_PATH = "/data/local/tmp/config.json";
    private static final String DB_PATH = "/data/data/com.kakao.talk/databases";
    public static final String IMAGE_DIR_PATH = "/sdcard/Android/data/com.kakao.talk/files"; // Changed to public static final


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
            System.err.println("Error reading preferences file: " + e.toString());
            notiRefValue = "default_noti_ref";
        } finally {
            if (prefsReader != null) {
                try {
                    prefsReader.close();
                } catch (IOException e) {
                    System.err.println("Error closing preferences file reader: " + e.toString());
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
        KakaoDB kakaoDb = new KakaoDB();
        Configurable.getInstance().setBotId(kakaoDb.BOT_ID);
        ObserverHelper observerHelper = new ObserverHelper();
        HttpServer httpServer = new HttpServer(kakaoDb);
        Replier.startMessageSender();

        DBObserver dbObserver = new DBObserver(kakaoDb, observerHelper);
        dbObserver.startPolling();
        System.out.println("Starting DB polling managed by DBObserver");

        long deletionInterval = TimeUnit.HOURS.toMillis(1);
        System.out.println("Starting image deletion thread, checking every 1 hour");
        new Thread(() -> {
            while (true) {
                deleteOldImages();
                try {
                    Thread.sleep(deletionInterval);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    System.err.println("Image deletion thread interrupted: " + e.toString());
                    break;
                }
            }
        }).start();


        httpServer.startServer();

        kakaoDb.closeConnection();
    }

    private static void deleteOldImages() {
        File imageDir = new File(IMAGE_DIR_PATH);
        if (!imageDir.exists() || !imageDir.isDirectory()) {
            System.out.println("Image directory does not exist: " + IMAGE_DIR_PATH);
            return;
        }

        long oneDayAgo = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(1);
        File[] files = imageDir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isFile() && file.lastModified() < oneDayAgo) {
                    if (file.delete()) {
                        System.out.println("Deleted old image file: " + file.getName());
                    } else {
                        System.err.println("Failed to delete image file: " + file.getName());
                    }
                }
            }
        }
    }
}