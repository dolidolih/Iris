package party.qwer.iris

import java.nio.file.Paths

class AssetManager {
    companion object {
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
}