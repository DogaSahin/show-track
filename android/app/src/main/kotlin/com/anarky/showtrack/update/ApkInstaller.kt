package com.anarky.showtrack.update

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject

/**
 * The hand-off to Android's own package installer. An interface so [UpdateViewModel] can be
 * tested without a real `PackageManager`; the system shows its own confirmation either way, so
 * nothing here installs silently.
 */
interface ApkInstaller {
    /** Whether the user has let this app install packages ("Install unknown apps"). */
    fun canInstall(): Boolean

    /** Opens this app's "Install unknown apps" switch in system settings. */
    fun openInstallPermissionSettings()

    /** Asks the system installer to install [apk], which must sit under `cacheDir/updates/`. */
    fun install(apk: File)
}

class AndroidApkInstaller
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : ApkInstaller {
        override fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

        override fun openInstallPermissionSettings() {
            val intent =
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }

        override fun install(apk: File) {
            // The authority matches the <provider> in AndroidManifest.xml (MergedManifestTest), and
            // update_paths.xml exposes only cacheDir/updates/, which is where downloads land.
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
            val intent =
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, APK_MIME_TYPE)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }

        private companion object {
            const val APK_MIME_TYPE = "application/vnd.android.package-archive"
        }
    }
