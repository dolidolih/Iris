package party.qwer.iris;

// Kakaodecrypt : jiru/kakaodecrypt

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class KakaoDecrypt {
    private static final java.util.Map<String, byte[]> keyCache = new java.util.HashMap<>();

    private static String incept(int n) {
        String[] dict1 = {"adrp.ldrsh.ldnp", "ldpsw", "umax", "stnp.rsubhn", "sqdmlsl", "uqrshl.csel", "sqshlu", "umin.usubl.umlsl", "cbnz.adds", "tbnz",
                "usubl2", "stxr", "sbfx", "strh", "stxrb.adcs", "stxrh", "ands.urhadd", "subs", "sbcs", "fnmadd.ldxrb.saddl",
                "stur", "ldrsb", "strb", "prfm", "ubfiz", "ldrsw.madd.msub.sturb.ldursb", "ldrb", "b.eq", "ldur.sbfiz", "extr",
                "fmadd", "uqadd", "sshr.uzp1.sttrb", "umlsl2", "rsubhn2.ldrh.uqsub", "uqshl", "uabd", "ursra", "usubw", "uaddl2",
                "b.gt", "b.lt", "sqshl", "bics", "smin.ubfx", "smlsl2", "uabdl2", "zip2.ssubw2", "ccmp", "sqdmlal",
                "b.al", "smax.ldurh.uhsub", "fcvtxn2", "b.pl"};
        String[] dict2 = {"saddl", "urhadd", "ubfiz.sqdmlsl.tbnz.stnp", "smin", "strh", "ccmp", "usubl", "umlsl", "uzp1", "sbfx",
                "b.eq", "zip2.prfm.strb", "msub", "b.pl", "csel", "stxrh.ldxrb", "uqrshl.ldrh", "cbnz", "ursra", "sshr.ubfx.ldur.ldnp",
                "fcvtxn2", "usubl2", "uaddl2", "b.al", "ssubw2", "umax", "b.lt", "adrp.sturb", "extr", "uqshl",
                "smax", "uqsub.sqshlu", "ands", "madd", "umin", "b.gt", "uabdl2", "ldrsb.ldpsw.rsubhn", "uqadd", "sttrb",
                "stxr", "adds", "rsubhn2.umlsl2", "sbcs.fmadd", "usubw", "sqshl", "stur.ldrsh.smlsl2", "ldrsw", "fnmadd", "stxrb.sbfiz",
                "adcs", "bics.ldrb", "l1ursb", "subs.uhsub", "ldurh", "uabd", "sqdmlal"};
        String word1 = dict1[n % dict1.length];
        String word2 = dict2[(n + 31) % dict2.length];
        return word1 + '.' + word2;
    }

    private static byte[] genSalt(long user_id, int encType) {
        if (user_id <= 0) {
            return new byte[16];
        }

        String[] prefixes = {"", "", "12", "24", "18", "30", "36", "12", "48", "7", "35", "40", "17", "23", "29",
                "isabel", "kale", "sulli", "van", "merry", "kyle", "james", "maddux",
                "tony", "hayden", "paul", "elijah", "dorothy", "sally", "bran",
                incept(830819), "veil"};
        String saltStr;
        try {
            saltStr = prefixes[encType] + user_id;
            saltStr = saltStr.substring(0, Math.min(saltStr.length(), 16));
        } catch (ArrayIndexOutOfBoundsException e) {
            throw new IllegalArgumentException("Unsupported encoding type " + encType, e);
        }
        saltStr = saltStr + "\0".repeat(Math.max(0, 16 - saltStr.length()));
        return saltStr.getBytes(StandardCharsets.UTF_8);
    }

    private static void pkcs16adjust(byte[] a, int aOff, byte[] b) {
        int x = (b[b.length - 1] & 0xff) + (a[aOff + b.length - 1] & 0xff) + 1;
        a[aOff + b.length - 1] = (byte) (x % 256);
        x = x >> 8;
        for (int i = b.length - 2; i >= 0; i--) {
            x = x + (b[i] & 0xff) + (a[aOff + i] & 0xff);
            a[aOff + i] = (byte) (x % 256);
            x = x >> 8;
        }
    }

    private static byte[] deriveKey(byte[] passwordBytes, byte[] saltBytes, int iterations, int dkeySize) throws Exception {
        String password = new String(passwordBytes, StandardCharsets.US_ASCII) + "\0";
        byte[] passwordUTF16BE = password.getBytes(StandardCharsets.UTF_16BE);

        MessageDigest hasher = MessageDigest.getInstance("SHA-1");
        int digestSize = hasher.getDigestLength();
        int blockSize = 64;

        byte[] D = new byte[blockSize];
        Arrays.fill(D, (byte) 1);
        byte[] S = new byte[blockSize * ((saltBytes.length + blockSize - 1) / blockSize)];
        for (int i = 0; i < S.length; i++) {
            S[i] = saltBytes[i % saltBytes.length];
        }
        byte[] P = new byte[blockSize * ((passwordUTF16BE.length + blockSize - 1) / blockSize)];
        for (int i = 0; i < P.length; i++) {
            P[i] = passwordUTF16BE[i % passwordUTF16BE.length];
        }

        byte[] I = new byte[S.length + P.length];
        System.arraycopy(S, 0, I, 0, S.length);
        System.arraycopy(P, 0, I, S.length, P.length);

        byte[] B = new byte[blockSize];
        int c = (dkeySize + digestSize - 1) / digestSize;

        byte[] dKey = new byte[dkeySize];
        for (int i = 1; i <= c; i++) {
            hasher = MessageDigest.getInstance("SHA-1");
            hasher.update(D);
            hasher.update(I);
            byte[] A = hasher.digest();

            for (int j = 1; j < iterations; j++) {
                hasher = MessageDigest.getInstance("SHA-1");
                hasher.update(A);
                A = hasher.digest();
            }

            for (int j = 0; j < B.length; j++) {
                B[j] = A[j % A.length];
            }

            for (int j = 0; j < I.length / blockSize; j++) {
                pkcs16adjust(I, j * blockSize, B);
            }

            int start = (i - 1) * digestSize;
            if (i == c) {
                System.arraycopy(A, 0, dKey, start, dkeySize - start);
            } else {
                System.arraycopy(A, 0, dKey, start, A.length);
            }
        }

        return dKey;
    }

    public static String decrypt(int encType, String b64_ciphertext, long user_id) throws Exception {
        byte[] keyBytes = new byte[] {
            (byte)0x16, (byte)0x08, (byte)0x09, (byte)0x6f, (byte)0x02, (byte)0x17, (byte)0x2b, (byte)0x08,
            (byte)0x21, (byte)0x21, (byte)0x0a, (byte)0x10, (byte)0x03, (byte)0x03, (byte)0x07, (byte)0x06
        };
        byte[] ivBytes = new byte[] {
            (byte)0x0f, (byte)0x08, (byte)0x01, (byte)0x00, (byte)0x19, (byte)0x47, (byte)0x25, (byte)0xdc,
            (byte)0x15, (byte)0xf5, (byte)0x17, (byte)0xe0, (byte)0xe1, (byte)0x15, (byte)0x0c, (byte)0x35
        };

        byte[] salt = genSalt(user_id, encType);
        byte[] key;
        String saltStr = new String(salt, StandardCharsets.UTF_8);
        if (keyCache.containsKey(saltStr)) {
            key = keyCache.get(saltStr);
        } else {
            key = deriveKey(keyBytes, salt, 2, 32);
            keyCache.put(saltStr, key);
        }

        SecretKeySpec secretKeySpec = new SecretKeySpec(key, "AES");
        IvParameterSpec ivParameterSpec = new IvParameterSpec(ivBytes);
        Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");

        cipher.init(Cipher.DECRYPT_MODE, secretKeySpec, ivParameterSpec);

        byte[] ciphertext = java.util.Base64.getDecoder().decode(b64_ciphertext);
        if (ciphertext.length == 0) {
            return b64_ciphertext;
        }
        byte[] padded;
        try {
            padded = cipher.doFinal(ciphertext);
        } catch (javax.crypto.BadPaddingException e) {
            System.err.println("BadPaddingException during decryption, possibly due to incorrect key or data. Returning original ciphertext.");
            return b64_ciphertext;
        }


        int paddingLength = padded[padded.length - 1];
        if (paddingLength <= 0 || paddingLength > cipher.getBlockSize()) {
            throw new IllegalArgumentException("Invalid padding length: " + paddingLength);
        }

        byte[] plaintextBytes = new byte[padded.length - paddingLength];
        System.arraycopy(padded, 0, plaintextBytes, 0, plaintextBytes.length);


        return new String(plaintextBytes, StandardCharsets.UTF_8);

    }

    public static String encrypt(int encType, String plaintext, long user_id) throws Exception {
        byte[] keyBytes = new byte[] {
            (byte)0x16, (byte)0x08, (byte)0x09, (byte)0x6f, (byte)0x02, (byte)0x17, (byte)0x2b, (byte)0x08,
            (byte)0x21, (byte)0x21, (byte)0x0a, (byte)0x10, (byte)0x03, (byte)0x03, (byte)0x07, (byte)0x06
        };
        byte[] ivBytes = new byte[] {
            (byte)0x0f, (byte)0x08, (byte)0x01, (byte)0x00, (byte)0x19, (byte)0x47, (byte)0x25, (byte)0xdc,
            (byte)0x15, (byte)0x5, (byte)0x17, (byte)0xe0, (byte)0xe1, (byte)0x15, (byte)0x0c, (byte)0x35
        };

        byte[] salt = genSalt(user_id, encType);
        byte[] key;
        String saltStr = new String(salt, StandardCharsets.UTF_8);
        if (keyCache.containsKey(saltStr)) {
            key = keyCache.get(saltStr);
        } else {
            key = deriveKey(keyBytes, salt, 2, 32);
            keyCache.put(saltStr, key);
        }
        SecretKeySpec secretKeySpec = new SecretKeySpec(key, "AES");
        IvParameterSpec ivParameterSpec = new IvParameterSpec(ivBytes);
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, secretKeySpec, ivParameterSpec);
        byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
        return java.util.Base64.getEncoder().encodeToString(ciphertext);
    }
}