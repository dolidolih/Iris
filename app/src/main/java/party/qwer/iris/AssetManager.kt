package party.qwer.iris

import java.io.IOException
import java.nio.file.Paths

object AssetManager {
    @Throws(IOException::class)
    fun readFile(filename: String?): String {
        val loader = AssetManager::class.java.classLoader
            ?: throw RuntimeException("ClassLoader를 찾을 수 없습니다.")
        val path = Paths.get("assets", filename).toString()

        loader.getResource(path).openStream().use { stream ->
            return stream.bufferedReader().use {
                it.readText()
            }
        }
    }
}