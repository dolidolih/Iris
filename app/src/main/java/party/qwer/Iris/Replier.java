package party.qwer.Iris;

// SendMsg : ye-seola/go-kdb
import android.os.ServiceManager;
import android.app.IActivityManager;
import android.content.Intent;
import android.content.ComponentName;
import android.app.RemoteInput;
import android.os.Bundle;
import java.io.IOException;
import java.io.File;
import java.io.FileOutputStream;
import android.net.Uri;
import java.io.OutputStream;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import android.os.IBinder;
import java.util.List;
import java.util.ArrayList;

public class Replier {
    private static IBinder binder = ServiceManager.getService("activity");
    private static IActivityManager activityManager = IActivityManager.Stub.asInterface(binder);
    private static final String NOTI_REF = Iris.NOTI_REF;
    private static final BlockingQueue<SendMessageRequest> messageQueue = new LinkedBlockingQueue<>();
    public static long messageSendRate = Configurable.getInstance().getMessageSendRate();

    interface SendMessageRequest {
        void send() throws Exception;
    }

    public static void startMessageSender() {
        new Thread(() -> {
            while (true) {
                try {
                    SendMessageRequest request = messageQueue.take();
                    request.send();
                    Thread.sleep(messageSendRate);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    System.err.println("Message sender thread interrupted: " + e.toString());
                    break;
                } catch (Exception e) {
                    System.err.println("Error sending message from queue: " + e.toString());
                }
            }
        }).start();
    }

    private static void SendMessageInternal(String notiRef, Long chatId, String msg) throws Exception {
        Intent intent = new Intent();
        intent.setComponent(new ComponentName("com.kakao.talk", "com.kakao.talk.notification.NotificationActionService"));

        intent.putExtra("noti_referer", notiRef);
        intent.putExtra("chat_id", chatId);
        intent.setAction("com.kakao.talk.notification.REPLY_MESSAGE");

        Bundle results = new Bundle();
        results.putCharSequence("reply_message", msg);

        RemoteInput remoteInput = new RemoteInput.Builder("reply_message").build();
        RemoteInput[] remoteInputs = new RemoteInput[]{remoteInput};
        RemoteInput.addResultsToIntent(remoteInputs, intent, results);
        activityManager.startService(
                null,
                intent,
                intent.getType(),
                false,
                "com.android.shell",
                null,
                -2
        );
    }

    public static void SendMessage(String notiRef, Long chatId, String msg) {
        messageQueue.offer(() -> SendMessageInternal(notiRef, chatId, msg));
    }


    private static void SendPhotoInternal(Long room, String base64ImageDataString) throws Exception {
        byte[] decodedImage = android.util.Base64.decode(base64ImageDataString, android.util.Base64.DEFAULT);
        String timestamp = String.valueOf(System.currentTimeMillis());
        File picDir = new File(Iris.IMAGE_DIR_PATH);
        if (!picDir.exists()) {
            picDir.mkdirs();
        }
        File imageFile = new File(picDir, timestamp + ".png");
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(imageFile);
            fos.write(decodedImage);
            fos.flush();
        } catch (IOException e) {
            System.err.println("Error saving image to file: " + e.toString());
            throw e;
        } finally {
            if (fos != null) {
                try {
                    fos.close();
                } catch (IOException e) {
                    System.err.println("Error closing FileOutputStream: " + e.toString());
                }
            }
        }

        Uri imageUri = Uri.fromFile(imageFile);

        Intent mediaScanIntent = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
        mediaScanIntent.setData(imageUri);
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
            );
            System.out.println("Media scanner broadcast intent sent for: " + imageUri.toString());
        } catch (Exception e) {
            System.err.println("Error broadcasting media scanner intent: " + e.toString());
            throw e;
        }

        Intent intent = new Intent();
        intent.setAction(Intent.ACTION_SENDTO);
        intent.setType("image/png");
        intent.putExtra(Intent.EXTRA_STREAM, imageUri);
        intent.putExtra("key_id", room);
        intent.putExtra("key_type", 1);
        intent.putExtra("key_from_direct_share", true);
        intent.setPackage("com.kakao.talk");

        try {
            activityManager.startActivityAsUserWithFeature(
                    null,
                    "com.android.shell",
                    null,
                    intent,
                    intent.getType(),
                    null, null, 0, 0,
                    null,
                    null,
                    -2
            );
        } catch (Exception e) {
            System.err.println("Error starting activity for sending image: " + e.toString());
            throw e;
        }
    }

    public static void SendPhoto(Long room, String base64ImageDataString) {
        messageQueue.offer(() -> SendPhotoInternal(room, base64ImageDataString));
    }

    private static void SendMultiplePhotosInternal(Long room, List<String> base64ImageDataStrings) throws Exception {
        List<Uri> uris = new ArrayList<>();
        for (String base64ImageDataString : base64ImageDataStrings) {
            byte[] decodedImage = android.util.Base64.decode(base64ImageDataString, android.util.Base64.DEFAULT);
            String timestamp = String.valueOf(System.currentTimeMillis());
            File picDir = new File(Iris.IMAGE_DIR_PATH);
            if (!picDir.exists()) {
                picDir.mkdirs();
            }
            File imageFile = new File(picDir, timestamp + ".png");
            FileOutputStream fos = null;
            try {
                fos = new FileOutputStream(imageFile);
                fos.write(decodedImage);
                fos.flush();
            } catch (IOException e) {
                System.err.println("Error saving image to file: " + e.toString());
                continue;
            } finally {
                if (fos != null) {
                    try {
                        fos.close();
                    } catch (IOException e) {
                        System.err.println("Error closing FileOutputStream: " + e.toString());
                    }
                }
            }
            Uri imageUri = Uri.fromFile(imageFile);
            Intent mediaScanIntent = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
            mediaScanIntent.setData(imageUri);
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
                );
                System.out.println("Media scanner broadcast intent sent for: " + imageUri.toString());
            } catch (Exception e) {
                System.err.println("Error broadcasting media scanner intent: " + e.toString());
                continue;
            }
            uris.add(imageUri);
        }

        if (uris.isEmpty()) {
            System.err.println("No image URIs created, cannot send multiple photos.");
            return;
        }

        Intent intent = new Intent(Intent.ACTION_SEND_MULTIPLE);
        intent.setPackage("com.kakao.talk");
        intent.setType("image/*");
        intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, new ArrayList<>(uris));
        intent.putExtra("key_id", room);
        intent.putExtra("key_type", 1);
        intent.putExtra("key_from_direct_share", true);


        try {
            activityManager.startActivityAsUserWithFeature(
                    null,
                    "com.android.shell",
                    null,
                    intent,
                    intent.getType(),
                    null, null, 0, 0,
                    null,
                    null,
                    -2
            );
        } catch (Exception e) {
            System.err.println("Error starting activity for sending multiple photos: " + e.toString());
            throw e;
        }
    }


    public static void SendMultiplePhotos(Long room, List<String> base64ImageDataStrings) {
        messageQueue.offer(() -> SendMultiplePhotosInternal(room, base64ImageDataStrings));
    }
}