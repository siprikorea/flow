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
    override val inputs = listOf("store", "password")
    override val outputs = listOf("privateKey", "certificate", "publicKey")
    override val options = listOf(
        ExtensionOption("type", OptionType.SELECT, "PKCS12", listOf("PKCS12", "JKS")),
        ExtensionOption("alias", OptionType.TEXT, ""),
        ExtensionOption("keyPassword", OptionType.TEXT, ""),
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
