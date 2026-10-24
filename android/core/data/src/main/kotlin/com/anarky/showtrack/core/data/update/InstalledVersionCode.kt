package com.anarky.showtrack.core.data.update

import javax.inject.Qualifier

/** The running build's `versionCode` (an `Int`), provided by `:app`. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class InstalledVersionCode
