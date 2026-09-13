package flow.io

import flow.qr.QrModule
import java.io.File
import kotlin.test.Test

/**
 * Writes sample codes out for checking against a real scanner. Skipped unless QR_DUMP names a
 * directory, so it costs nothing in an ordinary run.
 */
class QrDumpTest {
    @Test
    fun `dump sample codes`() {
        val dir = System.getenv("QR_DUMP")?.let { File(it) } ?: return
        dir.mkdirs()
        // lengths chosen to walk the version range: 1-9 (8-bit count), 10-26, then 27-40 with
        // version information blocks and 16-bit counts
        val sweep = listOf(1, 10, 30, 60, 100, 180, 280, 400, 600, 900, 1200, 1600, 2000, 2300)
        sweep.forEach { n ->
            val text = (1..n).joinToString("") { ('a' + (it % 26)).toString() }
            val png = QrModule().process(
                mapOf("in" to text.encodeToByteArray()),
                mapOf("correction" to "L", "moduleSize" to "4", "quietZone" to "4"),
            )["out"]!!
            File(dir, "sweep-%04d.png".format(n)).writeBytes(png)
            File(dir, "sweep-%04d.txt".format(n)).writeText(text)
        }

        val cases = listOf(
            "hello" to "HELLO WORLD",
            "url" to "https://github.com/siprikorea/flow-modules",
            "korean" to "안녕하세요, Flow 입니다",
            "long" to (1..40).joinToString(" ") { "chunk$it" },
        )
        cases.forEach { (name, text) ->
            listOf("L", "M", "Q", "H").forEach { ecc ->
                val png = QrModule().process(
                    mapOf("in" to text.encodeToByteArray()),
                    mapOf("correction" to ecc, "moduleSize" to "8", "quietZone" to "4"),
                )["out"]!!
                File(dir, "$name-$ecc.png").writeBytes(png)
                File(dir, "$name-$ecc.txt").writeText(text)
            }
        }
    }
}
