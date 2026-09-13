plugins {
    kotlin("jvm") version "2.4.0"
    // this view opens a window of its own, which means it is written in Compose
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.0"
    id("org.jetbrains.compose") version "1.12.0"
}

kotlin {
    jvmToolchain(25)
}

dependencies {
    // Everything here is compileOnly. The module contract and Compose both come from the app,
    // which lends them to the worker: Compose's rendering half ships a native library per platform,
    // and a jar carrying them all would be a hundred megabytes for every view that wanted a window.
    compileOnly(project(":flow-module-api"))
    compileOnly(compose.runtime)
    compileOnly(compose.foundation)
    compileOnly(compose.ui)
    compileOnly(compose.desktop.currentOs)
}



// distribution JAR: ./gradlew :flow-modules:imageview-module:jar → build/libs/imageview-module.jar
tasks.jar {
    archiveBaseName.set("imageview-module")
}
