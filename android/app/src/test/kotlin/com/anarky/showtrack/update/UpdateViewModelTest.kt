package com.anarky.showtrack.update

import com.anarky.showtrack.core.data.repository.AuthRepository
import com.anarky.showtrack.core.data.update.AppUpdateRepository
import com.anarky.showtrack.core.model.AppRelease
import com.anarky.showtrack.core.model.CurrentUser
import com.anarky.showtrack.core.model.UpdateState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class UpdateViewModelTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()
    private val repository = FakeAppUpdateRepository()
    private val installer = FakeApkInstaller()
    private val auth = FakeAuthRepository()
    private val clock = MutableClock(Instant.parse("2026-10-24T12:00:00Z"))

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        // A real file: a cached APK is only reused while it still exists.
        repository.apk = temp.newFile("showtrack-2.apk")
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun checkedViewModel(): UpdateViewModel {
        val viewModel = UpdateViewModel(repository, installer, auth, clock)
        viewModel.check()
        return viewModel
    }

    @Test
    fun `install without permission explains first and does not download`() =
        runTest(dispatcher) {
            installer.allowed = false
            val viewModel = checkedViewModel()
            advanceUntilIdle()

            viewModel.install()
            advanceUntilIdle()

            assertTrue(viewModel.ui.value.explainPermission)
            assertEquals(0, installer.settingsOpened)
            assertEquals(0, repository.downloads)
            assertNull(viewModel.ui.value.progress)
        }

    @Test
    fun `confirming the explanation opens settings and leaves an install pending`() =
        runTest(dispatcher) {
            installer.allowed = false
            val viewModel = checkedViewModel()
            advanceUntilIdle()
            viewModel.install()

            viewModel.confirmPermission()

            assertFalse(viewModel.ui.value.explainPermission)
            assertEquals(1, installer.settingsOpened)
            assertEquals(0, repository.downloads)
        }

    @Test
    fun `coming back with the permission granted downloads and installs, once`() =
        runTest(dispatcher) {
            installer.allowed = false
            val viewModel = checkedViewModel()
            advanceUntilIdle()
            viewModel.install()
            viewModel.confirmPermission()

            installer.allowed = true
            viewModel.resumed()
            advanceUntilIdle()
            viewModel.resumed()
            advanceUntilIdle()

            assertEquals(1, repository.downloads)
            assertEquals(listOf(repository.apk), installer.installed)
        }

    @Test
    fun `coming back without the permission clears the pending install and nothing else`() =
        runTest(dispatcher) {
            installer.allowed = false
            val viewModel = checkedViewModel()
            advanceUntilIdle()
            viewModel.install()
            viewModel.confirmPermission()

            viewModel.resumed()
            advanceUntilIdle()
            // Granted later, outside the flow: the pending flag is gone, so a resume does nothing.
            installer.allowed = true
            viewModel.resumed()
            advanceUntilIdle()

            assertEquals(0, repository.downloads)
            assertEquals(1, installer.settingsOpened)
            assertFalse(viewModel.ui.value.explainPermission)
            assertEquals(UpdateState.Available(RELEASE), viewModel.ui.value.state)
        }

    @Test
    fun `install downloads then hands the file to the installer`() =
        runTest(dispatcher) {
            val viewModel = checkedViewModel()
            advanceUntilIdle()

            viewModel.install()
            advanceUntilIdle()

            assertEquals(1, repository.downloads)
            assertEquals(listOf(repository.apk), installer.installed)
            assertNull(viewModel.ui.value.progress)
            assertFalse(viewModel.ui.value.failed)
        }

    @Test
    fun `installing the same release again re-offers the verified file without downloading`() =
        runTest(dispatcher) {
            val viewModel = checkedViewModel()
            advanceUntilIdle()

            viewModel.install()
            advanceUntilIdle()
            viewModel.install()
            advanceUntilIdle()

            assertEquals(1, repository.downloads)
            assertEquals(listOf(repository.apk, repository.apk), installer.installed)
        }

    @Test
    fun `a verified file that has since gone is downloaded again`() =
        runTest(dispatcher) {
            val viewModel = checkedViewModel()
            advanceUntilIdle()
            viewModel.install()
            advanceUntilIdle()

            repository.apk.delete()
            viewModel.install()
            advanceUntilIdle()

            assertEquals(2, repository.downloads)
        }

    @Test
    fun `a second install tap during a download does nothing`() =
        runTest(dispatcher) {
            val gate = CompletableDeferred<File>()
            repository.downloadResult = { gate.await() }
            val viewModel = checkedViewModel()
            advanceUntilIdle()

            viewModel.install()
            advanceUntilIdle()
            assertEquals(0.5f, viewModel.ui.value.progress)
            viewModel.install()
            advanceUntilIdle()
            gate.complete(repository.apk)
            advanceUntilIdle()

            assertEquals(1, repository.downloads)
            assertEquals(listOf(repository.apk), installer.installed)
        }

    @Test
    fun `a failed download shows failed and can be retried`() =
        runTest(dispatcher) {
            repository.downloadResult = { throw IOException("hash mismatch") }
            val viewModel = checkedViewModel()
            advanceUntilIdle()

            viewModel.install()
            advanceUntilIdle()
            assertTrue(viewModel.ui.value.failed)
            assertNull(viewModel.ui.value.progress)
            assertTrue(installer.installed.isEmpty())

            repository.downloadResult = { repository.apk }
            viewModel.install()
            advanceUntilIdle()

            assertFalse(viewModel.ui.value.failed)
            assertEquals(2, repository.downloads)
            assertEquals(listOf(repository.apk), installer.installed)
        }

    @Test
    fun `a file that is not a trusted ShowTrack build is deleted, not installed, and says so`() =
        runTest(dispatcher) {
            installer.trusted = false
            val viewModel = checkedViewModel()
            advanceUntilIdle()

            viewModel.install()
            advanceUntilIdle()

            assertTrue(installer.installed.isEmpty())
            assertFalse(repository.apk.exists())
            assertTrue(viewModel.ui.value.failed)
            assertTrue(viewModel.ui.value.notVerified)
            assertNull(viewModel.ui.value.progress)
        }

    @Test
    fun `a retry after a failed download asks the server again and downloads the release it offers now`() =
        runTest(dispatcher) {
            repository.downloadResult = { throw IOException("size mismatch") }
            val viewModel = checkedViewModel()
            advanceUntilIdle()
            viewModel.install()
            advanceUntilIdle()
            assertTrue(viewModel.ui.value.failed)

            val newer = RELEASE.copy(versionCode = 3, versionName = "1.2", sizeBytes = 2048, sha256 = "cd".repeat(32))
            repository.state = UpdateState.Available(newer)
            repository.downloadResult = { repository.apk }
            viewModel.install()
            advanceUntilIdle()

            assertEquals(2, repository.checks)
            assertEquals(listOf(RELEASE, newer), repository.downloaded)
            assertEquals(UpdateState.Available(newer), viewModel.ui.value.state)
            assertEquals(listOf(repository.apk), installer.installed)
            assertFalse(viewModel.ui.value.failed)
        }

    @Test
    fun `a retry that finds the app already up to date downloads nothing`() =
        runTest(dispatcher) {
            repository.downloadResult = { throw IOException("gone") }
            val viewModel = checkedViewModel()
            advanceUntilIdle()
            viewModel.install()
            advanceUntilIdle()

            repository.state = UpdateState.UpToDate
            viewModel.install()
            advanceUntilIdle()

            assertEquals(1, repository.downloads)
            assertEquals(UpdateState.UpToDate, viewModel.ui.value.state)
            assertNull(viewModel.ui.value.progress)
        }

    @Test
    fun `a release republished under the same version code is downloaded again`() =
        runTest(dispatcher) {
            val viewModel = checkedViewModel()
            advanceUntilIdle()
            viewModel.install()
            advanceUntilIdle()

            // Same versionCode, new file: the next session's check sees the new hash.
            viewModel.sessionEnded()
            repository.state = UpdateState.Available(RELEASE.copy(sha256 = "ef".repeat(32)))
            viewModel.check()
            advanceUntilIdle()
            viewModel.install()
            advanceUntilIdle()

            assertEquals(2, repository.downloads)
        }

    @Test
    fun `a resume re-checks only once the last check is six hours old`() =
        runTest(dispatcher) {
            val viewModel = checkedViewModel()
            advanceUntilIdle()

            clock.advance(Duration.ofHours(5))
            viewModel.resumed()
            advanceUntilIdle()
            assertEquals(1, repository.checks)

            // The server has since raised its minimum above the installed build.
            repository.state = UpdateState.Required(RELEASE)
            clock.advance(Duration.ofHours(2))
            viewModel.resumed()
            advanceUntilIdle()

            assertEquals(2, repository.checks)
            assertEquals(UpdateState.Required(RELEASE), viewModel.ui.value.state)
        }

    @Test
    fun `a resume does not re-check while a download runs`() =
        runTest(dispatcher) {
            val gate = CompletableDeferred<File>()
            repository.downloadResult = { gate.await() }
            val viewModel = checkedViewModel()
            advanceUntilIdle()
            viewModel.install()
            advanceUntilIdle()

            clock.advance(Duration.ofHours(7))
            viewModel.resumed()
            advanceUntilIdle()

            assertEquals(1, repository.checks)
            gate.complete(repository.apk)
            advanceUntilIdle()
        }

    @Test
    fun `settings that cannot open clear the pending install and show the failure`() =
        runTest(dispatcher) {
            installer.allowed = false
            installer.settingsFailure = IllegalStateException("no activity")
            val viewModel = checkedViewModel()
            advanceUntilIdle()
            viewModel.install()

            viewModel.confirmPermission()
            installer.allowed = true
            viewModel.resumed()
            advanceUntilIdle()

            assertTrue(viewModel.ui.value.failed)
            assertEquals(0, repository.downloads)
        }

    @Test
    fun `later snoozes the release and hides the banner`() =
        runTest(dispatcher) {
            val viewModel = checkedViewModel()
            advanceUntilIdle()
            assertEquals(UpdateState.Available(RELEASE), viewModel.ui.value.state)

            viewModel.later()
            advanceUntilIdle()

            assertEquals(listOf(RELEASE), repository.snoozed)
            assertEquals(UpdateState.UpToDate, viewModel.ui.value.state)
        }

    @Test
    fun `a required update cannot be put off`() =
        runTest(dispatcher) {
            repository.state = UpdateState.Required(RELEASE)
            val viewModel = checkedViewModel()
            advanceUntilIdle()

            viewModel.later()
            advanceUntilIdle()

            assertTrue(repository.snoozed.isEmpty())
            assertEquals(UpdateState.Required(RELEASE), viewModel.ui.value.state)
        }

    @Test
    fun `check asks the server only once per session`() =
        runTest(dispatcher) {
            val viewModel = checkedViewModel()
            advanceUntilIdle()

            viewModel.check()
            advanceUntilIdle()

            assertEquals(1, repository.checks)
        }

    @Test
    fun `sign out logs out and reports it, so the host can navigate`() =
        runTest(dispatcher) {
            repository.state = UpdateState.Required(RELEASE)
            val viewModel = checkedViewModel()
            advanceUntilIdle()

            viewModel.signOut()
            advanceUntilIdle()

            assertEquals(1, auth.logouts)
            assertTrue(viewModel.ui.value.signedOut)
        }

    @Test
    fun `a failed sign out says so and stays put`() =
        runTest(dispatcher) {
            auth.failLogout = true
            repository.state = UpdateState.Required(RELEASE)
            val viewModel = checkedViewModel()
            advanceUntilIdle()

            viewModel.signOut()
            advanceUntilIdle()

            assertTrue(viewModel.ui.value.signOutFailed)
            assertFalse(viewModel.ui.value.signedOut)
            assertEquals(UpdateState.Required(RELEASE), viewModel.ui.value.state)
        }

    @Test
    fun `the session ending clears the blocking screen, and the next sign-in checks again`() =
        runTest(dispatcher) {
            repository.state = UpdateState.Required(RELEASE)
            val viewModel = checkedViewModel()
            advanceUntilIdle()
            viewModel.signOut()
            advanceUntilIdle()

            viewModel.sessionEnded()

            assertEquals(UpdateUi(), viewModel.ui.value)

            viewModel.check()
            advanceUntilIdle()

            assertEquals(2, repository.checks)
            assertEquals(UpdateState.Required(RELEASE), viewModel.ui.value.state)
        }

    private class FakeAppUpdateRepository : AppUpdateRepository {
        var state: UpdateState = UpdateState.Available(RELEASE)
        lateinit var apk: File
        var downloadResult: suspend () -> File = { apk }
        var checks = 0
        var downloads = 0
        val downloaded = mutableListOf<AppRelease>()
        val snoozed = mutableListOf<AppRelease>()

        override suspend fun check(): UpdateState {
            checks++
            return state
        }

        override suspend fun download(
            release: AppRelease,
            onProgress: (Float) -> Unit,
        ): File {
            downloads++
            downloaded += release
            onProgress(0.5f)
            val file = downloadResult()
            // The real download (re)writes the file, so a deleted one comes back.
            file.createNewFile()
            return file
        }

        override suspend fun snooze(release: AppRelease) {
            snoozed += release
        }
    }

    private class FakeApkInstaller : ApkInstaller {
        var allowed = true
        var trusted = true
        var settingsFailure: RuntimeException? = null
        var settingsOpened = 0
        val installed = mutableListOf<File>()

        override fun canInstall(): Boolean = allowed

        override fun openInstallPermissionSettings() {
            settingsFailure?.let { throw it }
            settingsOpened++
        }

        override suspend fun isTrusted(apk: File): Boolean = trusted

        override fun install(apk: File) {
            installed += apk
        }
    }

    private class FakeAuthRepository : AuthRepository {
        var failLogout = false
        var logouts = 0

        override suspend fun hasSession(): Boolean = true

        override suspend fun currentUser(): CurrentUser = error("not exercised by UpdateViewModelTest")

        override suspend fun currentUserId(): String = error("not exercised by UpdateViewModelTest")

        override suspend fun login(
            email: String,
            password: String,
        ): Unit = error("not exercised by UpdateViewModelTest")

        override suspend fun register(
            username: String,
            email: String,
            password: String,
            inviteCode: String,
        ): Unit = error("not exercised by UpdateViewModelTest")

        override suspend fun logout() {
            logouts++
            if (failLogout) throw IOException("offline")
        }
    }

    private class MutableClock(
        private var now: Instant,
    ) : Clock() {
        fun advance(by: Duration) {
            now = now.plus(by)
        }

        override fun instant(): Instant = now

        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this
    }

    private companion object {
        val RELEASE =
            AppRelease(
                versionCode = 2,
                versionName = "1.1",
                minVersionCode = 1,
                notes = "Faster sync",
                sizeBytes = 1024,
                sha256 = "ab".repeat(32),
            )
    }
}
