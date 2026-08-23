package flow.hash

import flow.extension.ExtensionOption
import flow.extension.ProcessorExtension
import flow.extension.OptionType
import java.security.MessageDigest

/** Message digest module. Outputs the lowercase hex digest of "in" as ASCII bytes. */
class HashExtension : ProcessorExtension {
    override val id = "flow.hash"
    override val displayName = "Hash"
    override val inputs = listOf("in")
    override val outputs = listOf("out")
    override val options = listOf(
        ExtensionOption("algo", OptionType.SELECT, "SHA-256", listOf("MD5", "SHA-1", "SHA-256", "SHA-512")),
    )

    override fun process(inputs: Map<String, ByteArray?>, options: Map<String, String>): Map<String, ByteArray?> {
        val data = inputs["in"] ?: return mapOf("out" to null)
        val algo = options["algo"] ?: "SHA-256"
        // an unknown algorithm throws — the host surfaces that as visible output
        val hex = MessageDigest.getInstance(algo).digest(data)
            .joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
        return mapOf("out" to hex.encodeToByteArray())
    }
}
