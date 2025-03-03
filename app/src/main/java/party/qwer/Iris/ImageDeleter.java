package party.qwer.Iris;

import java.io.File;
import java.util.concurrent.TimeUnit;

public class ImageDeleter {
    private final String imageDirPath;
    private final long deletionInterval;
    private volatile boolean running = true;

    public ImageDeleter(String imageDirPath, long deletionInterval) {
        this.imageDirPath = imageDirPath;
        this.deletionInterval = deletionInterval;
    }

    public void startDeletion() {
        new Thread(() -> {
            while (running) {
                deleteOldImages();
                try {
                    Thread.sleep(deletionInterval);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    System.err.println("Image deletion thread interrupted: " + e);
                    running = false;
                }
            }
        }).start();
    }

    public void stopDeletion() {
        running = false;
    }

    private void deleteOldImages() {
        File imageDir = new File(imageDirPath);
        if (!imageDir.exists() || !imageDir.isDirectory()) {
            System.out.println("Image directory does not exist: " + imageDirPath);
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
