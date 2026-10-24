package party.qwer.iris

import kotlinx.serialization.Serializable
import java.io.PrintStream

/**
 * RuntimeLog — 동작 로그를 최근 순서로 보관하는 버퍼.
 *
 * UI(서비스) 프로세스와 데몬(app_process) 프로세스가 각각 own 버퍼를 가지며,
 * 로그 탭은 UI 버퍼 + 데몬 HTTP 조회 결과를 합쳐 보여준다.
 */
object RuntimeLog {
    @Serializable
    data class Entry(
        val timeMs: Long,
        val level: String,
        val source: String,
        val message: String
    )

    private const val MAX_ENTRIES = 200
    private val lock = Any()
    private val entries = ArrayList<Entry>()
    /** ISSUE-39#5: captureStdout idempotency — 두 번 wrap 하면 모든 줄이 duplicated. */
    @Volatile private var stdoutCaptured = false

    fun info(source: String, message: String) = log("INFO", source, message)
    fun warn(source: String, message: String) = log("WARN", source, message)
    fun error(source: String, message: String) = log("ERROR", source, message)

    fun log(level: String, source: String, message: String) {
        synchronized(lock) {
            entries.add(Entry(System.currentTimeMillis(), level, source, message))
            while (entries.size > MAX_ENTRIES) entries.removeAt(0)
        }
    }

    /** 가장 최신이 앞에 오도록. [limit] 으로_recent 건만 잘라낼 수 있다(HTTP 전송용). */
    fun snapshot(limit: Int = Int.MAX_VALUE): List<Entry> = synchronized(lock) {
        val out = entries.asReversed()
        if (out.size <= limit) out.toList() else out.take(limit)
    }

    fun clear() = synchronized(lock) { entries.clear() }

    /** stdout/stderr 리다이렉터 — 데몬 로그 탭용. */
    private class LineTee(
        private val delegate: PrintStream,
        private val source: String,
        private val level: String
    ) : PrintStream(delegate, true) {
        private val buffer = StringBuilder()

        // ISSUE-39#5: write(byte[],int,int) 만 오버라이드하면 write(int) 경로
        // (System.out.write(byte) 등) 는 tee 를 우회해 버린다 — 바이트 단위도 함께.
        override fun write(b: Int) {
            write(byteArrayOf(b.toByte()), 0, 1)
        }

        override fun write(buf: ByteArray, off: Int, len: Int) {
            delegate.write(buf, off, len)
            val text = String(buf, off, len, Charsets.UTF_8)
            if (text.isEmpty()) return
            synchronized(buffer) {
                for (ch in text) {
                    if (ch == '\n') {
                        flushLine()
                    } else if (ch != '\r') {
                        buffer.append(ch)
                    }
                }
            }
        }

        override fun flush() {
            delegate.flush()
            synchronized(buffer) { if (buffer.isNotEmpty()) flushLine() }
        }

        private fun flushLine() {
            val line = buffer.toString()
            buffer.setLength(0)
            if (line.isNotBlank()) log(level, source, line.trim())
        }
    }

    /**
     * stdout/stderr를 리다이렉트해 println/System.err 로그를 버퍼에도 남긴다.
     * 데몬(app_process)에서 호출해 데몬 출력 전체를 로그 탭에 노출한다.
     */
    fun captureStdout(source: String) {
        // ISSUE-39#5: 재호출 시 Tee 를 Tee 로 감싸 로그 전 줄이 복붙되는 사고 방지.
        synchronized(lock) {
            if (stdoutCaptured) return
            stdoutCaptured = true
        }
        System.setOut(LineTee(System.out, source, "INFO"))
        System.setErr(LineTee(System.err, source, "ERROR"))
    }
}
