package flow.modules

import flow.extension.ModuleExtension
import java.util.ServiceLoader
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * AES-GCM: the mode that says when a ciphertext has been changed.
 *
 * It was in the list of modes and could not be used. GCM does not pad, the JCE has no
 * "AES/GCM/PKCS5Padding" transformation, and padding defaults to PKCS5Padding — so choosing GCM
 * and pressing run threw NoSuchAlgorithmException before anything was encrypted. Nothing tested
 * it, so nothing said so.
 *
 * What GCM is for is the rest of these: a ciphertext that has been altered fails instead of
 * decrypting to rubbish, and the 'aad' port extends that promise to context that travels in the
 * clear — a message id, a filename — so a valid ciphertext replayed somewhere else is refused too.
 */
class GcmTest {

    private val cipher: ModuleExtension =
        ServiceLoader.load(ModuleExtension::class.java).first { it.id == "flow.cipher" }

    private val key = ByteArray(16) { it.toByte() }
    private val plaintext = "transfer 100 to 7781".encodeToByteArray()

    /** Runs it the way a caller must: options resolved through optionsFor, as the host does. */
    private fun run(vararg args: Pair<String, Any?>): ByteArray? {
        val given = args.toMap()
        val options = (given.filterValues { it is String } as Map<String, String>)
            .let { g -> cipher.optionsFor(g).associate { it.name to (g[it.name] ?: it.default) } + g }
        val inputs = args.filter { it.second is ByteArray? && it.second !is String }
            .associate { it.first to it.second as ByteArray? }
        return cipher.process(inputs, options)["out"]
    }

    private fun encrypt(vararg args: Pair<String, Any?>) =
        run("operation" to "encrypt", "algorithm" to "AES", "mode" to "GCM", "key" to key, *args)!!

    private fun decrypt(vararg args: Pair<String, Any?>) =
        run("operation" to "decrypt", "algorithm" to "AES", "mode" to "GCM", "key" to key, *args)

    @Test
    fun `GCM works at its defaults, which it did not before`() {
        // the whole defect: no padding named, mode GCM, and it threw
        val ct = encrypt("in" to plaintext)
        assertContentEquals(plaintext, decrypt("in" to ct))
        // the nonce is prepended, as with every other mode that needs one
        assertEquals(12 + plaintext.size + 16, ct.size, "nonce + ciphertext + 128-bit tag")
    }

    @Test
    fun `padding is not a choice in GCM, because there is only one`() {
        val padding = cipher.optionsFor(mapOf("algorithm" to "AES", "mode" to "GCM")).single { it.name == "padding" }
        assertEquals(listOf("NoPadding"), padding.choices)
        assertEquals("NoPadding", padding.default)
        // and one left over from a flow saved before this is refused, rather than throwing about
        // an algorithm nobody asked for
        val failure = runCatching {
            cipher.process(
                mapOf("in" to plaintext, "key" to key),
                mapOf("operation" to "encrypt", "algorithm" to "AES", "mode" to "GCM", "padding" to "PKCS5Padding"),
            )
        }.exceptionOrNull()
        assertTrue(failure?.message?.contains("GCM does not pad") == true, "${failure?.message}")
    }

    @Test
    fun `a ciphertext that has been altered does not decrypt`() {
        val ct = encrypt("in" to plaintext)
        ct[ct.size - 3] = (ct[ct.size - 3].toInt() xor 1).toByte()
        val failure = runCatching { decrypt("in" to ct) }.exceptionOrNull()
        assertTrue(failure != null, "a tampered ciphertext decrypted")
        assertTrue(failure is javax.crypto.AEADBadTagException, "${failure!!::class.simpleName}")
    }

    /* ───────── the AAD ───────── */

    @Test
    fun `aad is authenticated without being encrypted, and is needed again to decrypt`() {
        val context = "message-4471".encodeToByteArray()
        val ct = encrypt("in" to plaintext, "aad" to context)
        assertContentEquals(plaintext, decrypt("in" to ct, "aad" to context))
        // it is not in the ciphertext — that is the point of it being additional
        assertEquals(12 + plaintext.size + 16, ct.size)
    }

    @Test
    fun `the same ciphertext under a different aad is refused`() {
        val ct = encrypt("in" to plaintext, "aad" to "message-4471".encodeToByteArray())
        // a valid ciphertext replayed in another context: this is what aad exists to stop
        assertTrue(
            runCatching { decrypt("in" to ct, "aad" to "message-4472".encodeToByteArray()) }.exceptionOrNull() != null,
            "a ciphertext bound to one context decrypted under another",
        )
        assertTrue(
            runCatching { decrypt("in" to ct) }.exceptionOrNull() != null,
            "a ciphertext bound to a context decrypted with no context at all",
        )
    }

    @Test
    fun `an empty aad is the same as none, so an unconnected port is not a different ciphertext`() {
        val ct = encrypt("in" to plaintext, "aad" to ByteArray(0))
        assertContentEquals(plaintext, decrypt("in" to ct))
    }

    @Test
    fun `the aad port is there for GCM and nowhere else`() {
        assertTrue("aad" in cipher.inputsFor(mapOf("algorithm" to "AES", "mode" to "GCM")))
        assertTrue("aad" !in cipher.inputsFor(mapOf("algorithm" to "AES", "mode" to "CBC")))
        assertTrue("aad" !in cipher.inputsFor(mapOf("algorithm" to "RSA")))
        // and it may always be left out
        assertTrue("aad" in cipher.optionalInputsFor(mapOf("mode" to "GCM")))
    }

    /* ───────── the tag ───────── */

    @Test
    fun `a shorter tag is shorter, and decrypts only at the same length`() {
        val ct = encrypt("in" to plaintext, "tagLength" to "96")
        assertEquals(12 + plaintext.size + 12, ct.size, "the tag was not 96 bits")
        assertContentEquals(plaintext, decrypt("in" to ct, "tagLength" to "96"))
        assertTrue(
            runCatching { decrypt("in" to ct, "tagLength" to "128") }.exceptionOrNull() != null,
            "a 96-bit tag was accepted as a 128-bit one",
        )
    }

    @Test
    fun `a tag length GCM does not have says so in terms of the option`() {
        val failure = runCatching { encrypt("in" to plaintext, "tagLength" to "64") }.exceptionOrNull()
        assertTrue(failure?.message?.contains("GCM tag length") == true, "${failure?.message}")
        assertTrue(failure?.message?.contains("128") == true, "it does not say what to use: ${failure?.message}")
    }

    @Test
    fun `tagLength is offered for GCM and hidden everywhere else`() {
        assertTrue(cipher.optionsFor(mapOf("algorithm" to "AES", "mode" to "GCM")).any { it.name == "tagLength" })
        assertTrue(cipher.optionsFor(mapOf("algorithm" to "AES", "mode" to "CBC")).none { it.name == "tagLength" })
        assertTrue(cipher.optionsFor(mapOf("algorithm" to "RSA")).none { it.name == "tagLength" })
    }

    /* ───────── round trip, with an IV of the caller's own ───────── */

    @Test
    fun `a nonce given by the flow is used as it stands and not prepended`() {
        val nonce = ByteArray(12) { (it + 7).toByte() }
        val ct = encrypt("in" to plaintext, "iv" to nonce, "aad" to "ctx".encodeToByteArray())
        assertEquals(plaintext.size + 16, ct.size, "the nonce was prepended although it was given")
        assertContentEquals(
            plaintext,
            decrypt("in" to ct, "iv" to nonce, "aad" to "ctx".encodeToByteArray()),
        )
    }
}
