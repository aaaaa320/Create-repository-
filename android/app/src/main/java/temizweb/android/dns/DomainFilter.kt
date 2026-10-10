package temizweb.android.dns

import temizweb.android.util.DomainNormalizer

/** Alan adı listelerinin adları; uzantıdaki `RULESET_IDS` karşılığı. */
enum class BlockList(val key: String) {
    ADS("ads"),
    TRACKING("tracking")
}

/** Bir sorgu için verilen karar. */
sealed class FilterDecision {
    /** Alan adı bir listede: sorgu yanıtlanmaz (NXDOMAIN döner). */
    data class Blocked(val list: BlockList, val rule: String) : FilterDecision()

    /** Alan adı listede değil ya da kullanıcı duraklatmış: sorgu iletilir. */
    data object Allowed : FilterDecision()
}

/**
 * Alan adı tabanlı filtre.
 *
 * Uzantı `||alan-adı^` urlFilter kalıbını kullanır; DNS düzeyinde bunun karşılığı
 * "alan adının kendisi veya herhangi bir alt alan adı" eşleşmesidir. Eşleşme,
 * sorgulanan adın her son eki (suffix) hash kümesinde aranarak yapılır:
 * 124 kural için sorgu başına maliyet etiket sayısı kadardır.
 */
class DomainFilter private constructor(
    private val ads: Set<String>,
    private val tracking: Set<String>,
    @Volatile private var paused: Set<String>
) {

    val adCount: Int get() = ads.size
    val trackingCount: Int get() = tracking.size
    val pausedSites: List<String> get() = paused.sorted()

    /**
     * Sorguyu değerlendirir.
     *
     * @param domain   sorgulanan alan adı (küçük harf beklenir, gerekirse dönüştürülür)
     * @param adsEnabled      reklam listesi açık mı
     * @param trackingEnabled izleme listesi açık mı
     */
    fun decide(
        domain: String,
        adsEnabled: Boolean,
        trackingEnabled: Boolean
    ): FilterDecision {
        if (domain.isEmpty()) return FilterDecision.Allowed

        // Duraklatma her zaman kazanır: uzantıdaki "allow" kuralları da daha yüksek
        // önceliğe sahiptir. Duraklatılan sitenin kendisi ve alt alan adları serbesttir.
        if (paused.isNotEmpty() && matchesAny(paused, domain)) return FilterDecision.Allowed

        if (adsEnabled) {
            val rule = match(ads, domain)
            if (rule != null) return FilterDecision.Blocked(BlockList.ADS, rule)
        }
        if (trackingEnabled) {
            val rule = match(tracking, domain)
            if (rule != null) return FilterDecision.Blocked(BlockList.TRACKING, rule)
        }
        return FilterDecision.Allowed
    }

    /** Yalnızca duraklatma listesine bakılır (arayüzde "bu alan adı duraklatıldı mı?" için). */
    fun isPaused(domain: String?): Boolean {
        val normalized = DomainNormalizer.normalizeDomain(domain) ?: return false
        return matchesAny(paused, normalized)
    }

    /** Duraklatma listesini değiştirir; tünel her sorguda güncel listeyi görür. */
    fun setPausedSites(sites: Iterable<String>) {
        paused = DomainNormalizer.sanitizePausedSites(sites).toSet()
    }

    private fun matchesAny(rules: Set<String>, domain: String): Boolean =
        match(rules, domain) != null

    /**
     * `domain` adının kendisi veya bir üst alan adı kümede varsa o kuralı döndürür.
     * "a.b.doubleclick.net" → "doubleclick.net" eşleşir.
     */
    private fun match(rules: Set<String>, domain: String): String? {
        if (rules.isEmpty()) return null
        val name = domain.trimEnd('.').lowercase()
        if (name.isEmpty()) return null

        var index = 0
        while (true) {
            if (rules.contains(name.substring(index))) return name.substring(index)
            val dot = name.indexOf('.', index)
            if (dot < 0) return null
            index = dot + 1
        }
    }

    companion object {
        /**
         * Listelerden filtre kurar. Satırlar boş olabilir; `#` ile başlayanlar yok sayılır
         * (ileride elle eklenen notlar için alan bırakır).
         */
        fun create(
            adDomains: Iterable<String>,
            trackingDomains: Iterable<String>,
            pausedSites: Iterable<String> = emptyList()
        ): DomainFilter = DomainFilter(
            ads = readDomains(adDomains),
            tracking = readDomains(trackingDomains),
            paused = DomainNormalizer.sanitizePausedSites(pausedSites).toSet()
        )

        private fun readDomains(lines: Iterable<String>): Set<String> {
            val rules = LinkedHashSet<String>()
            for (line in lines) {
                val domain = line.trim().lowercase().removeSuffix(".")
                if (domain.isEmpty() || domain.startsWith("#")) continue
                if (DomainNormalizer.normalizeDomain(domain) == null) continue
                rules.add(domain)
            }
            return rules
        }
    }
}
