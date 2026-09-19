package flow.pkcs12

import flow.extension.ModuleExtension
import flow.extension.ModuleOption
import flow.extension.OptionType
import flow.pki.Keys
import java.io.ByteArrayOutputStream
import java.security.KeyStore
import java.security.cert.X509Certificate

/**
 * Packs a key and its certificates into a PKCS#12 — the file a server, a browser or a phone takes.
 *
 * A private key and a certificate as two separate files is Flow's shape, not the world's: nearly
 * everything that consumes them wants one .p12 (or .pfx) with both inside, protected by a password.
 * This is that step. Key Store reads one back; this writes one.
 *
 * A chain can go in beside the key — the issued certificate first, then whoever signed it, up to
 * the root — which is what a TLS server has to present. Certificate Authority's "chain" output is
 * exactly that, in that order.
 */
class Pkcs12Module : ModuleExtension {
    override val id = "flow.pkcs12"
    override val displayName = "PKCS#12"
    override val version = "1.0.0"
    override val category = "pki"

    override val inputs = listOf("privateKey", "certificate", "chain", "password")
    override val outputs = listOf("store")

    override val options = listOf(
        ModuleOption("alias", OptionType.TEXT, "flow"),
    )

    override fun optionalInputsFor(values: Map<String, String>) = listOf("chain", "password")

    override val sensitiveInputs = listOf("privateKey", "password")

    override val portDescriptions = mapOf(
        "_module" to
            "Pack a private key and its certificate into a PKCS#12 (.p12/.pfx) — the single file most " +
            "servers and clients want, protected by a password. Key Store reads one back.",
        "privateKey" to "The private key to store, PKCS#8 (DER or PEM). Treat it as a secret.",
        "certificate" to "The certificate for that key, PEM or DER. Its public key has to be the key's.",
        "chain" to "Optional. The issuers above it, PEM — Certificate Authority's 'chain' output goes straight in here. Without it the store holds the one certificate.",
        "password" to "Optional. The password protecting the store, as text. Unconnected, it is written with an empty password, which most tools accept and no one should ship.",
        "store" to "The PKCS#12 bytes. Save it as .p12 (or .pfx — the same thing under the other name).",
    )

    override val optionDescriptions = mapOf(
        "alias" to "The entry name inside the store. Most tools take the only entry and never show this; Java's keytool shows it.",
    )

    override fun process(inputs: Map<String, ByteArray?>, options: Map<String, String>): Map<String, ByteArray?> {
        val keyBytes = inputs["privateKey"] ?: error("no private key to store")
        val certificateBytes = inputs["certificate"] ?: error("no certificate to store")

        val privateKey = Keys.privateKey(keyBytes)
        val leaf = Keys.certificate(certificateBytes)
        // the chain input may hold several, and may repeat the leaf — a PEM file written by
        // Certificate Authority starts with it
        val above = inputs["chain"]?.let { Keys.certificates(it) }.orEmpty()
            .filterNot { it.encoded.contentEquals(leaf.encoded) }
        val chain: Array<X509Certificate> = (listOf(leaf) + above).toTypedArray()

        require(leaf.publicKey.encoded.contentEquals(publicKeyOf(privateKey, leaf))) {
            "the certificate is not for this private key"
        }

        val password = (inputs["password"]?.decodeToString() ?: "").toCharArray()
        val store = KeyStore.getInstance("PKCS12")
        store.load(null, null)
        store.setKeyEntry(options["alias"].orEmpty().ifBlank { "flow" }, privateKey, password, chain)
        return mapOf("store" to ByteArrayOutputStream().use { out -> store.store(out, password); out.toByteArray() })
    }

    /**
     * Whether the key and the certificate belong together, checked by using them: sign with the
     * private key, verify with the certificate's public one.
     *
     * Comparing encodings would not do it — a private key does not carry its public half in a form
     * you can compare, and for EC it is not there at all. Returning the certificate's own key when
     * the pair checks out lets the caller compare and fail on one line.
     */
    private fun publicKeyOf(privateKey: java.security.PrivateKey, certificate: X509Certificate): ByteArray {
        val data = "flow pkcs12".encodeToByteArray()
        val algorithm = Keys.signatureAlgorithm("SHA-256", privateKey.algorithm)
        val signature = java.security.Signature.getInstance(algorithm).run {
            initSign(privateKey)
            update(data)
            sign()
        }
        val matches = runCatching {
            java.security.Signature.getInstance(algorithm).run {
                initVerify(certificate.publicKey)
                update(data)
                verify(signature)
            }
        }.getOrDefault(false)
        return if (matches) certificate.publicKey.encoded else ByteArray(0)
    }
}
