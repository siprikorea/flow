plugins {
    kotlin("jvm") version "2.2.20"
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // 앱이 Plugin 인터페이스를 제공하므로 컴파일 시에만 필요 (JAR 에 포함하지 않음)
    compileOnly(project(":plugin-api"))
}

// 배포용 JAR: ./gradlew :plugins:sample-plugin:jar → build/libs/sample-plugin.jar
tasks.jar {
    archiveBaseName.set("sample-plugin")
}
