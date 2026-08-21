import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("multiplatform") version "2.2.20"
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.20"
    kotlin("plugin.serialization") version "2.2.20"
    id("org.jetbrains.compose") version "1.9.0"
}

kotlin {
    jvmToolchain(17)
    jvm()

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.ui)
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
        }
        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
            implementation(project(":flow-extension-api"))
        }
    }
}

// First-party extensions bundled into the app install itself (never user-removable).
// id -> the flow-extensions project that builds its jar.
val builtinExtensions = mapOf(
    "flow.base64" to ":flow-extensions:base64-extension",
    "flow.cipher" to ":flow-extensions:cipher-extension",
    "flow.hash" to ":flow-extensions:hash-extension",
    "flow.merge" to ":flow-extensions:merge-extension",
    "flow.split" to ":flow-extensions:split-extension",
    "flow.sleep" to ":flow-extensions:sleep-extension",
    "flow.mac" to ":flow-extensions:mac-extension",
    "flow.signature" to ":flow-extensions:signature-extension",
    "flow.keygen" to ":flow-extensions:keygen-extension",
    "flow.keypairgen" to ":flow-extensions:keypairgen-extension",
    "flow.keyfactory" to ":flow-extensions:keyfactory-extension",
    "flow.mcp" to ":flow-extensions:mcp-extension",
    "flow.slice" to ":flow-extensions:slice-extension",
    "flow.keystore" to ":flow-extensions:keystore-extension",
    "flow.securerandom" to ":flow-extensions:securerandom-extension",
)

// Lays each built-in extension's jar out as modules/<id>/<id>.jar (same shape ExtensionLoader
// expects for the user modules dir) under an app-resources root that Compose bundles into the
// install and exposes at runtime via the compose.application.resources.dir system property.
// Must live under a "common" subdir: Compose's prepareAppResources only pulls appResourcesRootDir's
// common/<os-id>/<target-id> subdirs (see ConfigureJvmApplicationKt.configureCommonJvmDesktopTasks),
// not the root itself, then flattens each of those into the resources dir.
val prepareBuiltinModules = tasks.register<Copy>("prepareBuiltinModules") {
    into(layout.buildDirectory.dir("builtinModules/common/modules"))
    builtinExtensions.forEach { (id, path) ->
        from(project(path).tasks.named("jar")) {
            into(id)
            rename { "$id.jar" }
        }
    }
}

compose.desktop {
    application {
        mainClass = "flow.MainKt"
        // App name + Dock icon shown at launch (so the default Java icon never flashes)
        jvmArgs += listOf(
            "-Dapple.awt.application.name=Flow",
            "-Xdock:name=Flow",
            "-Xdock:icon=${project.projectDir}/appicon.png",
        )

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "Flow"
            packageVersion = "1.0.0"
            appResourcesRootDir.set(layout.buildDirectory.dir("builtinModules"))

            macOS {
                iconFile.set(project.file("icons/appicon.icns"))
                bundleID = "com.siprikorea.flow"
            }
            linux {
                iconFile.set(project.file("appicon.png"))
            }
            // no .ico on hand (built on macOS, no Windows/ImageMagick tooling here) — jpackage falls
            // back to its default icon for the Windows target until one is added.
        }
    }
}

// the compose plugin registers prepareAppResources lazily, so match by name rather than tasks.named()
tasks.matching { it.name == "prepareAppResources" }.configureEach { dependsOn(prepareBuiltinModules) }

// Launcher script for the MCP server. An MCP client starts the server with a plain command and
// reads the protocol off its stdout, which gradle's own output would corrupt — so this bakes the
// runtime classpath into build/flow-mcp instead of going through :flow:cli.
tasks.register("mcpLauncher") {
    group = "application"
    description = "Write build/flow-mcp, a launcher for the MCP server"
    dependsOn("jvmMainClasses", prepareBuiltinModules)
    val compilation = kotlin.jvm().compilations.getByName("main")
    val runtimeClasspath = files(compilation.output.allOutputs, compilation.runtimeDependencyFiles)
    val resourcesDir = layout.buildDirectory.dir("builtinModules/common")
    val script = layout.buildDirectory.file("flow-mcp")
    outputs.file(script)
    doLast {
        val file = script.get().asFile
        val resources = resourcesDir.get().asFile
        file.writeText(
            "#!/bin/sh\n" +
                "# generated by :flow:mcpLauncher\n" +
                "exec java -Dcompose.application.resources.dir=\"" + resources + "\" " +
                "-cp \"" + runtimeClasspath.asPath + "\" flow.cli.CliKt --mcp \"$@\"\n"
        )
        file.setExecutable(true)
    }
}

