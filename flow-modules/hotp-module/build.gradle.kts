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

kotlin.sourceSets["main"].kotlin.srcDir("../otp-common/kotlin")

// distribution JAR: ./gradlew :flow-modules:hotp-module:jar → build/libs/hotp-module.jar
tasks.jar {
    archiveBaseName.set("hotp-module")
}
