package flow.jwt

import flow.extension.ExtensionOption
import flow.extension.ProcessorExtension
import flow.extension.OptionType
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Splits a JWT into its three parts and checks the signature against "secret".
 *
 * The algorithm comes from the token's own `alg` header, since that is what a verifier has to
 * work from — but only after checking it against the one the caller expects, because a token
 * naming its own algorithm is exactly how "alg: none" and HMAC/RSA confusion get in. Leave
 * `expect` at "any" only when the source is already trusted.
 *
 * Header and payload come out decoded, so they can go straight into a JSON formatter or a view.
 */
class JwtExtension : ProcessorExtension {
    override val id = "flow.jwt"
    override val displayName = "JWT"
    override val version = "1.0.0"
    override val inputs = listOf("jwt", "secret")
    override val outputs = listOf("header", "payload", "signature")
    override val options = listOf(
        ExtensionOption("expect", OptionType.SELECT, "HS256", listOf("HS256", "HS384", "HS512", "any")),
    )

    /** the signing secret — never echoed back, logged, or put in an error message. */
    override val sensitiveInputs = listOf("secret")

    override fun process(inputs: Map<String, ByteArray?>, options: Map<String, String>): Map<String, ByteArray?> {
        val token = (inputs["jwt"] ?: ByteArray(0)).decodeToString().trim()
        require(token.isNotEmpty()) { "no JWT on 'jwt'" }
        val parts = token.split(".")
        require(parts.size == 3) { "a JWT has three dot-separated parts; this has ${parts.size}" }
        val (headerPart, payloadPart, signaturePart) = parts

        val header = decode(headerPart, "header")
        val payload = decode(payloadPart, "payload")
        val signature = decode(signaturePart, "signature")

        val alg = headerValue(header.decodeToString(), "alg")
            ?: error("the header has no 'alg', so there is nothing to verify against")
        val expected = options["expect"] ?: "HS256"
        require(expected == "any" || alg == expected) {
            "the token is signed with '$alg' but '$expected' was expected — refusing to verify it as " +
                "something it is not"
        }
        val mac = MAC_BY_ALG[alg]
            ?: error("'$alg' is not an algorithm this module can verify (it does the HMAC family)")

        val secret = inputs["secret"] ?: ByteArray(0)
        require(secret.isNotEmpty()) { "no secret on 'secret'" }
        val signed = "$headerPart.$payloadPart".toByteArray(Charsets.US_ASCII)
        val actual = Mac.getInstance(mac).apply { init(SecretKeySpec(secret, mac)) }.doFinal(signed)
        require(MessageDigest.isEqual(actual, signature)) { "the signature does not match the secret" }

        return mapOf("header" to header, "payload" to payload, "signature" to signature)
    }

    private fun decode(part: String, what: String): ByteArray =
        runCatching { Base64.getUrlDecoder().decode(part) }
            .getOrElse { error("the $what is not base64url") }

    /** The one header field this needs, read without pulling in a JSON parser. */
    private fun headerValue(json: String, key: String): String? =
        Regex("\"" + Regex.escape(key) + "\"\\s*:\\s*\"([^\"]*)\"").find(json)?.groupValues?.get(1)

    private companion object {
        val MAC_BY_ALG = mapOf("HS256" to "HmacSHA256", "HS384" to "HmacSHA384", "HS512" to "HmacSHA512")
    }
}
