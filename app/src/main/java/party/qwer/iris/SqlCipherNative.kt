package party.qwer.iris

import java.io.File
import java.util.zip.ZipFile

/**
 * Loads libsqlcipher.so.
 *
 * net.zetetic:sqlcipher-android does NOT auto-load its native library; the caller must
 * load it before using net.zetetic.database.sqlcipher.SQLiteDatabase.
 *
 * Iris is pushed to `/data/local/tmp/Iris.apk` and run through `app_process`, so the usual
 * "app process" resolution never applies and there is no installed package to read an
 * extracted lib dir from. The order below is chosen for that reality:
 *
 *   1. `System.loadLibrary("sqlcipher")` — only ever succeeds if Iris is a real installed
 *      app (some deployments do install it). Cheap, so it goes first.
 *   2. This process's own `nativeLibraryDir` — same installed-app case.
 *   3. **Extract `lib/<abi>/libsqlcipher.so` out of the APK we are running from** into a
 *      private (owner-only) dir and `System.load` it. This is the path that makes SQLCipher
 *      work for a daemon that was only ever `adb push`ed, and the copy is version-matched
 *      to our own build.
 *   4. Installed package lib dirs under `/data/app` (depth-bounded scan). Another package's
 *      copy — KakaoTalk bundles the same project — is a last resort and is announced, because
 *      an ABI/version mismatch shows up as a bogus key error rather than a load error.
 */
object SqlCipherNative {
    @Volatile
    private var loaded = false

    /** Which file we ended up loading — for status/diagnostics. */
    @Volatile
    var loadedPath: String? = null
        private set

    /** 어디서 났는가 — foreign 여부가 아니라 출처 자체로 판별한다 (extracted path 에는
     *  패키지 이름이 들어있지 않아서 path 문자열 검색으로는 "우리 것" 을 구분 못 한다). */
    @Volatile
    var source: String = "none"
        private set

    private const val NATIVE_LIB_NAME = "libsqlcipher.so"
    private const val APK_LIB_PREFIX = "lib/"

    fun ensureLoaded(): Boolean {
        if (loaded) return true
        synchronized(this) {
            if (loaded) return true

            if (runCatching { System.loadLibrary("sqlcipher") }.isSuccess) {
                return markLoaded("loadLibrary(sqlcipher)", "loadLibrary")
            }

            ownNativeLibraryDir()?.let {
                if (runCatching { System.load(it) }.isSuccess) return markLoaded(it, "own-package")
            }

            extractFromRunningApk()?.let {
                if (runCatching { System.load(it) }.isSuccess) return markLoaded(it, "apk-extract")
            }

            val scanned = runCatching { scanPackageLibDirs() }.getOrElse {
                System.err.println("SqlCipherNative: /data/app scan failed: ${it.message}")
                emptyList()
            }
            val ours = scanned.firstOrNull { "party.qwer.iris" in it }
            if (ours != null && runCatching { System.load(ours) }.isSuccess) return markLoaded(ours, "package-scan")

            val kakao = scanned.firstOrNull { "com.kakao.talk" in it }
            if (kakao != null && runCatching { System.load(kakao) }.isSuccess) {
                System.err.println(
                    "SqlCipherNative: our own $NATIVE_LIB_NAME is missing — falling back to the " +
                        "bundled copy at $kakao. A different SQLCipher build here means key errors " +
                        "are indistinguishable from wrong-key errors."
                )
                return markLoaded(kakao, "foreign-package")
            }

            System.err.println(
                "SqlCipherNative: could not load native $NATIVE_LIB_NAME " +
                    "(apk=${apkCandidates().map { it.path }}, scanned=${scanned.size})"
            )
            return false
        }
    }

    /** True when the loaded library came from another package, i.e. unverified. */
    fun isForeignLibrary(): Boolean = source == "foreign-package"

    /** 한 줄 설명 — /process-status·로그에서 "무슨 lib 을 탔나" 를 보기 위해. */
    fun describe(): String =
        "sqlcipher native: $source -> ${loadedPath ?: "<not loaded>"}" +
            if (isForeignLibrary()) " (FOREIGN BUILD — key errors may be misleading)" else ""

    private fun markLoaded(path: String, from: String): Boolean {
        loaded = true
        loadedPath = path
        source = from
        println("SqlCipherNative: loaded $path ($from)")
        return true
    }

    /** This process's own nativeLibraryDir (installed-app case); null in app_process. */
    private fun ownNativeLibraryDir(): String? = runCatching {
        val app = Class.forName("android.app.ActivityThread")
            .getMethod("currentApplication")
            .invoke(null) ?: return null
        val info = app.javaClass.getMethod("getApplicationInfo").invoke(app)
        val dir = info.javaClass.getField("nativeLibraryDir").get(info) as? String ?: return null
        val f = File(dir, NATIVE_LIB_NAME)
        if (f.isFile) f.absolutePath else null
    }.getOrNull()

    // ── APK extraction (the pushed-apk daemon path) ───────────────────────

