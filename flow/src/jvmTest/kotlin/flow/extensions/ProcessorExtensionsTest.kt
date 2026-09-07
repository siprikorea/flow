package flow.extensions

import flow.extension.ProcessorExtension
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.util.ServiceLoader
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What each shipped processor actually computes.
 *
 * Published vectors where there are any — a digest, an HMAC — because an extension that computes
 * something plausible but wrong is the failure that survives every other kind of test: the flow
 * runs, bytes come out, and nothing says they are the wrong bytes. Where there is no vector, the
 * property is used instead: encrypt then decrypt, sign then verify, split then merge.
 *
 * Extensions are reached through the service loader rather than by constructing them, so this also
 * fails if one stops being registered — which is how it would reach a user, as a module that is
 * simply not in the palette.
 */
class ProcessorExtensionsTest {

    private fun ext(id: String): ProcessorExtension =
        ServiceLoader.load(ProcessorExtension::class.java).firstOrNull { it.id == id }
            ?: error("$id is not registered")

    /** Runs one, with its declared defaults underneath the options given. */
    private fun run(id: String, vararg inputs: Pair<String, ByteArray?>, options: Map<String, String> = emptyMap()): Map<String, ByteArray?> {
        val e = ext(id)
        val values = e.options.associate { it.name to it.default } + options
        return e.process(inputs.toMap(), values)
    }

    private fun text(id: String, vararg inputs: Pair<String, ByteArray?>, options: Map<String, String> = emptyMap()): String =
        run(id, *inputs, options = options)["out"]?.decodeToString().orEmpty()

    private fun bytes(hex: String): ByteArray =
        hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    /* ───────── digests and codes ───────── */

