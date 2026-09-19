package flow.pki

import java.io.ByteArrayOutputStream
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Just enough DER to write a certificate.
 *
 * A certificate is an ASN.1 structure that has to be byte-exact, because the signature is over the
 * bytes: anything that re-encodes it differently verifies as broken. The JDK can read certificates
 * (CertificateFactory) but the only thing in it that writes one is `sun.security.x509`, which is
 * internal, not exported, and gone whenever the JDK decides. A library would do it — Bouncy Castle
 * is the usual answer — but this is a few hundred bytes of TLV and the module set has no
 * third-party dependency in it today, so here it is written out.
 *
 * Definite-length encoding throughout, which is what DER is: length first, then content.
 */
internal object Der {

    /** Tag, length, value — the whole of DER, and the only thing everything below is made of. */
    fun tlv(tag: Int, content: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(tag)
        val n = content.size
        if (n < 0x80) {
            out.write(n)
        } else {
            // long form: how many length bytes follow, then the length, big-endian and unpadded
            val bytes = ArrayList<Int>()
            var v = n
            while (v > 0) { bytes.add(0, v and 0xFF); v = v ushr 8 }
            out.write(0x80 or bytes.size)
            bytes.forEach(out::write)
        }
        out.write(content)
        return out.toByteArray()
    }

    fun seq(vararg parts: ByteArray): ByteArray = tlv(0x30, concat(*parts))

    /** An INTEGER holding [value], read as a positive number. */
    fun integer(value: ByteArray): ByteArray {
        val stripped = value.dropWhile { it == 0.toByte() }.toByteArray()
        val trimmed = if (stripped.isEmpty()) byteArrayOf(0) else stripped
        // a leading bit of 1 would read as negative, so a zero byte goes in front of it
        val body = if (trimmed[0].toInt() and 0x80 != 0) byteArrayOf(0) + trimmed else trimmed
        return tlv(0x02, body)
    }

    fun integer(value: Int): ByteArray = integer(byteArrayOf((value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte()))

    /** A BIT STRING of whole bytes — no unused bits, which is every use here. */
    fun bitString(content: ByteArray): ByteArray = tlv(0x03, byteArrayOf(0) + content)

    /** A BIT STRING of [bits], most significant bit first — KeyUsage, and nothing else here. */
    fun bitFlags(bits: List<Int>): ByteArray {
        if (bits.isEmpty()) return tlv(0x03, byteArrayOf(0))
        val highest = bits.max()
        val body = ByteArray(highest / 8 + 1)
        bits.forEach { bit -> body[bit / 8] = (body[bit / 8].toInt() or (0x80 ushr (bit % 8))).toByte() }
        val unused = (body.size * 8 - (highest + 1))
        return tlv(0x03, byteArrayOf(unused.toByte()) + body)
    }

    fun octetString(content: ByteArray): ByteArray = tlv(0x04, content)

    fun boolean(value: Boolean): ByteArray = tlv(0x01, byteArrayOf(if (value) 0xFF.toByte() else 0))

    fun nullValue(): ByteArray = byteArrayOf(0x05, 0x00)

    /** An OBJECT IDENTIFIER from its dotted form: the first two arcs share a byte, then base 128. */
    fun oid(dotted: String): ByteArray {
        val arcs = dotted.split('.').map { it.toLong() }
        require(arcs.size >= 2) { "an OID needs at least two arcs: $dotted" }
        val out = ByteArrayOutputStream()
        out.write((arcs[0] * 40 + arcs[1]).toInt())
        arcs.drop(2).forEach { arc ->
            val chunks = ArrayList<Int>()
            var v = arc
            do { chunks.add(0, (v and 0x7F).toInt()); v = v ushr 7 } while (v > 0)
            chunks.forEachIndexed { i, c -> out.write(if (i == chunks.size - 1) c else c or 0x80) }
        }
        return tlv(0x06, out.toByteArray())
    }

    fun ia5String(value: String): ByteArray = tlv(0x16, value.toByteArray(Charsets.US_ASCII))

    /** `[n] EXPLICIT`: the value, wrapped in a context tag. */
    fun explicit(n: Int, content: ByteArray): ByteArray = tlv(0xA0 or n, content)

    /** `[n] IMPLICIT` on a primitive — a GeneralName's dNSName and iPAddress. */
    fun implicit(n: Int, content: ByteArray): ByteArray = tlv(0x80 or n, content)

    /**
     * A certificate's Time: UTCTime through 2049 and GeneralizedTime after it.
     *
     * RFC 5280 says exactly this, and it is not cosmetic — a UTCTime year is two digits, so 2050
     * written that way is 1950 to whoever reads it back.
     */
    fun time(at: ZonedDateTime): ByteArray {
        val utc = at.withZoneSameInstant(ZoneOffset.UTC)
        return if (utc.year < 2050) {
            tlv(0x17, utc.format(DateTimeFormatter.ofPattern("yyMMddHHmmss'Z'")).toByteArray(Charsets.US_ASCII))
        } else {
            tlv(0x18, utc.format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss'Z'")).toByteArray(Charsets.US_ASCII))
        }
    }

    fun concat(vararg parts: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        parts.forEach(out::write)
        return out.toByteArray()
    }
}
