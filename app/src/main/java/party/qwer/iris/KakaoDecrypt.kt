package party.qwer.iris

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Arrays
import java.util.Base64
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.max
import kotlin.math.min

// Kakaodecrypt : jiru/kakaodecrypt

class KakaoDecrypt {
    companion object {
        private val keyCache: MutableMap<String, ByteArray?> = HashMap()

        private fun incept(n: Int): String {
            val dict1 = arrayOf(
                "adrp.ldrsh.ldnp",
                "ldpsw",
                "umax",
                "stnp.rsubhn",
                "sqdmlsl",
                "uqrshl.csel",
                "sqshlu",
                "umin.usubl.umlsl",
                "cbnz.adds",
                "tbnz",
                "usubl2",
                "stxr",
                "sbfx",
                "strh",
                "stxrb.adcs",
                "stxrh",
                "ands.urhadd",
                "subs",
                "sbcs",
                "fnmadd.ldxrb.saddl",
                "stur",
                "ldrsb",
                "strb",
                "prfm",
                "ubfiz",
                "ldrsw.madd.msub.sturb.ldursb",
                "ldrb",
                "b.eq",
                "ldur.sbfiz",
                "extr",
                "fmadd",
                "uqadd",
                "sshr.uzp1.sttrb",
                "umlsl2",
                "rsubhn2.ldrh.uqsub",
                "uqshl",
                "uabd",
                "ursra",
                "usubw",
                "uaddl2",
                "b.gt",
                "b.lt",
                "sqshl",
                "bics",
                "smin.ubfx",
                "smlsl2",
                "uabdl2",
                "zip2.ssubw2",
                "ccmp",
                "sqdmlal",
                "b.al",
                "smax.ldurh.uhsub",
                "fcvtxn2",
                "b.pl"
            )
            val dict2 = arrayOf(
                "saddl",
                "urhadd",
                "ubfiz.sqdmlsl.tbnz.stnp",
                "smin",
                "strh",
                "ccmp",
                "usubl",
                "umlsl",
                "uzp1",
                "sbfx",
                "b.eq",
                "zip2.prfm.strb",
                "msub",
                "b.pl",
                "csel",
                "stxrh.ldxrb",
                "uqrshl.ldrh",
                "cbnz",
                "ursra",
                "sshr.ubfx.ldur.ldnp",
                "fcvtxn2",
                "usubl2",
                "uaddl2",
                "b.al",
                "ssubw2",
                "umax",
                "b.lt",
                "adrp.sturb",
                "extr",
                "uqshl",
                "smax",
                "uqsub.sqshlu",
                "ands",
                "madd",
                "umin",
                "b.gt",
                "uabdl2",
                "ldrsb.ldpsw.rsubhn",
                "uqadd",
                "sttrb",
                "stxr",
                "adds",
                "rsubhn2.umlsl2",
                "sbcs.fmadd",
                "usubw",
                "sqshl",
                "stur.ldrsh.smlsl2",
                "ldrsw",
                "fnmadd",
                "stxrb.sbfiz",
                "adcs",
                "bics.ldrb",
                "l1ursb",
                "subs.uhsub",
                "ldurh",
                "uabd",
                "sqdmlal"
            )
            val word1 = dict1[n % dict1.size]
            val word2 = dict2[(n + 31) % dict2.size]
            return "$word1.$word2"
        }

        private fun genSalt(user_id: Long, encType: Int): ByteArray {
            if (user_id <= 0) {
                return ByteArray(16)
            }

            val prefixes = arrayOf(
                "", "", "12", "24", "18", "30", "36", "12", "48", "7", "35", "40", "17", "23", "29",
                "isabel", "kale", "sulli", "van", "merry", "kyle", "james", "maddux",
                "tony", "hayden", "paul", "elijah", "dorothy", "sally", "bran",
                incept(830819), "veil"
            )
            var saltStr: String
            try {
                saltStr = prefixes[encType] + user_id
                saltStr = saltStr.substring(0, min(saltStr.length.toDouble(), 16.0).toInt())
            } catch (e: ArrayIndexOutOfBoundsException) {
                throw IllegalArgumentException("Unsupported encoding type $encType", e)
            }
            saltStr += "\u0000".repeat(max(0.0, (16 - saltStr.length).toDouble()).toInt())
            return saltStr.toByteArray(StandardCharsets.UTF_8)
        }

        private fun pkcs16adjust(a: ByteArray, aOff: Int, b: ByteArray) {
            var x = (b[b.size - 1].toInt() and 0xff) + (a[aOff + b.size - 1].toInt() and 0xff) + 1
            a[aOff + b.size - 1] = (x % 256).toByte()
            x = x shr 8
            for (i in b.size - 2 downTo 0) {
                x += (b[i].toInt() and 0xff) + (a[aOff + i].toInt() and 0xff)
                a[aOff + i] = (x % 256).toByte()
                x = x shr 8
            }
        }

        @Throws(Exception::class)
        private fun deriveKey(
            passwordBytes: ByteArray,
            saltBytes: ByteArray,
            iterations: Int,
            dkeySize: Int
        ): ByteArray {
            val password = String(passwordBytes, StandardCharsets.US_ASCII) + "\u0000"
            val passwordUTF16BE = password.toByteArray(StandardCharsets.UTF_16BE)

            var hasher = MessageDigest.getInstance("SHA-1")
            val digestSize = hasher.digestLength
            val blockSize = 64

            val D = ByteArray(blockSize)
            Arrays.fill(D, 1.toByte())
            val S = ByteArray(blockSize * ((saltBytes.size + blockSize - 1) / blockSize))
            for (i in S.indices) {
                S[i] = saltBytes[i % saltBytes.size]
            }
            val P = ByteArray(blockSize * ((passwordUTF16BE.size + blockSize - 1) / blockSize))
            for (i in P.indices) {
                P[i] = passwordUTF16BE[i % passwordUTF16BE.size]
            }

            val I = ByteArray(S.size + P.size)
            System.arraycopy(S, 0, I, 0, S.size)
            System.arraycopy(P, 0, I, S.size, P.size)

            val B = ByteArray(blockSize)
            val c = (dkeySize + digestSize - 1) / digestSize

            val dKey = ByteArray(dkeySize)
            for (i in 1..c) {
                hasher = MessageDigest.getInstance("SHA-1")
                hasher.update(D)
                hasher.update(I)
                var A = hasher.digest()

                for (j in 1 until iterations) {
                    hasher = MessageDigest.getInstance("SHA-1")
                    hasher.update(A)
                    A = hasher.digest()
                }

                for (j in B.indices) {
                    B[j] = A[j % A.size]
                }

                for (j in 0 until I.size / blockSize) {
                    pkcs16adjust(I, j * blockSize, B)
                }

                val start = (i - 1) * digestSize
                if (i == c) {
                    System.arraycopy(A, 0, dKey, start, dkeySize - start)
                } else {
                    System.arraycopy(A, 0, dKey, start, A.size)
                }
            }

            return dKey
        }

        @Throws(Exception::class)
        fun decrypt(encType: Int, b64_ciphertext: String, user_id: Long): String {
            val keyBytes = byteArrayOf(
                0x16.toByte(),
                0x08.toByte(),
                0x09.toByte(),
                0x6f.toByte(),
                0x02.toByte(),
                0x17.toByte(),
                0x2b.toByte(),
                0x08.toByte(),
                0x21.toByte(),
                0x21.toByte(),
                0x0a.toByte(),
                0x10.toByte(),
                0x03.toByte(),
                0x03.toByte(),
                0x07.toByte(),
                0x06.toByte()
            )
            val ivBytes = byteArrayOf(
                0x0f.toByte(),
                0x08.toByte(),
                0x01.toByte(),
                0x00.toByte(),
                0x19.toByte(),
                0x47.toByte(),
                0x25.toByte(),
                0xdc.toByte(),
                0x15.toByte(),
                0xf5.toByte(),
                0x17.toByte(),
                0xe0.toByte(),
                0xe1.toByte(),
                0x15.toByte(),
                0x0c.toByte(),
                0x35.toByte()
            )

            val salt = genSalt(user_id, encType)
            val key: ByteArray?
            val saltStr = String(salt, StandardCharsets.UTF_8)
            if (keyCache.containsKey(saltStr)) {
                key = keyCache[saltStr]
            } else {
                key = deriveKey(keyBytes, salt, 2, 32)
                keyCache[saltStr] = key
            }

            val secretKeySpec = SecretKeySpec(key, "AES")
            val ivParameterSpec = IvParameterSpec(ivBytes)
            val cipher = Cipher.getInstance("AES/CBC/NoPadding")

            cipher.init(Cipher.DECRYPT_MODE, secretKeySpec, ivParameterSpec)

            val ciphertext = Base64.getDecoder().decode(b64_ciphertext)
            if (ciphertext.size == 0) {
                return b64_ciphertext
            }
            val padded: ByteArray
            try {
                padded = cipher.doFinal(ciphertext)
            } catch (e: BadPaddingException) {
                System.err.println("BadPaddingException during decryption, possibly due to incorrect key or data. Returning original ciphertext.")
                return b64_ciphertext
            }


            val paddingLength = padded[padded.size - 1].toInt()
            require(!(paddingLength <= 0 || paddingLength > cipher.blockSize)) { "Invalid padding length: $paddingLength" }

            val plaintextBytes = ByteArray(padded.size - paddingLength)
            System.arraycopy(padded, 0, plaintextBytes, 0, plaintextBytes.size)


            return String(plaintextBytes, StandardCharsets.UTF_8)
        }
    }
}