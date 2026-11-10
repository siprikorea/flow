package flow.signature

import flow.extension.ModuleOption
import flow.extension.ModuleExtension
import flow.extension.OptionType
import flow.pki.Keys
import java.security.Signature

/**
 * Digital signature module: sign "in" with a PKCS#8-encoded private key, or verify "in" against a
 * "signature" input using an X.509-encoded public key or the certificate that carries one.
 *
 * Keys are taken as DER or PEM. DER is what java.security.KeyPair.getPrivate()/getPublic().encoded
 * produce, so flow.keypairgen's raw output plugs straight into "key"; PEM is what Certificate and
 * Certificate Request hand out by default, and what a key from anywhere else usually arrives as.
 */
class SignatureModule : ModuleExtension {
    override val id = "flow.signature"
    override val displayName = "Signature"
    override val version = "1.1.0"
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

    /** the signing or verifying key — never echoed back, logged, or put in an error message. */
    override val sensitiveInputs = listOf("key")

    override val portDescriptions = mapOf(
        "_module" to "Sign bytes with a private key, or verify a signature with a public one. Use it when the verifier must not be able to produce the signature themselves; use MAC when both sides share a secret and that is acceptable.",
        "in" to "The bytes that were, or are to be, signed. Text is taken as UTF-8; prefix with 'hex:' or 'b64:' for bytes.",
        "key" to "To sign, the private key (PKCS#8). To verify, the public key (X.509) or a certificate. PEM text, or the DER bytes as 'hex:' or 'b64:' — Key Pair Generator and Key Store produce DER, Certificate produces PEM.",
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
        val sig = Signature.getInstance(algorithm)

        // let failures (bad key encoding, wrong key type for the algorithm, missing signature
        // input on verify) propagate — the host surfaces the exception message as visible output.
        // What kind of key it is comes out of the key; a key the chosen algorithm cannot use is
        // refused by initSign/initVerify, which names both.
        val out = if (sign) {
            sig.initSign(Keys.privateKey(key))
            sig.update(data)
            sig.sign()
        } else {
            val signature = inputs["signature"] ?: error("verify requires a 'signature' input")
            sig.initVerify(Keys.publicKey(key))
            sig.update(data)
            (if (sig.verify(signature)) "true" else "false").encodeToByteArray()
        }
        return mapOf("out" to out)
    }
}
