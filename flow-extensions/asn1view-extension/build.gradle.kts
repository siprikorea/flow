plugins {
    kotlin("jvm") version "2.2.20"
    // this view opens a window of its own, which means it is written in Compose
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.20"
    id("org.jetbrains.compose") version "1.9.0"
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // Everything here is compileOnly. The extension contract and Compose both come from the app,
    // which lends them to the worker: Compose's rendering half ships a native library per platform,
    // and a jar carrying them all would be a hundred megabytes for every view that wanted a window.
    compileOnly(project(":flow-extension-api"))
    compileOnly(compose.runtime)
    compileOnly(compose.foundation)
    compileOnly(compose.ui)
    compileOnly(compose.desktop.currentOs)
}



// distribution JAR: ./gradlew :flow-extensions:asn1view-extension:jar → build/libs/asn1view-extension.jar
tasks.jar {
    archiveBaseName.set("asn1view-extension")
}
