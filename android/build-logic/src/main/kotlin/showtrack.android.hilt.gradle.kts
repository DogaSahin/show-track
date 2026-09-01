import org.gradle.api.artifacts.VersionCatalogsExtension

plugins {
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}

// The sixth convention plugin, and the first thing in this build to apply KSP or Dagger.
// Three modules need the same wiring (:core:network, :core:data and, in Phase 8's last task,
// :app), so the plugin ids, the runtime dependency and the KSP compiler live here once rather
// than in three build files.
//
// It applies com.google.devtools.ksp and NOT org.jetbrains.kotlin.android: AGP 9 registers the
// `kotlin` extension itself and hard-fails if KGP is applied alongside it. KSP has no such
// conflict — it only needs `android.disallowKotlinSourceSets=false` in gradle.properties so it
// can register its generated-source directory (see the comment there).
val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

dependencies {
    add("implementation", libs.findLibrary("hilt-android").get())
    add("ksp", libs.findLibrary("hilt-compiler").get())
}
