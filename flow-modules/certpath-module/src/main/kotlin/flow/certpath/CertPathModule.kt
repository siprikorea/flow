package flow.certpath

import flow.extension.ModuleExtension
import flow.extension.ModuleOption
import flow.extension.OptionType
import flow.pki.Keys
import java.io.ByteArrayInputStream
import java.security.cert.CertPathValidator
import java.security.cert.CertificateFactory
import java.security.cert.PKIXParameters
import java.security.cert.TrustAnchor
import java.security.cert.X509CRL
import java.security.cert.X509Certificate
import java.util.Date

/**
 * The question everything else in the set exists to answer: is this certificate any good?
 *
 * A certificate proves nothing on its own. It has to chain to something already trusted, every
 * signature along the way has to verify, nothing may have expired, the intermediates have to be
 * allowed to have signed, and nothing may have been revoked. That is what a TLS client does before
 * it says a word, and it is what this does — through the JDK's own PKIX validator, so the answer is
 * the one a real client would give rather than a second opinion written here.
 */
class CertPathModule : ModuleExtension {
    override val id = "flow.certpath"
    override val displayName = "Certificate Path"
    override val version = "1.0.0"
    override val category = "pki"

    override val inputs = listOf("certificate", "chain", "trusted", "crl")
    override val outputs = listOf("valid", "problem", "certificate")

    override val options = listOf(
        ModuleOption("date", OptionType.TEXT, ""),
        ModuleOption("onFailure", OptionType.SELECT, "report", listOf("report", "fail")),
    )

    override fun optionalInputsFor(values: Map<String, String>) = listOf("chain", "crl")

    override val portDescriptions = mapOf(
        "_module" to
            "Check a certificate the way a client would: chain it to something you trust, verify every " +
            "signature, check the dates, the CA flags and — given a CRL — whether it has been revoked. " +
            "'valid' says yes or no; 'problem' says what was wrong with it.",
        // in and out are the same port name: what came in, checked, and passed on
        "certificate" to
            "The certificate to check, PEM or DER — and, on the output side, that same certificate " +
            "passed through, so a valid one can go straight on to whatever uses it.",
        "chain" to "Optional. The intermediates between it and the trusted root, PEM. Certificate Authority's 'chain' output goes straight in here.",
        "trusted" to "What you trust: one or more certificates, PEM. A root CA, or the certificate itself for a pinned self-signed one.",
        "crl" to "Optional. A revocation list from the issuing CA, PEM or DER. Given one, revocation is checked; without it, it is not checked at all.",
        "valid" to "'true' or 'false', as text — connect it to a Branch to take a different path on a bad certificate.",
        "problem" to "Empty when it is valid; otherwise what failed — an expiry, a broken signature, a missing issuer, a revocation.",
    )

    override val optionDescriptions = mapOf(
        "date" to "Check as at this moment rather than now — 2026-01-01T00:00:00Z. For asking whether something was valid then, or will be.",
        "onFailure" to "report puts the answer on the ports and leaves the flow running, which is what a check usually wants. fail stops the flow with the problem as the error.",
    )

    override fun process(inputs: Map<String, ByteArray?>, options: Map<String, String>): Map<String, ByteArray?> {
        val certificateBytes = inputs["certificate"] ?: error("no certificate to check")
        val certificate = Keys.certificate(certificateBytes)
        val trustedBytes = inputs["trusted"] ?: error("nothing to trust: give it a root certificate")
        val trusted = Keys.certificates(trustedBytes)
        require(trusted.isNotEmpty()) { "the trusted input held no certificate" }

        val chain = inputs["chain"]?.let { Keys.certificates(it) }.orEmpty()
        val crls = inputs["crl"]?.let { crls(it) }.orEmpty()
        val at = options["date"].orEmpty().trim().takeIf { it.isNotEmpty() }
            ?.let { Date.from(java.time.Instant.parse(it)) } ?: Date()

        val problem = validate(certificate, chain, trusted, crls, at)
        if (problem != null && (options["onFailure"] ?: "report") == "fail") error(problem)

        return mapOf(
            "valid" to (problem == null).toString().encodeToByteArray(),
            "problem" to (problem ?: "").encodeToByteArray(),
            "certificate" to certificateBytes,
        )
    }

    /** Null when it checks out; otherwise what a client would have refused it for. */
    private fun validate(
        certificate: X509Certificate,
        chain: List<X509Certificate>,
        trusted: List<X509Certificate>,
        crls: List<X509CRL>,
        at: Date,
    ): String? = runCatching {
        val factory = CertificateFactory.getInstance("X.509")
        // The anchors are the trusted certificates; the path is everything between, and never
        // includes an anchor — PKIX refuses a path that ends in one.
        //
        // The chain input usually starts with the certificate itself (Certificate Authority writes
        // it that way, and so does every server), so the same certificate arrives twice; leaving
        // the repeat in makes PKIX read it as its own issuer and refuse it for not being a CA.
        val anchors = trusted.map { TrustAnchor(it, null) }.toSet()
        val ordered = (listOf(certificate) + chain)
            .distinctBy { it.encoded.toList() }
            .filterNot { candidate -> trusted.any { it.encoded.contentEquals(candidate.encoded) } }
        val path = factory.generateCertPath(ordered)
        if (path.certificates.isEmpty()) {
            // the certificate is itself one of the trusted ones: pinned, and trusted by being so.
            // PKIX has nothing to validate, but the dates still have to hold.
            certificate.checkValidity(at)
            return@runCatching null
        }
        val parameters = PKIXParameters(anchors).apply {
            date = at
            isRevocationEnabled = crls.isNotEmpty()
            if (crls.isNotEmpty()) {
                addCertStore(
                    java.security.cert.CertStore.getInstance(
                        "Collection",
                        java.security.cert.CollectionCertStoreParameters(crls),
                    ),
                )
            }
        }
        CertPathValidator.getInstance("PKIX").validate(path, parameters)
        null
    }.getOrElse { failure -> failure.message ?: failure::class.simpleName ?: "the certificate did not validate" }

    private fun crls(bytes: ByteArray): List<X509CRL> {
        val factory = CertificateFactory.getInstance("X.509")
        return flow.pki.Pem.all(bytes).map { factory.generateCRL(ByteArrayInputStream(it)) as X509CRL }
    }
}
