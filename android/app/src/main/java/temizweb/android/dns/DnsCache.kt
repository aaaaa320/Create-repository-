package temizweb.android.dns

/**
 * Üst sunucudan gelen yanıtlar için küçük bir önbellek.
 *
 * Amaç pil ve veri tasarrufudur: aynı alan adı kısa aralıklarla birçok
 * uygulamadan sorulur. Yalnızca başarılı (NOERROR) ve en az bir cevap kaydı
 * içeren yanıtlar saklanır; NXDOMAIN ve hatalı yanıtlar önbelleğe alınmaz.
 *
 * Sorgulanan alan adları günlüğe yazılmaz, diske aktarılmaz; süreç sonlanınca
 * bellek ile birlikte silinir.
 */
class DnsCache(
    private val maxSize: Int = DEFAULT_MAX_ENTRIES,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {

    private class Entry(
        val packet: ByteArray,
        val expiresAt: Long,
        val storedAt: Long
    )

    // Erişim sıralı LinkedHashMap basit bir LRU sağlar.
    private val entries = object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean =
            size > maxSize
    }

    private var hitCount = 0L
    private var missCount = 0L

    /** Önbellekten karşılanan sorgu sayısı (arayüzde gösterilir). */
    val hits: Long get() = synchronized(this) { hitCount }

    /** Önbellekte bulunmayıp üst sunucuya iletilen sorgu sayısı. */
    val misses: Long get() = synchronized(this) { missCount }

    /** Şu anda saklanan yanıt sayısı. */
    val size: Int get() = synchronized(this) { entries.size }

    /**
     * Önbellekteki yanıtı döndürür. TTL değerleri geçen süreye göre azaltılır ve
     * sorgu kimliği (ID) istemcinin beklediği değerle değiştirilir.
     */
    fun get(name: String, type: Int, queryId: Int): ByteArray? = synchronized(this) {
        val key = key(name, type)
        val entry = entries[key]
        val now = clock()

        if (entry == null || now >= entry.expiresAt) {
            if (entry != null) entries.remove(key)
            missCount += 1
            return@synchronized null
        }

        hitCount += 1
        val ageSeconds = ((now - entry.storedAt) / 1000L).coerceAtLeast(0L)
        val aged = DnsMessage.ageTtls(entry.packet, entry.packet.size, ageSeconds)
        DnsMessage.withId(aged, aged.size, queryId)
    }

    /**
     * Üst sunucu yanıtını saklar. Yanıt yalnızca NOERROR + en az bir cevap kaydı
     * olduğunda ve TTL >= [MIN_TTL_SECONDS] ise önbelleğe alınır.
     *
     * @return kayıt önbelleğe alındıysa `true`
     */
    fun put(name: String, type: Int, packet: ByteArray, length: Int): Boolean = synchronized(this) {
        if (length < DnsMessage.HEADER_SIZE) return@synchronized false
        if (DnsMessage.responseCode(packet, length) != DnsMessage.RCODE_NO_ERROR) return@synchronized false
        if (DnsMessage.answerCount(packet, length) < 1) return@synchronized false

        val ttl = DnsMessage.minAnswerTtl(packet, length)
        if (ttl == Long.MAX_VALUE || ttl < MIN_TTL_SECONDS) return@synchronized false

        val now = clock()
        entries[key(name, type)] = Entry(
            packet = packet.copyOf(length),
            expiresAt = now + ttl * 1000L,
            storedAt = now
        )
        true
    }

    fun clear() = synchronized(this) {
        entries.clear()
        hitCount = 0
        missCount = 0
    }

    private fun key(name: String, type: Int): String = "${name.lowercase()}|$type"

    companion object {
        const val DEFAULT_MAX_ENTRIES = 512
        const val MIN_TTL_SECONDS = 5L
    }
}
