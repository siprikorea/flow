package flow.pki

import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/**
 * A PKCS#10 request, taken apart.
 *
 * The JDK has no public reader for one, and a CA has to read three things out of it before it
 * issues anything: the name being claimed, the key being claimed, and whether the signature proves
 * the sender holds that key.
 *
 * ```
 * CertificationRequest ::= SEQUENCE {
 *     certificationRequestInfo SEQUENCE { version, subject, subjectPKInfo, [0] attributes },
 *     signatureAlgorithm       AlgorithmIdentifier,
 *     signature                BIT STRING }
 * ```
 */
internal class CertRequest private constructor(
    /** The subject Name exactly as it was encoded — what goes into the certificate verbatim. */
    val subject: ByteArray,
    /** The SubjectPublicKeyInfo, likewise verbatim. */
    val subjectPublicKeyInfo: ByteArray,
    /** The extensions the request asks for, each already encoded. Usually just subjectAltName. */
    val requestedExtensions: List<ByteArray>,
    private val info: ByteArray,
    private val signatureAlgorithmOid: String,
    private val signature: ByteArray,
) {
    val publicKey: PublicKey by lazy {
        java.security.KeyFactory.getInstance(Keys.algorithmOf(subjectPublicKeyInfo))
            .generatePublic(X509EncodedKeySpec(subjectPublicKeyInfo))
    }

    /** Whether the request was signed by the key it carries — the proof of possession. */
    fun verify(): Boolean = runCatching {
        Signature.getInstance(signatureName()).run {
            initVerify(publicKey)
            update(info)
            verify(signature)
        }
    }.getOrDefault(false)

    private fun signatureName(): String = SIGNATURE_NAMES[signatureAlgorithmOid]
        ?: error("unsupported request signature algorithm $signatureAlgorithmOid")

    companion object {
        private val SIGNATURE_NAMES = mapOf(
            "1.2.840.113549.1.1.11" to "SHA256withRSA",
            "1.2.840.113549.1.1.12" to "SHA384withRSA",
            "1.2.840.113549.1.1.13" to "SHA512withRSA",
            "1.2.840.10045.4.3.2" to "SHA256withECDSA",
            "1.2.840.10045.4.3.3" to "SHA384withECDSA",
            "1.2.840.10045.4.3.4" to "SHA512withECDSA",
            "1.3.101.112" to "Ed25519",
        )

        /** The extensionRequest attribute, where a CSR asks for the extensions it wants. */
        private const val EXTENSION_REQUEST = "1.2.840.113549.1.9.14"

        fun parse(bytes: ByteArray): CertRequest {
            val top = Asn1.parse(Pem.der(bytes))
            val parts = top.children()
            require(parts.size == 3) { "not a PKCS#10 request: expected three elements, found ${parts.size}" }
            val info = parts[0]
            val fields = info.children()
            require(fields.size >= 3) { "not a PKCS#10 request: its info has ${fields.size} fields" }
            val extensions = fields.getOrNull(3)
                ?.takeIf { it.tag == 0xA0 }
                ?.let { attributes -> extensionsIn(attributes) }
                .orEmpty()
            return CertRequest(
                subject = fields[1].encoded,
                subjectPublicKeyInfo = fields[2].encoded,
                requestedExtensions = extensions,
                info = info.encoded,
                signatureAlgorithmOid = parts[1][0].asOid(),
                // the first content byte of a BIT STRING is the unused-bit count, always 0 here
                signature = parts[2].content.copyOfRange(1, parts[2].content.size),
            )
        }

        private fun extensionsIn(attributes: Asn1): List<ByteArray> =
            attributes.children()
                .filter { it.isConstructed && it.children().firstOrNull()?.tag == 0x06 }
                .filter { it[0].asOid() == EXTENSION_REQUEST }
                .flatMap { attribute ->
                    // Attribute ::= SEQUENCE { type OID, values SET OF Extensions }
                    attribute.children().getOrNull(1)?.children()?.flatMap { it.children().map(Asn1::encoded) }.orEmpty()
                }
    }
}
