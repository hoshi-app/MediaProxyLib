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
    // Only expo-modules-core is used, not the RN API.
    // An unversioned react-android resolves only through the RN plugin substitution and fails
    // when the module builds outside the root project.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
