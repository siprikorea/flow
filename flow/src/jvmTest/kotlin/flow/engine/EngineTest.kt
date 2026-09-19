package flow.engine

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Each case here is a defect the engine actually had. They are written against the .flow fixtures
 * so a regression shows up as the behaviour a user would see, not only as a broken internal.
 */
class EngineTest {

    private fun run(fixture: String, vararg inputs: Pair<String, String>): Pair<Map<String, ByteArray?>, Map<String, String>> {
        val engine = Fixtures.engine()
        val flow = Fixtures.load(fixture)
        val out = runBlocking { engine.run(flow, inputs.associate { it.first to it.second.encodeToByteArray() }) }
        return out to engine.errors
    }

    private fun Map<String, ByteArray?>.text(port: String) = this[port]?.decodeToString()

    @Test
    fun `a straight chain produces its output`() {
        val (out, errors) = run("chain.flow", "Text" to "hello")
        assertEquals("HELLO", out.text("Result"))
        assertTrue(errors.isEmpty(), "expected no errors, got $errors")
    }

    /**
     * A pass can be narrowed to part of the flow — which is what live mode runs on every edit.
     *
     * The rest is not merely skipped for speed: a module is arbitrary code and can have a
     * consequence, so a branch the change never reached must not be called again at all. The value
     * it produced last time is handed back for it, and test.effect records anything that ran.
     */
    @Test
    fun `only the named nodes run, and the rest keep the values they had`() {
        Fixtures.effects.clear()
        val engine = Fixtures.engine()
        val flow = Fixtures.load("two-branches.flow")
        val inputs = mapOf("in" to "abc".encodeToByteArray())

        val first = runBlocking { engine.values(flow, inputs) }
        assertEquals("ABC", engine.coutValues(flow, first)["outU"]?.decodeToString())
        assertEquals("cba", engine.coutValues(flow, first)["outR"]?.decodeToString())

        // the same input, but only the upper branch is in this pass
        val second = runBlocking {
            engine.values(flow, mapOf("in" to "xyz".encodeToByteArray()), first, setOf("in", "u", "outU"))
        }
        val out = engine.coutValues(flow, first + second)
        assertEquals("XYZ", out["outU"]?.decodeToString(), "the branch in the pass did not run")
        assertEquals("cba", out["outR"]?.decodeToString(), "a branch outside the pass was run again")
    }

    /** And a module outside the pass is never called — not called and producing nothing differ. */
    @Test
    fun `a module outside the pass is not called`() {
        Fixtures.effects.clear()
        val engine = Fixtures.engine()
        val flow = Fixtures.load("branch.flow")
        // "no" is the side the effect module is on, so the first pass records it running
        val inputs = mapOf("in" to "no".encodeToByteArray())

        val first = runBlocking { engine.values(flow, inputs) }
        val ran = Fixtures.effects.toList()
        Fixtures.effects.clear()

        // a pass that names nothing but the input node: everything else keeps what it had
        runBlocking { engine.values(flow, inputs, first, setOf("in")) }

        assertTrue(ran.isNotEmpty(), "the fixture never recorded a module running at all")
        assertEquals(emptyList(), Fixtures.effects, "a module outside the pass ran anyway")
    }

    @Test
    fun `branches that share only their input both produce results`() {
        val (out, errors) = run("two-branches.flow", "Text" to "abc")
        assertEquals("ABC", out.text("Uppered"))
        assertEquals("cba", out.text("Reversed"))
        assertTrue(errors.isEmpty(), "expected no errors, got $errors")
    }

    /**
     * The side a branch did not take does not run.
     *
     * Producing nothing and never running look the same from the outputs, and they are not the
     * same thing at all: a node with a consequence — the Slack node telling someone the build
     * failed — must not fire on the branch that says it did not. test.effect records that it ran,
     * which is the only way to tell the two apart.
     */
    @Test
    fun `a branch runs one side and skips the other entirely`() {
        Fixtures.effects.clear()
        val (out, errors) = run("branch.flow", "Text" to "yes")
        assertEquals("YES", out.text("Taken"))
        assertNull(out.text("Dead"), "the untaken side produced a value")
        assertEquals(emptyList(), Fixtures.effects, "a node on the untaken side ran anyway")
        // skipped is not failed: nothing here is wrong, so nothing is reported as wrong
        assertTrue(errors.isEmpty(), "an untaken branch was reported as an error: $errors")
    }

    /**
     * One dead input is enough to stop a node, even when its other input is live.
     *
     * This is the shape that makes a branch usable in the middle of a flow: encrypt-then-notify,
     * where the key comes from elsewhere and only the value comes down the branch. Running on the
     * half that arrived would encrypt nothing, or post an empty message.
     */
    @Test
    fun `a node fed by both a live input and an untaken one does not run`() {
        val (out, errors) = run("branch.flow", "Text" to "yes")
        assertNull(out.text("Both"))
        assertTrue(errors.isEmpty(), "$errors")
    }

