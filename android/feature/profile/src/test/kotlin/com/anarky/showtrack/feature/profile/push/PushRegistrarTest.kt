package com.anarky.showtrack.feature.profile.push

import com.anarky.showtrack.core.data.push.PushRepository
import com.anarky.showtrack.core.model.PushNotification
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val ENDPOINT = "https://push.example.test/UPabcdef0123456789"

private class FakeRepository(
    private val failure: Exception? = null,
) : PushRepository {
    val registered = mutableListOf<String>()
    var unregisterCalls = 0

    override suspend fun register(endpoint: String) {
        failure?.let { throw it }
        registered += endpoint
    }

    override suspend fun unregister() {
        failure?.let { throw it }
        unregisterCalls++
    }

    override fun decodeMessage(body: ByteArray): PushNotification? = null
}

/**
 * Robolectric, and not for the usual reason: nothing here needs a `Context`. [PushRegistrar]'s
 * containment path calls `android.util.Log.w`, and a plain JVM unit test answers every
 * `android.util` method with "not mocked" — which THROWS. Without a shadow, the two
 * failure-containment tests below would fail for the opposite of the reason they exist,
 * reporting that a swallowed exception escaped when what escaped was the logger.
 *
 * `isReturnDefaultValues = true` would silence it more cheaply and would also silence the next
 * android.* call anyone adds here, including one whose real behaviour a test depended on.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PushRegistrarTest {
    @Test
    fun `submitting forwards the endpoint`() =
        runTest {
            val repository = FakeRepository()

            PushRegistrar(repository).submit(ENDPOINT)

            assertEquals(listOf(ENDPOINT), repository.registered)
        }

    @Test
    fun `a registration failure does not escape`() =
        runTest {
            // The caller is a BroadcastReceiver. An exception escaping it is an "app keeps
            // stopping" dialog for something the user did not do, at whatever hour the episode
            // airs. The next onNewEndpoint — the next app start — retries.
            PushRegistrar(FakeRepository(IllegalStateException("500"))).submit(ENDPOINT)
        }

    @Test
    fun `a delete failure does not escape`() =
        runTest {
            PushRegistrar(FakeRepository(IllegalStateException("503"))).delete()
        }

    @Test
    fun `cancellation is rethrown rather than swallowed`() =
        runTest {
            // Cancellation is structured concurrency's control flow, not a failure. A coroutine
            // that eats its own cancellation stops its parent from ever completing — and the
            // parent here is the one that calls PendingResult.finish(), so swallowing it would
            // hold the receiver open until the system's ~10s ceiling killed it.
            assertThrows(CancellationException::class.java) {
                kotlinx.coroutines.runBlocking {
                    PushRegistrar(FakeRepository(CancellationException("cancelled"))).submit(ENDPOINT)
                }
            }
        }
}
