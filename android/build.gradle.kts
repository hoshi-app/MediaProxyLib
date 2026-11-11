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
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.facebook.react:react-android")
}
