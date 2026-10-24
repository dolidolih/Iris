package party.qwer.iris

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Arrays
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.max
import kotlin.math.min

// Kakaodecrypt : jiru/kakaodecrypt

class KakaoDecrypt {
    companion object {
        private val keyCache: MutableMap<String, ByteArray?> = ConcurrentHashMap()

        /**
         * ISSUE-19: 복호화 실패를 조용한 스킵으로 넘기지 않기 위한 노출 지수. 카톡이
         * 저장/인코딩 형식을 앱 업데이트 없이 바꾸면 모든 답장이 사라질 수 있는데, 지금은
         * stdout 한 줄만 남고 카운트가 없어 관측이 불가능하다.
         */
        private val failureCount = java.util.concurrent.atomic.AtomicLong()
        private val plaintextCount = java.util.concurrent.atomic.AtomicLong()
        @Volatile private var lastFailureAtMs: Long = 0
        @Volatile private var lastFailureReason: String = ""
        @Volatile private var lastFailureSample: String = ""

        /** 총 복호화 실패(pass-through 포함) 건수 — status/diagnostics 에서 조회. */
        fun failureCount(): Long = failureCount.get()

        /** 암호화되지 않은 raw 행(enc=0 또는 인코딩 비-기존)을 통과시킨 건수. */
        fun plaintextPassThroughCount(): Long = plaintextCount.get()

        /** 사람이 읽을 수 있는 한 줄 — "no messages" 와 "messages skipped" 를 구분하게. */
        fun describeFailures(): String = if (failureCount.get() == 0L) {
            "decrypt: ok"
        } else {
            "decrypt: ${failureCount.get()} failure(s), last=\"$lastFailureReason\" " +
                "sample=\"$lastFailureSample\" @${lastFailureAtMs}"
        }

        /**
         * 실패를 로그성으로 남긴다 — spam 을 막기 위해 reason 이 바뀐 경우거나 60s 가
         * 지나야만 print 한다. 카운트는 어차피 무조건 올라간다.
         */
        private fun noteFailure(reason: String, sample: String) {
            failureCount.incrementAndGet()
            val now = System.currentTimeMillis()
            if (reason != lastFailureReason || now - lastFailureAtMs > 60_000L) {
                lastFailureReason = reason
                lastFailureSample = sample.take(80)
                lastFailureAtMs = now
                System.err.println("KakaoDecrypt: $reason (total=$failureCount) sample='${lastFailureSample}'")
            }
        }

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
                // ISSUE-34: 조용한 zero-salt 지점. bot user_id 를 해석하지 못하면(0) salt 가
                // 전부 0 으로 굳어 복호가 "성공처럼" 깨진다 — 카운터/로그로 남긴다.
                noteFailure("user_id<=0 — zero-filled salt (bot id unresolved?) enc=$encType", "")
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

        /**
         * @throws Exception 는 더이상 던지지 않는다 (ISSUE-19) — 실패하면 입력을 그대로
         *   돌려주는 pass-through 이고, 카운터만 올라간다. 호출측은 `decoded` 구분 없이
         *   텍스트를 넘겨받지만, raw 원문이 나갔다는 사실은 status/describeFailures 로 안다.
         */
        fun decrypt(encType: Int, b64_ciphertext: String, user_id: Long): String {
            // enc=0 은 "암호화 안 됨" 컬럼 표기. salt 를 만들고 AES 를 태워 깨진 문자열(또는
            // BadPadding 으로 인한 원문반환)이 아니라, 애초에 원문을 그대로 쓴다.
            if (encType == 0) {
                plaintextCount.incrementAndGet()
                return b64_ciphertext
            }

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

            // strict base64 가 아니면 (평문 JSON, base64url, 줄바꿈 낀 MIME) 예외가 아니라
            // 다른 디코더를 순서대로 타고, 그래도 안 되면 pass-through. 예외는 메시지 유실을
            // 만든다 — buildFrame 호출측이 catch 이후 null 을 돌려 같은 메시지를 버리기 때문.
            val ciphertext = decodeTolerant(b64_ciphertext)
            if (ciphertext == null) {
                plaintextCount.incrementAndGet()
                return b64_ciphertext
            }
            if (ciphertext.size == 0) {
                return b64_ciphertext
            }
            val padded: ByteArray
            try {
                padded = cipher.doFinal(ciphertext)
            } catch (e: BadPaddingException) {
                noteFailure("bad padding (key/salt mismatch?) enc=$encType", b64_ciphertext)
                return b64_ciphertext
            } catch (e: Exception) {
                noteFailure("cipher.doFinal ${e.javaClass.simpleName}: ${e.message}", b64_ciphertext)
                return b64_ciphertext
            }

            // 서명된 byte 로 pad 길이를 읽으면 byte>=0x80 에서 음수가 되어 require 가 실패하고
            // 그 자체가 메시지 한 건을 통째로 날린다. unsigned 로 읽는다.
            val paddingLength = padded[padded.size - 1].toInt() and 0xff
            if (paddingLength <= 0 || paddingLength > cipher.blockSize || paddingLength > padded.size) {
                noteFailure("invalid padding length $paddingLength (enc=$encType)", b64_ciphertext)
                return b64_ciphertext
            }

            val plaintextBytes = ByteArray(padded.size - paddingLength)
            System.arraycopy(padded, 0, plaintextBytes, 0, plaintextBytes.size)

            val text = String(plaintextBytes, StandardCharsets.UTF_8)
            if (text.isEmpty() && b64_ciphertext.isNotEmpty()) {
                noteFailure("decoded empty payload", b64_ciphertext)
                return b64_ciphertext
            }
            return text
        }

        /**
         * KakaoTalk is not guaranteed to stick to strict RFC base64: some payloads are
         * base64url, some come with line separators, and a "plain JSON" row may show up where
         * encryption was expected. Try the decoders in order; null means "not base64 at all".
         */
        private fun decodeTolerant(value: String): ByteArray? {
            runCatching { return Base64.getDecoder().decode(value) }
            runCatching { return Base64.getUrlDecoder().decode(value) }
            runCatching { return Base64.getMimeDecoder().decode(value) }
            noteFailure("not base64 (enc-decoder exhausted)", value)
            return null
        }
    }
}