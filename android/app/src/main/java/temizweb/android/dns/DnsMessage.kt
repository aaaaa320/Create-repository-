package temizweb.android.dns

import java.io.ByteArrayOutputStream

/**
 * DNS tel biçimi (RFC 1035 / RFC 6891) çözümleme ve üretim yardımcıları.
 *
 * Bu dosya saf Kotlin'dir: Android API'lerine dokunmaz, bu yüzden birim
 * testlerle doğrudan sınanabilir. Yalnızca UDP sorguları ele alınır; TCP DNS
 * ayrı bir tünel yığını gerektirdiği için bilinçli olarak kapsam dışıdır.
 */
object DnsMessage {

    const val HEADER_SIZE = 12
    const val TYPE_A = 1
    const val TYPE_AAAA = 28
    const val TYPE_OPT = 41
    const val CLASS_IN = 1

    const val OPCODE_QUERY = 0
    const val RCODE_NO_ERROR = 0
    const val RCODE_SERVER_FAILURE = 2
    const val RCODE_NAME_ERROR = 3

    const val MIN_UDP_PAYLOAD = 512
    const val MAX_UDP_PAYLOAD = 4096
    const val MAX_NAME_LENGTH = 253
    const val MAX_COMPRESSION_HOPS = 16

    private const val FLAG_QR = 0x8000
    private const val FLAG_AA = 0x0400
    private const val FLAG_TC = 0x0200
    private const val FLAG_RD = 0x0100
    private const val FLAG_RA = 0x0080

    /** Çözümlenmiş bir sorgu. */
    class Query(
        val id: Int,
        val flags: Int,
        val name: String,
        val type: Int,
        val dnsClass: Int,
        /** Orijinal pakette soru bölümünün bittiği bayt indeksi. */
        val questionEnd: Int,
        /** Sorgu EDNS0 (OPT) kaydı taşıyor mu? */
        val hasEdns: Boolean,
        /** İstemcinin kabul ettiği en büyük UDP yük boyu. */
        val maxResponseSize: Int
    ) {
        /** Yalnızca IN sınıfı A/AAAA sorguları filtreye konu edilir. */
        val isStandardInternetQuery: Boolean
            get() = dnsClass == CLASS_IN && (type == TYPE_A || type == TYPE_AAAA)
    }

    private class NameResult(val name: String, val nextOffset: Int)

    private class RecordInfo(
        val type: Int,
        val dnsClass: Int,
        val ttl: Long,
        val ttlOffset: Int,
        val nextOffset: Int
    )

    // ---------------------------------------------------------------- çözümleme

    /**
     * Paketi sorgu olarak çözümler.
     * Yanıt, bozuk paket veya tek soru dışındaki biçimler için `null` döner.
     */
    fun parseQuery(packet: ByteArray, length: Int): Query? {
        if (length < HEADER_SIZE || length > packet.size) return null

        val flags = u16(packet, 2)
        if (flags and FLAG_QR != 0) return null                 // bu bir yanıt
        if (opcode(flags) != OPCODE_QUERY) return null          // yalnızca standart sorgular

        val qdCount = u16(packet, 4)
        if (qdCount != 1) return null

        val name = decodeName(packet, length, HEADER_SIZE) ?: return null
        var offset = name.nextOffset
        if (offset + 4 > length) return null

        val type = u16(packet, offset)
        val dnsClass = u16(packet, offset + 2)
        offset += 4
        val questionEnd = offset

        // EDNS0: ilk ek (additional) kayıt OPT ise istemcinin UDP yük boyutunu taşır.
        var hasEdns = false
        var payloadSize = MIN_UDP_PAYLOAD
        if (u16(packet, 10) > 0) {
            val record = readRecord(packet, length, offset)
            if (record != null && record.type == TYPE_OPT) {
                hasEdns = true
                payloadSize = record.dnsClass
            }
        }

        return Query(
            id = u16(packet, 0),
            flags = flags,
            name = name.name,
            type = type,
            dnsClass = dnsClass,
            questionEnd = questionEnd,
            hasEdns = hasEdns,
            maxResponseSize = payloadSize.coerceIn(MIN_UDP_PAYLOAD, MAX_UDP_PAYLOAD)
        )
    }

    /** Yanıt paketinin RCODE değerini döndürür; paket yanıt değilse `null`. */
    fun responseCode(packet: ByteArray, length: Int): Int? {
        if (length < HEADER_SIZE) return null
        val flags = u16(packet, 2)
        if (flags and FLAG_QR == 0) return null
        return flags and 0x000F
    }

    /** Yanıttaki cevap (answer) kaydı sayısı. */
    fun answerCount(packet: ByteArray, length: Int): Int =
        if (length < HEADER_SIZE) 0 else u16(packet, 6)

