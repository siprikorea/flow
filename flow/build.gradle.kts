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
            // Lucide icon set (the design guide's one icon language) — Compose Multiplatform
            // variant: `com.composables:icons-lucide`, not the `-android`-suffixed one.
            implementation("com.composables:icons-lucide:1.1.0")
            // Renders the AI panel's replies, which come back as Markdown. The core module only
            // (no -m2/-m3): those pull in a Material theme this app doesn't otherwise use, and the
            // core module's colors/typography are supplied directly from Palette/FlowType instead.
            // Pinned to 0.38.0 rather than latest: newer releases pull a kotlin-stdlib built with a
            // newer Kotlin than this project's 2.2.20 plugin, which that compiler cannot even read
            // the metadata of ("compiled with an incompatible version of Kotlin"). 0.38.0 is the
            // newest release still built against a 2.2.x stdlib.
            implementation("com.mikepenz:multiplatform-markdown-renderer:0.38.0")
        }
        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
            implementation(project(":flow-extension-api"))
            implementation(project(":flow-extension-host"))
            // JBR's window-decoration API: lets the title bar tell the OS exactly how tall it is,
            // so native traffic lights centre on it instead of on some assumed default height.
            // compose.desktop.currentOs already runs on JetBrains Runtime, so this is always
            // available at runtime; Main.kt still checks JBR.isAvailable() defensively.
            implementation("org.jetbrains.runtime:jbr-api:1.5.0")
        }
        // AppIcon.kt (the Dock/Taskbar/window icon) loads "appicon.png" off the runtime
        // classpath — reading straight out of icons/ instead of keeping a second copy under
        // src/jvmMain/resources, so the PNG that Gradle packages the app with (jpackage,
        // mcpbStage) and the one the running app loads are the same file, not two that can drift.
        // Only the PNG is pulled in — icons/appicon.svg and .icns are build-time-only and would
        // otherwise ride along into the jar for no reason.
        getByName("jvmMain") {
            resources.srcDir("icons")
            resources.include("appicon.png")
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
            // renders a composable to an image without a window, for looking at what a change did
            implementation(compose.desktop.uiTestJUnit4)
            // the shipped extensions are tested against their specs here, where a failure is loud;
            // in the app they are loaded from jars, so this is a compile dependency only
            implementation(project(":flow-extensions:hotp-extension"))
            implementation(project(":flow-extensions:totp-extension"))
            implementation(project(":flow-extensions:jwt-extension"))
            implementation(project(":flow-extensions:json-extension"))
            implementation(project(":flow-extensions:qr-extension"))
            implementation(project(":flow-extensions:asn1view-extension"))
            implementation(project(":flow-extensions:imageview-extension"))
            implementation(project(":flow-extension-api"))
        }
    }
}

// The version jpackage stamps on the installer, and so the name of the file that ships:
// Flow-<version>.dmg. CI passes -PflowVersion=1.2.3 off the v* tag it is building, so a tagged
// release cannot go out named after the previous version; a local build takes the fallback.
val flowVersion = (findProperty("flowVersion") as String?)?.takeIf { it.isNotBlank() } ?: "1.0.0"
require(Regex("""[1-9]\d*(\.\d+){0,2}""").matches(flowVersion)) {
    // jpackage is strict here and fails late, well into the build, with a message about CFBundleVersion
    "flowVersion must be 1-3 numbers with a non-zero major, e.g. 1.2.3 — got '$flowVersion'"
}

compose.desktop {
    application {
        mainClass = "flow.MainKt"
        // App name + Dock icon shown at launch (so the default Java icon never flashes)
        jvmArgs += listOf(
            "-Dapple.awt.application.name=Flow",
            "-Xdock:name=Flow",
            "-Xdock:icon=${project.projectDir}/icons/appicon.png",
        )

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "Flow"
            packageVersion = flowVersion

            macOS {
                iconFile.set(project.file("icons/appicon.icns"))
                bundleID = "com.siprikorea.flow"
            }
            linux {
                iconFile.set(project.file("icons/appicon.png"))
            }
            // no .ico on hand (built on macOS, no Windows/ImageMagick tooling here) — jpackage falls
            // back to its default icon for the Windows target until one is added.
        }
    }
}

