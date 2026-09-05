plugins {
    kotlin("jvm") version "2.4.0"
}

kotlin {
    jvmToolchain(25)
}

dependencies {
    // the app provides the extension interfaces, so this is compile-only (not bundled in the JAR)
    compileOnly(project(":flow-extension-api"))
}

// distribution JAR: ./gradlew :flow-extensions:split-extension:jar → build/libs/split-extension.jar
tasks.jar {
    archiveBaseName.set("split-extension")
}
