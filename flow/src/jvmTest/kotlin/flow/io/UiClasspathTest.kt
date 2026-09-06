package flow.io

import flow.extension.host.Wire
import flow.platform.ExtensionProcess
import java.awt.GraphicsEnvironment
import java.io.File
import java.security.KeyPairGenerator
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A view really opens a window with the classpath its process is given.
 *
 * A view is written in Compose but does not carry it — the app lends its own. Getting what to lend
 * wrong fails silently: the class that builds the window is not there, the toolkit swallows the
 * NoClassDefFoundError on its own thread, and no window appears.
 *
 * That has now happened twice, and what this test used to do would not have caught either: it
 * called Class.forName on a handful of top-level names, and loading a class does not resolve what
 * that class refers to. Compose 1.12 added androidx.navigationevent, the lending list did not have
 * it, every name here still loaded, and views stopped opening. So this opens one for real — the
 * only check that exercises the whole graph.
 */
class UiClasspathTest {

    private var worker: ExtensionProcess? = null

    @AfterTest
    fun stop() {
        worker?.kill()
    }

    /** Whether this machine can put a window on a screen at all; without one there is nothing to test. */
    private fun hasDisplay(): Boolean {
        if (GraphicsEnvironment.isHeadless()) return false
        return runCatching {
            java.awt.Frame().apply { pack(); dispose() }
            true
        }.getOrDefault(false)
    }

    @Test
    fun `a view opens a window with what the app lends it`() {
        if (!hasDisplay()) return

        val jar = File("../flow-extensions/asn1view-extension/build/libs/asn1view-extension.jar")
            .let { if (it.isFile) it else File("flow-extensions/asn1view-extension/build/libs/asn1view-extension.jar") }
        assertTrue(jar.isFile, "run :flow-extensions:asn1view-extension:jar first — ${jar.absolutePath}")

        val proc = ExtensionProcess(jar.parentFile, listOf(jar)).also { worker = it }
        val key = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().public.encoded
        val reply = proc.request(Wire.VIEW_OPEN) { o ->
            Wire.writeString(o, "flow.view.asn1")
            Wire.writeBytes(o, key)
            Wire.writeStringMap(o, emptyMap())
        }

        // the worker answers only once a window is actually on screen, so this is the real thing
        assertTrue(reply.ok, "the view did not open: ${reply.payload.decodeToString()}")
    }
}