    /**
     * Unless the node says that input is one it can do without.
     *
     * Merge exists to join two branches, and joining one branch with a branch that did not happen
     * is exactly what it is for. Skipping it would make a branch impossible to rejoin.
     */
    @Test
    fun `a node whose missing input is an optional one still runs`() {
        val (out, errors) = run("branch.flow", "Text" to "yes")
        assertEquals("yes", out.text("Either"))
        assertTrue(errors.isEmpty(), "$errors")
    }

    @Test
    fun `the other side of the branch is the one that runs when the test fails`() {
        Fixtures.effects.clear()
        val (out, errors) = run("branch.flow", "Text" to "no")
        assertNull(out.text("Taken"))
        assertEquals("no", out.text("Dead"))
        assertEquals(listOf("no"), Fixtures.effects)
        assertEquals("nono", out.text("Both"), "a node fed by two live inputs did not run")
        assertTrue(errors.isEmpty(), "$errors")
    }

    @Test
    fun `a module that is not installed fails instead of passing its input through`() {
        val (out, errors) = run("missing-module.flow", "Text" to "hello")
        // the defect: this used to return "hello", so a flow silently skipped the work it names
        assertNull(out.text("Result"))
        assertEquals(setOf("m"), errors.keys)
        assertContains(errors.getValue("m"), "not installed")
    }

    @Test
    fun `a failing node stops its own branch and leaves the others alone`() {
        val (out, errors) = run("one-branch-fails.flow", "Text" to "hi")
        assertNull(out.text("FromBoom"))
        assertEquals("HI", out.text("FromFine"))
        // only the node that broke is blamed — the one behind it never ran, so it has nothing to say
        assertEquals(setOf("boom"), errors.keys)
    }

    @Test
    fun `a module writing through its input cannot corrupt another branch`() {
        repeat(5) {
            val (out, _) = run("shared-value.flow", "Text" to "hello")
            assertEquals("hello", out.text("FromWitness"), "the witness branch was altered by the vandal")
            assertEquals("Xello", out.text("FromVandal"))
        }
    }

    @Test
    fun `a cycle terminates instead of deadlocking`() {
        // nodes wait on the nodes feeding them, so a cycle must not become a circular wait
        val (_, errors) = run("cycle.flow")
        assertTrue(errors.isEmpty() || errors.isNotEmpty()) // the point is that it returned at all
    }

    @Test
    fun `a component failing inside fails the node that used it, and its siblings survive`() {
        val (out, errors) = run("uses-component.flow", "Text" to "hi")
        assertEquals("HI", out.text("FromComponent"))
        assertNull(out.text("FromBoom"))
        // the sub-flow used to clear the parent's errors on the way in, losing this entirely
        assertEquals(setOf("boom"), errors.keys)
    }

    @Test
    fun `a component that fails inside fails the node that used it`() {
        val (out, errors) = run("uses-broken-component.flow", "Text" to "hi")
        assertNull(out.text("FromBroken"))
        // the branch that has nothing to do with the component still produces its result
        assertEquals("HI", out.text("FromFine"))
        val message = assertNotNull(errors["c"], "the component's failure never reached the node using it")
        assertContains(message, "broken-component.flow")
        assertContains(message, "boom") // and it says what actually went wrong inside
        assertEquals(setOf("c"), errors.keys)
    }

    @Test
    fun `a component that is not there fails the node that named it`() {
        val (out, errors) = run("missing-component.flow", "Text" to "hi")
        assertNull(out.text("Result"))
        assertEquals(setOf("c"), errors.keys)
        assertContains(errors.getValue("c"), "not found")
    }

    @Test
    fun `a component referring to itself is refused instead of exhausting the process`() {
        val (_, errors) = run("self-reference.flow", "Text" to "hi")
        val message = assertNotNull(errors["c"])
        assertContains(message, "nests more than")
        // the message must not be the same sentence wrapped once per level
        assertTrue(
            message.split("nests more than").size == 2,
            "the depth failure was re-wrapped on the way out: $message",
        )
    }

    @Test
    fun `a sub-flow's nodes are not reported to the caller as its own`() {
        val settled = mutableListOf<String>()
        val engine = Fixtures.engine { id, _ -> synchronized(settled) { settled += id } }
        runBlocking { engine.run(Fixtures.load("uses-component.flow"), mapOf("Text" to "hi".encodeToByteArray())) }
        val ownIds = Fixtures.load("uses-component.flow").nodes.map { it.id }.toSet()
        assertEquals(emptyList(), settled.filterNot { it in ownIds }, "ids from inside the component leaked out")
    }
}
