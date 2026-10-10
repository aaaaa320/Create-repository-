package temizweb.android.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Uzantıdaki `shared/temizweb.mjs` testleriyle aynı beklentiler: iki sürümün
 * normalleştirme kuralları bilinçli olarak birebir aynıdır.
 */
class DomainNormalizerTest {

    @Test
    fun `url yazimlari alan adina donusur`() {
        assertEquals("ornek.com", DomainNormalizer.normalizeDomain("https://www.ornek.com/haber"))
        assertEquals("ornek.com", DomainNormalizer.normalizeDomain("http://ornek.com:8080/x?y=1#z"))
        assertEquals("ornek.com", DomainNormalizer.normalizeDomain("*.ornek.com"))
        assertEquals("ornek.com", DomainNormalizer.normalizeDomain("...ornek.com..."))
        assertEquals("ornek.com", DomainNormalizer.normalizeDomain("  ORNEK.COM  "))
        assertEquals("ornek.com", DomainNormalizer.normalizeDomain("kullanici@ornek.com"))
    }

    @Test
    fun `gecersiz girdiler null doner`() {
        assertNull(DomainNormalizer.normalizeDomain(null))
        assertNull(DomainNormalizer.normalizeDomain(""))
        assertNull(DomainNormalizer.normalizeDomain("   "))
        assertNull(DomainNormalizer.normalizeDomain("-ornek.com"))
        assertNull(DomainNormalizer.normalizeDomain("ornek"))
        assertNull(DomainNormalizer.normalizeDomain("ornek..com"))
        assertNull(DomainNormalizer.normalizeDomain("a".repeat(300)))
    }

    @Test
    fun `alt alan adlari korunur`() {
        assertEquals("haber.ornek.com", DomainNormalizer.normalizeDomain("haber.ornek.com"))
        assertEquals("a.b.ornek.com", DomainNormalizer.normalizeDomain("www.a.b.ornek.com"))
    }

    @Test
    fun `duraklatma listesi temizlenir ve siralanir`() {
        val sites = DomainNormalizer.sanitizePausedSites(
            listOf("b.com", "https://a.com/x", "b.com", "!!", null, "*.c.com")
        )
        assertEquals(listOf("a.com", "b.com", "c.com"), sites)
    }

    @Test
    fun `duraklatma listesi ust sinirla kisitlanir`() {
        val many = (1..150).map { "site$it.com" }
        assertEquals(DomainNormalizer.MAX_PAUSED_SITES, DomainNormalizer.sanitizePausedSites(many).size)
    }

    @Test
    fun `url icinden makine adi cikarilir`() {
        assertEquals("haber.ornek.com", DomainNormalizer.hostnameFromUrl("https://haber.ornek.com/x?y=1"))
        assertNull(DomainNormalizer.hostnameFromUrl("file:///sdcard/x"))
        assertNull(DomainNormalizer.hostnameFromUrl(null))
    }
}