// Launcher script for the MCP server. An MCP client starts the server with a plain command and
// reads the protocol off its stdout, which gradle's own output would corrupt — so this bakes the
// runtime classpath into build/flow-mcp instead of going through :flow:cli.
tasks.register("mcpLauncher") {
    group = "application"
    description = "Write build/flow-mcp, a launcher for the MCP server"
    dependsOn("jvmMainClasses")
    val compilation = kotlin.jvm().compilations.getByName("main")
    val runtimeClasspath = files(compilation.output.allOutputs, compilation.runtimeDependencyFiles)
    val script = layout.buildDirectory.file("flow-mcp")
    outputs.file(script)
    doLast {
        val file = script.get().asFile
        file.writeText(
            "#!/bin/sh\n" +
                "# generated by :flow:mcpLauncher\n" +
                "exec java -cp \"" + runtimeClasspath.asPath + "\" flow.cli.CliKt --mcp \"$@\"\n"
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

// Claude Desktop extension bundle (MCPB), built in three steps: mcpbStage lays out the directory,
// mcpbBundleVerify drives the server staged there, and mcpbBundle zips it into build/flow.mcpb —
// installable from Settings ▸ Extensions ▸ Advanced settings ▸ Install Extension….
//
// The MCP/CLI path never touches Compose or Skiko, so only these dependency jars ship — keeping the
// UI stack out takes the bundle well under 1MB now that no extension jars ride along either. A new
// runtime dependency on that path needs its prefix added here (mcpbBundleVerify below catches a miss).
val mcpbServerJars = listOf("kotlin-stdlib", "kotlinx-serialization", "annotations-", "flow-extension-api", "flow-extension-host")

val mcpbStage = tasks.register<Copy>("mcpbStage") {
    group = "application"
    description = "Stage build/mcpb, the contents of the Claude Desktop extension bundle"
    val compilation = kotlin.jvm().compilations.getByName("main")
    val deps = compilation.runtimeDependencyFiles.filter { f ->
        mcpbServerJars.any { f.name.startsWith(it) }
    }

    into(layout.buildDirectory.dir("mcpb"))
    // The version in the manifest is what Claude Desktop compares to decide an installed bundle is
    // out of date, so a release stamps the version it actually is rather than whatever the file was
    // last edited to say. "manifest_version" is a different key and so is left alone.
    from("mcpb/manifest.json") {
        val version = Regex("(\"version\":\\s*\")[^\"]+(\")")
        filter { line -> version.replace(line) { m -> m.groupValues[1] + flowVersion + m.groupValues[2] } }
    }
    from("icons/appicon.png") { rename { "icon.png" } }
    from("mcpb/flow-mcp") { into("server"); filePermissions { unix("0755") } }
    from(tasks.named("jvmJar")) { into("server/lib") }
    from(deps) { into("server/lib") }
}

// Smoke test: drive the staged bundle over stdio the way Claude Desktop does and require the tool
// list back, so a bundle that is missing a jar fails here instead of in the client.
val mcpbBundleVerify = tasks.register("mcpbBundleVerify") {
    group = "verification"
    description = "Run the staged bundle's server and check it lists tools"
    dependsOn(mcpbStage)
    val script = layout.buildDirectory.file("mcpb/server/flow-mcp")
    doLast {
        val proc = ProcessBuilder("/bin/sh", script.get().asFile.absolutePath)
            .redirectErrorStream(false).start()
        proc.outputStream.bufferedWriter().use {
            it.write("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}""" + "\n")
            it.write("""{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}""" + "\n")
            // nothing ships with the app any more, so what this proves is that the tool runs at all
            // — it reaches the extension store and answers, whatever the machine happens to have
            it.write("""{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"list_nodes","arguments":{}}}""" + "\n")
        }
        val out = proc.inputStream.bufferedReader().readText()
        val err = proc.errorStream.bufferedReader().readText()
        proc.waitFor()
        check(out.contains("\"tools\"")) {
            "bundle server did not list its tools\nstdout: $out\nstderr: $err"
        }
        // The tool set is fixed, so every one of them must be here — a miss means the bundle is
        // short a jar and would fail in the client instead.
        val expected = listOf("list_nodes", "list_flows", "read_flow", "validate_flow", "run_flow", "build_flow", "save_flow")
        val missing = expected.filterNot { out.contains("\"name\":\"$it\"") }
        check(missing.isEmpty()) {
            "bundle server is missing tool(s): ${missing.joinToString(", ")}\nstdout: $out\nstderr: $err"
        }
        check(out.contains("boundary nodes")) {
            "list_nodes did not answer — the bundle is short a jar\nstdout: $out\nstderr: $err"
        }
        logger.lifecycle("mcpb bundle OK — ${expected.size} tools, list_nodes answers")
    }
}

// The zip step is the mcpb CLI's own, so it stays authoritative on the archive format; it ships as
// an npm package and Claude Desktop already requires Node, so npx is a fair thing to reach for.
// Verification is a dependency rather than a separate habit — the bundle it checks for a missing
// jar is exactly the one about to be handed to the client.
tasks.register<Exec>("mcpbBundle") {
    group = "application"
    description = "Build build/flow.mcpb, the Claude Desktop extension bundle"
    dependsOn(mcpbBundleVerify)
    val staged = layout.buildDirectory.dir("mcpb")
    val bundle = layout.buildDirectory.file("flow.mcpb")
    inputs.dir(staged)
    outputs.file(bundle)
    commandLine("npx", "-y", "@anthropic-ai/mcpb", "pack", staged.get().asFile.path, bundle.get().asFile.path)
    doFirst {
        check(ProcessBuilder("sh", "-c", "command -v npx").start().waitFor() == 0) {
            "npx not found — the .mcpb is zipped by @anthropic-ai/mcpb, which needs Node. " +
                "Install Node, or hand Claude Desktop the staged directory instead: run " +
                ":flow:mcpbStage and pick ${staged.get().asFile.path} with Install Unpacked Extension."
        }
    }
    doLast { logger.lifecycle("mcpb bundle written to ${bundle.get().asFile.path}") }
}
