package temizweb.android.dns

import java.io.ByteArrayOutputStream
import java.net.Inet6Address
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tünelden geçen IP + UDP paketlerinin çözümlenmesi ve kurulması.
 *
 * Saf Kotlin'dir: `android.*` API'lerine bağımlılığı yoktur ve birim testlerle
 * doğrulanır. Yalnızca UDP ele alınır; TCP paketleri (53/TCP dahil) düşürülür
 * çünkü TUN üzerinde bir TCP yığını kurmak bu projenin kapsamı dışındadır.
 */
object IpPacket {

    const val DNS_PORT = 53
    const val PROTOCOL_UDP = 17
    const val IPV4_HEADER_LENGTH = 20
    const val IPV6_HEADER_LENGTH = 40
    const val UDP_HEADER_LENGTH = 8
    const val MAX_PACKET_LENGTH = 32_767
    const val DEFAULT_TTL = 64

    /** Tünel arayüzünün kendi adresleri: sistem DNS istemcisi sorguları buraya gönderir. */
    const val TUNNEL_IPV4_ADDRESS = "10.211.211.211"
    const val TUNNEL_IPV6_ADDRESS = "fd00:ad00::53"

    /** Tünel adresleri (bayt olarak); arayüz kurulurken ve yanıt üretilirken kullanılır. */
    val TUNNEL_IPV4_BYTES: ByteArray = parseIpv4(TUNNEL_IPV4_ADDRESS)
    val TUNNEL_IPV6_BYTES: ByteArray = parseIpv6(TUNNEL_IPV6_ADDRESS)

    private val packetId = AtomicInteger(1)

