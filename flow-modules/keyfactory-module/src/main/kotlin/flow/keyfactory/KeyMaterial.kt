package flow.keyfactory

import java.io.ByteArrayInputStream
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.PublicKey
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Reads a key off a port: PEM or raw DER. A public key may also arrive as a certificate, which is
 * what a server's key material usually looks like.
 */
internal object KeyMaterial {

    fun publicKey(bytes: ByteArray, algorithm: String): PublicKey {
        val (der, label) = der(bytes)
        if (label != null && label.contains("CERTIFICATE")) return certificate(der)
        return runCatching { KeyFactory.getInstance(algorithm).generatePublic(X509EncodedKeySpec(der)) }
            .getOrElse { keyError -> runCatching { certificate(der) }.getOrElse { throw keyError } }
    }

    fun privateKey(bytes: ByteArray, algorithm: String): PrivateKey =
        KeyFactory.getInstance(algorithm).generatePrivate(PKCS8EncodedKeySpec(der(bytes).first))

    private fun certificate(der: ByteArray): PublicKey =
        CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der)).publicKey

    /** DER bytes plus the PEM label they came from (null when the input was already DER). */
    fun der(bytes: ByteArray): Pair<ByteArray, String?> {
        val text = runCatching { bytes.decodeToString() }.getOrNull() ?: return bytes to null
        val begin = text.indexOf("-----BEGIN ")
        if (begin < 0) return bytes to null
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
        val body = text.substring(labelEnd + 5, end).filterNot { it.isWhitespace() }
        return Base64.getDecoder().decode(body) to label
    }
}
