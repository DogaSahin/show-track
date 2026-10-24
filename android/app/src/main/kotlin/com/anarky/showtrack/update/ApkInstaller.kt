package com.anarky.showtrack.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
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

    /**
     * Whether [apk] is a build of this app, signed with the key the installed app is signed with.
     * The hash check proves the file is the one the server described; this proves it is ShowTrack,
     * so a server that hands out some other package never gets as far as the installer. Parses the
     * whole archive, so it runs off the main thread.
     */
    suspend fun isTrusted(apk: File): Boolean

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

        // Fails closed: an archive Android cannot parse, or one that carries no signers, is untrusted.
        override suspend fun isTrusted(apk: File): Boolean =
            withContext(Dispatchers.IO) {
                val packageManager = context.packageManager
                val archive = packageManager.archiveInfo(apk) ?: return@withContext false
                if (archive.packageName != context.packageName) return@withContext false
                val installed = packageManager.installedInfo(context.packageName)
                val expected = installed.signerDigests()
                expected.isNotEmpty() && archive.signerDigests() == expected
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

// The int-flag overloads are deprecated from API 33; minSdk is 29, so both shapes are needed.
private fun PackageManager.archiveInfo(apk: File): PackageInfo? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getPackageArchiveInfo(
            apk.path,
            PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
        )
    } else {
        @Suppress("DEPRECATION")
        getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNING_CERTIFICATES)
    }

private fun PackageManager.installedInfo(packageName: String): PackageInfo =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getPackageInfo(
            packageName,
            PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
        )
    } else {
        @Suppress("DEPRECATION")
        getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
    }

/**
 * SHA-256 of each certificate the package is signed with now. `apkContentsSigners` rather than the
 * rotation history: the same current key is required, so a future key rotation needs this revisited.
 */
private fun PackageInfo.signerDigests(): Set<String> =
    signingInfo
        ?.apkContentsSigners
        .orEmpty()
        .map { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it) }
        }.toSet()
