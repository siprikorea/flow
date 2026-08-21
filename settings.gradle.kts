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
include(":flow-extensions:cipher-extension")
include(":flow-extensions:hash-extension")
include(":flow-extensions:merge-extension")
include(":flow-extensions:split-extension")
include(":flow-extensions:sleep-extension")
include(":flow-extensions:mac-extension")
include(":flow-extensions:signature-extension")
include(":flow-extensions:keygen-extension")
include(":flow-extensions:keypairgen-extension")
include(":flow-extensions:keyfactory-extension")
include(":flow-extensions:mcp-extension")
include(":flow-extensions:securerandom-extension")
