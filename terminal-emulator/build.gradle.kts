plugins { id("com.android.library") }

android {
    namespace = "com.termux.emulator"
    compileSdk = 35
    ndkVersion = "26.1.10909125"

    defaultConfig {
        minSdk = 24
        // Native PTY library (libtermux.so) is built from src/main/jni with the NDK.
        externalNativeBuild { ndkBuild { cFlags("-std=c11", "-Wall", "-Wextra", "-Os", "-fno-stack-protector") } }
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }
    externalNativeBuild { ndkBuild { path = file("src/main/jni/Android.mk") } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies { implementation("androidx.annotation:annotation:1.8.2") }
