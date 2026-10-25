plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.chessassist.hook"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.chessassist.hook"
        minSdk = 27
        targetSdk = 34
        versionCode = 5
        versionName = "0.5"
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
    // Compile-time stub generated from tools/xapi (real classes are injected by LSPosed
    // at runtime, so this must never be bundled into the APK).
    compileOnly(files("libs/XposedBridgeApi-82-stub.jar"))
}

// Bake module/src/main/assets/remap.json into generated Kotlin (RemapTable) so the
// hook always resolves classes without ANY runtime I/O: assets/ are invisible to the
// module ClassLoader inside the target process, and createPackageContext() for our own
// package from Duolingo always throws NameNotFoundException on Android 11+ (package
// visibility: Duolingo's manifest has no <queries> for us). The documented update flow
// stays "tools/remap.py -> rebuild module".
val generateRemapTable by tasks.registering {
    val input = file("src/main/assets/remap.json")
    val outDir = layout.buildDirectory.dir("generated/remap/com/chessassist/hook")
    inputs.file(input)
    outputs.dir(outDir)
    doLast {
        val dir = outDir.get().asFile.apply { mkdirs() }
        val json = input.readText(Charsets.UTF_8)
        val versionKey = "\"appVersion\""
        val version = json.substringAfter(versionKey).substringAfter("\"").substringBefore("\"")
            .ifEmpty { "unknown" }
        // Escape for a Kotlin triple-quoted string: backslashes first, then dollar signs.
        val dollar = '$'
        val escaped = json.replace("\\", "\\\\").replace("$", dollar + "{'$'}")
        val tq = "\"\"\""
        val body = "package com.chessassist.hook\n\n" +
            "/** Auto-generated from module/src/main/assets/remap.json - DO NOT EDIT. */\n" +
            "internal object RemapTable {\n" +
            "    const val APP_VERSION = \"" + version + "\"\n" +
            "    const val JSON = " + tq + escaped + tq + "\n" +
            "}\n"
        File(dir, "RemapTable.kt").writeText(body, Charsets.UTF_8)
    }
}

android {
    sourceSets {
        getByName("main").kotlin.srcDir(layout.buildDirectory.dir("generated/remap"))
    }
}

tasks.named("preBuild") { dependsOn(generateRemapTable) }
