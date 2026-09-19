package flow.cert

import flow.extension.ModuleExtension
import flow.extension.ModuleOption
import flow.extension.OptionType
import flow.pki.Keys
import flow.pki.Pem
import flow.pki.X509
import java.io.ByteArrayOutputStream
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.time.ZoneOffset
import java.time.ZonedDateTime
import javax.security.auth.x500.X500Principal

/**
 * A self-signed certificate, and the private key that goes with it.
 *
 * Flow could make keys and read a certificate out of a keystore, and had no way to make one: a key
 * pair on its own is not something a TLS server, a signing client or a truststore will take. This
 * is the root of the PKI set — Certificate Request and Certificate Authority are the other way
 * round, where somebody else vouches for the key.
 *
 * Self-signed means the issuer is the subject and the signature is its own. That is what a test
 * server, a development root or a key you pin yourself needs; it is not, and cannot be, a
 * certificate a public CA has vouched for.
 */
class CertModule : ModuleExtension {
    override val id = "flow.cert"
    override val displayName = "Certificate"
    override val version = "1.0.0"
    override val category = "pki"

    // all optional: with nothing connected it generates the key pair too, which is the common case
    override val inputs = listOf("privateKey", "publicKey", "password")
    override val outputs = listOf("certificate", "privateKey", "publicKey", "store")

    override val options = listOf(
        ModuleOption("subject", OptionType.TEXT, "CN=Flow"),
        ModuleOption("algorithm", OptionType.SELECT, "RSA", listOf("RSA", "EC")),
        ModuleOption("keySize", OptionType.NUMBER, "2048"),
        ModuleOption("hash", OptionType.SELECT, "SHA-256", listOf("SHA-256", "SHA-384", "SHA-512")),
        ModuleOption("days", OptionType.NUMBER, "365"),
        ModuleOption("altNames", OptionType.TEXT, ""),
        ModuleOption("usage", OptionType.TEXT, ""),
        ModuleOption("ca", OptionType.SELECT, "no", listOf("no", "yes")),
        ModuleOption("alias", OptionType.TEXT, "flow"),
        ModuleOption("encoding", OptionType.SELECT, "PEM", listOf("PEM", "DER")),
    )

    override fun optionalInputsFor(values: Map<String, String>) = listOf("privateKey", "publicKey", "password")

    /** The key that signs, and the password that protects the store — never logged or echoed. */
    override val sensitiveInputs = listOf("privateKey", "password")

    override val portDescriptions = mapOf(
        "_module" to
            "Make a self-signed X.509 certificate and the private key for it. Connect nothing and it " +
            "generates the key pair too; give it a key pair and it certifies that one. Self-signed " +
            "means it vouches for itself: right for a test server, a development root or a key you " +
            "pin yourself, never a substitute for a certificate from a public CA. To have another " +
            "certificate vouch for a key, use Certificate Request and Certificate Authority.",
        // one entry serves both directions: the port is called the same thing on each side, because
        // it is the same key — given to be certified, or handed back because it was generated here
        "privateKey" to
            "The private key, PKCS#8 (DER or PEM). As an input it is optional: an existing key to " +
            "certify, and then publicKey must come with it. As the output it is the key for the " +
            "certificate — generated here when none was given. Treat it as a secret.",
        "publicKey" to
            "The public key, X.509/SubjectPublicKeyInfo (DER or PEM). Optional as an input, beside " +
            "privateKey; as the output it is the certificate's key — the bytes Signature verifies with.",
        "password" to "Optional. The password for the PKCS#12 on 'store', as text. Unconnected, the store is written with an empty password.",
        "certificate" to "The certificate, PEM or DER by the 'encoding' option. This is what a client trusts and a server presents.",
        "store" to "The same key and certificate as a PKCS#12 (.p12) — what a server usually wants, and what Key Store reads back.",
    )

