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
    implementation(libs.kotlinx.serialization.json)
}
