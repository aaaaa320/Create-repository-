package temizweb.android

import temizweb.android.dns.BlockList
import temizweb.android.dns.DnsTunnel
import java.util.concurrent.atomic.AtomicLong

/**
 * Tünel çalıştığı sürece tutulan sayaçlar.
 *
 * Gizlilik ilkesi uzantıyla aynıdır: hangi alan adının sorulduğu ya da
 * engellendiği hiçbir yerde saklanmaz, yalnızca toplam sayılar tutulur.
 */
class FilterStats : DnsTunnel.TunnelStats {

    val startedAt: Long = System.currentTimeMillis()

    private val queries = AtomicLong()
    private val blockedAds = AtomicLong()
    private val blockedTracking = AtomicLong()
    private val forwarded = AtomicLong()
    private val cacheHits = AtomicLong()
    private val upstreamFailures = AtomicLong()
    private val droppedPackets = AtomicLong()
    private val malformedQueries = AtomicLong()

    override fun recordQuery() {
        queries.incrementAndGet()
    }

    override fun recordBlocked(list: BlockList) {
        when (list) {
            BlockList.ADS -> blockedAds.incrementAndGet()
            BlockList.TRACKING -> blockedTracking.incrementAndGet()
        }
    }

    override fun recordForwarded() {
        forwarded.incrementAndGet()
    }

    override fun recordCacheHit() {
        cacheHits.incrementAndGet()
    }

    override fun recordUpstreamFailure() {
        upstreamFailures.incrementAndGet()
    }

    override fun recordDroppedPacket() {
        droppedPackets.incrementAndGet()
    }

    override fun recordMalformedQuery() {
        malformedQueries.incrementAndGet()
    }

    /** Arayüzde gösterilen anlık görüntü. */
    data class Snapshot(
        val queries: Long,
        val blockedAds: Long,
        val blockedTracking: Long,
        val forwarded: Long,
        val cacheHits: Long,
        val upstreamFailures: Long,
        val droppedPackets: Long,
        val malformedQueries: Long,
        val uptimeMillis: Long
    ) {
        val blockedTotal: Long get() = blockedAds + blockedTracking
    }

    fun snapshot(): Snapshot = Snapshot(
        queries = queries.get(),
        blockedAds = blockedAds.get(),
        blockedTracking = blockedTracking.get(),
        forwarded = forwarded.get(),
        cacheHits = cacheHits.get(),
        upstreamFailures = upstreamFailures.get(),
        droppedPackets = droppedPackets.get(),
        malformedQueries = malformedQueries.get(),
        uptimeMillis = System.currentTimeMillis() - startedAt
    )
}
