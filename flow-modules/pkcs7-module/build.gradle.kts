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

kotlin.sourceSets["main"].kotlin.srcDir("../pki-common/kotlin")

// distribution JAR: ./gradlew :flow-modules:pkcs7-module:jar → build/libs/pkcs7-module.jar
tasks.jar {
    archiveBaseName.set("pkcs7-module")
}
