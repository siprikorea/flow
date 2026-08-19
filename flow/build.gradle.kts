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

// 터미널 실행: ./gradlew :flow:cli --args="triple 5"
tasks.register<JavaExec>("cli") {
    group = "application"
    description = "Run the DataFlow CLI (component executor)"
    dependsOn("jvmMainClasses")
    val compilation = kotlin.jvm().compilations.getByName("main")
    classpath = files(compilation.output.allOutputs, compilation.runtimeDependencyFiles)
    mainClass.set("flow.cli.CliKt")
}