    // ---------------------------------------------------------------- üretim

    /**
     * Engellenen alan adı için hata yanıtı (genellikle NXDOMAIN) üretir.
     * Soru bölümü orijinal paketten bayt bayt kopyalanır; adın yazımı korunur.
     */
    fun buildErrorResponse(query: Query, packet: ByteArray, rcode: Int): ByteArray {
        val question = packet.copyOfRange(HEADER_SIZE, query.questionEnd)
        val out = ByteArrayOutputStream(HEADER_SIZE + question.size)

        val flags = FLAG_QR or FLAG_RA or (query.flags and FLAG_RD) or (rcode and 0x000F)
        out.write(query.id ushr 8 and 0xFF)
        out.write(query.id and 0xFF)
        out.write(flags ushr 8 and 0xFF)
        out.write(flags and 0xFF)
        out.write(0); out.write(1)   // QDCOUNT
        out.write(0); out.write(0)   // ANCOUNT
        out.write(0); out.write(0)   // NSCOUNT
        out.write(0); out.write(0)   // ARCOUNT
        out.write(question, 0, question.size)

        return out.toByteArray()
    }

    /**
     * Üst sunucuya iletilecek sorguyu hazırlar.
     *
     * EDNS0 (OPT) kaydı bilerek çıkarılır: DNSSEC doğrulaması ve büyük UDP yükleri
     * istemiyoruz, böylece yanıtlar 512 bayt içinde kalır ve TCP'ye düşme gereği doğmaz.
     */
    fun buildUpstreamQuery(query: Query, packet: ByteArray, length: Int): ByteArray {
        if (!query.hasEdns) return packet.copyOf(length)

        val out = ByteArray(HEADER_SIZE)
        System.arraycopy(packet, 0, out, 0, HEADER_SIZE)

        // RD=1 (özyineleme istiyoruz), QR=0, AA=0, TC=0, ARCOUNT=0.
        val flags = (query.flags or FLAG_RD) and FLAG_QR.inv() and FLAG_AA.inv() and FLAG_TC.inv()
        putU16(out, 2, flags)
        putU16(out, 10, 0)

        val question = packet.copyOfRange(HEADER_SIZE, query.questionEnd)
        return out + question
    }

    /** Yanıtın sorgu kimliğini (ID) değiştirir; yeni bir dizi döner. */
    fun withId(packet: ByteArray, length: Int, id: Int): ByteArray {
        val copy = packet.copyOf(length)
        putU16(copy, 0, id)
        return copy
    }

    /**
     * Önbellekten servis edilen yanıtın TTL değerlerini `ageSeconds` kadar azaltır.
     * Böylece istemciler kaydı süresi dolmuş biçimde önbelleğe almaz.
     */
    fun ageTtls(packet: ByteArray, length: Int, ageSeconds: Long): ByteArray {
        val copy = packet.copyOf(length)
        if (length < HEADER_SIZE || ageSeconds <= 0) return copy

        var offset = skipQuestions(copy, length, u16(copy, 4))
        if (offset < 0) return copy

        for (section in 0 until 3) {
            val count = u16(copy, 6 + section * 2)
            for (index in 0 until count) {
                val record = readRecord(copy, length, offset) ?: return copy
                val aged = (record.ttl - ageSeconds).coerceAtLeast(0L)
                putU32(copy, record.ttlOffset, aged.toInt())
                offset = record.nextOffset
            }
        }
        return copy
    }

    /** Cevap bölümündeki en küçük TTL; cevap yoksa [Long.MAX_VALUE]. */
    fun minAnswerTtl(packet: ByteArray, length: Int): Long {
        if (length < HEADER_SIZE) return Long.MAX_VALUE

        var offset = skipQuestions(packet, length, u16(packet, 4))
        if (offset < 0) return Long.MAX_VALUE

        var minimum = Long.MAX_VALUE
        for (index in 0 until answerCount(packet, length)) {
            val record = readRecord(packet, length, offset) ?: break
            if (record.ttl < minimum) minimum = record.ttl
            offset = record.nextOffset
        }
        return minimum
    }

