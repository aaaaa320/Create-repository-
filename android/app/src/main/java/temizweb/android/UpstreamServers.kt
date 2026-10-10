package temizweb.android

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import temizweb.android.dns.IpPacket
import temizweb.android.dns.UpstreamDns
import java.net.InetAddress

/**
 * Üst (gerçek) DNS sunucularını bulur.
 *
 * Sıra:
 *   1. Kullanıcının ayarlara yazdığı IP adresleri
 *   2. Cihazın bağlı olduğu ağın kendi DNS sunucuları (VPN ağları atlanır)
 *   3. [UpstreamDns.FALLBACK_SERVERS]
 *
 * Ağın kendi sunucularını kullanmak bilerek tercih edilir: TemizWeb, kullanıcı
 * aksi istemedikçe hiçbir üçüncü tarafa sorgu göndermez. Sonuçlar kısa süre
 * önbelleğe alınır; Wi-Fi → hücresel geçişinde liste kendiliğinden tazelenir.
 */
class UpstreamServers(context: Context, private val prefs: FilterPrefs) {

    private val appContext = context.applicationContext

    @Volatile
    private var cached: List<InetAddress> = emptyList()

    @Volatile
    private var cachedAt: Long = 0L

    /** Geçerli üst sunucu listesi (boş dönmez; en kötü durumda yedekler). */
    fun servers(): List<InetAddress> {
        val manual = UpstreamDns.parseServers(prefs.upstreamServers)
        if (manual.isNotEmpty()) return manual

        val now = SystemClock.elapsedRealtime()
        val snapshot = cached
        if (snapshot.isNotEmpty() && now - cachedAt < CACHE_MILLIS) return snapshot

        val detected = detectFromNetwork()
        val resolved = if (detected.isNotEmpty()) {
            detected
        } else {
            UpstreamDns.parseServers(UpstreamDns.FALLBACK_SERVERS)
        }

        cached = resolved
        cachedAt = now
        return resolved
    }

    /** Elle sunucu yazıldığında ya da ağ değiştiğinde önbelleği düşürür. */
    fun invalidate() {
        cached = emptyList()
        cachedAt = 0L
    }

    private fun detectFromNetwork(): List<InetAddress> {
        val manager = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return emptyList()

        // API 35'te kullanımı önerilmiyor; tüm sürümlerde çalışan tek yol bu olduğu
        // için uyarı bilinçli olarak susturulur.
        @Suppress("DEPRECATION")
        val networks = try {
            manager.allNetworks
        } catch (error: RuntimeException) {
            return emptyList()
        }

        for (network in networks) {
            val capabilities = manager.getNetworkCapabilities(network) ?: continue
            if (isVpnNetwork(capabilities)) continue
            if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue

            val linkProperties = manager.getLinkProperties(network) ?: continue
            val servers = linkProperties.dnsServers
                .filterNotNull()
                .filter { !it.isLoopbackAddress && !isTunnelAddress(it) }
            if (servers.isNotEmpty()) return servers.take(UpstreamDns.MAX_SERVERS)
        }
        return emptyList()
    }

    /**
     * VPN ağları `NET_CAPABILITY_NOT_VPN` yeteneğini taşımaz; kendi tünelimizi
     * bu eksiklikten tanırız (üst sunucu olarak kendimizi seçmeyiz).
     */
    private fun isVpnNetwork(capabilities: NetworkCapabilities): Boolean =
        !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)

    private fun isTunnelAddress(address: InetAddress): Boolean =
        address.hostAddress == IpPacket.TUNNEL_IPV4_ADDRESS ||
            address.hostAddress == IpPacket.TUNNEL_IPV6_ADDRESS

    companion object {
        const val CACHE_MILLIS = 10_000L
    }
}
