package flow.modules

import flow.extension.ModuleExtension
import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.ServiceLoader
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The certificate module, checked against the thing that has to accept its output: the JDK's own
 * X.509 parser, and a PKCS#12 store read back through the module that reads stores.
 *
 * A certificate is written by hand here, byte by byte — the JDK can read one but has nothing public
 * that writes one — so "it produced bytes" proves nothing at all. Every case below ends in
 * something parsing those bytes and agreeing with what they were supposed to say.
 */
class CertModuleTest {

    private fun module(): ModuleExtension =
        ServiceLoader.load(ModuleExtension::class.java).firstOrNull { it.id == "flow.cert" }
            ?: error("flow.cert is not registered")

    private fun run(
        vararg inputs: Pair<String, ByteArray?>,
        options: Map<String, String> = emptyMap(),
    ): Map<String, ByteArray?> {
        val m = module()
        val values = m.optionsFor(options).associate { it.name to (options[it.name] ?: it.default) }
        return m.process(inputs.toMap(), values)
    }

    private fun parse(bytes: ByteArray): X509Certificate =
        CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(der(bytes))) as X509Certificate

    private fun der(bytes: ByteArray): ByteArray {
        val text = bytes.decodeToString()
        if (!text.startsWith("-----BEGIN")) return bytes
        val body = text.substringAfter("-----\n").substringBefore("\n-----END")
        return Base64.getMimeDecoder().decode(body)
    }

    @Test
    fun `it produces a certificate the JDK reads, signed by the key it hands back`() {
        val out = run(options = mapOf("subject" to "CN=flow.test,O=Flow", "days" to "30"))
        val certificate = parse(assertNotNull(out["certificate"]))

        assertEquals("CN=flow.test,O=Flow", certificate.subjectX500Principal.name)
        // self-signed: issued by itself, and verifying against its own key is the whole claim
        assertEquals(certificate.subjectX500Principal, certificate.issuerX500Principal)
        certificate.verify(certificate.publicKey)
        certificate.checkValidity()
        assertEquals(3, certificate.version, "a certificate with extensions has to be v3")
    }

    /** The private key on the output is the one that signed it — otherwise the pair is useless. */
    @Test
    fun `the key it hands back matches the certificate`() {
        val out = run(options = mapOf("encoding" to "DER"))
        val certificate = parse(assertNotNull(out["certificate"]))
        val privateKey = assertNotNull(out["privateKey"])
        val publicKey = assertNotNull(out["publicKey"])

        assertContentEquals(certificate.publicKey.encoded, publicKey, "the public key is not the certificate's")
        // sign something with the private key, verify it with the certificate: the pair, end to end
        val data = "flow".encodeToByteArray()
        val signature = java.security.Signature.getInstance("SHA256withRSA").run {
            initSign(java.security.KeyFactory.getInstance("RSA").generatePrivate(java.security.spec.PKCS8EncodedKeySpec(privateKey)))
            update(data)
            sign()
        }
        val verified = java.security.Signature.getInstance("SHA256withRSA").run {
            initVerify(certificate.publicKey)
            update(data)
            verify(signature)
        }
        assertTrue(verified, "the private key does not go with the certificate")
    }

    /** What a TLS client actually matches a hostname against. CN has not been enough for years. */
    @Test
    fun `alternative names reach the certificate, hostnames and addresses alike`() {
        val out = run(options = mapOf("subject" to "CN=localhost", "altNames" to "localhost, *.flow.test, 127.0.0.1"))
        val names = parse(assertNotNull(out["certificate"])).subjectAlternativeNames.orEmpty()
            .map { (it[0] as Int) to (it[1] as String) }

        assertTrue(2 to "localhost" in names, "dNSName missing: $names")
        assertTrue(2 to "*.flow.test" in names, "wildcard dNSName missing: $names")
        assertTrue(7 to "127.0.0.1" in names, "iPAddress missing — an address written as a DNS name is not one: $names")
    }

    @Test
    fun `a CA certificate says it is one, and an ordinary one says it is not`() {
        // basicConstraints: -1 is "not a CA", and any other value is the path length it allows
        assertEquals(-1, parse(assertNotNull(run()["certificate"])).basicConstraints)
        val ca = parse(assertNotNull(run(options = mapOf("ca" to "yes"))["certificate"]))
        assertTrue(ca.basicConstraints >= 0, "a CA certificate was not marked as one")
        assertTrue(ca.keyUsage[5], "a CA has to be allowed to sign certificates (keyCertSign)")
    }

    /** EC, because its AlgorithmIdentifier has no parameters where RSA's carries an explicit NULL. */
    @Test
    fun `it signs with EC as well as RSA`() {
        val out = run(options = mapOf("algorithm" to "EC", "keySize" to "256", "hash" to "SHA-384"))
        val certificate = parse(assertNotNull(out["certificate"]))
        certificate.verify(certificate.publicKey)
        assertEquals("SHA384withECDSA", certificate.sigAlgName)
    }

    /** The store output is read back by the module that reads stores — the round trip, both halves. */
    @Test
    fun `the PKCS12 it writes is one Key Store opens`() {
        val password = "secret".encodeToByteArray()
        val out = run("password" to password, options = mapOf("alias" to "server", "subject" to "CN=store.test"))

        val store = KeyStore.getInstance("PKCS12")
        ByteArrayInputStream(assertNotNull(out["store"])).use { store.load(it, "secret".toCharArray()) }
        assertTrue(store.isKeyEntry("server"), "the alias asked for is not in the store: ${store.aliases().toList()}")

        val keystore = ServiceLoader.load(ModuleExtension::class.java).first { it.id == "flow.keystore" }
        val read = keystore.process(
            mapOf("store" to out["store"], "password" to password),
            keystore.options.associate { it.name to it.default },
        )
        assertEquals(
            "CN=store.test",
            parse(assertNotNull(read["certificate"])).subjectX500Principal.name,
            "Key Store read a different certificate out than Certificate put in",
        )
    }

    /** PEM by default, because that is what tools and config files take. */
    @Test
    fun `PEM and DER are both on offer, and both parse`() {
        val pem = run()
        assertTrue(assertNotNull(pem["certificate"]).decodeToString().startsWith("-----BEGIN CERTIFICATE-----"))
        assertTrue(assertNotNull(pem["privateKey"]).decodeToString().startsWith("-----BEGIN PRIVATE KEY-----"))
        parse(assertNotNull(pem["certificate"]))

        val der = run(options = mapOf("encoding" to "DER"))
        assertEquals(0x30, assertNotNull(der["certificate"])[0].toInt(), "DER output is not a SEQUENCE")
        parse(assertNotNull(der["certificate"]))
    }

    /** A key pair made elsewhere can be certified — Key Pair Generator into Certificate. */
    @Test
    fun `it certifies a key pair handed to it`() {
        val keypair = ServiceLoader.load(ModuleExtension::class.java).first { it.id == "flow.keypairgen" }
        val keys = keypair.process(emptyMap(), mapOf("algorithm" to "RSA", "keySize" to "2048"))

        val out = run(
            "privateKey" to keys["privateKey"],
            "publicKey" to keys["publicKey"],
            options = mapOf("subject" to "CN=brought.my.own", "encoding" to "DER"),
        )
        val certificate = parse(assertNotNull(out["certificate"]))

        assertContentEquals(keys["publicKey"], certificate.publicKey.encoded, "it certified a different key")
        certificate.verify(certificate.publicKey)
        assertContentEquals(keys["privateKey"], out["privateKey"], "the private key handed in came back changed")
    }

    /** Half a key pair cannot sign for the other half, and saying so beats a confusing failure. */
    @Test
    fun `one half of a key pair is refused`() {
        val keypair = ServiceLoader.load(ModuleExtension::class.java).first { it.id == "flow.keypairgen" }
        val keys = keypair.process(emptyMap(), mapOf("algorithm" to "RSA", "keySize" to "2048"))

        val failure = assertFailsWith<IllegalArgumentException> {
            run("privateKey" to keys["privateKey"])
        }
        assertTrue(failure.message.orEmpty().contains("both halves"), failure.message.orEmpty())
    }

    /** Validity is what the option says, not a default buried in the code. */
    @Test
    fun `it is valid for the days asked for`() {
        val certificate = parse(assertNotNull(run(options = mapOf("days" to "7"))["certificate"]))
        val span = certificate.notAfter.time - certificate.notBefore.time
        assertEquals(7L, span / (24 * 60 * 60 * 1000), "the certificate does not last the days it was asked for")
        assertTrue(certificate.notBefore.time <= System.currentTimeMillis(), "it is not valid yet")
    }
}
