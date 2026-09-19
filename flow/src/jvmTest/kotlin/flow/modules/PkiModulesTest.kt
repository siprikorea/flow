package flow.modules

import flow.extension.ModuleExtension
import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509CRL
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
 * The PKI set, run the way it is meant to be used: as one chain of modules.
 *
 * Each of these writes ASN.1 by hand — the JDK reads certificates, requests, CRLs and CMS, and
 * writes none of them — so "bytes came out" proves nothing. Every case ends in something else
 * parsing those bytes and agreeing: the JDK's own X.509 reader, its PKIX validator, a PKCS#12 store
 * opened by the module that opens stores, and a CMS signature verified field by field.
 *
 * The shape of it is the shape of a real private CA: a root, a request from a server that keeps its
 * key, a certificate issued against that request, a chain checked by a client, and a revocation
 * when the key is lost.
 */
class PkiModulesTest {

    private fun module(id: String): ModuleExtension =
        ServiceLoader.load(ModuleExtension::class.java).firstOrNull { it.id == id }
            ?: error("$id is not registered")

    private fun run(id: String, vararg inputs: Pair<String, ByteArray?>, options: Map<String, String> = emptyMap()): Map<String, ByteArray?> {
        val m = module(id)
        val values = m.optionsFor(options).associate { it.name to (options[it.name] ?: it.default) }
        return m.process(inputs.toMap(), values)
    }

