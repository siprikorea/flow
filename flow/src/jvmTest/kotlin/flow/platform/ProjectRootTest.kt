package flow.platform

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The project is whichever folder the user opened, or none — the app starts with none, the way an
 * editor does. Every path operation is relative to that folder and has to read as empty while there
 * is none, rather than falling back to somewhere of its own choosing.
 */
class ProjectRootTest {

    private val temp = mutableListOf<File>()

    private fun folder(vararg flows: String): File {
        val dir = File.createTempFile("flow-project", "").apply { delete(); mkdirs() }
        temp += dir
        flows.forEach { rel ->
            File(dir, rel).apply { parentFile.mkdirs() }.writeText(EMPTY_FLOW)
        }
        return dir
    }

    @AfterTest
    fun cleanUp() {
        // the root is process-wide, so leaving one open would follow the next test around
        Platform.openProject(null)
        temp.forEach { it.deleteRecursively() }
    }

    @Test
    fun `with no folder open there is no project and nothing is listed`() {
        Platform.openProject(null)
        assertNull(Platform.projectRoot())
        assertNull(Platform.projectName())
        assertEquals(emptyList(), Platform.listFlows())
        assertEquals(emptyList(), Platform.listFlowDirs())
        assertNull(Platform.readFlow("anything.flow"))
    }

    @Test
    fun `opening a folder lists what is in it, at paths relative to it`() {
        val dir = folder("a.flow", "sub/b.flow")
        Platform.openProject(dir.absolutePath)
        assertEquals(dir.absolutePath, Platform.projectRoot())
        assertEquals(dir.name, Platform.projectName())
        assertEquals(listOf("a.flow", "sub/b.flow"), Platform.listFlows())
        assertEquals(listOf("sub"), Platform.listFlowDirs())
    }

    @Test
    fun `opening another folder replaces the first`() {
        val one = folder("a.flow")
        val two = folder("b.flow")
        Platform.openProject(one.absolutePath)
        assertEquals(listOf("a.flow"), Platform.listFlows())
        Platform.openProject(two.absolutePath)
        assertEquals(listOf("b.flow"), Platform.listFlows())
    }

    @Test
    fun `closing the folder leaves nothing open`() {
        Platform.openProject(folder("a.flow").absolutePath)
        Platform.openProject(null)
        assertNull(Platform.projectRoot())
        assertEquals(emptyList(), Platform.listFlows())
    }

    @Test
    fun `a path cannot reach outside the folder that is open`() {
        val dir = folder("a.flow")
        val outside = File(dir.parentFile, "outside-${dir.name}.flow").apply { writeText(EMPTY_FLOW) }
        temp += outside
        Platform.openProject(dir.absolutePath)
        assertNull(Platform.readFlow("../${outside.name}"), "a relative path climbed out of the project")
        assertNull(Platform.readFlow("sub/../../${outside.name}"))
    }

    @Test
    fun `a file outside any folder is read and written by its own path`() {
        // this is what File > Open File does with no folder open: the file is worked on where it is
        val f = File.createTempFile("flow-standalone", ".flow").also { temp += it }
        f.writeText(EMPTY_FLOW)
        Platform.openProject(null)
        assertTrue(Platform.readExternalFlow(f.absolutePath) != null)
        assertTrue(Platform.writeExternalFlow(f.absolutePath, EMPTY_FLOW))
        assertEquals(EMPTY_FLOW, f.readText())
    }

    @Test
    fun `only flow files are written by absolute path`() {
        val other = File.createTempFile("flow-test", ".txt").also { temp += it }
        assertFalse(Platform.writeExternalFlow(other.absolutePath, EMPTY_FLOW))
    }

    private companion object {
        const val EMPTY_FLOW = """{"version":1,"nodes":[],"edges":[],"seq":1}"""
    }
}
