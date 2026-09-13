package flow.signature

import flow.extension.ModuleOption
import flow.extension.ModuleExtension
import flow.extension.OptionType
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

/**
 * Digital signature module: sign "in" with a PKCS#8-encoded private key, or verify "in" against a
 * "signature" input using an X.509-encoded public key. Both key encodings are what
 * java.security.KeyPair.getPrivate()/getPublic().encoded already produce — e.g. the flow.keypairgen
 * module's raw output plugs straight into "key" here.
 */
class SignatureModule : ModuleExtension {
    override val id = "flow.signature"
    override val displayName = "Signature"
    override val version = "1.0.2"
    override val category = "crypto"
    override val inputs = listOf("in", "key", "signature") // full set; see inputsFor for the per-operation set
    override val outputs = listOf("out")
    override val options = listOf(
        ModuleOption("operation", OptionType.SELECT, "sign", listOf("sign", "verify")),
        ModuleOption(
            "algorithm", OptionType.SELECT, "SHA256withRSA",
            listOf(
                "SHA1withRSA", "SHA256withRSA", "SHA384withRSA", "SHA512withRSA",
                "SHA1withDSA", "SHA256withDSA",
                "SHA1withECDSA", "SHA256withECDSA", "SHA384withECDSA", "SHA512withECDSA",
            ),
        ),
    )

    // "signature" is only meaningful for verify (the value being checked); sign produces it, doesn't consume it
    override fun inputsFor(options: Map<String, String>): List<String> =
        if (options["operation"] == "verify") listOf("in", "key", "signature") else listOf("in", "key")

    // the key algorithm KeyFactory needs is encoded in the tail of the signature algorithm name
    private fun keyAlgoFor(signatureAlgorithm: String): String = when {
        signatureAlgorithm.endsWith("ECDSA") -> "EC"
        signatureAlgorithm.endsWith("RSA") -> "RSA"
        signatureAlgorithm.endsWith("DSA") -> "DSA"
        else -> error("cannot determine key algorithm for '$signatureAlgorithm'")
    }

    /** the signing or verifying key — never echoed back, logged, or put in an error message. */
    override val sensitiveInputs = listOf("key")

    override val portDescriptions = mapOf(
        "_module" to "Sign bytes with a private key, or verify a signature with a public one. Use it when the verifier must not be able to produce the signature themselves; use MAC when both sides share a secret and that is acceptable.",
        "in" to "The bytes that were, or are to be, signed. Text is taken as UTF-8; prefix with 'hex:' or 'b64:' for bytes.",
        "key" to "To sign, the private key (PKCS#8). To verify, the public key (X.509) or a certificate. Both are binary: give them as 'hex:' or 'b64:' bytes, which is what Key Pair Generator and Key Store produce.",
        "signature" to "To verify: the signature to check, 'hex:' or 'b64:'. Not used when signing.",
        "out" to "The signature when signing; when verifying, whether it matched.",
    )

    override val optionDescriptions = mapOf(
        "operation" to "sign produces a signature from 'in' and a private key; verify checks one against a public key and reports the result.",
        "algorithm" to "Must match the key: ...withRSA for an RSA key, ...withECDSA for an EC key, ...withDSA for DSA. SHA256 or better for anything new.",
    )

    override fun process(inputs: Map<String, ByteArray?>, options: Map<String, String>): Map<String, ByteArray?> {
        val data = inputs["in"] ?: return mapOf("out" to null)
        val key = inputs["key"] ?: return mapOf("out" to null)
        val algorithm = options["algorithm"] ?: "SHA256withRSA"
        val sign = (options["operation"] ?: "sign") == "sign"
        val keyAlgo = keyAlgoFor(algorithm)
        val keyFactory = KeyFactory.getInstance(keyAlgo)
        val sig = Signature.getInstance(algorithm)

        // let failures (bad key encoding, wrong key type for the algorithm, missing signature
        // input on verify) propagate — the host surfaces the exception message as visible output
        val out = if (sign) {
            sig.initSign(keyFactory.generatePrivate(PKCS8EncodedKeySpec(key)))
            sig.update(data)
            sig.sign()
        } else {
            val signature = inputs["signature"] ?: error("verify requires a 'signature' input")
            sig.initVerify(keyFactory.generatePublic(X509EncodedKeySpec(key)))
            sig.update(data)
            (if (sig.verify(signature)) "true" else "false").encodeToByteArray()
        }
        return mapOf("out" to out)
    }
}
