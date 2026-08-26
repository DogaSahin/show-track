import io.gitlab.arturbosch.detekt.Detekt
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import showtrack.buildlogic.ModuleRules

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jlleitschuh.gradle.ktlint")
    id("io.gitlab.arturbosch.detekt")
}

// A pure-Kotlin/JVM counterpart to showtrack.android.library: no AGP, no Android dependencies —
// for modules like :core:model that must stay importable without pulling in Retrofit or Room.
kotlin {
    jvmToolchain(21)
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

// Unlike showtrack.android.library, this plugin does NOT widen ktlint's task sources. Android
// modules need that widening because AGP 9 forbids applying the Kotlin Gradle Plugin directly, so
// ktlint-gradle's `plugins.withId(...)` hook for it never fires there. A pure-Kotlin/JVM module
// applies org.jetbrains.kotlin.jvm itself — the exact id ktlint-gradle listens for — so it
// registers its main/test source-set tasks natively. Confirmed via
// `:core:model:tasks --all` showing `ktlintMainSourceSetCheck`/`ktlintTestSourceSetCheck`, and via
// the malformed-file probe in task-2-report.md. Carrying the workaround here anyway would be
// folklore: a fix copied into a module that was never broken.

// Same two architecture rules as showtrack.android.library, for symmetry and for any future
// :core:* module that applies this plugin instead. Currently a no-op for :core:model itself:
// violationOf only ever fires for a :feature: consumer, and apiLeakOf only ever fires for a
// producer re-exporting :core:network or :core:database — :core:model is neither.
val consumerPath = project.path

configurations.configureEach {
    val configurationName = name
    dependencies.configureEach {
        val dependencyPath = (this as? ProjectDependency)?.path ?: return@configureEach
        ModuleRules.violationOf(consumerPath, dependencyPath)?.let { error(it) }
        ModuleRules.apiLeakOf(consumerPath, configurationName, dependencyPath)?.let { error(it) }
    }
}
