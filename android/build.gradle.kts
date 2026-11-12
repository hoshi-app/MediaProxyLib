plugins {
    id("com.android.library")
    id("expo-module-gradle-plugin")
}

group = "tv.mediaproxy"
version = "0.1.0"

android {
    namespace = "tv.mediaproxy"
    defaultConfig {
        versionCode = 1
        versionName = "0.1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // RN API не используем (только expo-modules-core): без версии react-android разрешается
    // лишь substitution'ом RN-плагина и падает при сборке модуля вне root-проекта.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
