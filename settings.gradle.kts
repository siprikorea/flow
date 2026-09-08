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
include(":flow-extension-host")
include(":flow-extensions:ai-extension")
include(":flow-extensions:base64-extension")
include(":flow-extensions:cipher-extension")
include(":flow-extensions:hash-extension")
include(":flow-extensions:merge-extension")
include(":flow-extensions:split-extension")
include(":flow-extensions:sleep-extension")
include(":flow-extensions:mac-extension")
include(":flow-extensions:signature-extension")
include(":flow-extensions:slack-extension")
include(":flow-extensions:telegram-extension")
include(":flow-extensions:keygen-extension")
include(":flow-extensions:keypairgen-extension")
include(":flow-extensions:keyfactory-extension")
include(":flow-extensions:mcp-extension")
include(":flow-extensions:slice-extension")
include(":flow-extensions:keystore-extension")
include(":flow-extensions:securerandom-extension")
include(":flow-extensions:json-extension")
include(":flow-extensions:jwt-extension")
include(":flow-extensions:qr-extension")
include(":flow-extensions:asn1view-extension")
include(":flow-extensions:imageview-extension")
include(":flow-extensions:hotp-extension")
include(":flow-extensions:totp-extension")
