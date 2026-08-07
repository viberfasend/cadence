plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

/**
 * Android refuses to install a build over an app signed by a different key — the installer
 * just says "App not installed" — so the signing key has to be the *same* one every release,
 * not merely a valid one.
 *
 * That is why `debug.keystore` is committed next to this file instead of being left to the
 * Android Gradle plugin. AGP auto-creates `~/.android/debug.keystore` when none exists, with a
 * fresh random key pair, and a CI runner starts with an empty home directory: every build got
 * its own key, so no release could ever update the one before it. Checking the key in is what
 * pins it. It carries the standard debug identity and password and is deliberately public —
 * a debug key authenticates nothing.
 */
val debugKeystore = file("debug.keystore")

/**
 * Release signing is optional. When the four CADENCE_* environment variables are present
 * (CI with secrets configured) the release build is signed with that key; otherwise it
 * falls back to the debug key so `assembleRelease` still produces an installable APK — which
 * also means an unsigned-by-secret release build is only as private as the committed key.
 */
val releaseKeystorePath: String? = System.getenv("CADENCE_KEYSTORE")
val hasReleaseKeystore = !releaseKeystorePath.isNullOrBlank() && file(releaseKeystorePath).exists()

/**
 * CI derives the version from the Conventional Commits since the last tag
 * (`.github/scripts/next-version.sh`) and passes it in. A local build has no release to name,
 * so it stays on the placeholder rather than pretending to be a shipped version.
 */
val cadenceVersionName: String = System.getenv("CADENCE_VERSION_NAME") ?: "0.0.0-dev"
val cadenceVersionCode: Int = System.getenv("CADENCE_VERSION_CODE")?.toIntOrNull() ?: 1

android {
    namespace = "de.andi1984.cadence"
    compileSdk = 35

    defaultConfig {
        applicationId = "de.andi1984.cadence"
        minSdk = 26
        targetSdk = 35
        versionCode = cadenceVersionCode
        versionName = cadenceVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        getByName("debug") {
            // Only override AGP's default when the key is actually there, so a stripped
            // checkout still builds rather than failing on a missing file.
            if (debugKeystore.exists()) {
                storeFile = debugKeystore
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = file(releaseKeystorePath!!)
                storePassword = System.getenv("CADENCE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("CADENCE_KEY_ALIAS")
                keyPassword = System.getenv("CADENCE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (hasReleaseKeystore) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    // LifecycleEventEffect: automatic backup sync writes the file as the app leaves.
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Backup format lives in domain/, so the codec must stay pure Kotlin (no org.json).
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
