package party.qwer.Iris

import java.io.File
import java.util.concurrent.TimeUnit

class ImageDeleter(private val imageDirPath: String, private val deletionInterval: Long) {
    private var running = true

    fun startDeletion() {
        Thread {
            while (running) {
                deleteOldImages()
                try {
                    Thread.sleep(deletionInterval)
                } catch (e: InterruptedException) {
                    System.err.println("Image deletion thread interrupted: $e")
                    running = false
                    break
                }
            }
        }.start()
    }

    fun stopDeletion() {
        running = false
    }

    private fun deleteOldImages() {
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
