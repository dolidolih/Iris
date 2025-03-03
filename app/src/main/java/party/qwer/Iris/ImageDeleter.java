package party.qwer.Iris;

import java.io.File;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class ImageDeleter {
    private final String imageDirPath;
    private final long deletionInterval;
    private volatile boolean running = true;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    public ImageDeleter(String imageDirPath, long deletionInterval) {
        this.imageDirPath = imageDirPath;
        this.deletionInterval = deletionInterval;
    }

    public void startDeletion() {
        scheduler.scheduleWithFixedDelay(this::deleteOldImages, 0, deletionInterval, TimeUnit.MILLISECONDS);
    }

    public void stopDeletion() {
        running = false;
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
            System.err.println("Error shutting down image deletion scheduler: " + e);
        }
    }

    private void deleteOldImages() {
        if (!running) {
            System.out.println("Image deletion task stopped.");
            scheduler.shutdown();
            return;
        }

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