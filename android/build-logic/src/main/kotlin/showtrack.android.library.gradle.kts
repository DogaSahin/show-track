import io.gitlab.arturbosch.detekt.Detekt
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
    id("org.jlleitschuh.gradle.ktlint")
    id("io.gitlab.arturbosch.detekt")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

// Derived rather than declared per module: a namespace that cannot drift from the module path, and
// one less thing every new module has to remember.
val moduleNamespace = "com.anarky.showtrack." + path.removePrefix(":").replace(':', '.')

android {
    namespace = moduleNamespace

    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        minSdk = 29
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_21
    }
}

detekt {
    // The defaults plus config/detekt/detekt.yml, rather than the yml alone: a fresh detekt release
    // then brings its new rules with it instead of silently analysing nothing.
    buildUponDefaultConfig = true
    config.setFrom(isolated.rootProject.projectDirectory.file("config/detekt/detekt.yml"))
}

tasks.withType<Detekt>().configureEach {
    jvmTarget = JvmTarget.JVM_21.target
}

dependencies {
    add("implementation", libs.findLibrary("kotlinx-coroutines-android").get())

    // Test dependencies ship here, not per module: every module gets the same harness and none of
    // them can quietly diverge from it.
    add("testImplementation", libs.findLibrary("junit").get())
    add("testImplementation", libs.findLibrary("kotlinx-coroutines-test").get())
    add("testImplementation", libs.findLibrary("turbine").get())
}
