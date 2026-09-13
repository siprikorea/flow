plugins {
    kotlin("jvm") version "2.4.0"
}

kotlin {
    jvmToolchain(25)
}

// The MCP protocol, and nothing about Flow.
//
// This module is the one place that knows the Model Context Protocol exists: it wraps the official
// Java SDK (io.modelcontextprotocol.sdk, MIT) and offers it as a tool list and a stdio server. The
// app depends on it and hands it tools; it depends on nothing of the app's, so what the protocol
// requires and what a Flow tool does stay separable — and a second transport (HTTP) is a change in
// here alone.
//
// The SDK brings Jackson (Apache-2.0), Project Reactor (Apache-2.0), reactive-streams (MIT-0) and
// the SLF4J facade (MIT). All are licences this repository accepts. No SLF4J *binding* is added on
// purpose: with none, SLF4J is a no-op and cannot write a log line into the protocol's own stdout.
dependencies {
    api("io.modelcontextprotocol.sdk:mcp-core:2.0.1")
    // The SDK reads and writes its JSON through whichever mapper is on the classpath, found by
    // ServiceLoader — so exactly one of these belongs here, and it is needed only at runtime.
    //
    // Not the `mcp` umbrella artifact: it brings both the Jackson 2 and the Jackson 3 module, and
    // they ask for different major versions of the schema validator. Gradle resolves that to the
    // newer one, and the Jackson 2 module then dies on a method that no longer exists the moment a
    // server is built. One module, one validator.
    runtimeOnly("io.modelcontextprotocol.sdk:mcp-json-jackson3:2.0.1")
}
