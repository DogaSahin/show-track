package com.anarky.showtrack.feature.profile

import com.anarky.showtrack.core.model.ImportFailure
import com.anarky.showtrack.core.model.ImportSummary
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
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * Task 9b.6. Exercised against [FakeLibraryRepository] — the same fake
 * [ProfileViewModelTest]/[ProfileResumeTest] already share — never
 * [com.anarky.showtrack.core.data.repository.LibraryRepositoryImpl], which would need Retrofit,
 * off this module's compile classpath (architecture rule 2).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ImportViewModelTest {
    // viewModelScope is hard-wired to Dispatchers.Main, which has no implementation on a plain
    // JVM. Substituting a TestDispatcher is what makes the launch inside `import` run at all.
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    /**
     * The server's own `UserListNotAvailable` cannot distinguish "no such user" from "a private
     * list" — both surface as the same 404 (`ImportFailure.ListNotPublic`'s KDoc). This pins that
     * the ViewModel does not invent a distinction the server does not make: it maps straight to
     * the ONE case that covers both, never to a guessed "user not found" or "list is private".
     */
    @Test
    fun `a 404 says the profile must be public, without guessing which cause it was`() =
        runTest(dispatcher) {
            val repository = FakeLibraryRepository(importFailure = ImportFailure.ListNotPublic(IOException("404")))
            val viewModel = ImportViewModel(repository)

            viewModel.import("someone")
            advanceUntilIdle()

            assertEquals(ImportUiState.Form(error = ImportError.ProfileNotPublic), viewModel.state.value)
        }

    @Test
    fun `a successful import reports the three counts`() =
        runTest(dispatcher) {
            val summary = ImportSummary(imported = 40, skipped = 5, failed = 2, truncated = false)
            val repository = FakeLibraryRepository(importResult = summary)
            val viewModel = ImportViewModel(repository)

            viewModel.import("someone")
            advanceUntilIdle()

            assertEquals(ImportUiState.Success(summary), viewModel.state.value)
        }

    /**
     * M4, task 9b.6 fix round: without `FakeLibraryRepository.lastUsername`, every test in this
     * class that called `import("someone")` would have stayed green even if `import()` passed the
     * repository a hardcoded or blank string instead of the caller's actual [String] argument — the
     * fake counted CALLS but discarded the one thing that made each call meaningful.
     */
    @Test
    fun `the exact username typed reaches the repository`() =
        runTest(dispatcher) {
            val repository = FakeLibraryRepository()
            val viewModel = ImportViewModel(repository)

            viewModel.import("Frieren1998")
            advanceUntilIdle()

            assertEquals("Frieren1998", repository.lastUsername)
        }

    /**
     * The acceptance criterion, and the thing that makes a second run safe to offer: nothing here
     * refuses a repeat call, and the FAKE's own `importCalls` proves the ViewModel genuinely
     * issued the request again rather than short-circuiting to a cached result — a state-only
     * assertion could not tell those two apart.
     */
    @Test
    fun `re-running reports everything skipped and changes nothing`() =
        runTest(dispatcher) {
            val allSkipped = ImportSummary(imported = 0, skipped = 47, failed = 0, truncated = false)
            val repository = FakeLibraryRepository(importResult = allSkipped)
            val viewModel = ImportViewModel(repository)
            viewModel.import("someone")
            advanceUntilIdle()

            viewModel.import("someone")
            advanceUntilIdle()

            assertEquals(ImportUiState.Success(allSkipped), viewModel.state.value)
            assertEquals(2, repository.importCalls)
        }

    @Test
    fun `a failed submit clears the submitting flag`() =
        runTest(dispatcher) {
            // Without this the button stays disabled forever and the screen is dead — the same
            // regression `AuthViewModelTest`'s identically-named test guards against.
            val repository = FakeLibraryRepository(importFailure = IOException("offline"))
            val viewModel = ImportViewModel(repository)

            viewModel.import("someone")
            advanceUntilIdle()

            assertFalse((viewModel.state.value as ImportUiState.Form).submitting)
        }

    @Test
    fun `a 422 reports an invalid username`() =
        runTest(dispatcher) {
            val repository = FakeLibraryRepository(importFailure = ImportFailure.InvalidUsername(IOException()))
            val viewModel = ImportViewModel(repository)

            viewModel.import("")
            advanceUntilIdle()

            assertEquals(ImportUiState.Form(error = ImportError.InvalidUsername), viewModel.state.value)
        }

    @Test
    fun `an upstream failure is reported distinctly from being offline`() =
        runTest(dispatcher) {
            val repository = FakeLibraryRepository(importFailure = ImportFailure.UpstreamUnavailable(IOException()))
            val viewModel = ImportViewModel(repository)

            viewModel.import("someone")
            advanceUntilIdle()

            assertEquals(ImportUiState.Form(error = ImportError.UpstreamUnavailable), viewModel.state.value)
        }

    @Test
    fun `being offline surfaces as Offline, not Unknown`() =
        runTest(dispatcher) {
            val repository = FakeLibraryRepository(importFailure = ImportFailure.Offline(IOException()))
            val viewModel = ImportViewModel(repository)

            viewModel.import("someone")
            advanceUntilIdle()

            assertEquals(ImportUiState.Form(error = ImportError.Offline), viewModel.state.value)
        }

    /**
     * Retrying clears the previous error rather than leaving it stacked underneath a new
     * `submitting = true` — decision C-S ("clear the error before launching a retry, not only on
     * success"), the same shape `ProfileViewModelTest`'s `retrying a failed sign-out clears the
     * previous error` pins for sign-out.
     */
    @Test
    fun `retrying after a failure clears the previous error`() =
        runTest(dispatcher) {
            val repository = FakeLibraryRepository(importFailure = IOException("offline"))
            val viewModel = ImportViewModel(repository)
            viewModel.import("someone")
            advanceUntilIdle()
            assertEquals(ImportError.Unknown, (viewModel.state.value as ImportUiState.Form).error)

            repository.importFailure = null
            repository.importResult = ImportSummary(imported = 1, skipped = 0, failed = 0, truncated = false)
            viewModel.import("someone")

            // Mid-flight, before advanceUntilIdle(): the error must already be gone, not merely
            // gone once the retry finishes — a version that cleared it only in the success branch
            // would still pass an end-state-only assertion here.
            assertEquals(ImportUiState.Form(submitting = true, error = null), viewModel.state.value)
        }
}
