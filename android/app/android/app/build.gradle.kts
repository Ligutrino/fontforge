plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.ffglifos"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.ffglifos"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    buildTypes {
        // Firmado con la clave de depuración para poder instalarlo directo en la tablet.
        getByName("debug") { isDebuggable = true }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    // Las bibliotecas nativas (libfontforge.so y libffglifos.so) se compilan antes con el NDK
    // y se copian a src/main/jniLibs/arm64-v8a.

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}
