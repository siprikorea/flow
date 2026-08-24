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

kotlin.sourceSets["main"].kotlin.srcDir("../output-common/kotlin")

// distribution JAR: ./gradlew :flow-extensions:asn1output-extension:jar → build/libs/asn1output-extension.jar
tasks.jar {
    archiveBaseName.set("asn1output-extension")
}
