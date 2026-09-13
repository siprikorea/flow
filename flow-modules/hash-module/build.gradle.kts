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

// distribution JAR: ./gradlew :flow-modules:hash-module:jar → build/libs/hash-module.jar
tasks.jar {
    archiveBaseName.set("hash-module")
}