    /**
     * Yanıt istemcinin kabul ettiği boyuta sığmıyorsa küçültür: yetki (authority) ve
     * ek (additional) bölümleri atılır, gerekirse cevap kayıtları kırpılır. Hiçbir
     * cevap sığmazsa TC (kırpma) biti kurulu bir yanıt döner.
     *
     * Kopyalama sırasında ad sıkıştırma işaretçileri korunan bölgeyi gösterdiği için
     * mesaj geçerli kalır.
     */
    fun shrinkResponse(packet: ByteArray, length: Int, maxBytes: Int): ByteArray {
        if (length <= maxBytes) return packet.copyOf(length)

        val questionEnd = skipQuestions(packet, length, u16(packet, 4))
        if (questionEnd < 0 || maxBytes < questionEnd) return packet.copyOf(maxBytes)

        val header = packet.copyOfRange(0, HEADER_SIZE)
        putU16(header, 8, 0)   // NSCOUNT
        putU16(header, 10, 0)  // ARCOUNT

        val out = ByteArrayOutputStream(maxBytes)
        out.write(header, 0, HEADER_SIZE)
        out.write(packet, HEADER_SIZE, questionEnd - HEADER_SIZE)

        var kept = 0
        var offset = questionEnd
        val answers = answerCount(packet, length)
        while (kept < answers) {
            val record = readRecord(packet, length, offset) ?: break
            if (record.nextOffset > maxBytes) break
            kept += 1
            offset = record.nextOffset
        }

        if (kept == 0) {
            val truncated = header.copyOf()
            putU16(truncated, 2, u16(truncated, 2) or FLAG_TC)
            putU16(truncated, 6, 0)
            val result = ByteArrayOutputStream(questionEnd)
            result.write(truncated, 0, HEADER_SIZE)
            result.write(packet, HEADER_SIZE, questionEnd - HEADER_SIZE)
            return result.toByteArray()
        }

        out.write(packet, questionEnd, offset - questionEnd)
        val result = out.toByteArray()
        putU16(result, 6, kept)
        return result
    }

    // ---------------------------------------------------------------- iç yardımcılar

    fun opcode(flags: Int): Int = flags ushr 11 and 0x000F

    private fun decodeName(packet: ByteArray, length: Int, start: Int): NameResult? {
        val labels = StringBuilder()
        var offset = start
        var jumped = false
        var nextOffset = -1
        var hops = 0
        var totalLength = 0

        while (true) {
            if (offset < 0 || offset >= length) return null
            val size = packet[offset].toInt() and 0xFF

            if (size == 0) {
                if (!jumped) nextOffset = offset + 1
                break
            }

            if (size and 0xC0 == 0xC0) {
                if (offset + 1 >= length) return null
                if (!jumped) nextOffset = offset + 2
                val pointer = ((size and 0x3F) shl 8) or (packet[offset + 1].toInt() and 0xFF)
                if (pointer <= 0 || pointer >= length) return null
                if (++hops > MAX_COMPRESSION_HOPS) return null
                offset = pointer
                jumped = true
                continue
            }

            if (size and 0xC0 != 0) return null            // ayrılmış etiket biçimleri
            if (offset + 1 + size > length) return null

            if (labels.isNotEmpty()) labels.append('.')
            for (index in 1..size) {
                labels.append((packet[offset + index].toInt() and 0xFF).toChar())
            }
            totalLength += size + 1
            if (totalLength > MAX_NAME_LENGTH) return null
            offset += size + 1
        }

        if (nextOffset < 0) return null
        val name = labels.toString().lowercase()
        if (name.isEmpty()) return null                   // kök ad: filtre için anlamsız
        return NameResult(name, nextOffset)
    }

    private fun readRecord(packet: ByteArray, length: Int, start: Int): RecordInfo? {
        val name = decodeName(packet, length, start) ?: return null
        var offset = name.nextOffset
        if (offset + 10 > length) return null

        val type = u16(packet, offset)
        val dnsClass = u16(packet, offset + 2)
        val ttl = u32(packet, offset + 4)
        val ttlOffset = offset + 4
        val rdLength = u16(packet, offset + 8)
        offset += 10
        if (offset + rdLength > length) return null

        return RecordInfo(type, dnsClass, ttl, ttlOffset, offset + rdLength)
    }

    private fun skipQuestions(packet: ByteArray, length: Int, count: Int): Int {
        var offset = HEADER_SIZE
        for (index in 0 until count) {
            val name = decodeName(packet, length, offset) ?: return -1
            offset = name.nextOffset + 4
            if (offset > length) return -1
        }
        return offset
    }

    private fun u16(packet: ByteArray, offset: Int): Int =
        ((packet[offset].toInt() and 0xFF) shl 8) or (packet[offset + 1].toInt() and 0xFF)

    private fun u32(packet: ByteArray, offset: Int): Long =
        (u16(packet, offset).toLong() shl 16) or u16(packet, offset + 2).toLong()

    private fun putU16(packet: ByteArray, offset: Int, value: Int) {
        packet[offset] = (value ushr 8 and 0xFF).toByte()
        packet[offset + 1] = (value and 0xFF).toByte()
    }

    private fun putU32(packet: ByteArray, offset: Int, value: Int) {
        putU16(packet, offset, value ushr 16 and 0xFFFF)
        putU16(packet, offset + 2, value and 0xFFFF)
    }
}
