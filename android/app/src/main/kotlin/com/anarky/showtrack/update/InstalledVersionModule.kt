package com.anarky.showtrack.update

import com.anarky.showtrack.BuildConfig
import com.anarky.showtrack.core.data.update.InstalledVersionCode
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * What `:core:data`'s update check needs from the composition root: the running build's
 * `versionCode` (only `:app` has the `BuildConfig` that carries it) and the installer hand-off.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class InstalledVersionModule {
    @Binds
    abstract fun apkInstaller(impl: AndroidApkInstaller): ApkInstaller

    companion object {
        @Provides
        @InstalledVersionCode
        fun installed(): Int = BuildConfig.VERSION_CODE
    }
}
