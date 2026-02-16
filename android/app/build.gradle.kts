plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.tinyclaw"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.tinyclaw"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // Include pre-built native libraries from jniLibs/
    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }
}

dependencies {
    // Core AndroidX
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.localbroadcastmanager:localbroadcastmanager:1.1.0")

    // Compose BOM
    implementation(platform("androidx.compose:compose-bom:2025.05.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    // Kotlinx Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Navigation3
    implementation("androidx.navigation3:navigation3-runtime:1.0.0-alpha10")
    implementation("androidx.navigation3:navigation3-ui:1.0.0-alpha10")

    // Material3 Adaptive
    implementation("androidx.compose.material3.adaptive:adaptive:1.1.0")
    implementation("androidx.compose.material3.adaptive:adaptive-layout:1.1.0")
    implementation("androidx.compose.material3.adaptive:adaptive-navigation:1.1.0")
    implementation("androidx.compose.material3.adaptive:adaptive-navigation3:1.0.0-alpha03")

    // Debug
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

// Task to copy the Rust-built .so from cargo target into jniLibs.
// Run: ./gradlew copyNativeLib (or it runs automatically before assembleDebug)
val jniLibsDir = layout.projectDirectory.dir("src/main/jniLibs/arm64-v8a")

tasks.register("copyNativeLib") {
    val cargoOutput = rootProject.projectDir.resolve(
        "../target/aarch64-linux-android/release/libtinyclaw_android.so"
    )
    outputs.dir(jniLibsDir)
    doLast {
        if (cargoOutput.exists()) {
            copy {
                from(cargoOutput)
                into(jniLibsDir)
            }
        } else {
            logger.warn(
                "Native library not found at $cargoOutput. " +
                "Build with: cargo ndk -t arm64-v8a build --release -p tinyclaw-android"
            )
            // Ensure the directory exists even without the .so
            jniLibsDir.asFile.mkdirs()
        }
    }
}

tasks.matching { it.name.startsWith("merge") && it.name.endsWith("JniLibFolders") }.configureEach {
    dependsOn("copyNativeLib")
}
