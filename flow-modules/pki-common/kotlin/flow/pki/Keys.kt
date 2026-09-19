package flow.pki

import java.io.ByteArrayInputStream
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.PublicKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

/**
 * Keys and certificates off a port, whatever shape they arrive in.
 *
 * The algorithm is read out of the key rather than asked for as an option: it is written inside
 * every PKCS#8 and every SubjectPublicKeyInfo, and a module that made the user say "this is RSA"
 * would only be able to disagree with the key about it.
 */
internal object Keys {

    /** OID → the name KeyFactory and Signature know it by. */
    private val ALGORITHMS = mapOf(
        "1.2.840.113549.1.1.1" to "RSA",
        "1.2.840.10045.2.1" to "EC",
        "1.2.840.10040.4.1" to "DSA",
        "1.3.101.112" to "Ed25519",
        "1.3.101.113" to "Ed448",
    )

    /** The algorithm named inside a PKCS#8 private key or an X.509 SubjectPublicKeyInfo. */
    fun algorithmOf(der: ByteArray): String {
        val top = Asn1.parse(der)
        val algorithmId = top.children().firstOrNull { it.tag == 0x30 && it.children().firstOrNull()?.tag == 0x06 }
            ?: error("no algorithm identifier in this key")
        val oid = algorithmId[0].asOid()
        return ALGORITHMS[oid] ?: error("unsupported key algorithm $oid")
    }

    fun privateKey(bytes: ByteArray): PrivateKey {
        val der = Pem.der(bytes)
        return KeyFactory.getInstance(algorithmOf(der)).generatePrivate(PKCS8EncodedKeySpec(der))
    }

    fun publicKey(bytes: ByteArray): PublicKey {
        val der = Pem.der(bytes)
        // a public key often travels as the certificate that carries it
        if (looksLikeCertificate(der)) return certificate(der).publicKey
        return KeyFactory.getInstance(algorithmOf(der)).generatePublic(X509EncodedKeySpec(der))
    }

    fun certificate(bytes: ByteArray): X509Certificate =
        CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(Pem.der(bytes))) as X509Certificate

    /** Every certificate in [bytes]: one DER, or a PEM file holding a chain. */
    fun certificates(bytes: ByteArray): List<X509Certificate> = Pem.all(bytes).map(::certificate)

    /**
     * A certificate begins SEQUENCE { SEQUENCE { [0] version ... — a key's second element is an
     * algorithm identifier instead. Told apart by looking rather than by trying and catching, so a
     * genuinely broken key reports what is wrong with it.
     */
    private fun looksLikeCertificate(der: ByteArray): Boolean = runCatching {
        val inner = Asn1.parse(der)[0]
        inner.tag == 0x30 && inner.children().firstOrNull()?.tag == 0xA0
    }.getOrDefault(false)

    /** "SHA-256" over an RSA key → "SHA256withRSA"; EC signs with ECDSA whatever the key is called. */
    fun signatureAlgorithm(hash: String, keyAlgorithm: String): String {
        val digest = hash.replace("-", "").uppercase()
        return when (keyAlgorithm.uppercase()) {
            "ED25519" -> "Ed25519"
            "ED448" -> "Ed448"
            "EC", "ECDSA" -> "${digest}withECDSA"
            "DSA" -> "${digest}withDSA"
            else -> "${digest}withRSA"
        }
    }

    /**
     * The AlgorithmIdentifier that names [signatureAlgorithm] inside a signed structure.
     *
     * RSA carries an explicit NULL parameter and ECDSA carries none at all. Both are what RFC 5280
     * says, and a verifier that re-encodes the field will not agree with anything else.
     */
    fun signatureAlgorithmId(signatureAlgorithm: String): ByteArray = when (signatureAlgorithm) {
        "SHA256withRSA" -> Der.seq(Der.oid("1.2.840.113549.1.1.11"), Der.nullValue())
        "SHA384withRSA" -> Der.seq(Der.oid("1.2.840.113549.1.1.12"), Der.nullValue())
        "SHA512withRSA" -> Der.seq(Der.oid("1.2.840.113549.1.1.13"), Der.nullValue())
        "SHA256withECDSA" -> Der.seq(Der.oid("1.2.840.10045.4.3.2"))
        "SHA384withECDSA" -> Der.seq(Der.oid("1.2.840.10045.4.3.3"))
        "SHA512withECDSA" -> Der.seq(Der.oid("1.2.840.10045.4.3.4"))
        "SHA256withDSA" -> Der.seq(Der.oid("2.16.840.1.101.3.4.3.2"))
        "Ed25519" -> Der.seq(Der.oid("1.3.101.112"))
        "Ed448" -> Der.seq(Der.oid("1.3.101.113"))
        else -> error("no signature algorithm identifier for '$signatureAlgorithm'")
    }

    /** The digest AlgorithmIdentifier CMS names its content digest with. */
    fun digestAlgorithmId(hash: String): ByteArray = when (hash.replace("-", "").uppercase()) {
        "SHA256" -> Der.seq(Der.oid("2.16.840.1.101.3.4.2.1"), Der.nullValue())
        "SHA384" -> Der.seq(Der.oid("2.16.840.1.101.3.4.2.2"), Der.nullValue())
        "SHA512" -> Der.seq(Der.oid("2.16.840.1.101.3.4.2.3"), Der.nullValue())
        else -> error("no digest algorithm identifier for '$hash'")
    }

    /** The JDK's name for [hash], as MessageDigest takes it. */
    fun digestName(hash: String): String = when (hash.replace("-", "").uppercase()) {
        "SHA256" -> "SHA-256"
        "SHA384" -> "SHA-384"
        "SHA512" -> "SHA-512"
        else -> error("unsupported digest '$hash'")
    }
}
