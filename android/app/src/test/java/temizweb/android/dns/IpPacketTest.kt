package temizweb.android.dns

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class IpPacketTest {

    @Test
    fun `ipv4 udp paketi cozumlenir`() {
        val payload = "merhaba-dns".toByteArray(Charsets.US_ASCII)
        val packet = ipv4Udp(payload)

        val datagram = IpPacket.parse(packet, packet.size)!!

        assertEquals(4, datagram.version)
        assertEquals(5353, datagram.sourcePort)
        assertEquals(53, datagram.destinationPort)
        assertTrue(datagram.isDns)
        assertEquals("192.168.1.10", IpPacket.formatIpv4(datagram.sourceAddress))
        assertEquals(IpPacket.TUNNEL_IPV4_ADDRESS, IpPacket.formatIpv4(datagram.destinationAddress))
        assertArrayEquals(payload, datagram.copyPayload(packet))
    }

    @Test
    fun `dns olmayan paketler dusurulur`() {
        val payload = byteArrayOf(1)
        val packet = ipv4Udp(payload, dstPort = 443)
        val datagram = IpPacket.parse(packet, packet.size)!!
        assertTrue(!datagram.isDns)

        // TCP paketi (proto 6) çözülmez.
        val tcp = packet.copyOf()
        tcp[9] = 6
        assertNull(IpPacket.parse(tcp, tcp.size))
    }

    @Test
    fun `ipv4 yaniti adresleri tersine cevirir ve saglama toplamlari dogrudur`() {
        val packet = ipv4Udp(byteArrayOf(1, 2, 3, 4))
        val request = IpPacket.parse(packet, packet.size)!!

        val response = IpPacket.buildResponse(request, byteArrayOf(9, 9), IpPacket.TUNNEL_IPV4_BYTES)!!
        val parsed = IpPacket.parse(response, response.size)!!

        assertEquals(53, parsed.sourcePort)
        assertEquals(5353, parsed.destinationPort)
        assertEquals(IpPacket.TUNNEL_IPV4_ADDRESS, IpPacket.formatIpv4(parsed.sourceAddress))
        assertEquals("192.168.1.10", IpPacket.formatIpv4(parsed.destinationAddress))
        assertArrayEquals(byteArrayOf(9, 9), parsed.copyPayload(response))

        assertTrue("IPv4 başlık sağlama toplamı tutarsız", headerChecksumValid(response))
        assertTrue("IPv4 UDP sağlama toplamı tutarsız", udpChecksumValid(response, ipv6 = false))
    }

    @Test
    fun `ipv6 yaniti zorunlu udp saglama toplamini tasir`() {
        val payload = byteArrayOf(5, 6)
        val packet = ipv6Udp(payload)
        val request = IpPacket.parse(packet, packet.size)!!

        assertEquals(6, request.version)

        val response = IpPacket.buildResponse(request, payload, IpPacket.TUNNEL_IPV6_BYTES)!!
        val parsed = IpPacket.parse(response, response.size)!!

        assertEquals(6, parsed.version)
        assertEquals(53, parsed.sourcePort)
        assertArrayEquals(payload, parsed.copyPayload(response))
        assertTrue("IPv6 UDP sağlama toplamı tutarsız", udpChecksumValid(response, ipv6 = true))

        // IPv6'da sıfır sağlama toplamı "yok" anlamına gelir ve yasaktır.
        val udpChecksum = u16(response, 46)
        assertTrue(udpChecksum != 0)
    }

    @Test
    fun `adres metinleri cozumlenir`() {
        assertArrayEquals(byteArrayOf(10, -45, -45, -45), IpPacket.parseIpv4("10.211.211.211"))
        assertEquals(16, IpPacket.parseIpv6("fd00:ad00::53").size)
    }

    @Test
    fun `mtu siniri dns yukunu kisitlar`() {
        val packet = ipv4Udp(byteArrayOf(1))
        val datagram = IpPacket.parse(packet, packet.size)!!
        assertEquals(1500 - 20 - 8, datagram.maxDnsPayload(1500))
    }

    // ---------------------------------------------------------------- yardımcılar

    private fun ipv4Udp(
        payload: ByteArray,
        srcIp: String = "192.168.1.10",
        dstIp: String = IpPacket.TUNNEL_IPV4_ADDRESS,
        srcPort: Int = 5353,
        dstPort: Int = 53
    ): ByteArray {
        val total = 20 + 8 + payload.size
        val out = ByteArray(total)
        out[0] = 0x45
        putU16(out, 2, total)
        putU16(out, 6, 0x4000)
        out[8] = 64
        out[9] = IpPacket.PROTOCOL_UDP.toByte()
        IpPacket.parseIpv4(srcIp).copyInto(out, 12)
        IpPacket.parseIpv4(dstIp).copyInto(out, 16)
        putU16(out, 20, srcPort)
        putU16(out, 22, dstPort)
        putU16(out, 24, 8 + payload.size)
        payload.copyInto(out, 28)
        return out
    }

    private fun ipv6Udp(payload: ByteArray): ByteArray {
        val udpLength = 8 + payload.size
        val out = ByteArray(40 + udpLength)
        out[0] = 0x60.toByte()
        putU16(out, 4, udpLength)
        out[6] = IpPacket.PROTOCOL_UDP.toByte()
        out[7] = 64
        IpPacket.parseIpv6("fd00::1").copyInto(out, 8)
        IpPacket.parseIpv6(IpPacket.TUNNEL_IPV6_ADDRESS).copyInto(out, 24)
        putU16(out, 40, 5353)
        putU16(out, 42, 53)
        putU16(out, 44, udpLength)
        putU16(out, 46, 0)
        payload.copyInto(out, 48)
        return out
    }

    /** Başlık üzerindeki 16 bitlik toplam 0xFFFF ise sağlama toplamı doğrudur. */
    private fun headerChecksumValid(packet: ByteArray): Boolean =
        sum16(packet.copyOfRange(0, 20)) == 0xFFFFL

    private fun udpChecksumValid(packet: ByteArray, ipv6: Boolean): Boolean {
        val udpOffset = if (ipv6) 40 else (packet[0].toInt() and 0x0F) * 4
        val udpLength = u16(packet, udpOffset + 4)
        val addressLength = if (ipv6) 16 else 4
        val sourceOffset = if (ipv6) 8 else 12

        val stream = ByteArrayOutputStream()
        stream.write(packet, sourceOffset, addressLength)
        stream.write(packet, sourceOffset + addressLength, addressLength)
        if (ipv6) {
            // IPv6 sözde başlığı: uzunluk (4) + üç sıfır bayt + sonraki başlık (1).
            writeU32(stream, udpLength.toLong())
            stream.write(0)
            stream.write(0)
            stream.write(0)
            stream.write(IpPacket.PROTOCOL_UDP)
        } else {
            stream.write(0)
            stream.write(IpPacket.PROTOCOL_UDP)
            writeU16(stream, udpLength)
        }
        stream.write(packet, udpOffset, udpLength)

        return sum16(stream.toByteArray()) == 0xFFFFL
    }

    private fun sum16(data: ByteArray): Long {
        var sum = 0L
        var index = 0
        while (index + 1 < data.size) {
            sum += ((data[index].toInt() and 0xFF) shl 8) or (data[index + 1].toInt() and 0xFF)
            index += 2
        }
        if (index < data.size) sum += (data[index].toInt() and 0xFF) shl 8
        while (sum shr 16 != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
        return sum and 0xFFFF
    }

    private fun u16(packet: ByteArray, offset: Int): Int =
        ((packet[offset].toInt() and 0xFF) shl 8) or (packet[offset + 1].toInt() and 0xFF)

    private fun putU16(packet: ByteArray, offset: Int, value: Int) {
        packet[offset] = (value ushr 8 and 0xFF).toByte()
        packet[offset + 1] = (value and 0xFF).toByte()
    }

    private fun writeU16(out: ByteArrayOutputStream, value: Int) {
        out.write(value ushr 8 and 0xFF)
        out.write(value and 0xFF)
    }

    private fun writeU32(out: ByteArrayOutputStream, value: Long) {
        writeU16(out, (value ushr 16 and 0xFFFF).toInt())
        writeU16(out, (value and 0xFFFF).toInt())
    }
}
