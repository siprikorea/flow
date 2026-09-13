package flow.hash

import flow.extension.ExtensionOption
import flow.extension.ProcessorExtension
import flow.extension.OptionType
import java.security.MessageDigest

/** Message digest module. Outputs the lowercase hex digest of "in" as ASCII bytes. */
class HashExtension : ProcessorExtension {
    override val id = "flow.hash"
    override val displayName = "Hash"
    override val version = "1.0.2"
    override val category = "crypto"
    override val inputs = listOf("in")
    override val outputs = listOf("out")
    override val options = listOf(
        ExtensionOption("algo", OptionType.SELECT, "SHA-256", listOf("MD5", "SHA-1", "SHA-256", "SHA-512")),
    )

    override val portDescriptions = mapOf(
        "_module" to "Hash bytes with a standard digest. Use it for a checksum, a fingerprint, or as one step of a signature. Not for passwords — a digest is fast by design, so use Key Factory (PBKDF2) for those — and not for authentication, where MAC is the one that takes a key.",
        "in" to "The bytes to digest. Text is taken as UTF-8; prefix with 'hex:' or 'b64:' for bytes.",
        "out" to "The digest as lowercase hex.",
    )

    override val optionDescriptions = mapOf(
        "algo" to "SHA-256 unless something else requires otherwise. MD5 and SHA-1 are broken for anything security-bearing and are here for reading old data; SHA-512 is stronger and faster on 64-bit machines.",
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
