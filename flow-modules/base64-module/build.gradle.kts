plugins {
    kotlin("jvm") version "2.4.0"
}

kotlin {
    jvmToolchain(25)
}

dependencies {
    // the app provides the module interfaces, so this is compile-only (not bundled in the JAR)
    compileOnly(project(":flow-module-api"))
}

// distribution JAR: ./gradlew :flow-modules:base64-module:jar → build/libs/base64-module.jar
tasks.jar {
    archiveBaseName.set("base64-module")
}
