import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
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
    compileSdk = 36

    defaultConfig {
        applicationId = "de.andi1984.cadence"
        minSdk = 26
        targetSdk = 36
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

// Outside `android {}` deliberately: `kotlinOptions` inside it became an error in Kotlin 2.2,
// and the compilerOptions DSL that replaces it hangs off the Kotlin extension, not AGP's.
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // The model, recurrence engine, quick-add parser and backup codec. Shared with the desktop
    // app; see docs/adr/0001-desktop-app-and-multi-device-sync.md.
    implementation(project(":core"))
    // Theme, components, formatters, screens and the ViewModel — everything but this shell.
    implementation(project(":ui"))

    implementation(platform(libs.compose.bom))
    androidTestImplementation(platform(libs.compose.bom))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    // ProcessLifecycleOwner: sync follows the app to the foreground and back out again, and only
    // the process lifecycle can tell that apart from a rotation (ADR 0002, decision 11).
    implementation(libs.androidx.lifecycle.process)
    // LifecycleEventEffect: automatic backup sync writes the file as the app leaves.
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)

    // Home-screen widgets (widget/). Glance renders through its own composition, not Compose
    // UI's, so it needs no extra Compose Multiplatform artefact beyond the compiler plugin
    // already applied above.
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)
    // SyncWorker: a widget tap's write reaches the server through a one-shot WorkManager job, the
    // only thing that survives the cached process a broadcast leaves behind. Glance runs on
    // WorkManager already; this names the dependency instead of riding its transitive one.
    implementation(libs.androidx.work.runtime.ktx)

    // Backup format lives in domain/, so the codec must stay pure Kotlin (no org.json).
    implementation(libs.kotlinx.serialization.json)

    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(libs.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.test.androidx.junit)
    androidTestImplementation(libs.compose.ui.test.junit4)
}
