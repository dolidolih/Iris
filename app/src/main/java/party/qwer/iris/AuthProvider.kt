package party.qwer.iris

import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import android.util.Base64
import java.io.File
import org.json.JSONObject
import org.json.JSONException
import java.nio.charset.StandardCharsets

//Original Code from authdecoder by naijun0403
object AuthProvider {
    private val keys1 = intArrayOf(10, 2, 3, -4, 20, 73, 47, -38, 27, -22, 11, -20, -22, 37, 36, 54)
    private val keys2 = intArrayOf(67, 109, -115, -110, -23, 119, 33, 86, -99, -28, -102, 109, -73, 13, 43, -96, 109, -76, 91, -83, 73, -14, 107, -88, 6, 11, 74, 109, 84, -68, -80, 15)
    private val cipher: Cipher by lazy {
        try {
            Cipher.getInstance("AES/CBC/PKCS5Padding")
        } catch (e: Exception) {
            throw RuntimeException("Failed to get Cipher instance", e)
        }
    }

    @Volatile private var cipherInitialized = false
    private val initLock = Any()

    private fun ensureCipherInitialized() {
        if (!cipherInitialized) {
            synchronized(initLock) {
                if (!cipherInitialized) {
                    try {
                        val secretKey = SecretKeySpec(keys2.toByteArray(), "AES")
                        cipher.init(Cipher.DECRYPT_MODE, secretKey, IvParameterSpec(keys1.toByteArray()))
                        cipherInitialized = true
                    } catch (e: Exception) {
                        throw RuntimeException("Failed to initialize Cipher", e)
                    }
                }
            }
        }
    }

    fun getToken(): JSONObject {
        ensureCipherInitialized()

        val dataArray = readFiles()
        val aot = dataArray[0]
        val deviceId = dataArray[1]

        try {
            val decryptedAotByte = cipher.doFinal(Base64.decode(aot, Base64.DEFAULT))
            val decryptedAot = String(decryptedAotByte, StandardCharsets.UTF_8)
            val decryptedAotJson = JSONObject(decryptedAot)

            decryptedAotJson.put("d_id", deviceId)
            return decryptedAotJson

        } catch (e: JSONException) {
            throw Exception("Failed to parse decrypted AOT string into JSON: ${e.message}", e)
        } catch (e: Exception) {
            throw Exception("Failed during AOT decryption or JSON processing: ${e.message}", e)
        }
    }

    private fun readFiles(): Array<String> {
        val appPath = PathUtils.getAppPath()
        val prefsFilePath = "${appPath}shared_prefs/KakaoTalk.hw.perferences.xml"
        val aotFilePath = "${appPath}aot"

        val prefsFile = File(prefsFilePath)
        val aotFile = File(aotFilePath)

        if (!prefsFile.exists()) {
            throw Exception("Preferences file not found: ${prefsFile.path}")
        }
        if (!prefsFile.canRead()) {
            throw Exception("Preferences file cannot be read (check permissions): ${prefsFile.path}")
        }
        if (!aotFile.exists()) {
            throw Exception("AOT file not found: ${aotFile.path}")
        }
        if (!aotFile.canRead()) {
            throw Exception("AOT file cannot be read (check permissions): ${aotFile.path}")
        }

        val prefsContent = try {
            prefsFile.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        } catch (e: Exception) {
            throw Exception("Failed to read preferences file: ${e.message}", e)
        }

        val regex = Regex("""<string name="d_id">\s*(.*?)\s*</string>""")
        val matchResult = regex.find(prefsContent)
            ?: throw Exception("Failed to find d_id pattern in preferences file content.")

        val deviceId = matchResult.groupValues[1]
        if (deviceId.isBlank()) {
            throw Exception("Extracted d_id value is blank from preferences file.")
        }

        val aot = try {
            aotFile.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        } catch (e: Exception) {
            throw Exception("Failed to read AOT file: ${e.message}", e)
        }

        if (aot.isBlank()) {
            throw Exception("AOT file content is empty or blank.")
        }

        return arrayOf(aot, deviceId)
    }

    private fun IntArray.toByteArray(): ByteArray {
        return this.map { it.toByte() }.toByteArray()
    }
}