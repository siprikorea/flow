plugins {
    kotlin("jvm") version "2.2.20"
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // the app provides the extension interfaces, so this is compile-only (not bundled in the JAR)
    compileOnly(project(":flow-extension-api"))
}

// distribution JAR: ./gradlew :flow-extensions:view-extension:jar → build/libs/view-extension.jar
tasks.jar {
    archiveBaseName.set("view-extension")
}
