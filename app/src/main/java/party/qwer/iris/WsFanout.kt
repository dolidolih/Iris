package party.qwer.iris

import kotlinx.coroutines.channels.Channel
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicLong

/**
 * WsFanout — per-connection 브로드캐스트 허브 (ISSUE-15).
 *
 * SharedFlow + tryEmit 은 버퍼가 가득 찼을 때(느린 서브스크라이버 1 명) 모든 콜렉터의
 * 이벤트가 조용히 폐기된다. 또한 polling thread 에서 runBlocking{emit} 하면 막힌
 * WS send 하나에 DB 폴링 전체가 suspend 된다.
 *
 * 이 클래스는 각 연결마다 bounded 채널(기본 100)을 주고, publish 는 trySend 로
 * non-blocking 유지 — 느린 클라이언트는 **자기** 이벤트만 잃고(카운트됨), 나머지는
 * 무영향. 폐기 카운터(totalDrops)로 관측 가능하고, 일정량 이상 잃은 극단적 클라이언트는
 * 끊어버려(receive 종료 → WS close) 재접속하게 한다.
 */
class WsFanout(
    private val label: String,
    val capacity: Int = 100,
    val disconnectAfterDrops: Long = 2000
) {
    class Subscription internal constructor(val id: Long, cap: Int) {
        internal val channel = Channel<String>(cap)
        internal val drops = AtomicLong()
    }

    /** 누적 폐기 수 (구독자 × 이벤트 단위) — status/log 에서 확인용. */
    val totalDrops = AtomicLong()

    private val subs = CopyOnWriteArraySet<Subscription>()
    private val ids = AtomicLong()

    fun subscribe(): Subscription {
        val s = Subscription(ids.incrementAndGet(), capacity)
        subs.add(s)
        return s
    }

    /** idempotent 제거 — WS 핸들러 finally 에서 반드시 호출. */
    fun unsubscribe(sub: Subscription) {
        subs.remove(sub)
        runCatching { sub.channel.close() }
    }

    val subscriberCount: Int get() = subs.size

    /**
     * non-blocking 전체 전달. @return 이번 호출에서 버퍼 만충으로 폐기한 건수
     * (polling thread 예외 없이 바로 반환 — 절대 suspend 하지 않는다).
     */
    fun publish(message: String): Long {
        var failed = 0L
        for (sub in subs) {
            val result = sub.channel.trySend(message)
            if (!result.isSuccess) {
                failed++
                val d = sub.drops.incrementAndGet()
                if (d >= disconnectAfterDrops) {
                    println("WsFanout($label): sub#${sub.id} dropped $d events — cutting stuck client")
                    runCatching { sub.channel.close() }
                    subs.remove(sub)
                }
            }
        }
        if (failed > 0) {
            val t = totalDrops.addAndGet(failed)
            // 요란하게 — 폐기가 조용한 정상동작처럼 위장하지 않도록 rate-limited log
            if (t <= 20 || t % 100 == 0L) {
                println("WsFanout($label): $failed subscriber(s) full — dropped (total drops=$t)")
            }
        }
        return failed
    }
}
