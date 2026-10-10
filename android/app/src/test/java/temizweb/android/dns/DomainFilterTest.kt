package temizweb.android.dns

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DomainFilterTest {

    private fun newFilter(): DomainFilter = DomainFilter.create(
        adDomains = listOf("doubleclick.net", "ads.ornek.com"),
        trackingDomains = listOf("metrika.ornek.tr")
    )

    @Test
    fun `alan adi ve alt alan adlari eslesir`() {
        val blocked = newFilter().decide("x.y.doubleclick.net", true, false) as FilterDecision.Blocked
        assertEquals(BlockList.ADS, blocked.list)
        assertEquals("doubleclick.net", blocked.rule)

        val exact = newFilter().decide("doubleclick.net", true, false) as FilterDecision.Blocked
        assertEquals("doubleclick.net", exact.rule)
    }

    @Test
    fun `benzer alan adi eslesmez`() {
        // "notdoubleclick.net" içinde "doubleclick.net" geçse de son ek değildir.
        assertEquals(FilterDecision.Allowed, newFilter().decide("notdoubleclick.net", true, true))
        assertEquals(FilterDecision.Allowed, newFilter().decide("ornek.com", true, true))
    }

    @Test
    fun `izleme listesi varsayilan kapali davranisi`() {
        val filter = newFilter()
        assertEquals(FilterDecision.Allowed, filter.decide("metrika.ornek.tr", true, false))
        assertTrue(filter.decide("metrika.ornek.tr", true, true) is FilterDecision.Blocked)
    }

    @Test
    fun `duraklatma blokajin onune gecer`() {
        val filter = newFilter()
        filter.setPausedSites(listOf("doubleclick.net"))

        assertEquals(FilterDecision.Allowed, filter.decide("a.doubleclick.net", true, true))
        assertTrue(filter.isPaused("www.doubleclick.net"))
        // Diğer listeler çalışmaya devam eder.
        assertTrue(filter.decide("ads.ornek.com", true, true) is FilterDecision.Blocked)
    }

    @Test
    fun `liste sayilari varliklardan okunur`() {
        val filter = DomainFilter.create(
            adDomains = listOf("a.com", "#yorum", "", "b.com"),
            trackingDomains = listOf("c.com")
        )
        assertEquals(2, filter.adCount)
        assertEquals(1, filter.trackingCount)
    }

    @Test
    fun `duraklatma listesi normallestirilir`() {
        val filter = newFilter()
        filter.setPausedSites(listOf("https://www.ornek.com/haber", "gecersiz!!"))
        assertEquals(listOf("ornek.com"), filter.pausedSites)
    }
}
