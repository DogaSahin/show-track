plugins {
    // Pure Kotlin, not showtrack.android.library: route contracts are plain @Serializable data
    // classes with no Android API surface, so this mirrors :core:model rather than
    // :core:designsystem — no AGP, no manifest, no Android dependency reaching every module that
    // just wants to name a screen.
    id("showtrack.jvm.library")

    // Module-local rather than a convention plugin, same call :core:network makes: kotlinx.serialization
    // is a compiler plugin only this module needs, to generate KSerializers for the route types.
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    // -core, not -json: @Serializable and the generated KSerializers need only the runtime
    // annotations/interfaces; this module never encodes to JSON itself. `api`, not
    // `implementation`: every consumer of a route type transitively needs this on its own compile
    // classpath the moment it calls `serializer<DetailRoute>()` (as Task 9's NavGraphBuilder
    // entries will) — it is part of this module's public surface, not an implementation detail.
    api(libs.kotlinx.serialization.core)
}
