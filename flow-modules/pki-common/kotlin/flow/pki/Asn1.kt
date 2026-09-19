package flow.pki

/**
 * Just enough DER reading to take one structure apart.
 *
 * The JDK will parse a certificate for you and nothing else: a PKCS#10 request, a CMS blob and a
 * CRL are all ASN.1 it has no public reader for. The modules here need only a few fields out of
 * such a thing — a subject, a public key, a serial number, the bytes that were signed — and this
 * walks to them without a library.
 *
 * Every value keeps the bytes it was cut from, header and all, because that is what most of this is
 * for: a signature covers an encoding, so a field that is re-encoded rather than copied verbatim
 * verifies as broken.
 */
internal class Asn1 private constructor(
    /** The tag byte, e.g. 0x30 for SEQUENCE. */
    val tag: Int,
    /** This element as it appeared, tag and length included — what a signature would be over. */
    val encoded: ByteArray,
    /** The content, without tag and length. */
    val content: ByteArray,
) {
    val isConstructed: Boolean get() = tag and 0x20 != 0

    /** The elements inside a constructed value, in order. */
    fun children(): List<Asn1> {
        require(isConstructed) { "not a constructed value: tag 0x${tag.toString(16)}" }
        return parseAll(content)
    }

    /** The nth element inside, which is how every structure here is addressed. */
    operator fun get(index: Int): Asn1 = children()[index]

    /** This element's content as an unsigned integer, for a version or a small serial. */
    fun asInt(): Int {
        var value = 0
        content.forEach { value = (value shl 8) or (it.toInt() and 0xFF) }
        return value
    }

    /** An OBJECT IDENTIFIER's dotted form. */
    fun asOid(): String {
        require(tag == 0x06) { "not an OID: tag 0x${tag.toString(16)}" }
        val out = StringBuilder()
        val first = content[0].toInt() and 0xFF
        out.append(first / 40).append('.').append(first % 40)
        var value = 0L
        for (i in 1 until content.size) {
            val b = content[i].toInt() and 0xFF
            value = (value shl 7) or (b and 0x7F).toLong()
            if (b and 0x80 == 0) { out.append('.').append(value); value = 0 }
        }
        return out.toString()
    }

    companion object {
        /** The one element [bytes] begins with. */
        fun parse(bytes: ByteArray): Asn1 = read(bytes, 0).first

        /** Every element in [bytes], one after another. */
        fun parseAll(bytes: ByteArray): List<Asn1> {
            val out = ArrayList<Asn1>()
            var at = 0
            while (at < bytes.size) {
                val (element, next) = read(bytes, at)
                out.add(element)
                at = next
            }
            return out
        }

        private fun read(bytes: ByteArray, from: Int): Pair<Asn1, Int> {
            require(from < bytes.size) { "truncated DER" }
            val tag = bytes[from].toInt() and 0xFF
            require(tag and 0x1F != 0x1F) { "multi-byte tags are not supported" }
            var at = from + 1
            require(at < bytes.size) { "truncated DER: no length" }
            val first = bytes[at].toInt() and 0xFF
            at += 1
            val length: Int
            if (first < 0x80) {
                length = first
            } else {
                val count = first and 0x7F
                require(count in 1..4 && at + count <= bytes.size) { "unsupported DER length" }
                var value = 0
                repeat(count) { value = (value shl 8) or (bytes[at + it].toInt() and 0xFF) }
                at += count
                length = value
            }
            require(at + length <= bytes.size) { "DER length runs past the end" }
            return Asn1(
                tag = tag,
                encoded = bytes.copyOfRange(from, at + length),
                content = bytes.copyOfRange(at, at + length),
            ) to (at + length)
        }
    }
}
