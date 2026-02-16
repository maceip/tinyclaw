plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.tinyclaw"
    compileSdk = 35

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
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.localbroadcastmanager:localbroadcastmanager:1.1.0")
}

// Task to copy the Rust-built .so from cargo target into jniLibs.
// Run: ./gradlew copyNativeLib (or it runs automatically before assembleDebug)
val jniLibsDir = layout.projectDirectory.dir("src/main/jniLibs/arm64-v8a")

tasks.register<Copy>("copyNativeLib") {
    val cargoOutput = rootProject.projectDir.resolve(
        "../target/aarch64-linux-android/release/libtinyclaw_android.so"
    )
    from(cargoOutput)
    into(jniLibsDir)

    // Only run if cargo output exists (CI pre-copies the .so separately)
    onlyIf { cargoOutput.exists() }
}

// Wire copyNativeLib before any task that merges native libs, declared
// via mustRunAfter so Gradle's validation sees the ordering.
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("JniLibFolders") }.configureEach {
    mustRunAfter("copyNativeLib")
}
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("NativeLibs") }.configureEach {
    mustRunAfter("copyNativeLib")
}
