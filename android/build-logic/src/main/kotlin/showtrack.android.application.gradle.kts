import io.gitlab.arturbosch.detekt.Detekt
import org.jlleitschuh.gradle.ktlint.tasks.BaseKtLintCheckTask
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jlleitschuh.gradle.ktlint")
    id("io.gitlab.arturbosch.detekt")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

android {
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        minSdk = 29
        targetSdk = 36
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

// ktlint-gradle registers its source-set tasks only from `plugins.withId` for `kotlin`,
// `org.jetbrains.kotlin.android`, `.js` and `.multiplatform`. AGP 9 owns Kotlin itself and hard-fails
// if KGP is applied alongside it, so none of those ids is ever applied in this build and the plugin
// registers its .kts tasks and nothing else — `ktlintCheck` would lint build scripts and not one
// line of Kotlin source, while looking perfectly alive. Widening the tasks it does register reuses
// its own report-and-fail pipeline instead of reimplementing task creation against internal API.
// DependencyRuleTestKitTest drives a malformed .kt file through a real build to keep this honest.
tasks.withType<BaseKtLintCheckTask>().configureEach {
    source(layout.projectDirectory.dir("src").asFileTree.matching { include("**/*.kt") })
}

dependencies {
    add("implementation", libs.findLibrary("kotlinx-coroutines-android").get())

    add("testImplementation", libs.findLibrary("junit").get())
    add("testImplementation", libs.findLibrary("kotlinx-coroutines-test").get())
    add("testImplementation", libs.findLibrary("turbine").get())
}
