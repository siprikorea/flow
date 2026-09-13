import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("multiplatform") version "2.4.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.0"
    kotlin("plugin.serialization") version "2.4.0"
    id("org.jetbrains.compose") version "1.12.0"
}

// The Java this app is compiled for and runs on. Named once because three places need it: the
// toolchain, the launcher :flow:mcpLauncher bakes into its script, and flow/mcpb/flow-mcp's own
// version check (that one is a shell script, so it carries its own copy — keep them in step).
val jdk = 25

kotlin {
    jvmToolchain(jdk)
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
            // It declares no Compose dependency of its own (compileOnly), so it links against
            // whatever org.jetbrains.compose above resolves to and needs that to be at least as
            // new as the Compose it was built against. Pinning it to 0.38.0 while Compose sat at
            // 1.9.0 was what that costs when they drift: the version before this one compiled
            // fine and then died on the first reply rendered, NoSuchMethodError on
            // ComposeUiNode$Companion.getApplyOnDeactivatedNodeAssertion. Move the two together.
            implementation("com.mikepenz:multiplatform-markdown-renderer:0.45.0")
        }
        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
            implementation(project(":flow-module-api"))
            implementation(project(":flow-module-host"))
            // The MCP protocol, kept in one module of its own — see :flow-mcp. The app depends on
            // it because the app is what starts the server: the CLI's --mcp, and the launchers the
            // AI panel writes for Claude Code/Codex/Gemini, all run out of this same classpath.
            implementation(project(":flow-mcp"))
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
            // the shipped modules are tested against their specs here, where a failure is loud;
            // in the app they are loaded from jars, so this is a compile dependency only
            implementation(project(":flow-modules:ai-module"))
            implementation(project(":flow-modules:slack-module"))
            implementation(project(":flow-modules:telegram-module"))
            implementation(project(":flow-modules:base64-module"))
            implementation(project(":flow-modules:branch-module"))
            implementation(project(":flow-modules:cipher-module"))
            implementation(project(":flow-modules:hash-module"))
            implementation(project(":flow-modules:keyfactory-module"))
            implementation(project(":flow-modules:keygen-module"))
            implementation(project(":flow-modules:keypairgen-module"))
            implementation(project(":flow-modules:keystore-module"))
            implementation(project(":flow-modules:mac-module"))
            implementation(project(":flow-modules:mcp-module"))
            implementation(project(":flow-modules:merge-module"))
            implementation(project(":flow-modules:securerandom-module"))
            implementation(project(":flow-modules:signature-module"))
            implementation(project(":flow-modules:slice-module"))
            implementation(project(":flow-modules:sleep-module"))
            implementation(project(":flow-modules:split-module"))
            implementation(project(":flow-modules:hotp-module"))
            implementation(project(":flow-modules:totp-module"))
            implementation(project(":flow-modules:jwt-module"))
            implementation(project(":flow-modules:json-module"))
            implementation(project(":flow-modules:qr-module"))
            implementation(project(":flow-modules:asn1view-module"))
            implementation(project(":flow-modules:imageview-module"))
            implementation(project(":flow-module-api"))
        }
    }
}

// The view tests start real JVMs and load Skiko, so they need the same native-access grant the
// app is launched with — without it every test JVM prints JEP 472's restricted-method warning.
tasks.withType<Test>().configureEach {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    // Where the tests read the module store from. A developer machine has modules installed
    // under ~/.flow and CI has none, so tests that touch one pass here and fail there — which is
    // how two of them reached main red. `-PflowTestHome=/tmp/empty` runs them the way CI sees them.
    providers.gradleProperty("flowTestHome").orNull?.let { systemProperty("user.home", it) }
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
            // Skiko loads its native library with System.load, which JEP 472 restricts: on 25 that
            // prints a three-line warning to stderr at every launch and is set to become an error.
            // Granting it explicitly is the documented answer, and keeps the app working when the
            // default flips.
            "--enable-native-access=ALL-UNNAMED",
        )

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "Flow"
            packageVersion = flowVersion

            // The runtime jpackage builds contains only the modules named here — everything else
            // is left out, and a class from a missing one is a NoClassDefFoundError at startup
            // with no build ever having complained. That shipped: the AI providers use
            // java.net.http, which is not in the default set, and v1.5.x would not launch at all
            // from the .dmg while running perfectly from Gradle, where the whole JDK is present.
            //
            // :flow:suggestRuntimeModules lists what the app's own code reaches for. The rest are
            // for the modules, which run on this same runtime in a worker and are invisible to
            // that analysis: EC keys, the XML the ASN.1 viewer's DER work leans on, and the
            // scripting-free crypto providers the key modules ask for by name.
            modules(
                "java.instrument",
                "java.net.http",
                "jdk.unsupported",
                "jdk.crypto.ec",
                "java.naming",
                "java.security.jgss",
                "java.xml",
                "java.management",
                "jdk.zipfs",
            )

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

/**
 * Puts the `java` launcher back into the packaged runtime.
 *
 * jpackage builds the bundled runtime with jlink and strips its native commands, so the app image
 * ships a complete JVM with no way to start one. Everything Flow runs out of process needs exactly
 * that: every module runs in a worker (ModuleProcess), and so does the MCP server the AI
 * panel serves its tools from — all of them started with java.home/bin/java, which in an installed
 * app was a path to nothing. The result was an app where no module worked at all and nothing
 * said why, while everything ran perfectly from Gradle, where the JDK is whole.
 *
 * The launcher is the JDK's own and the runtime beside it is the one it was built from, so it finds
 * its libjli through the same relative rpath it always does. Copying into the image invalidates the
 * signature jpackage put on it, hence the ad-hoc re-sign — the same kind jpackage applies itself.
 */
