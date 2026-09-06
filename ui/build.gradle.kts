import org.jetbrains.compose.resources.ResourcesExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    androidTarget {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    jvm()

    applyDefaultHierarchyTemplate()

    sourceSets {
        /**
         * Same split as `:core`, and for the same reason: the formatters speak `java.time`, which
         * `commonMain` cannot see, and both targets are the JVM anyway. `commonMain` carries no
         * hand-written code — only the generated `Res` accessors for `composeResources/`, which
         * `jvmShared` can read because it depends on it.
         */
        val jvmShared by creating { dependsOn(commonMain.get()) }
        // The test half of the same split. Everything worth testing in this module — the
        // `CadenceUiState` derivations, `sortedFor`, the ViewModel's undo machine — is plain JVM
        // Kotlin with no Compose runtime in it, so one source set serves both compilations.
        val jvmSharedTest by creating { dependsOn(commonTest.get()) }

        androidMain.get().dependsOn(jvmShared)
        jvmMain.get().dependsOn(jvmShared)
        androidUnitTest.get().dependsOn(jvmSharedTest)
        jvmTest.get().dependsOn(jvmSharedTest)

        jvmShared.dependencies {
            // api, not implementation: every screen signature speaks Task, Project and the
            // repository's types, so the shells that call them need those on their path too.
            api(project(":core"))
            api(compose.runtime)
            api(compose.foundation)
            api(compose.material3)
            api(compose.materialIconsExtended)
            // Res.string / Res.plurals appear in public signatures (SortMode.label).
            api(compose.components.resources)
            implementation(compose.ui)
        }
        jvmSharedTest.dependencies {
            implementation(libs.test.junit)
            implementation(libs.kotlinx.coroutines.test)
            implementation(kotlin("test"))
            // The ViewModel tests drive a real `CadenceRepository` over the real SQLDelight stores
            // on an in-memory SQLite database, the same way `:core`'s do — the store contract is
            // written once, in SQL, and no test carries a second copy of it. Both compilations
            // need the driver: sqlite-jdbc's native library runs on the host JVM in Android unit
            // tests too, which `:core:testDebugUnitTest` already relies on.
            implementation(libs.sqldelight.sqlite.driver)
        }
    }
}

compose.resources {
    // The screens live in `de.andi1984.cadence.ui`; the generated accessors sit one package down
    // so `Res` never collides with a screen's own type.
    packageOfResClass = "de.andi1984.cadence.ui.resources"
    // Public, because the Android shell reads a handful of the same strings for its bottom bar.
    publicResClass = true
    generateResClass = ResourcesExtension.ResourceClassGeneration.Always
}

android {
    namespace = "de.andi1984.cadence.ui"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
