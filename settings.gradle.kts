pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "flow"
include(":flow")
include(":flow-extension-api")
include(":flow-extensions:sample-extension")
include(":flow-extensions:base64-extension")
