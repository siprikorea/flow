plugins {
    kotlin("jvm") version "2.4.0"
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // the app provides the extension interfaces, so this is compile-only (not bundled in the JAR)
    compileOnly(project(":flow-extension-api"))
}

kotlin.sourceSets["main"].kotlin.srcDir("../otp-common/kotlin")

// distribution JAR: ./gradlew :flow-extensions:totp-extension:jar → build/libs/totp-extension.jar
tasks.jar {
    archiveBaseName.set("totp-extension")
}