    /**
     * Candidate APK files this process could have been started from.
     *
     * `app_process /` gets its dex from `CLASSPATH`, so `java.class.path` is the authoritative
     * source; `IRIS_APK_PATH` lets iris_control/`DaemonLauncher` state it explicitly, and the
     * well-known push target is only a last guess.
     */
    private fun apkCandidates(): List<File> {
        val found = LinkedHashSet<String>()
        System.getenv("IRIS_APK_PATH")?.takeIf { it.isNotBlank() }?.let { found.add(it.trim()) }
        runCatching {
            val loc = SqlCipherNative::class.java.protectionDomain?.codeSource?.location?.path
            if (!loc.isNullOrBlank()) found.add(loc)
        }
        System.getProperty("java.class.path").orEmpty()
            .split(File.pathSeparator)
            .map { it.trim() }
            .filter { it.endsWith(".apk", ignoreCase = true) }
            .forEach { found.add(it) }
        found.add("/data/local/tmp/Iris.apk")
        return found.map { File(normalizePath(it)) }.filter { it.isFile }
    }

    /** `file:/x/a.apk`, `/x/a.apk!/lib/…`, `x.apk` → plain filesystem path. */
    private fun normalizePath(raw: String): String {
        var p = raw.trim()
        if (p.startsWith("file://")) p = p.removePrefix("file://")
        if (p.startsWith("file:")) p = p.removePrefix("file:")
        val sep = p.indexOf("!")
        if (sep > 0) p = p.substring(0, sep)
        return p
    }

    /** ABI 후보, 우선순위대로. */
    private fun abis(): List<String> {
        val supported = runCatching {
            (Class.forName("android.os.Build").getField("SUPPORTED_ABIS").get(null) as? Array<String>)
                ?.toList()
        }.getOrNull().orEmpty()
        val arch = System.getProperty("os.arch").orEmpty().lowercase()
        val mapped = when {
            arch.contains("aarch64") || arch.contains("arm64") -> listOf("arm64-v8a")
            arch.contains("x86_64") -> listOf("x86_64")
            arch.contains("arm") -> listOf("armeabi-v7a", "armeabi")
            arch.contains("i.86") || arch == "x86" -> listOf("x86")
            else -> emptyList()
        }
        return (supported + mapped + listOf("arm64-v8a", "x86_64", "armeabi-v7a", "x86")).distinct()
    }

    /**
     * Pull `lib/<abi>/libsqlcipher.so` out of the running APK into a private dir.
     *
     * The copy is reused across restarts when the APK has not changed (size + mtime), so a
     * restart does not pay for a ~10 MB write again.
     */
    private fun extractFromRunningApk(): String? {
        for (apk in apkCandidates()) {
            val target = runCatching {
                ZipFile(apk).use { zip ->
                    for (abi in abis()) {
                        val entry = zip.getEntry(APK_LIB_PREFIX + abi + "/" + NATIVE_LIB_NAME)
                            ?: continue
                        val dir = WorkDirs.sub("native") ?: return@use null
                        val dst = File(dir, "sqlcipher-$abi.so")
                        val fresh = dst.isFile && dst.length() == entry.size &&
                            dst.lastModified() >= apk.lastModified()
                        if (!fresh) {
                            zip.getInputStream(entry).use { input ->
                                dst.outputStream().use { out -> input.copyTo(out, 64 * 1024) }
                            }
                            dst.setLastModified(apk.lastModified())
                            runCatching {
                                dst.setReadable(false, false)
                                dst.setReadable(true, true)
                                dst.setWritable(false, false)
                                dst.setWritable(true, true)
                                dst.setExecutable(false, false)
                                dst.setExecutable(true, true)
                            }
                        }
                        return@use dst.absolutePath
                    }
                    System.err.println(
                        "SqlCipherNative: $NATIVE_LIB_NAME not present in $apk " +
                            "(checked abis ${abis().joinToString()})"
                    )
                    null
                }
            }.getOrElse {
                System.err.println("SqlCipherNative: extraction from $apk failed: ${it.message}")
                null
            }
            if (target != null) {
                println("SqlCipherNative: extracted $NATIVE_LIB_NAME from ${apk.path}")
                return target
            }
        }
        return null
    }

    /**
     * `/data/app/<pkg>-<hash>/lib/<abi>/libsqlcipher.so` (and the flat `lib/` layout).
     * Depth-bounded listFiles instead of walkTopDown: at most a few hundred stat() calls and
     * it never enters the app-private trees.
     */
    private fun scanPackageLibDirs(): List<String> {
        val root = File("/data/app")
        val packages = root.listFiles { f: File -> f.isDirectory } ?: return emptyList()
        val hits = ArrayList<String>(8)
        for (pkgDir in packages) {
            // pkg dir may nest one more level (base.apk layout); keep it bounded.
            val libHosts = listOf(pkgDir) +
                (pkgDir.listFiles { f: File -> f.isDirectory }?.take(2) ?: emptyList())
            for (host in libHosts) {
                val libDir = File(host, "lib")
                if (libDir.isDirectory) {
                    collectAbis(libDir, hits)
                } else {
                    val flat = File(host, NATIVE_LIB_NAME)
                    if (flat.isFile) hits += flat.absolutePath
                }
            }
            if (hits.size >= 8) break
        }
        return hits
    }

    private fun collectAbis(libDir: File, hits: MutableList<String>) {
        val abiDirs = libDir.listFiles { f: File -> f.isDirectory } ?: return
        for (abi in abiDirs) {
            val so = File(abi, NATIVE_LIB_NAME)
            if (so.isFile) hits += so.absolutePath
        }
        // old devices keep everything directly under lib/
        File(libDir, NATIVE_LIB_NAME).takeIf { it.isFile }?.let { hits += it.absolutePath }
    }

    /** Reset for tests/diagnostics — the real process never unloads a native lib. */
    internal fun resetForTests() {
        loaded = false
        loadedPath = null
        source = "none"
    }
}
