package temizweb.android.dns

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsMessageTest {

    @Test
    fun `sorgu cozumlenir`() {
        val packet = TestPackets.query("WWW.Ornek.COM")
        val query = DnsMessage.parseQuery(packet, packet.size)!!

        assertEquals(0x1234, query.id)
        assertEquals("www.ornek.com", query.name)
        assertEquals(DnsMessage.TYPE_A, query.type)
        assertEquals(DnsMessage.CLASS_IN, query.dnsClass)
        assertEquals(packet.size, query.questionEnd)
        assertFalse(query.hasEdns)
        assertTrue(query.isStandardInternetQuery)
    }

    @Test
    fun `edns yuk boyutu okunur`() {
        val packet = TestPackets.query("ornek.com", ednsPayload = 4096)
        val query = DnsMessage.parseQuery(packet, packet.size)!!

        assertTrue(query.hasEdns)
        assertEquals(4096, query.maxResponseSize)
        assertTrue(query.questionEnd < packet.size)
    }

    @Test
    fun `yanitlar sorgu olarak cozumlenmez`() {
        val packet = TestPackets.response(1, "ornek.com", listOf(300))

        assertNull(DnsMessage.parseQuery(packet, packet.size))
        assertEquals(DnsMessage.RCODE_NO_ERROR, DnsMessage.responseCode(packet, packet.size))
        assertEquals(1, DnsMessage.answerCount(packet, packet.size))
    }

    @Test
    fun `bozuk paketler guvenle reddedilir`() {
        assertNull(DnsMessage.parseQuery(ByteArray(4), 4))
        assertNull(DnsMessage.parseQuery(ByteArray(64), 64))

        val truncated = TestPackets.query("ornek.com")
        assertNull(DnsMessage.parseQuery(truncated, truncated.size - 2))
    }

    @Test
    fun `nxdomain yaniti uretilir ve soru korunur`() {
        val packet = TestPackets.query("ads.ornek.com")
        val query = DnsMessage.parseQuery(packet, packet.size)!!

        val response = DnsMessage.buildErrorResponse(query, packet, DnsMessage.RCODE_NAME_ERROR)

        assertEquals(DnsMessage.RCODE_NAME_ERROR, DnsMessage.responseCode(response, response.size))
        assertEquals(0, DnsMessage.answerCount(response, response.size))
        assertEquals(query.id, u16(response, 0))
        assertArrayEquals(
            packet.copyOfRange(DnsMessage.HEADER_SIZE, query.questionEnd),
            response.copyOfRange(DnsMessage.HEADER_SIZE, response.size)
        )
    }

    @Test
    fun `edns ust sorguda cikarilir`() {
        val packet = TestPackets.query("ornek.com", ednsPayload = 1232)
        val query = DnsMessage.parseQuery(packet, packet.size)!!

        val upstream = DnsMessage.buildUpstreamQuery(query, packet, packet.size)

        assertEquals(query.questionEnd, upstream.size)
        assertEquals(0, u16(upstream, 10))
        // RD biti korunur.
        assertTrue(u16(upstream, 2) and 0x0100 != 0)
    }

    @Test
    fun `ttl yaslandirilir`() {
        val response = TestPackets.response(7, "ornek.com", listOf(300, 600))

        assertEquals(300L, DnsMessage.minAnswerTtl(response, response.size))

        val aged = DnsMessage.ageTtls(response, response.size, 100)
        assertEquals(200L, DnsMessage.minAnswerTtl(aged, aged.size))

        // Yaş sınırı TTL'in altına inmez.
        val overAged = DnsMessage.ageTtls(response, response.size, 10_000)
        assertEquals(0L, DnsMessage.minAnswerTtl(overAged, overAged.size))
    }

    @Test
    fun `buyuk yanit mtuya sigacak sekilde kucultulur`() {
        val response = TestPackets.response(9, "ornek.com", List(40) { 300L })
        assertTrue(response.size > 200)

        val shrunk = DnsMessage.shrinkResponse(response, response.size, 150)

        assertTrue(shrunk.size <= 150)
        val answers = DnsMessage.answerCount(shrunk, shrunk.size)
        if (answers == 0) {
            // Hiç cevap sığmadıysa kırpma (TC) biti kurulu olmalı.
            assertTrue(u16(shrunk, 2) and 0x0200 != 0)
        } else {
            assertTrue(answers in 1..40)
        }
    }

    @Test
    fun `kucuk yanit degismez`() {
        val response = TestPackets.response(9, "ornek.com", listOf(300))
        assertArrayEquals(response, DnsMessage.shrinkResponse(response, response.size, 4096))
    }

    @Test
    fun `sorgu kimligi degistirilebilir`() {
        val response = TestPackets.response(1, "ornek.com", listOf(60))
        val patched = DnsMessage.withId(response, response.size, 0x00AA)
        assertEquals(0x00AA, u16(patched, 0))
        assertEquals(1, u16(response, 0))
    }

    private fun u16(packet: ByteArray, offset: Int): Int =
        ((packet[offset].toInt() and 0xFF) shl 8) or (packet[offset + 1].toInt() and 0xFF)
}
