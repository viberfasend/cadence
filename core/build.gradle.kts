import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.sqldelight)
}

kotlin {
    // DatabaseDriverFactory is expect/actual with a platform-specific constructor (Context vs.
    // File) — still Beta as of Kotlin 2.0, hence the flag on both targets.
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
            freeCompilerArgs.add("-Xexpect-actual-classes")
        }
    }
    jvm {
        compilerOptions {
            freeCompilerArgs.add("-Xexpect-actual-classes")
        }
    }

    applyDefaultHierarchyTemplate()

    sourceSets {
        /**
         * Android and the desktop JVM are both JVM targets, so code shared between them may use
         * the JDK — `java.time` in particular, which `commonMain` could not see. Everything in
         * the module lives here until a browser target actually exists (docs/adr/0001, phase 7);
         * `commonMain` stays empty rather than pretending to a portability nothing needs yet.
         */
        val jvmShared by creating { dependsOn(commonMain.get()) }
        val jvmSharedTest by creating { dependsOn(commonTest.get()) }

        androidMain.get().dependsOn(jvmShared)
        jvmMain.get().dependsOn(jvmShared)
        androidUnitTest.get().dependsOn(jvmSharedTest)
        jvmTest.get().dependsOn(jvmSharedTest)

        // SQLDelight generates its query code into commonMain by default. That code is plain
        // Kotlin with no JDK dependency — only the driver differs per platform — so it does not
        // reopen the "commonMain stays empty" rule above; the store implementations that convert
        // rows to/from java.time still live in jvmShared, same as Room's toDomain/toEntity did.
        commonMain.dependencies {
            api(libs.sqldelight.coroutines.extensions)
        }
        androidMain.dependencies {
            implementation(libs.sqldelight.android.driver)
        }
        jvmMain.dependencies {
            implementation(libs.sqldelight.sqlite.driver)
        }

        jvmShared.dependencies {
            implementation(libs.kotlinx.serialization.json)
            // api, not implementation: the store ports hand back Flow, so anything implementing
            // one — the Android app today, the desktop app next — needs the type on its path.
            api(libs.kotlinx.coroutines.core)
            // Sync (ADR 0002, decision 6). HTTPS and JSON are not a platform difference, so the
            // client is a plain class here rather than a port either shell implements; the only
            // thing that could have differed — the Ktor engine — is one dependency both targets
            // share, because OkHttp is the engine that runs on Android and the desktop alike.
            implementation(libs.supabase.auth)
            implementation(libs.supabase.postgrest)
            // Realtime (ADR 0002, decision 12): the same websocket engine, one channel beside
            // the round rather than in place of it.
            implementation(libs.supabase.realtime)
            implementation(libs.ktor.client.okhttp)
        }
        jvmSharedTest.dependencies {
            implementation(libs.test.junit)
            implementation(libs.kotlinx.coroutines.test)
            implementation(kotlin("test"))
            // The JVM tests exercise the real SQLDelight-backed stores against a throwaway file,
            // not fakes, wherever a store's own logic (not the repository's) is under test.
            implementation(libs.sqldelight.sqlite.driver)
        }
    }
}

sqldelight {
    databases {
        create("CadenceDatabase") {
            packageName.set("de.andi1984.cadence.data.db")
        }
    }
}

android {
    namespace = "de.andi1984.cadence.core"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
