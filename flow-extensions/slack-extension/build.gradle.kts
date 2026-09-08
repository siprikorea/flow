plugins {
    kotlin("jvm") version "2.4.0"
}

kotlin {
    jvmToolchain(25)
}

dependencies {
    // the app provides the extension interfaces, so this is compile-only (not bundled in the JAR)
    compileOnly(project(":flow-extension-api"))
    // and its JSON, on the same bargain the view extensions take Compose on: the worker is started
    // with the app's own libraries on its classpath, so bundling a second copy would be weight for
    // nothing. See ExtensionProcess.uiJars.
    compileOnly("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
}

// distribution JAR: ./gradlew :flow-extensions:slack-extension:jar → build/libs/slack-extension.jar
tasks.jar {
    archiveBaseName.set("slack-extension")
}
