package flow.io

import flow.ui.theme.FlowType
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Every size in the program is one of the six the scale has.
 *
 * They drifted: 8, 9, 10, 10.5, 11, 11.5, 12, 12.5, 13, 14 and 15 were all in use at once, most of
 * them in the screens written before the scale existed, and the result was a settings page whose
 * rows were smaller than the palette's beside it for no reason anyone could name. A size written as
 * a number is easy to add and impossible to notice, so this is what notices.
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
    fun `no text is sized outside the scale`() {
        // a size, but not the two text measurements that are not sizes
        val size = Regex("""(?<!letterSpacing = )(?<!lineHeight = )\b(\d+(?:\.\d+)?)\.sp\b""")
        val offenders = mutableListOf<String>()
        sources().forEach { file ->
            file.readLines().forEachIndexed { i, line ->
                size.findAll(line).forEach { match ->
                    val value = match.groupValues[1].toFloat()
                    if (value !in allowed) offenders += "${file.name}:${i + 1}  ${match.value}"
                }
            }
        }
        assertTrue(
            offenders.isEmpty(),
            "these are sized off the scale (${allowed.sorted().joinToString()}):\n" + offenders.joinToString("\n"),
        )
    }
}
