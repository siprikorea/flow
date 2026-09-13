package flow.modules

import flow.extension.ExtensionOption
import flow.extension.OptionType
import flow.extension.ModuleExtension
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The contract stays loadable by modules built before it changed.
 *
 * A module is a jar someone already has installed, compiled against whatever this interface
 * looked like then. Adding a member with a default is fine — the JVM finds it on the interface. But
 * adding a parameter to ExtensionOption, which every extension calls the constructor of, changes a
 * signature, and every jar built before it stops working:
 *
 *   NoSuchMethodError: 'void flow.extension.ExtensionOption.<init>(
 *       java.lang.String, flow.extension.OptionType, java.lang.String, java.util.List)'
 *
 * That shipped, in v1.5.0, and broke every installed module — while every test passed, because
 * a test compiles the modules it runs. So this checks the shape rather than the behaviour: the
 * constructors that jars in the wild call, by their exact JVM signatures.
 *
 * A signature here is a promise. Adding to it is a new member with a default; it is never a new
 * parameter on something that exists.
 */
class ModuleAbiTest {

    @Test
    fun `the ExtensionOption constructors that shipped jars call still exist`() {
        val cls = ExtensionOption::class.java
        // what an module writes: ("name", TYPE, "default", listOf(...))
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
     * Anything the host asks of an module has to be answerable by one that never heard of it.
     *
     * These are the members added after modules were already published; each has to be a default
     * on the interface, or a jar that does not implement it fails with AbstractMethodError the
     * first time the host looks at it.
     */
    @Test
    fun `the members added since extensions shipped all have defaults`() {
        val declared = ModuleExtension::class.java.methods.associateBy { it.name }
        listOf("getVersion", "getSettings", "getSecretSettings", "inputsFor", "outputsFor", "optionsFor", "settingsFor")
            .forEach { name ->
                val method = declared[name] ?: fail("$name is gone from the contract")
                assertTrue(
                    method.isDefault,
                    "$name has no default, so an extension built before it was added cannot load",
                )
            }
    }

    /**
     * The name a shipped jar names, in its class file and in its services file, still resolves.
     *
     * Every module published so far implements `flow.extension.ProcessorExtension` and registers
     * under `META-INF/services/flow.extension.ProcessorExtension`. The contract is called
     * ModuleExtension now; the older name stays as the same contract under it, or every one of
     * those jars fails to load with a NoClassDefFoundError the moment the app scans the store.
     */
    @Test
    fun `the older name for the contract is still the contract`() {
        val older = Class.forName("flow.extension.ProcessorExtension")
        assertTrue(
            ModuleExtension::class.java.isAssignableFrom(older),
            "flow.extension.ProcessorExtension no longer is a ModuleExtension — installed jars stop loading",
        )
        // and it adds nothing of its own beyond what the compiler puts there: the members live on
        // the contract, so a jar built against either name answers the same methods
        val ownWork = older.declaredMethods.filterNot { it.isSynthetic || it.isBridge || it.isDefault }
        assertTrue(ownWork.isEmpty(), "the older name has grown members of its own: ${ownWork.map { it.name }}")
    }
}
