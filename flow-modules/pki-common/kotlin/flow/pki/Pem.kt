package flow.pki

import java.util.Base64

/**
 * PEM in and PEM out.
 *
 * Everything in PKI arrives either as DER or as the same DER in Base64 between two dashed lines,
 * and a person has one or the other depending on which tool produced it. Every port here takes
 * both, and every port that emits offers both.
 */
internal object Pem {

    /** The DER inside [bytes], whether they were PEM or DER to begin with. */
    fun der(bytes: ByteArray): ByteArray {
        val text = runCatching { bytes.decodeToString() }.getOrNull() ?: return bytes
        val begin = text.indexOf("-----BEGIN ")
        if (begin < 0) return bytes
        val labelEnd = text.indexOf("-----", begin + 11)
        require(labelEnd > 0) { "malformed PEM header" }
        val label = text.substring(begin + 11, labelEnd)
        require(!label.contains("ENCRYPTED")) {
            "encrypted PEM keys are not supported — decrypt first: openssl pkcs8 -topk8 -nocrypt -in key.pem -out pkcs8.pem"
        }
        require(label != "RSA PRIVATE KEY" && label != "EC PRIVATE KEY") {
            "$label is PKCS#1/SEC1 — convert to PKCS#8: openssl pkcs8 -topk8 -nocrypt -in key.pem -out pkcs8.pem"
        }
        val end = text.indexOf("-----END", labelEnd)
        require(end > 0) { "PEM block has no END line" }
        return Base64.getDecoder().decode(text.substring(labelEnd + 5, end).filterNot { it.isWhitespace() })
    }

    /** Every DER block in [bytes] — a PEM file may hold a chain, and a DER file holds exactly one. */
    fun all(bytes: ByteArray): List<ByteArray> {
        val text = runCatching { bytes.decodeToString() }.getOrNull() ?: return listOf(bytes)
        if (!text.contains("-----BEGIN ")) return listOf(bytes)
        val blocks = ArrayList<ByteArray>()
        var at = 0
        while (true) {
            val begin = text.indexOf("-----BEGIN ", at)
            if (begin < 0) break
            val end = text.indexOf("-----END", begin)
            require(end > 0) { "PEM block has no END line" }
            val close = text.indexOf("-----\n", end).let { if (it < 0) text.length else it + 6 }
            blocks.add(der(text.substring(begin, minOf(close, text.length)).toByteArray()))
            at = close
        }
        return blocks
    }

    /** [der] wrapped in the dashed lines, at 64 characters a line, as every tool writes it. */
    fun text(label: String, der: ByteArray): ByteArray {
        val body = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(der)
        return "-----BEGIN $label-----\n$body\n-----END $label-----\n".toByteArray()
    }

    /** DER or PEM by what the module's `encoding` option says. */
    fun encode(label: String, der: ByteArray, encoding: String?): ByteArray =
        if ((encoding ?: "PEM") == "PEM") text(label, der) else der
}
