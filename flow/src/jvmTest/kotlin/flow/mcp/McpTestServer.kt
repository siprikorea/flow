package flow.mcp

import flow.mcp.stdio.McpStdio
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PipedInputStream
import java.io.PipedOutputStream

/**
 * The real server, on a pipe, for a test to talk to.
 *
 * The tools are reached the way a client reaches them — through the MCP SDK, over a transport,
 * as JSON-RPC text — rather than through a seam that skips the protocol. What a test asserts on
 * is then what a client would actually receive: the SDK's framing, its error shapes, and its
 * initialize handshake included.
 *
 * One server serves the whole suite: it is started on first use and lives for the JVM, because
 * starting one costs a scan of the module store and the tests are sequential anyway.
 */
internal object McpTestServer {

    private val toServer = PipedOutputStream()
    private val fromServer = PipedInputStream(BUFFER)
    private val writer = toServer.bufferedWriter()
    private val reader: BufferedReader by lazy { BufferedReader(InputStreamReader(fromServer)) }

    private var started = false

    private fun ensureStarted() {
        if (started) return
        started = true
        McpStdio.serve(
            "flow", "1.0.0", FlowMcpServer.tools(),
            PipedInputStream(toServer, BUFFER), PipedOutputStream(fromServer),
        )
        // A session answers nothing until it has been initialized, so the handshake is part of
        // starting up rather than something every test has to remember.
        send("""{"jsonrpc":"2.0","id":0,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"test","version":"1.0"}}}""")
        readReply(0)
        send("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")
    }

    /** Sends one request and returns the reply, as the client would read it off the pipe. */
    @Synchronized
    fun request(line: String): String {
        val id = Regex(""""id"\s*:\s*(\d+)""").find(line)?.groupValues?.get(1)?.toInt()
            ?: error("a request needs a numeric id: $line")
        ensureStarted()
        send(line)
        return readReply(id)
    }

    private fun send(line: String) {
        writer.write(line)
        writer.write("\n")
        writer.flush()
    }

    /**
     * The reply to one request.
     *
     * Anything else on the way — a notification the server sends of its own accord — is passed
     * over, so a test is never handed a message it did not ask for.
     */
    private fun readReply(id: Int): String {
        while (true) {
            val line = reader.readLine() ?: error("the server closed the pipe")
            if (line.isBlank()) continue
            if (Regex(""""id"\s*:\s*$id\b""").containsMatchIn(line)) return line
        }
    }

    /** Room for a reply bigger than a pipe's 1KB default — the tool list is tens of kilobytes. */
    private const val BUFFER = 1 shl 20
}
