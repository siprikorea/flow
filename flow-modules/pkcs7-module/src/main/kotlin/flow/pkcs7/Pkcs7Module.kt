package flow.pkcs7

import flow.extension.ModuleExtension
import flow.extension.ModuleOption
import flow.extension.OptionType
import flow.pki.Der
import flow.pki.Keys
import flow.pki.Pem
import java.security.MessageDigest
import java.security.Signature
import java.security.cert.X509Certificate
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * PKCS#7 / CMS: a signature with the signer's certificate inside it.
 *
 * The Signature module produces raw signature bytes, which say nothing about who made them —
 * whoever verifies has to already have the right public key and know to use it. A PKCS#7 blob
 * carries the signature, the certificate chain that identifies the signer, the digest algorithm and
 * the signing time together, which is what a receiver needs to check it without being told
 * anything first. It is what a signed e-mail, a signed document, a signed installer and most
 * signature-bearing file formats contain.
 *
 * Written out by hand (RFC 5652): the JDK reads CMS only through internal classes and writes none.
 *
 * "certs" produces the other common PKCS#7 — a bundle of certificates and no signature at all,
 * which is what a .p7b file is and how chains are handed around.
 */
class Pkcs7Module : ModuleExtension {
    override val id = "flow.pkcs7"
    override val displayName = "PKCS#7"
    override val version = "1.0.0"
    override val category = "pki"

    override val inputs = listOf("data", "certificate", "privateKey", "chain")
    override val outputs = listOf("pkcs7")

    override val options = listOf(
        ModuleOption("mode", OptionType.SELECT, "sign", listOf("sign", "certs")),
        ModuleOption("hash", OptionType.SELECT, "SHA-256", listOf("SHA-256", "SHA-384", "SHA-512")),
        ModuleOption("detached", OptionType.SELECT, "no", listOf("no", "yes")),
        ModuleOption("encoding", OptionType.SELECT, "PEM", listOf("PEM", "DER")),
    )

    override fun inputsFor(options: Map<String, String>): List<String> =
        if ((options["mode"] ?: "sign") == "certs") listOf("certificate", "chain")
        else inputs

    override fun optionsFor(values: Map<String, String>): List<ModuleOption> =
        if ((values["mode"] ?: "sign") == "certs") options.filterNot { it.name == "hash" || it.name == "detached" }
        else options

    override fun optionalInputsFor(values: Map<String, String>) = listOf("chain")

    override val sensitiveInputs = listOf("privateKey")

    override val portDescriptions = mapOf(
        "_module" to
            "Make a PKCS#7/CMS SignedData: the signature, the signer's certificate and the signing " +
            "time in one blob, so whoever receives it can check it without being handed the key " +
            "separately. Mode 'certs' makes the other kind — a .p7b bundle of certificates with no " +
            "signature, which is how chains are distributed.",
        "data" to "What is being signed, as the exact bytes — text as text, binary with 'hex:' or 'b64:'. With detached = no it is carried inside the blob; with yes only its digest is.",
        "certificate" to "The signer's certificate, PEM or DER — it identifies the signature and carries the key that verifies it. In 'certs' mode, the first certificate of the bundle.",
        "privateKey" to "The signer's private key, PKCS#8 (DER or PEM). It has to be the certificate's key.",
        "chain" to "Optional. Further certificates to include, PEM — the issuers above the signer, so a receiver can build the path.",
        "pkcs7" to "The PKCS#7 blob, PEM (-----BEGIN PKCS7-----) or DER. Save a signature as .p7s or .p7m, a bundle as .p7b.",
    )

    override val optionDescriptions = mapOf(
        "mode" to "sign makes a signature over the data. certs makes a certificate bundle (.p7b) and signs nothing.",
        "hash" to "The digest the signature is made over. SHA-256 unless something requires more.",
        "detached" to
            "yes leaves the data out of the blob — the signature travels beside the file it signs, " +
            "which is what a .p7s detached signature is. no keeps a copy inside, so the blob stands " +
            "alone. What is signed is the exact bytes, so verifying a detached one with openssl needs " +
            "-binary: without it openssl rewrites line endings first and the digest no longer matches.",
        "encoding" to "PEM is the text form; DER is what most tools that read .p7s/.p7b expect.",
    )

