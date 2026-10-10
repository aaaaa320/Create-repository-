package temizweb.android.dns

import java.io.ByteArrayOutputStream

/**
 * Testlerde kullanılan elle kurulmuş DNS paketleri.
 * Gerçek bir çözücünün üreteceği en sade biçimleri taklit eder.
 */
object TestPackets {

    fun query(
        name: String,
        type: Int = DnsMessage.TYPE_A,
        id: Int = 0x1234,
        ednsPayload: Int? = null
    ): ByteArray {
        val out = ByteArrayOutputStream()
        writeU16(out, id)
        writeU16(out, 0x0100)                    // RD
        writeU16(out, 1)                         // QDCOUNT
        writeU16(out, 0)                         // ANCOUNT
        writeU16(out, 0)                         // NSCOUNT
        writeU16(out, if (ednsPayload != null) 1 else 0)  // ARCOUNT
        writeName(out, name)
        writeU16(out, type)
        writeU16(out, DnsMessage.CLASS_IN)

        if (ednsPayload != null) {
            out.write(0)                         // kök ad
            writeU16(out, DnsMessage.TYPE_OPT)
            writeU16(out, ednsPayload)           // class alanı UDP yük boyutunu taşır
            writeU32(out, 0)                     // TTL
            writeU16(out, 0)                     // RDLENGTH
        }
        return out.toByteArray()
    }

    /** NOERROR yanıtı; her TTL için bir A kaydı ekler. `ttls` boşsa yalnızca soru döner. */
    fun response(
        id: Int,
        name: String,
        ttls: List<Long>,
        rcode: Int = DnsMessage.RCODE_NO_ERROR
    ): ByteArray {
        val out = ByteArrayOutputStream()
        writeU16(out, id)
        writeU16(out, 0x8180 or rcode)           // QR | RD | RA | rcode
        writeU16(out, 1)                         // QDCOUNT
        writeU16(out, ttls.size)                 // ANCOUNT
        writeU16(out, 0)                         // NSCOUNT
        writeU16(out, 0)                         // ARCOUNT
        writeName(out, name)
        writeU16(out, DnsMessage.TYPE_A)
        writeU16(out, DnsMessage.CLASS_IN)

        for (ttl in ttls) {
            writeName(out, name)
            writeU16(out, DnsMessage.TYPE_A)
            writeU16(out, DnsMessage.CLASS_IN)
            writeU32(out, ttl)
            writeU16(out, 4)
            out.write(byteArrayOf(127, 0, 0, 1))
        }
        return out.toByteArray()
    }

    private fun writeName(out: ByteArrayOutputStream, name: String) {
        for (label in name.split('.')) {
            val bytes = label.toByteArray(Charsets.US_ASCII)
            out.write(bytes.size)
            out.write(bytes, 0, bytes.size)
        }
        out.write(0)
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
