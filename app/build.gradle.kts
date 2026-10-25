plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.chessassist"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.chessassist"
        minSdk = 27
        targetSdk = 34
        versionCode = 5
        versionName = "0.5"
        ndk {
            // Stockfish builds for arm64 (NNUE needs NEON); armeabi-v7a optional but slow.
            abiFilters += listOf("arm64-v8a")
        }
        externalNativeBuild {
            cmake {
                cppFlags += "-O3"
                arguments += listOf("-DANDROID_STL=c++_static")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    packaging {
        jniLibs {
            // Stockfish is exec()'d from nativeLibraryDir (not dlopen'd), so the .so
            // must be physically extracted by the package installer (extractNativeLibs).
            useLegacyPackaging = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-service:2.8.4")
}