    override fun process(inputs: Map<String, ByteArray?>, options: Map<String, String>): Map<String, ByteArray?> {
        val mode = options["mode"] ?: "sign"
        val certificateBytes = inputs["certificate"] ?: error("no signer certificate")
        val certificates = Keys.certificates(certificateBytes) +
            inputs["chain"]?.let { Keys.certificates(it) }.orEmpty()
        val encoding = options["encoding"]

        if (mode == "certs") {
            // a bundle: SignedData with no signers and no content, which is all a .p7b is
            val signedData = Der.seq(
                Der.integer(1),
                Der.tlv(0x31, ByteArray(0)), // no digest algorithms — nothing was digested
                Der.seq(Der.oid(DATA)),
                Der.tlv(0xA0, Der.concat(*certificates.map { it.encoded }.toTypedArray())),
                Der.tlv(0x31, ByteArray(0)), // no signers
            )
            return mapOf("pkcs7" to Pem.encode("PKCS7", contentInfo(signedData), encoding))
        }

        val data = inputs["data"] ?: error("no data to sign")
        val keyBytes = inputs["privateKey"] ?: error("no signing key")
        val signer = certificates.first()
        val privateKey = Keys.privateKey(keyBytes)
        val hash = options["hash"] ?: "SHA-256"
        val detached = (options["detached"] ?: "no") == "yes"

        val digest = MessageDigest.getInstance(Keys.digestName(hash)).digest(data)

        // The signature is over the signed attributes, not over the content: that is what binds the
        // content type and the signing time to the signature rather than leaving them beside it.
        // They are signed as a SET (tag 0x31) and carried as [0] IMPLICIT — the one place in CMS
        // where the bytes signed and the bytes sent deliberately differ.
        val attributes = listOf(
            attribute(CONTENT_TYPE, Der.oid(DATA)),
            attribute(SIGNING_TIME, Der.time(ZonedDateTime.now(ZoneOffset.UTC))),
            attribute(MESSAGE_DIGEST, Der.octetString(digest)),
        )
        val signedAttrsForSigning = Der.tlv(0x31, Der.concat(*attributes.toTypedArray()))
        val signedAttrs = Der.tlv(0xA0, Der.concat(*attributes.toTypedArray()))

        val signatureAlgorithm = Keys.signatureAlgorithm(hash, privateKey.algorithm)
        val signature = Signature.getInstance(signatureAlgorithm).run {
            initSign(privateKey)
            update(signedAttrsForSigning)
            sign()
        }

        val signerInfo = Der.seq(
            Der.integer(1), // version 1: the signer is named by issuer and serial number
            issuerAndSerial(signer),
            Keys.digestAlgorithmId(hash),
            signedAttrs,
            signerAlgorithmId(privateKey.algorithm, signatureAlgorithm),
            Der.octetString(signature),
        )

        val encapsulated = if (detached) Der.seq(Der.oid(DATA))
        else Der.seq(Der.oid(DATA), Der.explicit(0, Der.octetString(data)))

        val signedData = Der.seq(
            Der.integer(1),
            Der.tlv(0x31, Keys.digestAlgorithmId(hash)),
            encapsulated,
            Der.tlv(0xA0, Der.concat(*certificates.map { it.encoded }.toTypedArray())),
            Der.tlv(0x31, signerInfo),
        )
        return mapOf("pkcs7" to Pem.encode("PKCS7", contentInfo(signedData), encoding))
    }

    /** ContentInfo ::= SEQUENCE { contentType OID, [0] EXPLICIT content } */
    private fun contentInfo(signedData: ByteArray): ByteArray =
        Der.seq(Der.oid(SIGNED_DATA), Der.explicit(0, signedData))

    private fun attribute(oid: String, value: ByteArray): ByteArray =
        Der.seq(Der.oid(oid), Der.tlv(0x31, value))

    /** IssuerAndSerialNumber: how CMS names a signer — the issuer's name plus the serial. */
    private fun issuerAndSerial(certificate: X509Certificate): ByteArray =
        Der.seq(certificate.issuerX500Principal.encoded, Der.integer(certificate.serialNumber.toByteArray()))

    /**
     * What a SignerInfo calls the signature algorithm.
     *
     * RSA is named as plain rsaEncryption: the digest is already named in its own field, and the
     * signature is PKCS#1 over a DigestInfo, which is exactly what that OID means. ECDSA names the
     * digest with it, because there it is part of the algorithm.
     */
    private fun signerAlgorithmId(keyAlgorithm: String, signatureAlgorithm: String): ByteArray =
        if (keyAlgorithm.uppercase() == "RSA") Der.seq(Der.oid("1.2.840.113549.1.1.1"), Der.nullValue())
        else Keys.signatureAlgorithmId(signatureAlgorithm)

    private companion object {
        const val SIGNED_DATA = "1.2.840.113549.1.7.2"
        const val DATA = "1.2.840.113549.1.7.1"
        const val CONTENT_TYPE = "1.2.840.113549.1.9.3"
        const val MESSAGE_DIGEST = "1.2.840.113549.1.9.4"
        const val SIGNING_TIME = "1.2.840.113549.1.9.5"
    }
}