    @Test
    fun `hash computes the digest the standard says`() {
        // FIPS 180-2 / the canonical "abc" vectors
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            text("flow.hash", "in" to "abc".encodeToByteArray()),
        )
        assertEquals(
            "900150983cd24fb0d6963f7d28e17f72",
            text("flow.hash", "in" to "abc".encodeToByteArray(), options = mapOf("algo" to "MD5")),
        )
    }

    @Test
    fun `mac computes the HMAC the RFC says`() {
        // RFC 4231, test case 1
        val key = ByteArray(20) { 0x0b }
        assertEquals(
            "b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7",
            text("flow.mac", "in" to "Hi There".encodeToByteArray(), "key" to key),
        )
    }

    /* ───────── encodings ───────── */

    @Test
    fun `base64 encodes, decodes, and comes back to where it started`() {
        assertEquals("aGVsbG8=", text("flow.base64", "in" to "hello".encodeToByteArray()))
        assertEquals(
            "hello",
            text("flow.base64", "in" to "aGVsbG8=".encodeToByteArray(), options = mapOf("mode" to "decode")),
        )
        // the url variant differs exactly where a byte would encode as + or /
        val awkward = bytes("fbff")
        val standard = text("flow.base64", "in" to awkward)
        val url = text("flow.base64", "in" to awkward, options = mapOf("variant" to "url"))
        assertEquals("+/8=", standard)
        assertEquals("-_8=", url)
        // and without padding the '=' that pads the last group out is simply not there
        assertEquals("+/8", text("flow.base64", "in" to awkward, options = mapOf("padding" to "false")))
    }

    @Test
    fun `json re-indents without changing what the document says`() {
        val out = text("flow.json", "in" to """{"b":1,"a":[2,3]}""".encodeToByteArray())
        assertTrue(out.contains("\n"), "it was not re-indented: $out")
        assertTrue(out.contains("\"a\"") && out.contains("\"b\""), out)
        val sorted = text(
            "flow.json",
            "in" to """{"b":1,"a":2}""".encodeToByteArray(),
            options = mapOf("sortKeys" to "true"),
        )
        assertTrue(sorted.indexOf("\"a\"") < sorted.indexOf("\"b\""), "keys were not sorted: $sorted")
    }

    /* ───────── cutting and joining ───────── */

    @Test
    fun `slice takes a piece and hands back the rest`() {
        val out = run(
            "flow.slice",
            "in" to "abcdef".encodeToByteArray(),
            options = mapOf("offset" to "2", "length" to "3"),
        )
        assertEquals("cde", out["out"]?.decodeToString())
        assertEquals("f", out["rest"]?.decodeToString())
    }

    @Test
    fun `split and merge are each other's undoing`() {
        val split = run("flow.split", "in" to "left,right".encodeToByteArray(), options = mapOf("sep" to ","))
        assertEquals("left", split["a"]?.decodeToString())
        assertEquals("right", split["b"]?.decodeToString())

        val merged = run(
            "flow.merge",
            "a" to split["a"], "b" to split["b"],
            options = mapOf("sep" to ","),
        )
        assertEquals("left,right", merged["out"]?.decodeToString())
    }

    @Test
    fun `sleep passes its input through`() {
        // the point of the module is the delay; the point of this is that it is not also a filter
        assertEquals("x", text("flow.sleep", "in" to "x".encodeToByteArray(), options = mapOf("ms" to "1")))
    }

    /* ───────── keys and ciphers ───────── */

    @Test
    fun `keygen produces a key of the size asked for`() {
        val key = run("flow.keygen", options = mapOf("algorithm" to "AES", "keySize" to "256"))["out"]!!
        assertEquals(32, key.size, "a 256-bit AES key is 32 bytes")
        val other = run("flow.keygen", options = mapOf("algorithm" to "AES", "keySize" to "256"))["out"]!!
        assertTrue(!key.contentEquals(other), "two generated keys were identical")
    }

    @Test
    fun `secure random produces the length asked for, and not the same twice`() {
        val a = run("flow.securerandom", options = mapOf("length" to "16"))["out"]!!
        val b = run("flow.securerandom", options = mapOf("length" to "16"))["out"]!!
        assertEquals(16, a.size)
        assertTrue(!a.contentEquals(b), "two random draws were identical")
    }

    @Test
    fun `key factory derives the same key from the same password`() {
        val options = mapOf("iterations" to "1000", "keySize" to "256")
        val once = run(
            "flow.keyfactory",
            "password" to "hunter2".encodeToByteArray(), "salt" to "saltsalt".encodeToByteArray(),
            options = options,
        )["out"]!!
        val twice = run(
            "flow.keyfactory",
            "password" to "hunter2".encodeToByteArray(), "salt" to "saltsalt".encodeToByteArray(),
            options = options,
        )["out"]!!
        assertEquals(32, once.size)
        assertContentEquals(once, twice, "the same password and salt gave two different keys")

        val elsewhere = run(
            "flow.keyfactory",
            "password" to "hunter2".encodeToByteArray(), "salt" to "different".encodeToByteArray(),
            options = options,
        )["out"]!!
        assertTrue(!once.contentEquals(elsewhere), "the salt made no difference")
    }

    @Test
    fun `cipher decrypts what it encrypted`() {
        val key = ByteArray(16) { it.toByte() }
        val iv = ByteArray(16) { (16 - it).toByte() }
        val plain = "attack at dawn".encodeToByteArray()

        val encrypted = run(
            "flow.cipher",
            "in" to plain, "key" to key, "iv" to iv,
            options = mapOf("operation" to "encrypt", "algorithm" to "AES", "mode" to "CBC"),
        )["out"]!!
        assertTrue(!encrypted.contentEquals(plain), "the output is the input")

        val decrypted = run(
            "flow.cipher",
            "in" to encrypted, "key" to key, "iv" to iv,
            options = mapOf("operation" to "decrypt", "algorithm" to "AES", "mode" to "CBC"),
        )["out"]!!
        assertContentEquals(plain, decrypted)
    }

    @Test
    fun `key pair generator produces a pair the JDK will parse back`() {
        val pair = run("flow.keypairgen", options = mapOf("algorithm" to "RSA", "keySize" to "2048"))
        val public = pair["publicKey"]!!
        val private = pair["privateKey"]!!
        // encoded the way everything else in the app expects: X.509 for the public half, PKCS#8
        // for the private one — anything else is bytes no other node can use
        java.security.KeyFactory.getInstance("RSA")
            .generatePublic(java.security.spec.X509EncodedKeySpec(public))
        java.security.KeyFactory.getInstance("RSA")
            .generatePrivate(java.security.spec.PKCS8EncodedKeySpec(private))
    }

    @Test
    fun `signature verifies what it signed, and refuses what it did not`() {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val message = "the message".encodeToByteArray()

        val signature = run(
            "flow.signature",
            "in" to message, "key" to pair.private.encoded,
            options = mapOf("operation" to "sign", "algorithm" to "SHA256withRSA"),
        )["out"]!!

        val verified = run(
            "flow.signature",
            "in" to message, "key" to pair.public.encoded, "signature" to signature,
            options = mapOf("operation" to "verify", "algorithm" to "SHA256withRSA"),
        )["out"]!!.decodeToString()
        assertTrue(verified.contains("true", ignoreCase = true) || verified.contains("ok", ignoreCase = true), verified)

        val tampered = run(
            "flow.signature",
            "in" to "another message".encodeToByteArray(), "key" to pair.public.encoded, "signature" to signature,
            options = mapOf("operation" to "verify", "algorithm" to "SHA256withRSA"),
        )["out"]!!.decodeToString()
        assertTrue(
            !tampered.contains("true", ignoreCase = true),
            "a signature over different bytes verified: $tampered",
        )
    }

    @Test
    fun `key store opens a PKCS12 and hands back what is in it`() {
        // built with keytool rather than in code: a certificate is a lot of DER to assemble by
        // hand, and what is being tested is the node that reads a store, not the making of one
        val file = java.io.File.createTempFile("flow-keystore", ".p12").apply { delete(); deleteOnExit() }
        val keytool = java.io.File(java.io.File(System.getProperty("java.home"), "bin"), "keytool").absolutePath
        val built = ProcessBuilder(
            keytool, "-genkeypair", "-alias", "mykey", "-keyalg", "RSA", "-keysize", "2048",
            "-dname", "CN=flow-test", "-validity", "1", "-storetype", "PKCS12",
            "-keystore", file.absolutePath, "-storepass", "secret", "-keypass", "secret",
        ).redirectErrorStream(true).start()
        val said = built.inputStream.readBytes().decodeToString()
        assertEquals(0, built.waitFor(), "keytool could not make a store to read: $said")

        val store = KeyStore.getInstance("PKCS12").apply { load(file.inputStream(), "secret".toCharArray()) }
        val expectedPrivate = (store.getKey("mykey", "secret".toCharArray()) as java.security.PrivateKey).encoded
        val expectedPublic = store.getCertificate("mykey").publicKey.encoded

        val out = run(
            "flow.keystore",
            "store" to file.readBytes(), "password" to "secret".encodeToByteArray(),
            options = mapOf("type" to "PKCS12", "alias" to "mykey", "keyPassword" to "secret"),
        )
        assertContentEquals(expectedPrivate, out["privateKey"], "the private key does not match the store's")
        assertContentEquals(expectedPublic, out["publicKey"], "the public key does not match the store's")
        assertTrue(out["certificate"]?.isNotEmpty() == true, "no certificate came out")
    }

    /* ───────── the one that talks to something else ───────── */

    @Test
    fun `the MCP node says what it needs rather than starting nothing`() {
        val e = ext("flow.mcp")
        val failure = runCatching {
            e.process(mapOf("in" to "x".encodeToByteArray()), e.options.associate { it.name to it.default })
        }.exceptionOrNull()
        assertTrue(failure != null, "it ran without a server command")
        assertTrue(failure.message!!.contains("command"), "not a message that says what is missing: ${failure.message}")
    }

    @Test
    fun `the MCP node drops its input port when it is listing tools instead of calling one`() {
        val e = ext("flow.mcp")
        // with no tool named it lists what the server offers, and there is nothing to feed it
        assertEquals(emptyList(), e.inputsFor(mapOf("tool" to "")))
        assertEquals(listOf("in"), e.inputsFor(mapOf("tool" to "something")))
    }
}
