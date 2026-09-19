package flow.crl

import flow.extension.ModuleExtension
import flow.extension.ModuleOption
import flow.extension.OptionType
import flow.pki.Der
import flow.pki.Keys
import flow.pki.Pem
import flow.pki.X509
import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509CRL
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * The revocation list: which certificates this CA has taken back.
 *
 * Issuing is only half of running a CA. A certificate is valid until it expires, and the only way
 * to say "not this one, not any more" before then is to publish a CRL — signed by the same key, so
 * a verifier can trust the retraction as much as it trusted the certificate. Certificate Path
 * checks against one.
 *
 * RFC 5280's TBSCertList, written out here for the same reason as the rest: the JDK reads CRLs and
 * does not write them.
 */
class CrlModule : ModuleExtension {
    override val id = "flow.crl"
    override val displayName = "Revocation List"
    override val version = "1.0.0"
    override val category = "pki"

    override val inputs = listOf("caCertificate", "caPrivateKey", "revoked")
    override val outputs = listOf("crl")

    override val options = listOf(
        ModuleOption("serials", OptionType.TEXT, ""),
        ModuleOption("reason", OptionType.SELECT, "unspecified", REASONS.keys.toList()),
        ModuleOption("days", OptionType.NUMBER, "7"),
        ModuleOption("hash", OptionType.SELECT, "SHA-256", listOf("SHA-256", "SHA-384", "SHA-512")),
        ModuleOption("encoding", OptionType.SELECT, "PEM", listOf("PEM", "DER")),
    )

    override fun optionalInputsFor(values: Map<String, String>) = listOf("revoked")

    override val sensitiveInputs = listOf("caPrivateKey")

    override val portDescriptions = mapOf(
        "_module" to
            "Publish a certificate revocation list: the serial numbers this CA has taken back, signed " +
            "by the CA key. Give it the certificates to revoke, or their serial numbers. Certificate " +
            "Path checks a certificate against one.",
        "caCertificate" to "The CA's certificate, PEM or DER — its name is the CRL's issuer.",
        "caPrivateKey" to "The CA's private key, PKCS#8 (DER or PEM). The same key that issued the certificates; a CRL signed by anything else means nothing.",
        "revoked" to "Optional. The certificates being revoked, PEM (one or many) — their serial numbers are read out of them, which is less error-prone than copying numbers by hand.",
        "crl" to "The signed CRL, PEM (-----BEGIN X509 CRL-----) or DER. Publish it where the certificates say to look.",
    )

    override val optionDescriptions = mapOf(
        "serials" to "Serial numbers to revoke, comma separated, hex or decimal (1A2B or 6699). Added to whatever arrives on 'revoked'.",
        "reason" to "Why, recorded against every entry in this list: keyCompromise if a key leaked, superseded if it was replaced, cessationOfOperation if it is simply no longer in use.",
        "days" to "When the next list is due (nextUpdate). A verifier that sees a list older than this should treat it as stale — publish again before then.",
        "hash" to "The digest the CA signs the list with.",
        "encoding" to "PEM or the raw DER bytes.",
    )

    override fun process(inputs: Map<String, ByteArray?>, options: Map<String, String>): Map<String, ByteArray?> {
        val caCertificate = Keys.certificate(inputs["caCertificate"] ?: error("no CA certificate"))
        val caKey = Keys.privateKey(inputs["caPrivateKey"] ?: error("no CA private key"))
        require(caCertificate.basicConstraints >= 0) { "the issuing certificate is not a CA" }

        val fromCertificates = inputs["revoked"]?.let { Keys.certificates(it) }.orEmpty().map { it.serialNumber }
        val fromOption = options["serials"].orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
            .map { text ->
                // hex unless it is plainly decimal: a serial is written both ways, and 16 is 16
                // either way while 1A2B is only one of them
                if (text.startsWith("0x") || text.any { it in "abcdefABCDEF" }) BigInteger(text.removePrefix("0x"), 16)
                else BigInteger(text)
            }
        val serials = (fromCertificates + fromOption).distinct()

        val now = ZonedDateTime.now(ZoneOffset.UTC)
        val days = (options["days"] ?: "7").trim().toLong()
        require(days > 0) { "days must be 1 or more" }
        val reason = REASONS[options["reason"] ?: "unspecified"] ?: 0

        val hash = options["hash"] ?: "SHA-256"
        val signatureAlgorithm = Keys.signatureAlgorithm(hash, caKey.algorithm)
        val algorithmId = Keys.signatureAlgorithmId(signatureAlgorithm)

        val entries = serials.map { serial ->
            Der.seq(
                Der.integer(serial.toByteArray()),
                Der.time(now),
                // the reason, as an extension on the entry — where a verifier looks for it
                Der.seq(X509.extension("2.5.29.21", critical = false, value = Der.tlv(0x0A, byteArrayOf(reason.toByte())))),
            )
        }

        val tbs = Der.seq(
            *buildList {
                add(Der.integer(1)) // v2 — a CRL with extensions has to be
                add(algorithmId)
                add(caCertificate.subjectX500Principal.encoded)
                add(Der.time(now))
                add(Der.time(now.plusDays(days)))
                if (entries.isNotEmpty()) add(Der.seq(*entries.toTypedArray()))
                // crlNumber, and which key signed: the two extensions every published list carries
                add(
                    Der.explicit(
                        0,
                        Der.seq(
                            X509.extension("2.5.29.20", critical = false, value = Der.integer(now.toEpochSecond().toInt())),
                            X509.authorityKeyIdentifier(caCertificate.publicKey),
                        ),
                    ),
                )
            }.toTypedArray(),
        )

        val signature = Signature.getInstance(signatureAlgorithm).run {
            initSign(caKey)
            update(tbs)
            sign()
        }
        val der = Der.seq(tbs, algorithmId, Der.bitString(signature))

        // read it back and check it against the CA's key, the same as a certificate: hand-written
        // bytes that no one has parsed are not a CRL, they are a hope
        val crl = CertificateFactory.getInstance("X.509")
            .generateCRL(ByteArrayInputStream(der)) as X509CRL
        crl.verify(caCertificate.publicKey)

        return mapOf("crl" to Pem.encode("X509 CRL", der, options["encoding"]))
    }

    internal companion object {
        /** RFC 5280's CRLReason, by the name a person would pick it by. */
        val REASONS = linkedMapOf(
            "unspecified" to 0,
            "keyCompromise" to 1,
            "caCompromise" to 2,
            "affiliationChanged" to 3,
            "superseded" to 4,
            "cessationOfOperation" to 5,
            "certificateHold" to 6,
        )
    }
}
