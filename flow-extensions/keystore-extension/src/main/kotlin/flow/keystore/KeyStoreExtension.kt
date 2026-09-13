package flow.keystore

import flow.extension.ExtensionOption
import flow.extension.ProcessorExtension
import flow.extension.OptionType
import java.io.ByteArrayInputStream
import java.security.KeyStore

/**
 * Opens a PKCS#12 or JKS key store — the form a server's key material usually ships in — and puts
 * its entry on the output ports: the private key PKCS#8-encoded on "privateKey", the leaf
 * certificate (X.509 DER) on "certificate" and that certificate's public key on "publicKey". Those
 * are the encodings flow.cipher, flow.signature and flow.keyfactory take directly.
 *
 * "store" is the file's bytes and "password" the store password. A blank "alias" takes the first
 * entry that has a private key, which is what a single-key server store has; "keyPassword" covers
 * a JKS whose key is protected separately from the store.
 */
class KeyStoreExtension : ProcessorExtension {
    override val id = "flow.keystore"
    override val displayName = "Key Store"
    override val version = "1.0.2"
    override val category = "crypto"
    override val inputs = listOf("store", "password")
    override val outputs = listOf("privateKey", "certificate", "publicKey")
    override val options = listOf(
        ExtensionOption("type", OptionType.SELECT, "PKCS12", listOf("PKCS12", "JKS")),
        ExtensionOption("alias", OptionType.TEXT, ""),
        ExtensionOption("keyPassword", OptionType.TEXT, ""),
    )

    /** the store password — never echoed back, logged, or put in an error message. */
    override val sensitiveInputs = listOf("password")

    override val portDescriptions = mapOf(
        "_module" to "Open a PKCS#12 or JKS keystore and take out what is in it. Use it when keys arrive as a .p12/.pfx/.jks rather than as raw bytes. It only reads; it does not create or modify a store.",
        "store" to "The keystore file's bytes — 'b64:' or 'hex:'.",
        "password" to "The password that opens the store, as text.",
        "privateKey" to "The private key for the chosen alias, PKCS#8 encoded.",
        "certificate" to "The certificate for that alias, DER encoded.",
        "publicKey" to "The certificate's public key, X.509 encoded.",
    )

    override val optionDescriptions = mapOf(
        "type" to "PKCS12 for .p12/.pfx, which is the portable format; JKS only for old Java-specific stores.",
        "alias" to "Which entry to take. Left empty, the first one in the store.",
        "keyPassword" to "The password on the key entry itself, when it differs from the store's.",
    )

    override fun process(inputs: Map<String, ByteArray?>, options: Map<String, String>): Map<String, ByteArray?> {
        val storeBytes = inputs["store"] ?: return outputs.associateWith { null }
        val password = (inputs["password"]?.decodeToString() ?: "").toCharArray()
        val type = options["type"] ?: "PKCS12"

        // a wrong password or type throws — the host surfaces the message as visible output
        val store = KeyStore.getInstance(type)
        ByteArrayInputStream(storeBytes).use { store.load(it, password) }

        val wanted = options["alias"].orEmpty().trim()
        val alias = if (wanted.isNotEmpty()) {
            require(store.containsAlias(wanted)) {
                "no alias '$wanted' in the store (found: ${store.aliases().toList().joinToString(", ")})"
            }
            wanted
        } else {
            store.aliases().toList().firstOrNull { store.isKeyEntry(it) }
                ?: error("the store holds no private key entry")
        }

        val keyPassword = options["keyPassword"].orEmpty().takeIf { it.isNotEmpty() }?.toCharArray() ?: password
        val key = store.getKey(alias, keyPassword)
        val certificate = store.getCertificate(alias)
        return mapOf(
            "privateKey" to key?.encoded,
            "certificate" to certificate?.encoded,
            "publicKey" to certificate?.publicKey?.encoded,
        )
    }
}
