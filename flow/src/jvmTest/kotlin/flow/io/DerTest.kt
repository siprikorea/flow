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

    @Test
    fun `text that is not DER at all is read as far as it goes, not thrown away`() {
        // a base64 signature is what someone will point this at first, because it is what an
        // output shows them — and it is not DER, so the parser has to say so rather than fail
        val base64 = "MEUCIQDx4kZ2vQpZ8mJ3nT1aBcDeFgHiJkLmNoPqRsTuVwXyZgIgAbCdEfGhIjKlMnOpQrStUvWxYz01234567890+/="
        val items = Der(base64.encodeToByteArray()).parse()
        assertTrue(items.isNotEmpty(), "nothing at all came back")
    }

    @Test
    fun `every element a parse returns can be shown`() {
        // the window reads these fields directly, so a value out of range is a crash rather than a
        // bad drawing — which is what took the viewer down and kept it down
        listOf(
            "MEUCIQDx4kZ2vQpZ8mJ3nT1aBcDeFgHiJkLmNoPq".encodeToByteArray(),
            ByteArray(200) { (it * 17).toByte() },
            byteArrayOf(0x30, 0x7F),
            byteArrayOf(0x30, 0x84.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()),
            rsaKey(),
        ).forEach { data ->
            Der(data).parse().forEach { item ->
                assertTrue(item.offset >= 0, "offset ${item.offset}")
                assertTrue(item.contentStart >= item.offset, "content before its own header")
                assertTrue(item.end >= item.contentStart, "ends before it starts")
                assertTrue(item.label.isNotEmpty(), "a row with nothing to show")
            }
        }
    }

    @Test
    fun `no two elements share an offset`() {
        // the tree keys its rows by offset, and a repeated key is not a bad drawing but a thrown
        // exception — which took the window down with it and, before it was fixed, kept it down
        listOf(
            "MEUCIQDx4kZ2vQpZ8mJ3nT1aBcDeFgHiJkLmNoPq".encodeToByteArray(),
            ByteArray(200) { (it * 17).toByte() },
            byteArrayOf(0x30, 0x0A, 0x02, 0x01),
            byteArrayOf(0x30, 0x7F),
            rsaKey(),
        ).forEach { data ->
            val offsets = Der(data).parse().map { it.offset }
            assertEquals(offsets.size, offsets.distinct().size, "repeated offsets in $offsets")
        }
    }
}
