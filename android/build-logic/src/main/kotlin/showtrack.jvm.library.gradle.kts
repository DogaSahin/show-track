import io.gitlab.arturbosch.detekt.Detekt
import org.gradle.api.artifacts.VersionCatalogsExtension
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

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

// The Kotlin JVM plugin's own test task is named `test`, not `testDebugUnitTest` — Gradle's
// task-name matching means the root `testDebugUnitTest` lifecycle task (see the root
// build.gradle.kts, which wires it to build-logic's own tests for the same reason) never reaches
// this module without an explicit alias. Verified with `./gradlew testDebugUnitTest --dry-run`:
// zero :core:model tasks before this line existed; a dozen after (compileKotlin, jar, test,
// testDebugUnitTest and the rest of the task graph they pull in) — this line is what makes
// `:core:model:test` reachable from the alias at all.
tasks.register("testDebugUnitTest") {
    dependsOn(tasks.named("test"))
}

// showtrack.android.library ships junit/turbine/coroutines-test to every module it applies "so
// none of them can quietly diverge from it". The Kotlin JVM plugin ships no test harness at all,
// so the JVM-appropriate equivalents are declared here for the same reason.
dependencies {
    add("testImplementation", libs.findLibrary("junit").get())
    add("testImplementation", libs.findLibrary("kotlinx-coroutines-test").get())
    add("testImplementation", libs.findLibrary("turbine").get())
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

// Same two architecture rules as showtrack.android.library. Only one half is unreachable here:
// violationOf can never fire for :core:model, because it only ever checks a :feature: consumer and
// :core:model can never be one by construction. apiLeakOf is live protection, not a no-op — the
// Kotlin JVM plugin brings java-library's `api` configuration along with it, so
// `api(project(":core:network"))` in :core:model would re-export Retrofit to every feature that
// depends on it exactly as it would from an Android module, and this block catches that the same
// way it does in showtrack.android.library. Covered by DependencyRuleTestKitTest.
val consumerPath = project.path

configurations.configureEach {
    val configurationName = name
    dependencies.configureEach {
        val dependencyPath = (this as? ProjectDependency)?.path ?: return@configureEach
        ModuleRules.violationOf(consumerPath, dependencyPath)?.let { error(it) }
        ModuleRules.apiLeakOf(consumerPath, configurationName, dependencyPath)?.let { error(it) }
    }
}
