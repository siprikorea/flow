package flow.pki

import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.time.ZonedDateTime

/**
 * Writing a certificate.
 *
 * The JDK reads certificates and does not write them: the only thing in it that does is
 * `sun.security.x509`, which is internal, unexported, and gone whenever the JDK decides. So the
 * TBSCertificate is built here, field by field, and signed with a plain [Signature].
 *
 * Both the module that makes a self-signed certificate and the one that issues from a request come
 * through here — the difference between them is only which name goes in `issuer` and which key
 * signs, which is exactly what "certificate authority" means.
 */
internal object X509 {

    /** An extension, wrapped the way the Extensions SEQUENCE wants it. */
    fun extension(oid: String, critical: Boolean, value: ByteArray): ByteArray =
        if (critical) Der.seq(Der.oid(oid), Der.boolean(true), Der.octetString(value))
        else Der.seq(Der.oid(oid), Der.octetString(value))

    /** Whether this certificate may sign others. An empty SEQUENCE is CA:FALSE. */
    fun basicConstraints(ca: Boolean, pathLength: Int? = null): ByteArray = extension(
        "2.5.29.19", critical = true,
        value = when {
            !ca -> Der.seq()
            pathLength != null -> Der.seq(Der.boolean(true), Der.integer(pathLength))
            else -> Der.seq(Der.boolean(true))
        },
    )

    /**
     * What the key may be used for: a CA signs certificates and revocation lists, an end-entity
     * signs and encrypts. Bit 0 digitalSignature, 2 keyEncipherment, 4 keyAgreement, 5 keyCertSign,
     * 6 cRLSign.
     */
    fun keyUsage(ca: Boolean): ByteArray =
        extension("2.5.29.15", critical = true, value = Der.bitFlags(if (ca) listOf(0, 5, 6) else listOf(0, 2, 4)))

    /** What the certificate is for: TLS server, TLS client, code signing, e-mail. */
    fun extendedKeyUsage(purposes: List<String>): ByteArray? {
        val oids = purposes.mapNotNull { EXTENDED_USAGES[it.trim().lowercase()] }
        if (oids.isEmpty()) return null
        return extension("2.5.29.37", critical = false, value = Der.seq(*oids.map { Der.oid(it) }.toTypedArray()))
    }

    val EXTENDED_USAGES = mapOf(
        "server" to "1.3.6.1.5.5.7.3.1",
        "client" to "1.3.6.1.5.5.7.3.2",
        "codesigning" to "1.3.6.1.5.5.7.3.3",
        "email" to "1.3.6.1.5.5.7.3.4",
        "timestamping" to "1.3.6.1.5.5.7.3.8",
        "ocspsigning" to "1.3.6.1.5.5.7.3.9",
    )

    /**
     * The key's own identifier: the SHA-1 of its bits, as RFC 5280's first method describes.
     *
     * SHA-1 here names a key; it protects nothing, and nothing is verified with it.
     */
    fun keyIdentifier(publicKey: PublicKey): ByteArray =
        MessageDigest.getInstance("SHA-1").digest(publicKeyBits(publicKey.encoded))

    fun subjectKeyIdentifier(publicKey: PublicKey): ByteArray =
        extension("2.5.29.14", critical = false, value = Der.octetString(keyIdentifier(publicKey)))

    /** Which key signed this — how a verifier finds the issuer among many. */
    fun authorityKeyIdentifier(issuerKey: PublicKey): ByteArray =
        extension("2.5.29.35", critical = false, value = Der.seq(Der.implicit(0, keyIdentifier(issuerKey))))

    /**
     * The names a TLS client actually matches a hostname against. CN has not been enough for years.
     *
     * An address is written as an iPAddress and everything else as a dNSName: 127.0.0.1 put in as
     * a name matches nothing.
     */
    fun subjectAltNames(names: String): ByteArray? {
        val parts = names.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        return extension("2.5.29.17", critical = false, value = Der.seq(*parts.map(::generalName).toTypedArray()))
    }

    private fun generalName(name: String): ByteArray {
        val octets = name.split('.')
        val isIpv4 = octets.size == 4 && octets.all { it.isNotEmpty() && it.all(Char::isDigit) && it.toInt() in 0..255 }
        return if (isIpv4) Der.implicit(7, ByteArray(4) { octets[it].toInt().toByte() })
        else Der.implicit(2, name.toByteArray(Charsets.US_ASCII))
    }

    /** A serial number no one else will pick: random, positive, and long enough to say so. */
    fun serial(): ByteArray = ByteArray(16).also { SecureRandom().nextBytes(it) }

    /**
     * Build and sign a certificate, then read it back and check it.
     *
     * The check is not ceremony: these bytes are written by hand, and a certificate that does not
     * parse — or whose signature does not verify — is worse than an error, because it looks like a
     * certificate and fails somewhere else, hours later.
     */
    fun certificate(
        subject: ByteArray,
        issuer: ByteArray,
        subjectKey: PublicKey,
        signingKey: PrivateKey,
        issuerKey: PublicKey,
        serial: ByteArray,
        notBefore: ZonedDateTime,
        notAfter: ZonedDateTime,
        hash: String,
        extensions: List<ByteArray>,
    ): X509Certificate {
        val signatureAlgorithm = Keys.signatureAlgorithm(hash, signingKey.algorithm)
        val algorithmId = Keys.signatureAlgorithmId(signatureAlgorithm)
        val tbs = Der.seq(
            Der.explicit(0, Der.integer(2)), // v3 — anything carrying extensions has to be
            Der.integer(serial),
            algorithmId,
            issuer,
            Der.seq(Der.time(notBefore), Der.time(notAfter)),
            subject,
            subjectKey.encoded, // SubjectPublicKeyInfo, already DER from the JDK
            Der.explicit(3, Der.seq(*extensions.toTypedArray())),
        )
        val signature = Signature.getInstance(signatureAlgorithm).run {
            initSign(signingKey)
            update(tbs)
            sign()
        }
        val der = Der.seq(tbs, algorithmId, Der.bitString(signature))
        val certificate = CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(der)) as X509Certificate
        certificate.verify(issuerKey)
        return certificate
    }

    /**
     * A public key's own bits, out of its SubjectPublicKeyInfo.
     *
     * The BIT STRING is the last element of the SEQUENCE; its first content byte is the unused-bit
     * count, which is zero for a key.
     */
    fun publicKeyBits(spki: ByteArray): ByteArray {
        val bitString = Asn1.parse(spki).children().last()
        require(bitString.tag == 0x03) { "SubjectPublicKeyInfo does not end in a BIT STRING" }
        return bitString.content.copyOfRange(1, bitString.content.size)
    }
}
