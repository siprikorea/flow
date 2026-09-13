package flow.mcp

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A failure a caller can act on, rather than a sentence about one.
 *
 * The client on the other end of this server is increasingly a model, and a model does not read an
 * exception message and think again — it retries what it did. What makes a retry different from the
 * last one is knowing *which* input was wrong and *what* to change, so that is what a failure
 * carries: a [code] to branch on, the [port] or argument at fault, and one sentence saying what to
 * do. The prose message stays too, for a person reading a transcript.
 *
 * No failure ever carries a value. A key, an IV, a password or a plaintext in an error message is a
 * secret written into a log, a transcript, and whatever the client keeps — and the whole reason
 * this server exists is to handle those. Say which port, never what was in it.
 */
internal class ToolFailure(
    val code: String,
    val hint: String,
    val port: String? = null,
    message: String = hint,
    cause: Throwable? = null,
) : Exception(message, cause) {

    fun toJson(): JsonObject = buildJsonObject {
        put("code", code)
        port?.let { put("port", it) }
        put("hint", hint)
        put("message", message ?: hint)
    }

    companion object {
        // The codes a caller may branch on. Kept few and specific: a code nobody can act on
        // differently from another is one code too many.

        /** Two arguments that cannot both be what they are — AES with an RSA padding, say. */
        const val INVALID_PORT_COMBINATION = "INVALID_PORT_COMBINATION"

        /** A required port or argument was not given at all. */
        const val MISSING_PORT = "MISSING_PORT"

        /** The bytes on a port are not the encoding the port asked for. */
        const val DECODE_FAILED = "DECODE_FAILED"

        /** A key the algorithm cannot use — 20 bytes for AES, a public key where a private one goes. */
        const val KEY_LENGTH_MISMATCH = "KEY_LENGTH_MISMATCH"

        /** An IV of the wrong size for the algorithm, or missing where one is needed. */
        const val IV_MISMATCH = "IV_MISMATCH"

        /**
         * Decryption produced nothing usable.
         *
         * The one failure that cannot be narrowed by looking at the inputs: a wrong key, a wrong
         * IV and a wrong padding all arrive here as the same exception from the JCE, because that
         * is the point of authenticated and padded modes — they refuse rather than explain. The
         * hint says which three things to check instead of pretending to know which it was.
         */
        const val DECRYPT_FAILED = "DECRYPT_FAILED"

        /** An option set to a value it does not accept. */
        const val INVALID_OPTION = "INVALID_OPTION"

        /** Named something that is not there: a flow, a module, a port. */
        const val NOT_FOUND = "NOT_FOUND"

        /** The app has to be running for this, and is not. */
        const val APP_NOT_RUNNING = "APP_NOT_RUNNING"

        /** Anything else. A caller cannot do better than report it. */
        const val INTERNAL = "INTERNAL"

        /**
         * Classifies what the JCE threw.
         *
         * These exceptions are deliberately uninformative — a padding oracle is exactly what a
         * cipher must not be — so this narrows only as far as the type honestly allows and says so.
         * Everything here is about the *type* of the exception and the *shape* of the inputs; no
         * value is read, and none is quoted.
         */
        fun fromCrypto(e: Throwable, operation: String): ToolFailure = when (e) {
            // GCM's own failure, and the only one here that means something specific: the
            // ciphertext, the tag, the AAD or the key is not what it was encrypted with. Which of
            // them it was is exactly what an authenticated mode refuses to say, so neither does this.
            is javax.crypto.AEADBadTagException -> ToolFailure(
                code = DECRYPT_FAILED,
                hint = "The authentication tag does not match. Something differs from when this was " +
                    "encrypted: the key, the nonce, the 'aad', the 'tagLength' — or the ciphertext " +
                    "itself has been altered, which is what GCM is for. Check all of them; the mode " +
                    "will not say which.",
                message = "$operation failed: the ciphertext did not authenticate",
                cause = e,
            )
            is javax.crypto.BadPaddingException -> ToolFailure(
                code = DECRYPT_FAILED,
                hint = "The key, the IV or the padding does not match the one used to encrypt. " +
                    "Check all three: the same key bytes, the same IV, and a padding the ciphertext " +
                    "was made with. For GCM it also means the same 'aad' and the same 'tagLength' — " +
                    "or that the ciphertext or its tag was altered, which is what GCM is for.",
                message = "$operation failed: the input could not be unpadded",
                cause = e,
            )
            is javax.crypto.IllegalBlockSizeException -> ToolFailure(
                code = DECRYPT_FAILED,
                port = "in",
                hint = "The input length is not a whole number of blocks. With NoPadding the input " +
                    "must be a multiple of the block size; for RSA it must be no larger than the key allows.",
                message = "$operation failed: the input is not a usable length",
                cause = e,
            )
            is java.security.InvalidKeyException -> ToolFailure(
                code = KEY_LENGTH_MISMATCH,
                port = "key",
                hint = "The key is not one this algorithm accepts — check its length (AES takes 16, " +
                    "24 or 32 bytes) and, for RSA, that it is a public key to encrypt with and a " +
                    "private key to decrypt with.",
                message = "$operation failed: the key is not usable by this algorithm",
                cause = e,
            )
            is java.security.InvalidAlgorithmParameterException -> ToolFailure(
                code = IV_MISMATCH,
                port = "iv",
                hint = "The IV is the wrong size for this algorithm — 16 bytes for AES in CBC/CFB/OFB/CTR, " +
                    "12 for GCM. Leave the port unconnected to have one generated and prepended.",
                message = "$operation failed: the IV is not the right size",
                cause = e,
            )
            is java.security.NoSuchAlgorithmException, is javax.crypto.NoSuchPaddingException -> ToolFailure(
                code = INVALID_PORT_COMBINATION,
                hint = "This runtime has no such algorithm, mode and padding together. Check the " +
                    "three against each other — RSA takes PKCS1Padding or an OAEP padding, block " +
                    "ciphers take PKCS5Padding or NoPadding.",
                message = e.message ?: "no such algorithm",
                cause = e,
            )
            // the module read its arguments and refused them; its own words are the hint, because
            // it is the only thing that knows which argument and why
            is IllegalArgumentException -> ToolFailure(
                code = INVALID_OPTION,
                hint = e.message ?: "The module refused one of these arguments.",
                message = e.message ?: "$operation refused an argument",
                cause = e,
            )
            else -> ToolFailure(
                code = INTERNAL,
                hint = "Not a failure this server knows how to narrow. The message is the runtime's own.",
                message = e.message ?: e::class.simpleName ?: "failed",
                cause = e,
            )
        }
    }
}
