package temizweb.android.dns

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsCacheTest {

    private var now = 1_000_000L

    private fun cache(): DnsCache = DnsCache(clock = { now })

    @Test
    fun `basarili yanit onbellege alinir ve kimlik yenilenir`() {
        val cache = cache()
        val response = TestPackets.response(1, "ornek.com", listOf(300))

        assertTrue(cache.put("ornek.com", DnsMessage.TYPE_A, response, response.size))

        val hit = cache.get("ornek.com", DnsMessage.TYPE_A, 0x77)
        assertNotNull(hit)
        assertEquals(0x77, ((hit!![0].toInt() and 0xFF) shl 8) or (hit[1].toInt() and 0xFF))
        assertEquals(1L, cache.hits)
        assertEquals(0L, cache.misses)
        assertEquals(1, cache.size)
    }

    @Test
    fun `suresi dolan kayit servis edilmez`() {
        val cache = cache()
        val response = TestPackets.response(1, "ornek.com", listOf(300))
        cache.put("ornek.com", DnsMessage.TYPE_A, response, response.size)

        now += 301_000
        assertNull(cache.get("ornek.com", DnsMessage.TYPE_A, 1))
        assertEquals(1L, cache.misses)
    }

    @Test
    fun `ttl yaslanarak servis edilir`() {
        val cache = cache()
        val response = TestPackets.response(1, "ornek.com", listOf(300))
        cache.put("ornek.com", DnsMessage.TYPE_A, response, response.size)

        now += 100_000
        val hit = cache.get("ornek.com", DnsMessage.TYPE_A, 1)!!
        assertEquals(200L, DnsMessage.minAnswerTtl(hit, hit.size))
    }

    @Test
    fun `tur farki ayri anahtardir`() {
        val cache = cache()
        val response = TestPackets.response(1, "ornek.com", listOf(300))
        cache.put("ornek.com", DnsMessage.TYPE_A, response, response.size)

        assertNull(cache.get("ornek.com", DnsMessage.TYPE_AAAA, 1))
    }

    @Test
    fun `hatali ve kisa omurlu yanitlar saklanmaz`() {
        val cache = cache()

        val nxdomain = TestPackets.response(1, "ornek.com", emptyList(), rcode = DnsMessage.RCODE_NAME_ERROR)
        assertFalse(cache.put("ornek.com", DnsMessage.TYPE_A, nxdomain, nxdomain.size))

        val shortTtl = TestPackets.response(1, "ornek.com", listOf(1))
        assertFalse(cache.put("ornek.com", DnsMessage.TYPE_A, shortTtl, shortTtl.size))

        assertEquals(0, cache.size)
    }

    @Test
    fun `lru siniri asilinca en eski kayit duser`() {
        val cache = DnsCache(maxSize = 2, clock = { now })
        repeat(3) { index ->
            val response = TestPackets.response(1, "site$index.com", listOf(300))
            cache.put("site$index.com", DnsMessage.TYPE_A, response, response.size)
        }
        assertEquals(2, cache.size)
        assertNull(cache.get("site0.com", DnsMessage.TYPE_A, 1))
        assertNotNull(cache.get("site2.com", DnsMessage.TYPE_A, 1))
    }
}
