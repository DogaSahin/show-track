plugins {
    id("showtrack.android.library")
    id("showtrack.android.compose")
}

// The module-dependency rules are NOT applied here. They live in showtrack.android.library, which
// this plugin applies: a feature module that reached for library + compose directly — exactly what
// :core:designsystem looks like — would otherwise slip past the guard entirely.
