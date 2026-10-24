package party.qwer.iris

import java.io.File

/**
 * WorkDirs — 데몬 임시 파일(평문으로 복호화된 DB 사본, 네이티브 라이브러리 추출물)을
 * 담는 비공개 루트.
 *
 * app_process(root) 데몬은 앱 Context가 없어서 `filesDir`를 쓸 수 없다. `/data/local/tmp`
 * 는 비특권 앱도 읽을 수 있는 ROM 이 많아서 거기에 평문 복호화 사본을 두면 유출이 된다 —
 * 그래서 owner-only(0700)로 만든 우리 디렉터리를 쓴다. 위치는 `IRIS_WORK_DIR` 로 바꿀 수 있다.
 */
object WorkDirs {
    private const val DEFAULT_ROOT = "/data/local/tmp/Iris.work"

    /** root 자체는 만들지 않는다 — 존재 확인만 하는 호출자(NativeLib 후보 로그 등)용. */
    val root: File = File(System.getenv("IRIS_WORK_DIR")?.takeIf { it.isNotBlank() } ?: DEFAULT_ROOT)

    /**
     * owner-only(0700) 디렉터리를 만들어 반환한다. 만들 수 없거나 여전히 group/world 에
     * 열려 있으면 null — 호출자는 조용히 기능을 끄지, 평문 데이터를 world-readable 에 쓰지 않는다.
     */
    fun sub(name: String): File? {
        val dir = if (name.isEmpty()) root else File(root, name)
        if (!dir.exists() && !dir.mkdirs()) return null
        if (!dir.isDirectory) return null
        runCatching {
            dir.setReadable(false, false)
            dir.setWritable(false, false)
            dir.setExecutable(false, false)
            dir.setReadable(true, true)
            dir.setWritable(true, true)
            dir.setExecutable(true, true)
        }
        val mode = runCatching { android.system.Os.stat(dir.absolutePath).st_mode }.getOrDefault(-1)
        if (mode >= 0 && (mode and 0x3f) != 0) {
            System.err.println(
                "WorkDirs: refusing to use $dir — group/world-accessible " +
                    "(mode ${Integer.toString(mode and 0x1ff, 8)}); owner-only required"
            )
            return null
        }
        return dir
    }
}
