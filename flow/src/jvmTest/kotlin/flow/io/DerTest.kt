package flow.io

import flow.view.Der
import java.security.KeyPairGenerator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The DER parser behind the ASN.1 view.
 *
 * What it must never do is throw the whole input away: a certificate that is wrong somewhere is
 * exactly when you open a viewer, and half a certificate plus the offset where it stopped making
 * sense is what you came for.
 */
class DerTest {

    private fun rsaKey() =
        KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().public.encoded

    @Test
    fun `a real public key is read as the structure it is`() {
        val items = Der(rsaKey()).parse()
        val labels = items.joinToString("\n") { it.label }
        // SubjectPublicKeyInfo ::= SEQUENCE { algorithm AlgorithmIdentifier, subjectPublicKey BIT STRING }
        assertTrue(labels.contains("SEQUENCE"), labels)
        assertTrue(labels.contains("rsaEncryption"), "the algorithm OID was not named:\n$labels")
        assertTrue(labels.contains("BIT STRING"), labels)
        assertTrue(labels.contains("NULL"), labels)
    }

    @Test
    fun `nesting is recorded as depth`() {
        val items = Der(rsaKey()).parse()
        val outer = items.first { it.label.startsWith("SEQUENCE") }
        val oid = items.first { it.label.contains("rsaEncryption") }
        assertEquals(0, outer.depth)
        assertTrue(oid.depth > outer.depth, "the algorithm identifier is not inside the sequence")
    }

    @Test
    fun `an OID reads name first, so a cut label keeps the half worth having`() {
        val oid = Der(rsaKey()).parse().first { it.oid != null }
        assertTrue(oid.oid!!.startsWith("rsaEncryption"), oid.oid!!)
        assertTrue(oid.oid!!.contains("1.2.840.113549.1.1.1"), oid.oid!!)
    }

    @Test
    fun `an integer small enough to read is a number`() {
        val items = Der(byteArrayOf(0x02, 0x01, 42)).parse()
        assertEquals("INTEGER  42", items.single().label)
    }

    @Test
    fun `each element knows where its bytes are`() {
        val data = rsaKey()
        Der(data).parse().forEach { item ->
            assertTrue(item.offset in 0..data.size, "${item.label} starts at ${item.offset}")
            assertTrue(item.end in item.contentStart..data.size, "${item.label} ends at ${item.end}")
            assertTrue(item.contentStart > item.offset, "${item.label} has no header")
        }
    }

    @Test
    fun `a truncated value reports where it went wrong instead of failing`() {
        // a SEQUENCE claiming ten bytes with only two present
        val items = Der(byteArrayOf(0x30, 0x0A, 0x02, 0x01)).parse()
        assertTrue(items.any { it.error?.contains("past the end") == true }, items.map { it.label }.toString())
    }

    @Test
    fun `random bytes do not take the parser down`() {
        val items = Der(ByteArray(64) { (it * 37).toByte() }).parse()
        assertTrue(items.isNotEmpty(), "nothing at all was read")
    }

    @Test
    fun `an empty input is empty, not a failure`() {
        assertEquals(emptyList(), Der(ByteArray(0)).parse())
    }
}
