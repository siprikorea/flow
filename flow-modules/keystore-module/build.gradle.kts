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

// distribution JAR: ./gradlew :flow-modules:keystore-module:jar → build/libs/keystore-module.jar
tasks.jar {
    archiveBaseName.set("keystore-module")
}