val restoreRuntimeLauncher = tasks.register("restoreRuntimeLauncher") {
    description = "Put bin/java back into the packaged runtime, which jpackage strips"
    val launcher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(jdk) }
        .map { it.executablePath.asFile }
    val imageDir = layout.buildDirectory.dir("compose/binaries/main/app")
    doLast {
        val java = launcher.get()
        val root = imageDir.get().asFile
        // The runtime sits at a different depth on each platform — Contents/runtime/Contents/Home
        // on macOS, lib/runtime elsewhere — so it is found by the one file every runtime has in the
        // same place: lib/modules, the jimage. Its grandparent is the home bin/ belongs beside.
        val homes = root.walkTopDown()
            .filter { it.isFile && it.name == "modules" && it.parentFile.name == "lib" }
            .map { it.parentFile.parentFile }
            .distinct()
            .toList()
        if (homes.isEmpty()) {
            logger.warn("no runtime found under $root — bin/java was not restored")
            return@doLast
        }
        homes.forEach { home ->
            val bin = File(home, "bin").apply { mkdirs() }
            val target = File(bin, java.name)
            java.copyTo(target, overwrite = true)
            target.setExecutable(true)
            logger.lifecycle("restored ${target.relativeTo(root)}")
        }
        // ad-hoc, the way jpackage signs an app image it built: without this the copy leaves the
        // signature it made no longer matching what is on disk, and macOS refuses to open it
        root.listFiles { f -> f.name.endsWith(".app") }?.forEach { app ->
            val signed = ProcessBuilder("codesign", "--force", "--sign", "-", app.absolutePath)
                .redirectErrorStream(true).start()
            val said = signed.inputStream.readBytes().decodeToString().trim()
            if (signed.waitFor() != 0) logger.warn("could not re-sign ${app.name}: $said")
        }
    }
}

// Every packaged form is made from the app image, so the launcher goes back before any of them is
// built rather than in each one.
tasks.matching { it.name == "createDistributable" }.configureEach { finalizedBy(restoreRuntimeLauncher) }

// Launcher script for the MCP server. An MCP client starts the server with a plain command and
// reads the protocol off its stdout, which gradle's own output would corrupt — so this bakes the
// runtime classpath into build/flow-mcp instead of going through :flow:cli.
//
// The toolchain's own java is baked in too, not a bare `java`: the classes are Java 25 bytecode and
// whatever is first on a developer's PATH usually isn't, which shows up in the client as a server
// that starts and immediately dies.
tasks.register("mcpLauncher") {
    group = "application"
    description = "Write build/flow-mcp, a launcher for the MCP server"
    dependsOn("jvmMainClasses")
    val compilation = kotlin.jvm().compilations.getByName("main")
    val runtimeClasspath = files(compilation.output.allOutputs, compilation.runtimeDependencyFiles)
    val java = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(jdk) }
        .map { it.executablePath.asFile.absolutePath }
    val script = layout.buildDirectory.file("flow-mcp")
    outputs.file(script)
    doLast {
        val file = script.get().asFile
        file.writeText(
            "#!/bin/sh\n" +
                "# generated by :flow:mcpLauncher\n" +
                "exec \"" + java.get() + "\" -cp \"" + runtimeClasspath.asPath + "\" flow.cli.CliKt --mcp \"$@\"\n"
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
// UI stack out takes the bundle well under 1MB now that no module jars ride along either. A new
// runtime dependency on that path needs its prefix added here (mcpbBundleVerify below catches a miss).
val mcpbServerJars = listOf(
    "kotlin-stdlib", "kotlinx-serialization", "annotations-", "flow-module-api", "flow-module-host",
    // the MCP server: :flow-mcp and the official SDK it wraps, with the JSON mapper the SDK finds
    // by ServiceLoader, the schema validator it checks tool schemas with, and Reactor underneath
    "flow-mcp", "mcp-core", "mcp-json-jackson3", "jackson-", "json-schema-validator", "itu-",
    "reactor-core", "reactive-streams", "slf4j-api", "snakeyaml",
)

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
        val toServer = proc.outputStream.bufferedWriter()
        val fromServer = proc.inputStream.bufferedReader()
        // Written and read one at a time, with the pipe kept open: the server stops reading the
        // moment stdin closes, so writing everything and then closing loses whatever it had not
        // got to yet.
        fun ask(line: String): String {
            toServer.write(line); toServer.write("\n"); toServer.flush()
            return fromServer.readLine() ?: ""
        }
        ask(
            """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05",""" +
                """"capabilities":{},"clientInfo":{"name":"mcpbBundleVerify","version":"1.0"}}}""",
        )
        // the handshake a client actually performs, since that is what is being checked here
        toServer.write("""{"jsonrpc":"2.0","method":"notifications/initialized"}""" + "\n")
        toServer.flush()
        val tools = ask("""{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}""")
        // nothing ships with the app any more, so what this proves is that the tool runs at all
        // — it reaches the module store and answers, whatever the machine happens to have
        val nodes = ask("""{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"list_nodes","arguments":{}}}""")
        toServer.close()
        val err = proc.errorStream.bufferedReader().readText()
        proc.waitFor()
        val out = tools + "\n" + nodes
        check(out.contains("\"tools\"")) {
            "bundle server did not list its tools\nstdout: $out\nstderr: $err"
        }
        // The tool set is fixed, so every one of them must be here — a miss means the bundle is
        // short a jar and would fail in the client instead.
        val expected = listOf(
            "list_nodes", "list_flows", "read_flow", "validate_flow", "run_flow", "build_flow", "save_flow",
            "open_flow", "set_flow_input", "start_flow", "stop_flow",
        )
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
