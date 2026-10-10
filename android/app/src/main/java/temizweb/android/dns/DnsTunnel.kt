package temizweb.android.dns

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

/** Tünelin o anki filtre tercihleri (her paket için güncel değer okunur). */
data class TunnelSettings(
    val adsEnabled: Boolean = true,
    val trackingEnabled: Boolean = false
)

/**
 * TUN arayüzünden gelen IP paketlerini okuyan DNS döngüsü.
 *
 * Akış:
 *   1. IP + UDP başlığı çözülür; 53 numaralı porta gitmeyen paketler düşürülür.
 *   2. DNS sorgusu çözülür ve alan adı [DomainFilter] ile değerlendirilir.
 *   3. Engellenen adlara NXDOMAIN döner; diğerleri önbellekten ya da üst
 *      sunucudan karşılanır.
 *
 * Giriş/çıkış akışları dışarıdan verildiği için döngü Android olmadan da
 * çalıştırılabilir ve birim testlerde doğrulanabilir.
 */
class DnsTunnel(
    private val input: InputStream,
    private val output: OutputStream,
    private val filter: DomainFilter,
    private val cache: DnsCache,
    private val upstream: UpstreamDns,
    private val stats: TunnelStats,
    private val executor: Executor,
    private val settings: () -> TunnelSettings,
    private val mtu: Int = DEFAULT_MTU,
    /**
     * `true` ise arayüz bloklu modda kurulmuştur: `read()` veri gelene kadar
     * bekler ve `-1` yalnızca tünel kapandığında döner.
     */
    private val blocking: Boolean = true
) : Runnable {

    /** Sayaçların tünelden bağımsız arayüzü (testlerde taklit edilebilir). */
    interface TunnelStats {
        fun recordQuery()
        fun recordBlocked(list: BlockList)
        fun recordForwarded()
        fun recordCacheHit()
        fun recordUpstreamFailure()
        fun recordDroppedPacket()
        fun recordMalformedQuery()
    }

    @Volatile
    private var running = true

    override fun run() {
        val buffer = ByteArray(MAX_PACKET_LENGTH)

        while (running) {
            val length = try {
                input.read(buffer)
            } catch (error: IOException) {
                if (!running) break
                stats.recordDroppedPacket()
                sleepQuietly(IDLE_SLEEP_MS)
                continue
            }

            if (length > 0) {
                // Paket iş parçacığı havuzuna devredilebildiği için kopyalanır.
                handlePacket(buffer.copyOf(length), length)
                continue
            }

            if (length < 0 && blocking) break          // tünel kapandı
            sleepQuietly(IDLE_SLEEP_MS)                // bloklu olmayan arayüzde veri yok
        }
    }

    /** Döngüyü durdurur; akışları kapatmak çağıranın sorumluluğundadır. */
    fun stop() {
        running = false
    }

    val isRunning: Boolean get() = running

    private fun handlePacket(packet: ByteArray, length: Int) {
        val datagram = IpPacket.parse(packet, length)
        if (datagram == null || !datagram.isDns) {
            stats.recordDroppedPacket()
            return
        }

        val payload = datagram.copyPayload(packet)
        val query = DnsMessage.parseQuery(payload, payload.size)
        if (query == null) {
            stats.recordMalformedQuery()
            return
        }

        stats.recordQuery()
        val current = settings()
        when (val decision = filter.decide(query.name, current.adsEnabled, current.trackingEnabled)) {
            is FilterDecision.Blocked -> {
                stats.recordBlocked(decision.list)
                respond(
                    datagram,
                    DnsMessage.buildErrorResponse(query, payload, DnsMessage.RCODE_NAME_ERROR)
                )
            }

            FilterDecision.Allowed -> resolve(datagram, payload, query)
        }
    }

    private fun resolve(
        datagram: IpPacket.Datagram,
        payload: ByteArray,
        query: DnsMessage.Query
    ) {
        val cached = cache.get(query.name, query.type, query.id)
        if (cached != null) {
            stats.recordCacheHit()
            respond(datagram, cached)
            return
        }

        try {
            executor.execute { resolveUpstream(datagram, payload, query) }
        } catch (rejected: RejectedExecutionException) {
            respond(
                datagram,
                DnsMessage.buildErrorResponse(query, payload, DnsMessage.RCODE_SERVER_FAILURE)
            )
        }
    }

    private fun resolveUpstream(
        datagram: IpPacket.Datagram,
        payload: ByteArray,
        query: DnsMessage.Query
    ) {
        val upstreamQuery = DnsMessage.buildUpstreamQuery(query, payload, payload.size)
        val answer = upstream.query(upstreamQuery)

        if (answer == null) {
            stats.recordUpstreamFailure()
            respond(
                datagram,
                DnsMessage.buildErrorResponse(query, payload, DnsMessage.RCODE_SERVER_FAILURE)
            )
            return
        }

        cache.put(query.name, query.type, answer, answer.size)
        stats.recordForwarded()
        respond(datagram, DnsMessage.withId(answer, answer.size, query.id))
    }

    /**
     * DNS yanıtını IP paketine sarıp tünele yazar.
     * Yanıt MTU'ya sığmıyorsa önce bölümleri kırpılır.
     */
    private fun respond(datagram: IpPacket.Datagram, dnsPayload: ByteArray) {
        val sourceAddress = if (datagram.version == 6) {
            IpPacket.TUNNEL_IPV6_BYTES
        } else {
            IpPacket.TUNNEL_IPV4_BYTES
        }

        val maxPayload = datagram.maxDnsPayload(mtu)
        val payload = if (dnsPayload.size > maxPayload) {
            DnsMessage.shrinkResponse(dnsPayload, dnsPayload.size, maxPayload)
        } else {
            dnsPayload
        }

        val packet = IpPacket.buildResponse(datagram, payload, sourceAddress)
        if (packet == null) {
            stats.recordDroppedPacket()
            return
        }
        write(packet)
    }

    private fun write(packet: ByteArray) {
        try {
            synchronized(output) {
                output.write(packet)
                output.flush()
            }
        } catch (error: IOException) {
            if (running) stats.recordDroppedPacket()
        }
    }

    private fun sleepQuietly(millis: Long) {
        try {
            Thread.sleep(millis)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            running = false
        }
    }

    companion object {
        const val MAX_PACKET_LENGTH = IpPacket.MAX_PACKET_LENGTH
        const val DEFAULT_MTU = 1_500
        const val IDLE_SLEEP_MS = 5L
    }
}
