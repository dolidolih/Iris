package party.qwer.iris

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Paths

object AssetManager {
    @Throws(IOException::class)
    fun readFile(filename: String?): String {
        val loader = AssetManager::class.java.classLoader
            ?: throw RuntimeException("ClassLoader를 찾을 수 없습니다.")
        val path = Paths.get("assets", filename).toString()

        loader.getResource(path).openStream().use { stream ->
            var count: Int
            val buffer = ByteArray(1024)
            val byteStream = ByteArrayOutputStream(stream.available())

            while (true) {
                count = stream.read(buffer)
                if (count <= 0) break
                byteStream.write(buffer, 0, count)
            }
            return byteStream.toString()
        }
    }
}