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
include(":flow-extensions:crypto-extension")
include(":flow-extensions:hash-extension")
include(":flow-extensions:merge-extension")
include(":flow-extensions:split-extension")
include(":flow-extensions:sleep-extension")
