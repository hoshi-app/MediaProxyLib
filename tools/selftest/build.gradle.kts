plugins {
    kotlin("jvm") version "2.1.20"
}

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(17)
}

// Only the platform-independent core is compiled, so the self-test runs without the Android SDK.
sourceSets {
    main {
        kotlin.setSrcDirs(listOf("../../android/src/main/java/tv/mediaproxy/core"))
    }
    test {
        kotlin.setSrcDirs(listOf("../../android/src/test/java/tv/mediaproxy"))
    }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}

tasks.register<JavaExec>("selftest") {
    group = "verification"
    description = "Runs the proxy core self-test."
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("tv.mediaproxy.core.SelfTestKt")
}
