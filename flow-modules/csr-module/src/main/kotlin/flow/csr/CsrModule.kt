package flow.csr

import flow.extension.ModuleExtension
import flow.extension.ModuleOption
import flow.extension.OptionType
import flow.pki.CertRequest
import flow.pki.Der
import flow.pki.Keys
import flow.pki.Pem
import flow.pki.X509
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Signature
import javax.security.auth.x500.X500Principal

/**
 * A certificate signing request: "here is my public key and the name I claim; please certify it."
 *
 * This is the half of PKI that happens on the machine that keeps the key. The private key never
 * goes anywhere — it signs the request, which proves the sender holds it, and the request is what
 * travels to the CA. Certificate Authority is the other end: it takes one of these and issues a
 * certificate.
 *
 * PKCS#10 (RFC 2986), which is what every CA and every `openssl req` means by a CSR.
 */
class CsrModule : ModuleExtension {
    override val id = "flow.csr"
    override val displayName = "Certificate Request"
    override val version = "1.0.0"
    override val category = "pki"

    override val inputs = listOf("privateKey", "publicKey")
    override val outputs = listOf("request", "privateKey", "publicKey")

    override val options = listOf(
        ModuleOption("subject", OptionType.TEXT, "CN=Flow"),
        ModuleOption("algorithm", OptionType.SELECT, "RSA", listOf("RSA", "EC")),
        ModuleOption("keySize", OptionType.NUMBER, "2048"),
        ModuleOption("hash", OptionType.SELECT, "SHA-256", listOf("SHA-256", "SHA-384", "SHA-512")),
        ModuleOption("altNames", OptionType.TEXT, ""),
        ModuleOption("encoding", OptionType.SELECT, "PEM", listOf("PEM", "DER")),
    )

    override fun optionalInputsFor(values: Map<String, String>) = listOf("privateKey", "publicKey")

    override val sensitiveInputs = listOf("privateKey")

    override val portDescriptions = mapOf(
        "_module" to
            "Make a PKCS#10 certificate signing request — what you send a CA to have a key certified. " +
            "Connect nothing and it generates the key pair too, and hands the private key back for " +
            "you to keep; the request carries only the public half. Feed the request to Certificate " +
            "Authority, or to a real CA.",
        "request" to "The signing request, PEM (-----BEGIN CERTIFICATE REQUEST-----) or DER. This is the file a CA asks for.",
        // the key ports carry the same key in either direction, so one line describes both
        "privateKey" to
            "The private key, PKCS#8 (DER or PEM). Optional as an input: the key to request a " +
            "certificate for, with publicKey beside it. As the output it is the key the request was " +
            "made for — it stays with you, the CA never sees it. Treat it as a secret.",
        "publicKey" to
            "The public key, X.509/SubjectPublicKeyInfo (DER or PEM). Optional as an input, beside " +
            "privateKey; as the output it is the key inside the request — the half that does travel.",
    )

    override val optionDescriptions = mapOf(
        "subject" to "The name being claimed, as an X.500 name: CN=example.com, or CN=Flow,O=Flow,C=KR. A public CA will replace or check most of it; a private CA usually keeps it.",
        "algorithm" to "The key to generate when none is connected. RSA is accepted everywhere; EC is smaller and faster at the same strength.",
        "keySize" to "Bits, when generating. 2048 is the floor for RSA; EC takes 256, 384 or 521.",
        "hash" to "The digest the request's own signature is made over — the proof that whoever sent it holds the private key.",
        "altNames" to "Subject alternative names to ask for, comma separated: example.com, *.example.com, 127.0.0.1. Carried as the standard extensionRequest attribute, which is what a CA reads.",
        "encoding" to "PEM is the text form every CA's upload box takes. DER is the raw bytes.",
    )

    override fun process(inputs: Map<String, ByteArray?>, options: Map<String, String>): Map<String, ByteArray?> {
        val subject = X500Principal(options["subject"]?.takeIf { it.isNotBlank() } ?: "CN=Flow")
        val hash = options["hash"] ?: "SHA-256"
        val pair = keyPair(inputs, options)

        // attributes: [0] IMPLICIT, and empty unless names were asked for. An extensionRequest
        // carries the extensions the CA is being asked to put in the certificate — SANs, in
        // practice, because that is the part a CA cannot guess.
        val attributes = X509.subjectAltNames(options["altNames"].orEmpty())?.let { san ->
            Der.tlv(
                0xA0,
                Der.seq(
                    Der.oid("1.2.840.113549.1.9.14"), // extensionRequest
                    Der.tlv(0x31, Der.seq(san)), // SET OF Extensions
                ),
            )
        } ?: Der.tlv(0xA0, ByteArray(0))

        val info = Der.seq(
            Der.integer(0), // version 0 is the only one PKCS#10 has
            subject.encoded,
            pair.public.encoded,
            attributes,
        )
        val signatureAlgorithm = Keys.signatureAlgorithm(hash, pair.private.algorithm)
        val signature = Signature.getInstance(signatureAlgorithm).run {
            initSign(pair.private)
            update(info)
            sign()
        }
        val der = Der.seq(info, Keys.signatureAlgorithmId(signatureAlgorithm), Der.bitString(signature))

        // read it back the way a CA will, and check the proof-of-possession signature: a request
        // that cannot be verified is one every CA will reject, and better refused here
        val request = CertRequest.parse(der)
        require(request.verify()) { "the request did not verify against its own key" }

        val encoding = options["encoding"]
        return mapOf(
            "request" to Pem.encode("CERTIFICATE REQUEST", der, encoding),
            "privateKey" to Pem.encode("PRIVATE KEY", pair.private.encoded, encoding),
            "publicKey" to Pem.encode("PUBLIC KEY", pair.public.encoded, encoding),
        )
    }

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
            "a request needs both halves of the key pair: connect privateKey and publicKey, or neither"
        }
        return KeyPair(Keys.publicKey(publicBytes), Keys.privateKey(privateBytes))
    }
}
