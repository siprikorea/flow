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

compose.desktop {
    application {
        mainClass = "flow.MainKt"
        // App name + Dock icon shown at launch (so the default Java icon never flashes)
        jvmArgs += listOf(
            "-Dapple.awt.application.name=Flow",
            "-Xdock:name=Flow",
            "-Xdock:icon=${project.projectDir}/appicon.png",
        )
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
