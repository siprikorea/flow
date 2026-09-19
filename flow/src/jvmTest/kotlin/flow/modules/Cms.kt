package flow.modules

/**
 * A CMS blob taken apart far enough to check it, with a DER reader of its own.
 *
 * Deliberately not the module's: a test that parses with the same code that wrote the bytes agrees
 * with itself whatever either of them does. This walks the structure RFC 5652 describes, by index,
 * and reports what it finds there.
 */
class Cms(bytes: ByteArray) {

    private class Element(val tag: Int, val encoded: ByteArray, val content: ByteArray) {
        fun children(): List<Element> = read(content)
        operator fun get(i: Int): Element = children()[i]
    }

    private companion object {
        fun read(bytes: ByteArray): List<Element> {
            val out = ArrayList<Element>()
            var at = 0
            while (at < bytes.size) {
                val start = at
                val tag = bytes[at].toInt() and 0xFF
                at += 1
                val first = bytes[at].toInt() and 0xFF
                at += 1
                var length = first
                if (first >= 0x80) {
                    val count = first and 0x7F
                    length = 0
                    repeat(count) { length = (length shl 8) or (bytes[at + it].toInt() and 0xFF) }
                    at += count
                }
                out.add(Element(tag, bytes.copyOfRange(start, at + length), bytes.copyOfRange(at, at + length)))
                at += length
            }
            return out
        }

        fun oid(element: Element): String {
            val content = element.content
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
    }

    private val contentInfo = read(bytes).first()
    private val signedData = contentInfo[1][0]
    private val fields = signedData.children()

    /** The outer content type: signedData, for everything this module makes. */
    val contentType: String = oid(contentInfo[0])

    /** The encapsulated content, or null when the signature is detached. */
    val content: ByteArray? = fields[2].children().getOrNull(1)?.get(0)?.content

    /** Every certificate carried along, in the order they were put in. */
    val certificates: List<ByteArray> = fields.firstOrNull { it.tag == 0xA0 }?.children()?.map { it.encoded }.orEmpty()

    private val signerInfo: Element? = fields.lastOrNull { it.tag == 0x31 }?.children()?.firstOrNull()

    /**
     * The signed attributes as they were signed: a SET, not the [0] IMPLICIT they are carried as.
     * Re-tagging them here is exactly what a verifier has to do, and getting it wrong is the
     * classic CMS bug.
     */
    val signedAttributes: ByteArray? = signerInfo?.children()?.firstOrNull { it.tag == 0xA0 }
        ?.let { byteArrayOf(0x31) + it.encoded.copyOfRange(1, it.encoded.size) }

    val signature: ByteArray? = signerInfo?.children()?.lastOrNull { it.tag == 0x04 }?.content

    /** The messageDigest attribute: what the signature actually commits the content to. */
    val messageDigest: ByteArray? = signerInfo?.children()?.firstOrNull { it.tag == 0xA0 }?.children()
        ?.firstOrNull { attribute -> oid(attribute[0]) == "1.2.840.113549.1.9.4" }
        ?.get(1)?.get(0)?.content
}
