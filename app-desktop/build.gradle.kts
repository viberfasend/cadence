import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    // A jvmToolchain(17) request would need an actual JDK 17 installed (or toolchain
    // auto-download enabled); every other module instead compiles 17 bytecode with whatever JDK
    // runs Gradle, and this one follows suit rather than requiring a matching local JDK on top.
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    // The model, recurrence engine, quick-add parser and backup codec, plus the SQLDelight
    // driver for this JVM target. Shared with the Android app; see
    // docs/adr/0001-desktop-app-and-multi-device-sync.md.
    implementation(project(":core"))
    // Theme, components, formatters, screens and the ViewModel — everything but this shell.
    implementation(project(":ui"))

    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)

    // Settings persist as a JSON file under PlatformDirs (there is no SharedPreferences here).
    implementation(libs.kotlinx.serialization.json)
}

/**
 * jpackage's msi/dmg formats refuse anything but a plain `major.minor.patch`, so the
 * `-dev`/`-debug` suffix CI's derived version can carry (see `.github/scripts/next-version.sh`)
 * has to come off before it reaches `packageVersion`. A local build with no env var at all falls
 * back to a fixed placeholder rather than "0.0.0-dev", which several packaging backends reject
 * outright for having an all-zero version.
 */
val cadenceVersionName: String =
    System.getenv("CADENCE_VERSION_NAME")?.substringBefore("-")?.takeIf { it.isNotBlank() }
        ?: "1.0.0"

compose.desktop {
    application {
        mainClass = "de.andi1984.cadence.desktop.MainKt"

        nativeDistributions {
            // Deb/Rpm and a plain tarball on Linux (distro-agnostic use, per ADR 0001 §8), Dmg
            // on macOS, Msi on Windows. jpackage must run on the target OS, so CI names a matrix
            // over ubuntu-latest/macos-latest/windows-latest — see .github/workflows/desktop.yml.
            targetFormats(TargetFormat.Deb, TargetFormat.Rpm, TargetFormat.Dmg, TargetFormat.Msi)
            // jlink's jdeps-based module scan misses java.sql: the SQLite JDBC driver registers
            // itself via ServiceLoader reflection rather than a static import jdeps can trace, so
            // the packaged runtime shipped without it and DatabaseDriverFactory blew up with
            // NoClassDefFoundError on java.sql.DriverManager at first launch.
            modules("java.sql")
            packageName = "Cadence"
            packageVersion = cadenceVersionName
            description = "A local-first todo app. No cloud, no account, no analytics."
            vendor = "Andreas Sander"

            linux {
                packageName = "cadence"
                debMaintainer = "mail@andi1984.de"
                menuGroup = "Office"
            }
            macOS {
                bundleID = "de.andi1984.cadence"
            }
            windows {
                menuGroup = "Cadence"
                // A fixed UUID, not a random one: jpackage/WiX use it to recognise "this is the
                // same product" across versions so an MSI upgrades in place instead of installing
                // side by side. Generated once for this app and never reused elsewhere.
                upgradeUuid = "8f2b9c3e-6a3f-4b8a-9b7a-1e6f2c9d4a01"
            }
        }
    }
}
