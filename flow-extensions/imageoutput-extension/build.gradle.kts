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

// distribution JAR: ./gradlew :flow-extensions:imageoutput-extension:jar → build/libs/imageoutput-extension.jar
tasks.jar {
    archiveBaseName.set("imageoutput-extension")
}
