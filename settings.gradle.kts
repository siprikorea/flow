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
include(":flow-mcp")
include(":flow-module-api")
include(":flow-module-host")
include(":flow-modules:ai-module")
include(":flow-modules:base64-module")
include(":flow-modules:cert-module")
include(":flow-modules:pkcs7-module")
include(":flow-modules:pkcs12-module")
include(":flow-modules:csr-module")
include(":flow-modules:crl-module")
include(":flow-modules:certpath-module")
include(":flow-modules:ca-module")
include(":flow-modules:cipher-module")
include(":flow-modules:hash-module")
include(":flow-modules:merge-module")
include(":flow-modules:split-module")
include(":flow-modules:sleep-module")
include(":flow-modules:mac-module")
include(":flow-modules:signature-module")
include(":flow-modules:slack-module")
include(":flow-modules:telegram-module")
include(":flow-modules:keygen-module")
include(":flow-modules:keypairgen-module")
include(":flow-modules:keyfactory-module")
include(":flow-modules:mcp-module")
include(":flow-modules:slice-module")
include(":flow-modules:keystore-module")
include(":flow-modules:securerandom-module")
include(":flow-modules:json-module")
include(":flow-modules:jwt-module")
include(":flow-modules:qr-module")
include(":flow-modules:asn1view-module")
include(":flow-modules:imageview-module")
include(":flow-modules:hotp-module")
include(":flow-modules:totp-module")
include(":flow-modules:branch-module")
