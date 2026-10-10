package temizweb.android.dns

import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * Sorguları üst (gerçek) DNS sunucularına iletir.
 *
 * Soketler VPN'den "korunur" ([SocketProtector]): böylece üst sunucuya giden
 * paketler tünele geri düşmez, doğrudan gerçek ağa çıkar. Sunucu listesi her
 * sorguda [serversProvider]'dan alınır; Wi-Fi'den hücresel ağa geçişte ağın
 * kendi DNS sunucuları kendiliğinden kullanılmaya başlar.
 */
class UpstreamDns(
    private val protector: SocketProtector,
    private val serversProvider: () -> List<InetAddress>,
    private val timeoutMs: Int = DEFAULT_TIMEOUT_MS
) {

    /** Soketi VPN'in dışına alan işlem; `VpnService.protect` ile eşlenir. */
    fun interface SocketProtector {
        fun protect(socket: DatagramSocket): Boolean
    }

    /** Tanılama için geçerli sunucu listesi. */
    val servers: List<InetAddress> get() = serversProvider()

    /**
     * Sorguyu sırayla sunuculara gönderir; ilk geçerli yanıtı döndürür.
     * Hiçbir sunucu yanıt vermezse `null` (çağıran SERVFAIL üretir).
     */
    fun query(payload: ByteArray): ByteArray? {
        for (server in serversProvider()) {
            val response = queryServer(server, payload)
            if (response != null) return response
        }
        return null
    }

    private fun queryServer(server: InetAddress, payload: ByteArray): ByteArray? {
        var socket: DatagramSocket? = null
        return try {
            // Koruma bağlanmadan önce gelmeli: protect() yalnızca bağlanmamış soketlerde çalışır.
            socket = DatagramSocket(null)
            protector.protect(socket)
            socket.bind(null)
            socket.soTimeout = timeoutMs

            socket.send(
                DatagramPacket(payload, payload.size, InetSocketAddress(server, IpPacket.DNS_PORT))
            )

            val buffer = ByteArray(MAX_RESPONSE_SIZE)
            val packet = DatagramPacket(buffer, buffer.size)
            socket.receive(packet)
            if (packet.length < DnsMessage.HEADER_SIZE) null else buffer.copyOf(packet.length)
        } catch (error: IOException) {
            null
        } catch (error: RuntimeException) {
            null
        } finally {
            socket?.close()
        }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 5_000
        const val MAX_RESPONSE_SIZE = 4_096
        const val MAX_SERVERS = 4

        /** Bağlı ağın DNS sunucusu bulunamazsa kullanılacak yedekler. */
        val FALLBACK_SERVERS = listOf("1.1.1.1", "9.9.9.9", "8.8.8.8")

        /** Metin olarak yazılmış IP adreslerini çözümler; alan adları yok sayılır. */
        fun parseServers(values: Iterable<String>): List<InetAddress> {
            val parsed = mutableListOf<InetAddress>()
            for (value in values) {
                val candidate = value.trim()
                if (!isNumericAddress(candidate)) continue
                val address = try {
                    InetAddress.getByName(candidate)
                } catch (error: IOException) {
                    null
                }
                if (address != null) parsed.add(address)
                if (parsed.size >= MAX_SERVERS) break
            }
            return parsed
        }

        /** Yalnızca sayısal IP yazımlarını kabul eder (gizli DNS çözümlemesi yapmayız). */
        fun isNumericAddress(candidate: String): Boolean {
            if (candidate.isEmpty()) return false
            if (candidate.contains(':')) return true
            val parts = candidate.split('.')
            if (parts.size != 4) return false
            return parts.all { it.toIntOrNull() in 0..255 }
        }
    }
}
