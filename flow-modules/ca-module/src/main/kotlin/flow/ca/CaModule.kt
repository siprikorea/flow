package flow.ca

import flow.extension.ModuleExtension
import flow.extension.ModuleOption
import flow.extension.OptionType
import flow.pki.CertRequest
import flow.pki.Keys
import flow.pki.Pem
import flow.pki.X509
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * The certificate authority: takes a request and issues a certificate against a CA key.
 *
 * This is what makes the rest of the set a PKI rather than a pile of self-signed certificates — one
 * key vouches for another, and a client that trusts the CA's certificate thereby trusts everything
 * it has issued. Certificate (with ca = yes) makes the root; Certificate Request makes the request;
 * this signs it; Certificate Path checks the result.
 *
 * The request's own signature is checked before anything is issued: it is the only evidence that
 * whoever sent it holds the private key for the public key inside it.
 */
class CaModule : ModuleExtension {
    override val id = "flow.ca"
    override val displayName = "Certificate Authority"
    override val version = "1.0.0"
    override val category = "pki"

    override val inputs = listOf("request", "caCertificate", "caPrivateKey")
    override val outputs = listOf("certificate", "chain")

    override val options = listOf(
        ModuleOption("days", OptionType.NUMBER, "365"),
        ModuleOption("hash", OptionType.SELECT, "SHA-256", listOf("SHA-256", "SHA-384", "SHA-512")),
        ModuleOption("altNames", OptionType.TEXT, ""),
        ModuleOption("usage", OptionType.TEXT, ""),
        ModuleOption("ca", OptionType.SELECT, "no", listOf("no", "yes")),
        ModuleOption("encoding", OptionType.SELECT, "PEM", listOf("PEM", "DER")),
    )

    override val sensitiveInputs = listOf("caPrivateKey")

    override val portDescriptions = mapOf(
        "_module" to
            "Issue a certificate from a signing request, signed by a CA key — the authority half of " +
            "PKI. Make the CA with Certificate (ca = yes) and the request with Certificate Request. " +
            "It refuses a request whose signature does not verify, and a CA certificate that is not " +
            "marked as a CA.",
        "request" to "The PKCS#10 request to issue against, PEM or DER.",
        "caCertificate" to "The CA's own certificate, PEM or DER. Its subject becomes the issuer, and it has to be marked as a CA.",
        "caPrivateKey" to "The CA's private key, PKCS#8 (DER or PEM). This is the key the whole trust chain rests on — treat it as a secret.",
        "certificate" to "The issued certificate: the request's key and name, vouched for by the CA.",
        "chain" to "The issued certificate followed by the CA's, in PEM — the order a server sends them in and most tools expect.",
    )

    override val optionDescriptions = mapOf(
        "days" to "How long the issued certificate is valid. It is also cut short at the CA certificate's own expiry — a certificate cannot outlive the thing vouching for it.",
        "hash" to "The digest the CA signs with. SHA-256 unless something requires more.",
        "altNames" to "Names to put in the certificate, comma separated, overriding what the request asked for. Empty keeps the request's own — which is what issuing from a request usually means.",
        "usage" to "What the certificate is for, comma separated: server, client, codesigning, email, timestamping, ocspsigning. Empty leaves it unrestricted.",
        "ca" to "yes issues an intermediate CA — one that may itself sign certificates. no is an ordinary end-entity certificate.",
        "encoding" to "PEM or the raw DER bytes. 'chain' is PEM either way, because a chain is several certificates and only PEM can hold more than one.",
    )

    override fun process(inputs: Map<String, ByteArray?>, options: Map<String, String>): Map<String, ByteArray?> {
        val requestBytes = inputs["request"] ?: error("no request to issue against")
        val caCertificateBytes = inputs["caCertificate"] ?: error("no CA certificate")
        val caKeyBytes = inputs["caPrivateKey"] ?: error("no CA private key")

        val request = CertRequest.parse(requestBytes)
        require(request.verify()) {
            "the request's signature does not verify — whoever sent it has not shown they hold the key"
        }

        val caCertificate = Keys.certificate(caCertificateBytes)
        require(caCertificate.basicConstraints >= 0) {
            "the issuing certificate is not a CA (basicConstraints CA:FALSE) — make one with Certificate, ca = yes"
        }
        val caKey = Keys.privateKey(caKeyBytes)
        require(caCertificate.publicKey.algorithm == caKey.algorithm) {
            "the CA certificate and the CA private key are not a pair"
        }

        val days = (options["days"] ?: "365").trim().toLong()
        require(days > 0) { "days must be 1 or more" }
        val notBefore = ZonedDateTime.now(ZoneOffset.UTC).minusMinutes(5)
        // nothing may outlive what vouches for it: a certificate valid past its issuer's expiry is
        // one every verifier rejects, on the day it matters and not before
        val caExpiry = ZonedDateTime.ofInstant(caCertificate.notAfter.toInstant(), ZoneOffset.UTC)
        val notAfter = minOf(notBefore.plusDays(days), caExpiry)
        require(notAfter.isAfter(notBefore)) { "the CA certificate has expired" }

        val ca = (options["ca"] ?: "no") == "yes"
        val asked = options["altNames"].orEmpty()
        val extensions = buildList {
            add(X509.basicConstraints(ca))
            add(X509.keyUsage(ca))
            add(X509.subjectKeyIdentifier(request.publicKey))
            add(X509.authorityKeyIdentifier(caCertificate.publicKey))
            X509.extendedKeyUsage(options["usage"].orEmpty().split(','))?.let(::add)
            if (asked.isBlank()) {
                // what the request asked for, carried through as it was encoded
                addAll(request.requestedExtensions.filter { isSubjectAltName(it) })
            } else {
                X509.subjectAltNames(asked)?.let(::add)
            }
        }

        val certificate = X509.certificate(
            subject = request.subject,
            issuer = caCertificate.subjectX500Principal.encoded,
            subjectKey = request.publicKey,
            signingKey = caKey,
            issuerKey = caCertificate.publicKey,
            serial = X509.serial(),
            notBefore = notBefore,
            notAfter = notAfter,
            hash = options["hash"] ?: "SHA-256",
            extensions = extensions,
        )

        val chain = Pem.text("CERTIFICATE", certificate.encoded) + Pem.text("CERTIFICATE", caCertificate.encoded)
        return mapOf(
            "certificate" to Pem.encode("CERTIFICATE", certificate.encoded, options["encoding"]),
            "chain" to chain,
        )
    }

    /** Only the subjectAltName is carried over from a request: the rest is the CA's to decide. */
    private fun isSubjectAltName(extension: ByteArray): Boolean = runCatching {
        flow.pki.Asn1.parse(extension)[0].asOid() == "2.5.29.17"
    }.getOrDefault(false)
}