// 터미널 실행: ./gradlew :flow:cli --args="triple 5"
tasks.register<JavaExec>("cli") {
    group = "application"
    description = "Run the DataFlow CLI (component executor)"
    dependsOn("jvmMainClasses")
    val compilation = kotlin.jvm().compilations.getByName("main")
    classpath = files(compilation.output.allOutputs, compilation.runtimeDependencyFiles)
    mainClass.set("flow.cli.CliKt")
}

// Claude Desktop extension bundle (MCPB). Stages build/mcpb with the manifest, the server jars and
// the built-in module jars; `npx @anthropic-ai/mcpb pack` zips that directory into a .mcpb file
// installable from Settings ▸ Extensions ▸ Advanced settings ▸ Install Extension….
//
// The MCP/CLI path never touches Compose or Skiko, so only these dependency jars ship — keeping the
// UI stack out takes the bundle from ~40MB to ~4MB. A new runtime dependency on that path needs its
// prefix added here (mcpbBundleVerify below catches a miss).
val mcpbServerJars = listOf("kotlin-stdlib", "kotlinx-serialization", "annotations-", "flow-extension-api")

val mcpbBundle = tasks.register<Copy>("mcpbBundle") {
    group = "application"
    description = "Stage build/mcpb, a Claude Desktop extension bundle for the MCP server"
    val compilation = kotlin.jvm().compilations.getByName("main")
    val deps = compilation.runtimeDependencyFiles.filter { f ->
        mcpbServerJars.any { f.name.startsWith(it) }
    }

    into(layout.buildDirectory.dir("mcpb"))
    from("mcpb/manifest.json")
    from("appicon.png") { rename { "icon.png" } }
    from("mcpb/flow-mcp") { into("server"); filePermissions { unix("0755") } }
    from(tasks.named("jvmJar")) { into("server/lib") }
    from(deps) { into("server/lib") }
    from(prepareBuiltinModules.map { it.destinationDir }) { into("server/resources/modules") }
}

// Smoke test: drive the staged bundle over stdio the way Claude Desktop does and require the tool
// list back, so a bundle that is missing a jar fails here instead of in the client.
tasks.register("mcpbBundleVerify") {
    group = "verification"
    description = "Run the staged bundle's server and check it lists tools"
    dependsOn(mcpbBundle)
    val script = layout.buildDirectory.file("mcpb/server/flow-mcp")
    doLast {
        val proc = ProcessBuilder("/bin/sh", script.get().asFile.absolutePath)
            .redirectErrorStream(false).start()
        proc.outputStream.bufferedWriter().use {
            it.write("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}""" + "\n")
            it.write("""{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}""" + "\n")
            // the tool list is fixed, so it alone would pass even with no module jars staged —
            // calling list_modules is what actually loads them
            it.write("""{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"list_modules","arguments":{}}}""" + "\n")
        }
        val out = proc.inputStream.bufferedReader().readText()
        val err = proc.errorStream.bufferedReader().readText()
        proc.waitFor()
        check(out.contains("\"tools\"")) {
            "bundle server did not list its tools\nstdout: $out\nstderr: $err"
        }
        // The tool set is fixed, so every one of them must be here — a miss means the bundle is
        // short a jar and would fail in the client instead.
        val expected = listOf("list_modules", "list_flows", "read_flow", "validate_flow", "write_flow")
        val missing = expected.filterNot { out.contains("\"name\":\"$it\"") }
        check(missing.isEmpty()) {
            "bundle server is missing tool(s): ${missing.joinToString(", ")}\nstdout: $out\nstderr: $err"
        }
        check(out.contains("flow.hash")) {
            "list_modules returned no built-in modules — the staged module jars did not load" +
                "\nstdout: $out\nstderr: $err"
        }
        logger.lifecycle("mcpb bundle OK — ${expected.size} tools, modules load")
    }
}