    /** Çözümlenmiş bir UDP paketinin IP/UDP başlığı bilgileri. */
    class Datagram(
        val version: Int,
        val sourceAddress: ByteArray,
        val destinationAddress: ByteArray,
        val sourcePort: Int,
        val destinationPort: Int,
        val payloadOffset: Int,
        val payloadLength: Int
    ) {
        val isDns: Boolean get() = destinationPort == DNS_PORT
        val headerLength: Int get() = if (version == 6) IPV6_HEADER_LENGTH else IPV4_HEADER_LENGTH

        /** DNS yükünün kopyası (iş parçacıkları arasında güvenle taşınabilir). */
        fun copyPayload(packet: ByteArray): ByteArray =
            packet.copyOfRange(payloadOffset, payloadOffset + payloadLength)

        /** Bu paket için yazılabilecek en büyük DNS yükü (MTU'ya göre). */
        fun maxDnsPayload(mtu: Int): Int =
            (mtu - headerLength - UDP_HEADER_LENGTH).coerceAtLeast(0)

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Datagram) return false
            return version == other.version &&
                sourcePort == other.sourcePort &&
                destinationPort == other.destinationPort &&
                payloadOffset == other.payloadOffset &&
                payloadLength == other.payloadLength &&
                sourceAddress.contentEquals(other.sourceAddress) &&
                destinationAddress.contentEquals(other.destinationAddress)
        }

        override fun hashCode(): Int {
            var result = version
            result = 31 * result + sourceAddress.contentHashCode()
            result = 31 * result + destinationAddress.contentHashCode()
            result = 31 * result + sourcePort
            result = 31 * result + destinationPort
            result = 31 * result + payloadOffset
            result = 31 * result + payloadLength
            return result
        }
    }

    // ---------------------------------------------------------------- çözümleme

    /**
     * Ham IP paketini çözümler. IPv4/IPv6 olmayan, UDP taşımayan veya bozuk
     * paketler için `null` döner (tünel bunları sessizce düşürür).
     */
    fun parse(packet: ByteArray, length: Int): Datagram? {
        if (length < 1 || length > packet.size) return null

        return when ((packet[0].toInt() and 0xF0) ushr 4) {
            4 -> parseIpv4Packet(packet, length)
            6 -> parseIpv6Packet(packet, length)
            else -> null
        }
    }

    private fun parseIpv4Packet(packet: ByteArray, length: Int): Datagram? {
        val headerLength = (packet[0].toInt() and 0x0F) * 4
        if (headerLength < IPV4_HEADER_LENGTH) return null
        if (length < headerLength + UDP_HEADER_LENGTH) return null
        if ((packet[9].toInt() and 0xFF) != PROTOCOL_UDP) return null

        val sourceAddress = packet.copyOfRange(12, 16)
        val destinationAddress = packet.copyOfRange(16, 20)

        return readUdp(packet, length, headerLength, 4, sourceAddress, destinationAddress)
    }

    private fun parseIpv6Packet(packet: ByteArray, length: Int): Datagram? {
        if (length < IPV6_HEADER_LENGTH + UDP_HEADER_LENGTH) return null
        // Uzantı başlıkları (hop-by-hop, fragment, ...) desteklenmez.
        if ((packet[6].toInt() and 0xFF) != PROTOCOL_UDP) return null

        val sourceAddress = packet.copyOfRange(8, 24)
        val destinationAddress = packet.copyOfRange(24, IPV6_HEADER_LENGTH)
        return readUdp(packet, length, IPV6_HEADER_LENGTH, 16, sourceAddress, destinationAddress)
    }

    private fun readUdp(
        packet: ByteArray,
        length: Int,
        udpOffset: Int,
        addressLength: Int,
        sourceAddress: ByteArray,
        destinationAddress: ByteArray
    ): Datagram? {
        if (udpOffset + UDP_HEADER_LENGTH > length) return null

        val sourcePort = u16(packet, udpOffset)
        val destinationPort = u16(packet, udpOffset + 2)
        val declaredLength = u16(packet, udpOffset + 4)
        val available = length - udpOffset - UDP_HEADER_LENGTH
        if (available <= 0) return null

        // Bildirilen uzunluk tutarsızsa elde olan baytlara güven (TUN dolgusu olabilir).
        val payloadLength = if (declaredLength in UDP_HEADER_LENGTH..(available + UDP_HEADER_LENGTH)) {
            declaredLength - UDP_HEADER_LENGTH
        } else {
            available
        }
        if (payloadLength <= 0) return null

        return Datagram(
            version = if (addressLength == 4) 4 else 6,
            sourceAddress = sourceAddress,
            destinationAddress = destinationAddress,
            sourcePort = sourcePort,
            destinationPort = destinationPort,
            payloadOffset = udpOffset + UDP_HEADER_LENGTH,
            payloadLength = payloadLength
        )
    }

    // ---------------------------------------------------------------- üretim

    /**
     * Gelen sorguya karşılık gelen tam IP paketini kurar: adresler ve portlar
     * yer değiştirir, sağlama toplamları yeniden hesaplanır.
     *
     * @param request     tünelden okunan sorgu paketi
     * @param payload     gönderilecek DNS mesajı
     * @param sourceAddress yanıtın kaynak adresi (tünelin kendi adresi)
     * @return MTU'ya sığmayan veya geçersiz paketler için `null`
     */
    fun buildResponse(
        request: Datagram,
        payload: ByteArray,
        sourceAddress: ByteArray,
        ttl: Int = DEFAULT_TTL
    ): ByteArray? {
        if (payload.isEmpty()) return null
        if (sourceAddress.size != request.sourceAddress.size) return null

        val udpLength = UDP_HEADER_LENGTH + payload.size
        if (udpLength > 0xFFFF) return null

        val segment = ByteArray(udpLength)
        putU16(segment, 0, DNS_PORT)               // kaynak port: 53
        putU16(segment, 2, request.sourcePort)     // istemcinin portu
        putU16(segment, 4, udpLength)
        putU16(segment, 6, 0)                      // sağlama toplamı aşağıda doldurulur
        System.arraycopy(payload, 0, segment, UDP_HEADER_LENGTH, payload.size)

        val ipv6 = request.version == 6
        val checksum = udpChecksum(sourceAddress, request.sourceAddress, segment, ipv6)
        // 0 değeri "sağlama toplamı yok" anlamına gelir; IPv6'da yasaktır, bu yüzden 0xFFFF.
        putU16(segment, 6, if (checksum == 0) 0xFFFF else checksum)

        return if (ipv6) {
            buildIpv6Packet(sourceAddress, request.sourceAddress, segment, ttl)
        } else {
            buildIpv4Packet(sourceAddress, request.sourceAddress, segment, ttl)
        }
    }

    private fun buildIpv4Packet(
        sourceAddress: ByteArray,
        destinationAddress: ByteArray,
        segment: ByteArray,
        ttl: Int
    ): ByteArray? {
        val totalLength = IPV4_HEADER_LENGTH + segment.size
        if (totalLength > 0xFFFF) return null

        val out = ByteArray(totalLength)
        out[0] = 0x45                                  // sürüm 4, IHL 5
        out[1] = 0                                     // hizmet türü
        putU16(out, 2, totalLength)
        putU16(out, 4, packetId.getAndIncrement() and 0xFFFF)
        putU16(out, 6, 0x4000)                         // DF: parçalama yok
        out[8] = (ttl and 0xFF).toByte()
        out[9] = PROTOCOL_UDP.toByte()
        putU16(out, 10, 0)                             // sağlama toplamı aşağıda
        System.arraycopy(sourceAddress, 0, out, 12, 4)
        System.arraycopy(destinationAddress, 0, out, 16, 4)
        putU16(out, 10, ipv4HeaderChecksum(out))
        System.arraycopy(segment, 0, out, IPV4_HEADER_LENGTH, segment.size)
        return out
    }

    private fun buildIpv6Packet(
        sourceAddress: ByteArray,
        destinationAddress: ByteArray,
        segment: ByteArray,
        ttl: Int
    ): ByteArray? {
        val totalLength = IPV6_HEADER_LENGTH + segment.size
        val out = ByteArray(totalLength)

        out[0] = 0x60                                  // sürüm 6, trafik sınıfı 0
        // out[1..3] akış etiketi: 0
        putU16(out, 4, segment.size)                   // yük uzunluğu
        out[6] = PROTOCOL_UDP.toByte()                 // sonraki başlık
        out[7] = (ttl and 0xFF).toByte()               // atlama sınırı
        System.arraycopy(sourceAddress, 0, out, 8, 16)
        System.arraycopy(destinationAddress, 0, out, 24, 16)
        System.arraycopy(segment, 0, out, IPV6_HEADER_LENGTH, segment.size)
        return out
    }

    // ---------------------------------------------------------------- sağlama toplamları

    /** IPv4 başlığı sağlama toplamı (başlıktaki alan sıfır sayılır). */
    fun ipv4HeaderChecksum(header: ByteArray): Int {
        if (header.size < IPV4_HEADER_LENGTH) return 0
        val sum = onesComplementSum(header, 0, IPV4_HEADER_LENGTH, skipOffset = 10)
        return sum.inv() and 0xFFFF
    }

    /**
     * UDP sağlama toplamı (RFC 768 / RFC 2460 sözde başlığı ile).
     * @param ipv6 `true` ise 40 baytlık IPv6 sözde başlığı kullanılır
     */
    fun udpChecksum(
        sourceAddress: ByteArray,
        destinationAddress: ByteArray,
        segment: ByteArray,
        ipv6: Boolean
    ): Int {
        val pseudo = ByteArrayOutputStream()
        pseudo.write(sourceAddress, 0, sourceAddress.size)
        pseudo.write(destinationAddress, 0, destinationAddress.size)
        if (ipv6) {
            // IPv6 sözde başlığı: kaynak (16) + hedef (16) + yük uzunluğu (4)
            // + üç sıfır bayt + sonraki başlık (1).
            putU16(pseudo, 0)                          // yük uzunluğunun yüksek 2 baytı
            putU16(pseudo, segment.size)               // yük uzunluğunun düşük 2 baytı
            putU16(pseudo, 0)                          // iki sıfır bayt
            putU16(pseudo, PROTOCOL_UDP)               // üçüncü sıfır bayt + protokol
        } else {
            // IPv4 sözde başlığı: sıfır bayt + protokol (2), ardından UDP uzunluğu (2).
            putU16(pseudo, PROTOCOL_UDP)
            putU16(pseudo, segment.size)
        }
        pseudo.write(segment, 0, segment.size)

        val bytes = pseudo.toByteArray()
        return onesComplementSum(bytes, 0, bytes.size).inv() and 0xFFFF
    }

    /** 16 bitlik bir tümleyeni toplamı; tek sayıda bayt yüksek bayt olarak sayılır. */
    private fun onesComplementSum(
        data: ByteArray,
        offset: Int,
        length: Int,
        skipOffset: Int = -1
    ): Int {
        var sum = 0L
        var index = offset
        val end = offset + length

        while (index + 1 < end) {
            if (index != skipOffset) {
                sum += ((data[index].toInt() and 0xFF) shl 8) or (data[index + 1].toInt() and 0xFF)
            }
            index += 2
        }
        if (index < end) {
            sum += (data[index].toInt() and 0xFF) shl 8
        }

        while (sum shr 16 != 0L) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        return sum.toInt() and 0xFFFF
    }

    // ---------------------------------------------------------------- adres yardımcıları

    /** "a.b.c.d" biçimindeki IPv4 adresini 4 bayta çevirir. */
    fun parseIpv4(address: String): ByteArray {
        val parts = address.split('.')
        require(parts.size == 4) { "Geçersiz IPv4 adresi: $address" }
        val bytes = ByteArray(4)
        for (index in 0 until 4) {
            val value = parts[index].toIntOrNull()
            require(value != null && value in 0..255) { "Geçersiz IPv4 adresi: $address" }
            bytes[index] = value.toByte()
        }
        return bytes
    }

    /** IPv6 metin adresini 16 bayta çevirir (yalnızca sayısal adresler). */
    fun parseIpv6(address: String): ByteArray {
        require(address.contains(':')) { "Geçersiz IPv6 adresi: $address" }
        val parsed = InetAddress.getByName(address)
        require(parsed is Inet6Address) { "Geçersiz IPv6 adresi: $address" }
        return parsed.address
    }

    /** Bayt dizisini noktalı IPv4 metnine çevirir (günlük ve hata mesajları için). */
    fun formatIpv4(bytes: ByteArray): String =
        bytes.joinToString(".") { (it.toInt() and 0xFF).toString() }

    private fun u16(packet: ByteArray, offset: Int): Int =
        ((packet[offset].toInt() and 0xFF) shl 8) or (packet[offset + 1].toInt() and 0xFF)

    private fun putU16(packet: ByteArray, offset: Int, value: Int) {
        packet[offset] = (value ushr 8 and 0xFF).toByte()
        packet[offset + 1] = (value and 0xFF).toByte()
    }

    private fun putU16(out: ByteArrayOutputStream, value: Int) {
        out.write(value ushr 8 and 0xFF)
        out.write(value and 0xFF)
    }
}
