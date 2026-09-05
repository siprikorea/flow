plugins {
    kotlin("jvm") version "2.4.0"
}

kotlin {
    jvmToolchain(25)
}

// The worker process runs with this jar, the extension contract and the extension's own jars on its
// classpath — and nothing else, which is what keeps an extension away from the app. So this module
// must depend on the contract and on nothing further.
dependencies {
    api(project(":flow-extension-api"))
}
