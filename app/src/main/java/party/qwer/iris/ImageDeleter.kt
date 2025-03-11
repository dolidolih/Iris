package party.qwer.iris

import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.concurrent.Volatile

class ImageDeleter(private val imageDirPath: String, private val deletionInterval: Long) {
    @Volatile
    private var running = true
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()

    fun startDeletion() {
        scheduler.scheduleWithFixedDelay(
            { this.deleteOldImages() }, 0,
            deletionInterval, TimeUnit.MILLISECONDS
        )
    }

    fun stopDeletion() {
        running = false
        scheduler.shutdown()
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow()
            }
        } catch (e: InterruptedException) {
            scheduler.shutdownNow()
            Thread.currentThread().interrupt()
            System.err.println("Error shutting down image deletion scheduler: $e")
        }
    }

    private fun deleteOldImages() {
        if (!running) {
            println("Image deletion task stopped.")
            scheduler.shutdown()
            return
        }

        val imageDir = File(imageDirPath)
        if (!imageDir.exists() || !imageDir.isDirectory) {
            println("Image directory does not exist: $imageDirPath")
            return
        }

        val oneDayAgo = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(1)
        val files = imageDir.listFiles()
        if (files != null) {
            for (file in files) {
                if (file.isFile && file.lastModified() < oneDayAgo) {
                    if (file.delete()) {
                        println("Deleted old image file: " + file.name)
                    } else {
                        System.err.println("Failed to delete image file: " + file.name)
                    }
                }
            }
        }
    }
}