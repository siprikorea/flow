package flow.io

import flow.ui.theme.FlowType
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * No screen names a size. They ask the scale for a style.
 *
 * Sizes drifted to eleven of them at once — 8 through 15 — most in the screens written before the
 * scale existed, which is why a settings row read smaller than the palette row beside it. Now the
 * only file with a number in it is the scale, so changing the balance is one edit and every screen
 * follows. A number typed at a call site is easy to add and impossible to see, so this is what
 * sees it.
 */
class TypeScaleTest {

    /** The sizes the scale actually has, as numbers, since that is how they are written. */
    private val allowed: Set<Float> = listOf(
        FlowType.caption, FlowType.small, FlowType.body, FlowType.bodyStrong, FlowType.title, FlowType.mono,
    ).map { it.size.value }.toSet()

    /** Where the UI is written. The scale itself is exempt: it is where the numbers are declared. */
    private fun sources(): List<File> {
        val root = File("src/commonMain/kotlin/flow/ui").takeIf { it.isDirectory }
            ?: File("flow/src/commonMain/kotlin/flow/ui")
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" && it.name != "FlowType.kt" }.toList()
    }

    @Test
    fun `no screen names a size of its own`() {
        // a size, but not the two text measurements that are not sizes
        val size = Regex("""(?<!letterSpacing = )(?<!lineHeight = )\b(\d+(?:\.\d+)?)\.sp\b""")
        val offenders = mutableListOf<String>()
        sources().forEach { file ->
            file.readLines().forEachIndexed { i, line ->
                size.findAll(line).forEach { match ->
                    offenders += "${file.name}:${i + 1}  ${match.value}"
                }
            }
        }
        assertTrue(
            offenders.isEmpty(),
            "these name a size instead of asking FlowType for one (the scale is " +
                "${allowed.sorted().joinToString()}):\n" + offenders.joinToString("\n"),
        )
    }
}
