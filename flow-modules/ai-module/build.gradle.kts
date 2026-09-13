plugins {
    kotlin("jvm") version "2.4.0"
}

kotlin {
    jvmToolchain(25)
}

dependencies {
    // the app provides the module interfaces, so this is compile-only (not bundled in the JAR)
    compileOnly(project(":flow-module-api"))
    // and its JSON, on the same bargain the view modules take Compose on: the worker is started
    // with the app's own libraries on its classpath, so bundling a second copy would be weight for
    // nothing. See ModuleProcess.uiJars.
    compileOnly("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
}

// distribution JAR: ./gradlew :flow-modules:ai-module:jar → build/libs/ai-module.jar
tasks.jar {
    archiveBaseName.set("ai-module")
}
