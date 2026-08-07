import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    androidTarget {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    jvm()

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

        jvmShared.dependencies {
            implementation(libs.kotlinx.serialization.json)
        }
        jvmSharedTest.dependencies {
            implementation(libs.test.junit)
            implementation(kotlin("test"))
        }
    }
}

android {
    namespace = "de.andi1984.cadence.core"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
