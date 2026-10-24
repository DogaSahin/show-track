package com.anarky.showtrack.update

import com.anarky.showtrack.core.data.update.AppUpdateRepository
import com.anarky.showtrack.core.model.AppRelease
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
import org.junit.Test
import java.io.File
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class UpdateViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val repository = FakeAppUpdateRepository()
    private val installer = FakeApkInstaller()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun checkedViewModel(): UpdateViewModel {
        val viewModel = UpdateViewModel(repository, installer)
        viewModel.check()
        return viewModel
    }

    @Test
    fun `install without permission opens settings and does not download`() =
        runTest(dispatcher) {
            installer.allowed = false
            val viewModel = checkedViewModel()
            advanceUntilIdle()

            viewModel.install()
            advanceUntilIdle()

            assertEquals(1, installer.settingsOpened)
            assertEquals(0, repository.downloads)
            assertNull(viewModel.ui.value.progress)
        }

    @Test
    fun `install downloads then hands the file to the installer`() =
        runTest(dispatcher) {
            val viewModel = checkedViewModel()
            advanceUntilIdle()

            viewModel.install()
            advanceUntilIdle()

            assertEquals(1, repository.downloads)
            assertEquals(listOf(APK), installer.installed)
            assertNull(viewModel.ui.value.progress)
            assertFalse(viewModel.ui.value.failed)
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
            gate.complete(APK)
            advanceUntilIdle()

            assertEquals(1, repository.downloads)
            assertEquals(listOf(APK), installer.installed)
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

            repository.downloadResult = { APK }
            viewModel.install()
            advanceUntilIdle()

            assertFalse(viewModel.ui.value.failed)
            assertEquals(2, repository.downloads)
            assertEquals(listOf(APK), installer.installed)
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

    private class FakeAppUpdateRepository : AppUpdateRepository {
        var state: UpdateState = UpdateState.Available(RELEASE)
        var downloadResult: suspend () -> File = { APK }
        var checks = 0
        var downloads = 0
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
            onProgress(0.5f)
            return downloadResult()
        }

        override suspend fun snooze(release: AppRelease) {
            snoozed += release
        }
    }

    private class FakeApkInstaller : ApkInstaller {
        var allowed = true
        var settingsOpened = 0
        val installed = mutableListOf<File>()

        override fun canInstall(): Boolean = allowed

        override fun openInstallPermissionSettings() {
            settingsOpened++
        }

        override fun install(apk: File) {
            installed += apk
        }
    }

    private companion object {
        val APK = File("showtrack-2.apk")
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
