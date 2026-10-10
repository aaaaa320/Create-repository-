package temizweb.android.util

/**
 * Kullanıcı girdisini karşılaştırılabilir alan adına çevirir.
 *
 * Bu dosya, tarayıcı uzantısındaki `shared/temizweb.mjs` içinde bulunan
 * `normalizeDomain` / `hostnameFromUrl` / `sanitizePausedSites` işlevlerinin
 * Kotlin karşılığıdır; iki tarafın aynı kuralları uygulaması bilerek önemlidir.
 */
object DomainNormalizer {

    /** Geçerli alan adı biçimi: uzantıdaki `DOMAIN_PATTERN` ile birebir aynıdır. */
    private val DOMAIN_PATTERN =
        Regex("^(?!-)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}$")

    private val SCHEME_PATTERN = Regex("^[a-z][a-z0-9+.-]*://")
    private val PORT_PATTERN = Regex(":\\d+$")
    private val TRAILING_DOT_PATTERN = Regex("\\.+$")
    private val WILDCARD_PATTERN = Regex("^\\*\\.")

    /** Duraklatma listesinde tutulabilecek en fazla alan adı (uzantıyla aynı üst sınır). */
    const val MAX_PAUSED_SITES = 100

    /**
     * Alan adı, URL, "www." ön ekli veya noktalı yazımı normalleştirir.
     * Geçersiz girdide `null` döner.
     */
    fun normalizeDomain(value: String?): String? {
        if (value == null) return null

        var candidate = value.trim().lowercase()
        if (candidate.isEmpty()) return null

        // Şema, yol, sorgu, parça, kullanıcı bilgisi ve portu ayıkla.
        candidate = SCHEME_PATTERN.replace(candidate, "")
        candidate = candidate.substringBefore('/').substringBefore('?').substringBefore('#')
        // "@" yoksa metin olduğu gibi kalır (eksik ayraç değeri olarak kendisini ver).
        candidate = candidate.substringAfterLast('@', candidate)
        candidate = PORT_PATTERN.replace(candidate, "")

        // Joker ve nokta ön eklerini kaldır.
        candidate = WILDCARD_PATTERN.replace(candidate, "")
        candidate = candidate.trimStart('.')
        candidate = TRAILING_DOT_PATTERN.replace(candidate, "")

        // "www.example.com" ve "example.com" aynı site sayılır.
        if (candidate.startsWith("www.")) candidate = candidate.substring(4)

        if (candidate.isEmpty() || candidate.length > 253) return null
        if (!DOMAIN_PATTERN.matches(candidate)) return null
        return candidate
    }

    /** Serbest biçimli bir URL/metin içindeki makine adını çıkarır; bulunamazsa `null`. */
    fun hostnameFromUrl(value: String?): String? {
        if (value.isNullOrEmpty()) return null
        val candidate = SCHEME_PATTERN.replace(value.trim().lowercase(), "")
        val authority = candidate
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
            .let { withoutPath -> withoutPath.substringAfterLast('@', withoutPath) }
        return normalizeDomain(authority)
    }

    /**
     * Duraklatma listesini temizler: geçersizleri atar, yinelenenleri birleştirir, sıralar.
     * Uzantıdaki `sanitizePausedSites` ile aynı davranış.
     */
    fun sanitizePausedSites(values: Iterable<String?>, limit: Int = MAX_PAUSED_SITES): List<String> {
        val unique = LinkedHashSet<String>()
        for (item in values) {
            val domain = normalizeDomain(item) ?: continue
            unique.add(domain)
            if (unique.size >= limit) break
        }
        return unique.sorted()
    }
}
