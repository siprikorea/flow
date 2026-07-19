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

rootProject.name = "dataflow-editor"
include(":composeApp")
include(":plugin-api")
include(":plugins:sample-plugin")
