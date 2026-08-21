plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.sqldelight) apply false
}

/**
 * Every test task in the build, configured once.
 *
 * **The timeout is the point.** `:app-desktop:test` spent eleven minutes at 100% CPU and was
 * still going when it was killed: one test built a `while (true) { poll(); delay(30s) }` loop on
 * `runTest`'s own `TestScheduler`, and the drain `runTest` performs once the body returns never
 * reached an idle scheduler. Nothing failed — the JVM simply never came back, so on a runner the
 * job ran until GitHub's limit rather than reporting anything. A hung test has to *fail*, and
 * five minutes is two orders of magnitude above what this whole suite needs (roughly five
 * seconds across ~450 tests), so it can only ever catch a hang.
 *
 * No `maxParallelForks` deliberately: at five seconds a second JVM costs more to start than the
 * tests it would run.
 */
subprojects {
    tasks.withType<Test>().configureEach {
        timeout.set(java.time.Duration.ofMinutes(5))
        testLogging {
            // A failure on a runner is only as useful as what it prints, and Gradle's default
            // prints the assertion without the stack that produced it.
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            events("failed")
            showStackTraces = true
        }
    }
}
