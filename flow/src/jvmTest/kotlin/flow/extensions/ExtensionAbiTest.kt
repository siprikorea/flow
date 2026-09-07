package flow.extensions

import flow.extension.ExtensionOption
import flow.extension.OptionType
import flow.extension.ProcessorExtension
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The extension contract stays loadable by extensions built before it changed.
 *
 * An extension is a jar someone already has installed, compiled against whatever this interface
 * looked like then. Adding a member with a default is fine — the JVM finds it on the interface. But
 * adding a parameter to ExtensionOption, which every extension calls the constructor of, changes a
 * signature, and every jar built before it stops working:
 *
 *   NoSuchMethodError: 'void flow.extension.ExtensionOption.<init>(
 *       java.lang.String, flow.extension.OptionType, java.lang.String, java.util.List)'
 *
 * That shipped, in v1.5.0, and broke every installed extension — while every test passed, because
 * a test compiles the extensions it runs. So this checks the shape rather than the behaviour: the
 * constructors that jars in the wild call, by their exact JVM signatures.
 *
 * A signature here is a promise. Adding to it is a new member with a default; it is never a new
 * parameter on something that exists.
 */
class ExtensionAbiTest {

    @Test
    fun `the ExtensionOption constructors that shipped jars call still exist`() {
        val cls = ExtensionOption::class.java
        // what an extension writes: ("name", TYPE, "default", listOf(...))
        runCatching {
            cls.getDeclaredConstructor(
                String::class.java, OptionType::class.java, String::class.java, List::class.java,
            )
        }.onFailure {
            fail(
                "ExtensionOption's four-argument constructor is gone — every extension jar built " +
                    "before this stops loading. Add a member with a default instead of a parameter.",
            )
        }
        // and what it writes when it leaves an argument out, which Kotlin compiles to the synthetic
        // constructor carrying a mask of which ones were defaulted
        runCatching {
            cls.getDeclaredConstructor(
                String::class.java, OptionType::class.java, String::class.java, List::class.java,
                Int::class.javaPrimitiveType, Class.forName("kotlin.jvm.internal.DefaultConstructorMarker"),
            )
        }.onFailure {
            fail("ExtensionOption's defaulted-argument constructor is gone — same breakage, for any extension that omits an argument")
        }
    }

    /**
     * Anything the host asks of an extension has to be answerable by one that never heard of it.
     *
     * These are the members added after extensions were already published; each has to be a default
     * on the interface, or a jar that does not implement it fails with AbstractMethodError the
     * first time the host looks at it.
     */
    @Test
    fun `the members added since extensions shipped all have defaults`() {
        val declared = ProcessorExtension::class.java.methods.associateBy { it.name }
        listOf("getVersion", "getSettings", "getSecretSettings", "inputsFor", "outputsFor", "optionsFor", "settingsFor")
            .forEach { name ->
                val method = declared[name] ?: fail("$name is gone from the contract")
                assertTrue(
                    method.isDefault,
                    "$name has no default, so an extension built before it was added cannot load",
                )
            }
    }
}