    override val optionDescriptions = mapOf(
        "subject" to "Who the certificate is for, as an X.500 name: CN=example.com, or CN=Flow Test,O=Flow,C=KR. Being self-signed, it is the issuer too.",
        "algorithm" to "The key to generate when none is connected. RSA is accepted everywhere; EC is far smaller and faster at the same strength.",
        "keySize" to "Bits, when generating. 2048 is the floor for RSA and 3072/4096 for longer-lived keys; EC takes 256, 384 or 521.",
        "hash" to "The digest the signature is made over. SHA-256 unless something requires more.",
        "days" to "How long it is valid, from a few minutes ago (clock skew) until this many days from now.",
        "altNames" to "Subject alternative names, comma separated: example.com, *.example.com, 127.0.0.1. TLS clients match the hostname against these, not against CN — a certificate for a hostname needs it here.",
        "usage" to "What the certificate is for, comma separated: server, client, codesigning, email, timestamping, ocspsigning. Empty leaves it unrestricted.",
        "ca" to "yes marks it a CA that may sign other certificates (basicConstraints CA:TRUE, keyCertSign) — a development root, to be used with Certificate Authority. no is an ordinary end-entity certificate.",
        "alias" to "The entry name inside the PKCS#12 on 'store'.",
        "encoding" to "PEM is the text form with -----BEGIN----- lines, which most tools and config files take. DER is the raw bytes.",
    )

    override fun process(inputs: Map<String, ByteArray?>, options: Map<String, String>): Map<String, ByteArray?> {
        val subject = X500Principal(options["subject"]?.takeIf { it.isNotBlank() } ?: "CN=Flow")
        val hash = options["hash"] ?: "SHA-256"
        val days = (options["days"] ?: "365").trim().toLong()
        require(days > 0) { "days must be 1 or more" }
        val ca = (options["ca"] ?: "no") == "yes"

        val pair = keyPair(inputs, options)
        val notBefore = ZonedDateTime.now(ZoneOffset.UTC).minusMinutes(5)

        val extensions = buildList {
            add(X509.basicConstraints(ca))
            add(X509.keyUsage(ca))
            add(X509.subjectKeyIdentifier(pair.public))
            X509.extendedKeyUsage(options["usage"].orEmpty().split(','))?.let(::add)
            X509.subjectAltNames(options["altNames"].orEmpty())?.let(::add)
        }

        val certificate = X509.certificate(
            subject = subject.encoded,
            issuer = subject.encoded, // itself: that is what self-signed means
            subjectKey = pair.public,
            signingKey = pair.private,
            issuerKey = pair.public,
            serial = X509.serial(),
            notBefore = notBefore,
            notAfter = notBefore.plusDays(days),
            hash = hash,
            extensions = extensions,
        )

        val encoding = options["encoding"]
        return mapOf(
            "certificate" to Pem.encode("CERTIFICATE", certificate.encoded, encoding),
            "privateKey" to Pem.encode("PRIVATE KEY", pair.private.encoded, encoding),
            "publicKey" to Pem.encode("PUBLIC KEY", pair.public.encoded, encoding),
            "store" to pkcs12(pair.private, certificate, options["alias"].orEmpty().ifBlank { "flow" }, inputs["password"]),
        )
    }

    /** The pair on the inputs, or a fresh one. Both keys or neither: one alone cannot sign for the other. */
    private fun keyPair(inputs: Map<String, ByteArray?>, options: Map<String, String>): KeyPair {
        val privateBytes = inputs["privateKey"]
        val publicBytes = inputs["publicKey"]
        if (privateBytes == null && publicBytes == null) {
            val algorithm = options["algorithm"] ?: "RSA"
            val keySize = (options["keySize"] ?: "2048").trim().toInt()
            return KeyPairGenerator.getInstance(algorithm).run {
                initialize(keySize, SecureRandom())
                generateKeyPair()
            }
        }
        require(privateBytes != null && publicBytes != null) {
            "a certificate needs both halves of the key pair: connect privateKey and publicKey, or neither"
        }
        return KeyPair(Keys.publicKey(publicBytes), Keys.privateKey(privateBytes))
    }

    private fun pkcs12(privateKey: PrivateKey, certificate: X509Certificate, alias: String, password: ByteArray?): ByteArray {
        val chars = (password?.decodeToString() ?: "").toCharArray()
        val store = KeyStore.getInstance("PKCS12")
        store.load(null, null)
        store.setKeyEntry(alias, privateKey, chars, arrayOf(certificate))
        return ByteArrayOutputStream().use { out -> store.store(out, chars); out.toByteArray() }
    }
}
