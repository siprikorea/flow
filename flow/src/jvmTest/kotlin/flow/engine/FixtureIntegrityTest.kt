package flow.engine

import flow.model.asComponent
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The fixtures are the flow files the other tests run, so they have to stay loadable and stay
 * wired to the modules the tests provide. A fixture that quietly stopped parsing would turn every
 * test using it into one that proves nothing.
 */
class FixtureIntegrityTest {

    private fun fixtureNames(): List<String> {
        val dir = File(javaClass.getResource("/flows")!!.toURI())
        return dir.listFiles { f: File -> f.name.endsWith(".flow") }!!.map { it.name }.sorted()
    }

    @Test
    fun `every fixture parses`() {
        val names = fixtureNames()
        assertTrue(names.isNotEmpty(), "no fixtures found")
        names.forEach { name ->
            runCatching { Fixtures.load(name) }.onFailure { fail("$name did not parse: ${it.message}") }
        }
    }

    @Test
    fun `every module a fixture names is one the tests provide, or is meant to be missing`() {
        // "test.nowhere" is deliberately absent — a fixture exists to prove a missing module fails
        val deliberatelyMissing = setOf("test.nowhere")
        fixtureNames().forEach { name ->
            Fixtures.load(name).nodes
                .map { it.type }
                .filter { it != "cin" && it != "cout" && !it.startsWith("comp:") }
                .forEach { type ->
                    assertTrue(
                        type in Fixtures.moduleIds || type in deliberatelyMissing,
                        "$name names '$type', which the tests neither provide nor expect to be missing",
                    )
                }
        }
    }

    @Test
    fun `every comp reference points at a fixture that exists, or is meant to be missing`() {
        val present = fixtureNames().toSet()
        val deliberatelyMissing = setOf("not-here.flow")
        fixtureNames().forEach { name ->
            Fixtures.load(name).nodes
                .map { it.type }
                .filter { it.startsWith("comp:") }
                .map { it.removePrefix("comp:") }
                .forEach { file ->
                    assertTrue(
                        file in present || file in deliberatelyMissing,
                        "$name refers to component '$file', which is neither a fixture nor expected to be missing",
                    )
                }
        }
    }

    @Test
    fun `every edge joins ports that its nodes actually declare`() {
        fixtureNames().forEach { name ->
            val flow = Fixtures.load(name)
            val byId = flow.nodes.associateBy { it.id }
            flow.edges.forEach { e ->
                val from = byId[e.from.node] ?: fail("$name: edge ${e.id} starts at an unknown node")
                val to = byId[e.to.node] ?: fail("$name: edge ${e.id} ends at an unknown node")
                assertTrue(
                    from.outputs.any { it.name == e.from.port },
                    "$name: ${from.id} has no output '${e.from.port}'",
                )
                assertTrue(to.inputs.any { it.name == e.to.port }, "$name: ${to.id} has no input '${e.to.port}'")
            }
        }
    }

    @Test
    fun `a fixture used as a component exposes the ports the flow using it expects`() {
        // uses-component.flow wires Text -> chain -> Result, so chain.flow has to offer those names
        val chain = Fixtures.load("chain.flow").asComponent("chain.flow")
            ?: fail("chain.flow no longer reads as a component")
        assertTrue("Text" in chain.ins, "chain.flow lost its Text input: ${chain.ins}")
        assertTrue("Result" in chain.outs, "chain.flow lost its Result output: ${chain.outs}")
    }
}
