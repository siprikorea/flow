package flow.extensions

import flow.extension.OptionType
import flow.extension.ProcessorExtension
import java.util.ServiceLoader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What every extension has to get right, whichever one it is.
 *
 * These are the mistakes that are invisible until the extension is installed and someone drags it
 * onto a canvas: an id that collides with another's, a SELECT whose default is not one of its
 * choices so the property panel opens on a value that is not there, a version the app cannot
 * compare so an update is never offered. None of them fails a build, and each of them is a thing
 * the next extension written can get wrong too — so this is written against whatever is on the
 * classpath rather than against a list, and covers the twenty-third extension as well as the first.
 */
class ExtensionContractTest {

    private val all: List<ProcessorExtension> =
        ServiceLoader.load(ProcessorExtension::class.java).toList()

    @Test
    fun `the extensions this build ships are all registered`() {
        // a jar whose META-INF/services entry is missing or misspelled loads as nothing at all, and
        // the only symptom is a module that never appears in the palette
        assertTrue(all.size >= 20, "only ${all.size} extensions were found: ${all.map { it.id }}")
        listOf("flow.hash", "flow.cipher", "flow.base64", "flow.ai").forEach { id ->
            assertTrue(all.any { it.id == id }, "$id is not registered")
        }
    }

    @Test
    fun `ids are unique, and named the way an id is`() {
        val ids = all.map { it.id }
        assertEquals(ids.size, ids.toSet().size, "two extensions share an id: ${ids.groupBy { it }.filter { it.value.size > 1 }.keys}")
        ids.forEach { id ->
            assertTrue(id.isNotBlank() && !id.contains(' '), "'$id' is not usable as an id")
            // ids are also directory names in the install store, so anything that could escape it
            // is refused there — better to never ship one
            assertTrue(!id.contains('/') && !id.contains('\\') && id != "." && id != "..", "'$id' is not a safe id")
        }
    }

    @Test
    fun `every extension says what it is called and which version it is`() {
        all.forEach { e ->
            assertTrue(e.displayName.isNotBlank(), "${e.id} has no display name")
            // the app compares this against what a registry offers to decide an update is available;
            // one it cannot read is one that never updates
            assertTrue(
                Regex("""\d+(\.\d+)*""").matches(e.version),
                "${e.id} has a version the app cannot compare: '${e.version}'",
            )
        }
    }

    @Test
    fun `ports are named, unique, and on the right side`() {
        all.forEach { e ->
            (e.inputs + e.outputs).forEach { port ->
                assertTrue(port.isNotBlank(), "${e.id} has a port with no name")
            }
            assertEquals(e.inputs.size, e.inputs.toSet().size, "${e.id} repeats an input port")
            assertEquals(e.outputs.size, e.outputs.toSet().size, "${e.id} repeats an output port")
            // a processor with nothing coming out is a node that cannot be connected to anything
            assertTrue(e.outputs.isNotEmpty(), "${e.id} has no output")
        }
    }

    @Test
    fun `options are unique, and a SELECT offers the value it defaults to`() {
        (all.map { it.id to it.options } + all.map { it.id to it.settings }).forEach { (id, options) ->
            val names = options.map { it.name }
            assertEquals(names.size, names.toSet().size, "$id repeats an option name: $names")
            options.forEach { opt ->
                assertTrue(opt.name.isNotBlank(), "$id has an option with no name")
                if (opt.type == OptionType.SELECT) {
                    assertTrue(opt.choices.isNotEmpty(), "$id: SELECT '${opt.name}' offers nothing")
                    assertTrue(
                        opt.default in opt.choices,
                        "$id: '${opt.name}' defaults to '${opt.default}', which is not one of ${opt.choices}",
                    )
                }
                if (opt.type == OptionType.NUMBER && opt.default.isNotEmpty()) {
                    assertTrue(
                        opt.default.toDoubleOrNull() != null,
                        "$id: NUMBER '${opt.name}' defaults to '${opt.default}'",
                    )
                }
            }
        }
    }

    /**
     * The ports a node actually gets, for the options it actually has.
     *
     * An extension may vary its ports by option — a verify operation needing a signature input that
     * sign does not — and the host keeps the node in step with whatever this returns. Returning a
     * port that is not one of the declared ones leaves a node with a port nothing can be connected
     * to, because the palette and the property panel disagree about what exists.
     */
    @Test
    fun `ports for the default options are among the declared ones`() {
        all.forEach { e ->
            val defaults = e.options.associate { it.name to it.default }
            val inputs = e.inputsFor(defaults)
            val outputs = e.outputsFor(defaults)
            assertTrue(e.inputs.containsAll(inputs), "${e.id} offers inputs it does not declare: $inputs")
            assertTrue(e.outputs.containsAll(outputs), "${e.id} offers outputs it does not declare: $outputs")
            val shown = e.optionsFor(defaults).map { it.name }
            assertTrue(
                e.options.map { it.name }.containsAll(shown),
                "${e.id} shows options it does not declare: $shown",
            )
        }
    }

    /**
     * Asking twice gives the same answer.
     *
     * The host calls these while drawing, so they run constantly and on any thread; one that
     * remembers something between calls is a node whose ports depend on what was asked before it.
     */
    @Test
    fun `describing an extension does not change it`() {
        all.forEach { e ->
            val defaults = e.options.associate { it.name to it.default }
            assertEquals(e.inputsFor(defaults), e.inputsFor(defaults), "${e.id} answers inputsFor differently")
            assertEquals(e.outputsFor(defaults), e.outputsFor(defaults), "${e.id} answers outputsFor differently")
            assertEquals(
                e.optionsFor(defaults).map { it.name },
                e.optionsFor(defaults).map { it.name },
                "${e.id} answers optionsFor differently",
            )
        }
    }
}