    private fun certificate(bytes: ByteArray): X509Certificate =
        CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der(bytes))) as X509Certificate

    private fun der(bytes: ByteArray): ByteArray {
        val text = runCatching { bytes.decodeToString() }.getOrNull() ?: return bytes
        if (!text.contains("-----BEGIN")) return bytes
        val body = text.substringAfter("-----\n").substringBefore("\n-----END")
        return Base64.getMimeDecoder().decode(body)
    }

    /** A root CA, as Certificate makes one. */
    private fun root(name: String = "CN=Flow Test Root,O=Flow") =
        run("flow.cert", options = mapOf("subject" to name, "ca" to "yes", "days" to "3650"))

    /* ───────── the chain, end to end ───────── */

    @Test
    fun `a root issues a server certificate from its request, and a client accepts it`() {
        val ca = root()
        val request = run(
            "flow.csr",
            options = mapOf("subject" to "CN=server.flow.test", "altNames" to "server.flow.test,127.0.0.1"),
        )

        val issued = run(
            "flow.ca",
            "request" to request["request"],
            "caCertificate" to ca["certificate"],
            "caPrivateKey" to ca["privateKey"],
            options = mapOf("days" to "90", "usage" to "server"),
        )
        val leaf = certificate(assertNotNull(issued["certificate"]))

        assertEquals("CN=server.flow.test", leaf.subjectX500Principal.name)
        assertEquals("CN=Flow Test Root,O=Flow", leaf.issuerX500Principal.name)
        leaf.verify(certificate(assertNotNull(ca["certificate"])).publicKey)
        // the names asked for in the request are carried into the certificate, which is the point
        // of asking for them there
        val names = leaf.subjectAlternativeNames.orEmpty().map { (it[0] as Int) to (it[1] as String) }
        assertTrue(2 to "server.flow.test" in names, "the requested DNS name is missing: $names")
        assertTrue(7 to "127.0.0.1" in names, "the requested address is missing: $names")
        assertEquals(-1, leaf.basicConstraints, "an issued server certificate must not be a CA")

        // and the client's side: chain to the root, and it validates
        val check = run(
            "flow.certpath",
            "certificate" to issued["certificate"],
            "chain" to issued["chain"],
            "trusted" to ca["certificate"],
        )
        assertEquals("true", assertNotNull(check["valid"]).decodeToString(), assertNotNull(check["problem"]).decodeToString())
    }

    /** The key never leaves the requester: what the CA gets back certifies the key it was sent. */
    @Test
    fun `the issued certificate is for the key that asked for it`() {
        val ca = root()
        val request = run("flow.csr", options = mapOf("subject" to "CN=keeps.its.key"))
        val issued = run(
            "flow.ca",
            "request" to request["request"],
            "caCertificate" to ca["certificate"],
            "caPrivateKey" to ca["privateKey"],
        )
        val leaf = certificate(assertNotNull(issued["certificate"]))

        assertContentEquals(
            der(assertNotNull(request["publicKey"])),
            leaf.publicKey.encoded,
            "the CA certified a different key than the one in the request",
        )
        // the private key that never travelled still matches what came back
        val data = "hello".encodeToByteArray()
        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(java.security.KeyFactory.getInstance("RSA").generatePrivate(java.security.spec.PKCS8EncodedKeySpec(der(assertNotNull(request["privateKey"])))))
            update(data)
            sign()
        }
        val ok = Signature.getInstance("SHA256withRSA").run { initVerify(leaf.publicKey); update(data); verify(signature) }
        assertTrue(ok, "the requester's key does not match the certificate it got")
    }

    @Test
    fun `a certificate signed by nothing you trust is refused`() {
        val ca = root()
        val stranger = root("CN=Somebody Else")
        val request = run("flow.csr", options = mapOf("subject" to "CN=server.flow.test"))
        val issued = run(
            "flow.ca",
            "request" to request["request"],
            "caCertificate" to stranger["certificate"],
            "caPrivateKey" to stranger["privateKey"],
        )

        val check = run(
            "flow.certpath",
            "certificate" to issued["certificate"],
            "chain" to issued["chain"],
            "trusted" to ca["certificate"], // the wrong root
        )
        assertEquals("false", assertNotNull(check["valid"]).decodeToString())
        assertTrue(assertNotNull(check["problem"]).decodeToString().isNotEmpty(), "it said no without saying why")
    }

    /** A CA that is not marked as one cannot issue: the flag is the whole of the authority. */
    @Test
    fun `an end-entity certificate cannot issue certificates`() {
        val notACa = run("flow.cert", options = mapOf("subject" to "CN=Plain", "ca" to "no"))
        val request = run("flow.csr", options = mapOf("subject" to "CN=hopeful"))

        val failure = assertFailsWith<IllegalArgumentException> {
            run(
                "flow.ca",
                "request" to request["request"],
                "caCertificate" to notACa["certificate"],
                "caPrivateKey" to notACa["privateKey"],
            )
        }
        assertTrue(failure.message.orEmpty().contains("not a CA"), failure.message.orEmpty())
    }

    /** Proof of possession is the only thing a request proves, so a broken one is worth nothing. */
    @Test
    fun `a request whose signature does not verify is refused`() {
        val ca = root()
        val request = assertNotNull(run("flow.csr", options = mapOf("subject" to "CN=liar"))["request"])
        // flip a byte inside the signature at the end of the DER
        val broken = der(request).copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }

        val failure = assertFailsWith<IllegalArgumentException> {
            run(
                "flow.ca",
                "request" to broken,
                "caCertificate" to ca["certificate"],
                "caPrivateKey" to ca["privateKey"],
            )
        }
        assertTrue(failure.message.orEmpty().contains("does not verify"), failure.message.orEmpty())
    }

    /** Nothing may outlive what vouches for it. */
    @Test
    fun `an issued certificate cannot outlast the CA that issued it`() {
        val ca = run("flow.cert", options = mapOf("subject" to "CN=Short Root", "ca" to "yes", "days" to "10"))
        val request = run("flow.csr", options = mapOf("subject" to "CN=greedy"))
        val issued = run(
            "flow.ca",
            "request" to request["request"],
            "caCertificate" to ca["certificate"],
            "caPrivateKey" to ca["privateKey"],
            options = mapOf("days" to "3650"),
        )
        val leaf = certificate(assertNotNull(issued["certificate"]))
        val caCertificate = certificate(assertNotNull(ca["certificate"]))
        assertTrue(
            leaf.notAfter <= caCertificate.notAfter,
            "the issued certificate outlives its issuer: ${leaf.notAfter} > ${caCertificate.notAfter}",
        )
    }

    /* ───────── revocation ───────── */

    @Test
    fun `a revoked certificate is refused, and one that is not stays good`() {
        val ca = root()
        fun issue(name: String): Map<String, ByteArray?> {
            val request = run("flow.csr", options = mapOf("subject" to "CN=$name"))
            return run(
                "flow.ca",
                "request" to request["request"],
                "caCertificate" to ca["certificate"],
                "caPrivateKey" to ca["privateKey"],
            )
        }
        val lost = issue("lost.flow.test")
        val fine = issue("fine.flow.test")

        val crl = run(
            "flow.crl",
            "caCertificate" to ca["certificate"],
            "caPrivateKey" to ca["privateKey"],
            "revoked" to lost["certificate"],
            options = mapOf("reason" to "keyCompromise"),
        )
        val parsed = CertificateFactory.getInstance("X.509")
            .generateCRL(ByteArrayInputStream(der(assertNotNull(crl["crl"])))) as X509CRL
        parsed.verify(certificate(assertNotNull(ca["certificate"])).publicKey)
        assertTrue(parsed.isRevoked(certificate(assertNotNull(lost["certificate"]))), "the revoked certificate is not in the list")
        assertTrue(!parsed.isRevoked(certificate(assertNotNull(fine["certificate"]))), "an unrevoked certificate is in the list")

        val checkRevoked = run(
            "flow.certpath",
            "certificate" to lost["certificate"],
            "chain" to lost["chain"],
            "trusted" to ca["certificate"],
            "crl" to crl["crl"],
        )
        assertEquals("false", assertNotNull(checkRevoked["valid"]).decodeToString(), "a revoked certificate validated")
        assertTrue(assertNotNull(checkRevoked["problem"]).decodeToString().lowercase().contains("revok"), assertNotNull(checkRevoked["problem"]).decodeToString())

        val checkFine = run(
            "flow.certpath",
            "certificate" to fine["certificate"],
            "chain" to fine["chain"],
            "trusted" to ca["certificate"],
            "crl" to crl["crl"],
        )
        assertEquals("true", assertNotNull(checkFine["valid"]).decodeToString(), assertNotNull(checkFine["problem"]).decodeToString())
    }

    /* ───────── PKCS#12 ───────── */

    @Test
    fun `it packs a key and its chain into a store that opens`() {
        val ca = root()
        val request = run("flow.csr", options = mapOf("subject" to "CN=stored.flow.test"))
        val issued = run(
            "flow.ca",
            "request" to request["request"],
            "caCertificate" to ca["certificate"],
            "caPrivateKey" to ca["privateKey"],
        )

        val p12 = run(
            "flow.pkcs12",
            "privateKey" to request["privateKey"],
            "certificate" to issued["certificate"],
            "chain" to issued["chain"],
            "password" to "secret".encodeToByteArray(),
            options = mapOf("alias" to "server"),
        )

        val store = KeyStore.getInstance("PKCS12")
        ByteArrayInputStream(assertNotNull(p12["store"])).use { it -> store.load(it, "secret".toCharArray()) }
        assertTrue(store.isKeyEntry("server"))
        val chain = assertNotNull(store.getCertificateChain("server"))
        assertEquals(2, chain.size, "the store should hold the certificate and its issuer")
        assertEquals("CN=stored.flow.test", (chain[0] as X509Certificate).subjectX500Principal.name)
        assertEquals("CN=Flow Test Root,O=Flow", (chain[1] as X509Certificate).subjectX500Principal.name)

        // and Key Store, the module that reads them, gets the same thing back out
        val read = run("flow.keystore", "store" to p12["store"], "password" to "secret".encodeToByteArray())
        assertEquals("CN=stored.flow.test", certificate(assertNotNull(read["certificate"])).subjectX500Principal.name)
    }

    @Test
    fun `a key and a certificate that do not go together are refused`() {
        val one = run("flow.cert", options = mapOf("subject" to "CN=one"))
        val other = run("flow.cert", options = mapOf("subject" to "CN=other"))

        val failure = assertFailsWith<IllegalArgumentException> {
            run("flow.pkcs12", "privateKey" to one["privateKey"], "certificate" to other["certificate"])
        }
        assertTrue(failure.message.orEmpty().contains("not for this private key"), failure.message.orEmpty())
    }

    /* ───────── PKCS#7 / CMS ───────── */

    @Test
    fun `a PKCS7 signature carries the signer and verifies field by field`() {
        val signer = run("flow.cert", options = mapOf("subject" to "CN=signer.flow.test"))
        val data = "the message that was signed".encodeToByteArray()

        val blob = assertNotNull(
            run(
                "flow.pkcs7",
                "data" to data,
                "certificate" to signer["certificate"],
                "privateKey" to signer["privateKey"],
                options = mapOf("encoding" to "DER"),
            )["pkcs7"],
        )

        val cms = Cms(blob)
        assertEquals("1.2.840.113549.1.7.2", cms.contentType, "not a SignedData")
        assertContentEquals(data, cms.content, "the content is not inside the blob")
        assertEquals(
            certificate(assertNotNull(signer["certificate"])).encoded.toList(),
            cms.certificates.first().toList(),
            "the signer's certificate is not in the blob",
        )
        // the digest attribute is over the content...
        assertContentEquals(MessageDigest.getInstance("SHA-256").digest(data), cms.messageDigest)
        // ...and the signature is over the attributes, which is what binds the two together
        val verified = Signature.getInstance("SHA256withRSA").run {
            initVerify(certificate(assertNotNull(signer["certificate"])).publicKey)
            update(cms.signedAttributes)
            verify(cms.signature)
        }
        assertTrue(verified, "the signature does not verify over the signed attributes")
    }

    @Test
    fun `a detached signature leaves the data out`() {
        val signer = run("flow.cert", options = mapOf("subject" to "CN=signer"))
        val data = "big file".encodeToByteArray()
        val blob = assertNotNull(
            run(
                "flow.pkcs7",
                "data" to data,
                "certificate" to signer["certificate"],
                "privateKey" to signer["privateKey"],
                options = mapOf("detached" to "yes", "encoding" to "DER"),
            )["pkcs7"],
        )
        val cms = Cms(blob)
        assertTrue(cms.content == null, "a detached signature should not carry the data")
        // it still commits to it: the digest is the digest of the file that was left out
        assertContentEquals(MessageDigest.getInstance("SHA-256").digest(data), cms.messageDigest)
    }

    @Test
    fun `certs mode makes a bundle of certificates and signs nothing`() {
        val ca = root()
        val request = run("flow.csr", options = mapOf("subject" to "CN=bundled"))
        val issued = run(
            "flow.ca",
            "request" to request["request"],
            "caCertificate" to ca["certificate"],
            "caPrivateKey" to ca["privateKey"],
        )
        val bundle = assertNotNull(
            run(
                "flow.pkcs7",
                "certificate" to issued["certificate"],
                "chain" to ca["certificate"],
                options = mapOf("mode" to "certs", "encoding" to "DER"),
            )["pkcs7"],
        )
        val cms = Cms(bundle)
        assertEquals(2, cms.certificates.size, "a .p7b should hold both certificates")
        assertTrue(cms.signature == null, "a certificate bundle must not claim to be signed")
    }

    /** PEM by default, and DER when asked — both parse as what they claim to be. */
    @Test
    fun `everything offers PEM and DER`() {
        val ca = root()
        assertTrue(assertNotNull(ca["certificate"]).decodeToString().startsWith("-----BEGIN CERTIFICATE-----"))
        val request = run("flow.csr")
        assertTrue(assertNotNull(request["request"]).decodeToString().startsWith("-----BEGIN CERTIFICATE REQUEST-----"))
        val crl = run("flow.crl", "caCertificate" to ca["certificate"], "caPrivateKey" to ca["privateKey"])
        assertTrue(assertNotNull(crl["crl"]).decodeToString().startsWith("-----BEGIN X509 CRL-----"))

        val derCa = run("flow.cert", options = mapOf("ca" to "yes", "encoding" to "DER"))
        assertEquals(0x30, assertNotNull(derCa["certificate"])[0].toInt())
        certificate(assertNotNull(derCa["certificate"]))
    }
}
