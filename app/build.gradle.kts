plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)       // only the settings screens use Compose, and they run in their own process
}

android {
    namespace = "app.wisp.keyboard"
    compileSdk = 36
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "app.wisp.keyboard"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "1.0.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Personal build: signed with the debug key so it always installs.
            signingConfig = signingConfigs.getByName("debug")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }

    packaging { jniLibs.useLegacyPackaging = false }

    // Dictionaries are memory-mapped straight out of the APK, so they must stay uncompressed.
    androidResources { noCompress += listOf("dict") }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets["main"].jniLibs.srcDir(layout.buildDirectory.dir("rustJniLibs"))

    buildFeatures { buildConfig = true; compose = true }
    testOptions {
        unitTests.isReturnDefaultValues = true
        // Show println output (the gesture evaluation prints its report there).
        unitTests.all { it.testLogging { showStandardStreams = true } }
    }
}

// Rust core (core/): built with cargo-ndk into build/rustJniLibs, which is packaged as jniLibs.
val buildRust by tasks.registering(Exec::class) {
    val release = gradle.startParameter.taskNames.any { it.contains("Release", ignoreCase = true) }
    workingDir = rootProject.file("core")
    environment("ANDROID_NDK_HOME", android.sdkDirectory.resolve("ndk/${android.ndkVersion}").path)
    commandLine(
        listOf("cargo", "ndk", "-t", "arm64-v8a", "-t", "armeabi-v7a", "-t", "x86_64",
            "-o", layout.buildDirectory.dir("rustJniLibs").get().asFile.path, "build", "-p", "wisp-jni")
            + if (release) listOf("--release") else emptyList()
    )
    inputs.files(fileTree(rootProject.file("core")) { exclude("target") }).withPropertyName("rustSources")
    outputs.dir(layout.buildDirectory.dir("rustJniLibs"))
}
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(buildRust) }

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

// Deliberately tiny: the always-resident keyboard process carries no UI framework or
// network stack (see design.md D4, D9, D10). Add dependencies only with a reason.
dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.autofill)  // styles for the password manager's login chips in the toolbar (Android 11+)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.commons.compress)  // reads the speech models' .tar.bz2 archives
    implementation(project(":net"))      // the only code that may use the network
    testImplementation(libs.junit)
    testImplementation(libs.json) // org.json for JVM tests; Android provides it at runtime
}
