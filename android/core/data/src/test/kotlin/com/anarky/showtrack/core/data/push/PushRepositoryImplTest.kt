package com.anarky.showtrack.core.data.push

import com.anarky.showtrack.core.network.api.ShowTrackApi
import com.anarky.showtrack.core.network.dto.LibraryPageDto
import com.anarky.showtrack.core.network.dto.PushTargetDto
import com.anarky.showtrack.core.network.dto.RegisterTargetRequest
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val ENDPOINT = "https://push.example.test/UPabcdef0123456789"

/**
 * The bytes below are the REAL wire payload, captured on 2026-08-30 by driving the backend's
 * `UnifiedPushTransport` at the ntfy server in `docker compose` and reading the message back off
 * `GET /{topic}/json?poll=1`. Written by hand, this test would only prove the DTO matches its
 * author's assumptions — the same argument `:core:network`'s `WireContractTest` makes.
 */
private const val REAL_PAYLOAD =
    """{"title":"Cowboy Bebop","body":"Episode 12 airs soon",""" +
        """"media_id":"11111111-2222-3333-4444-555555555555","episode_number":12,"threshold":"24h"}"""

private class FakeApi : ShowTrackApi {
    var registrations = mutableListOf<RegisterTargetRequest>()
    var deletions = mutableListOf<String>()
    var registerFailure: Exception? = null
    var deleteFailure: Exception? = null
    var nextId = "target-1"

    override suspend fun library(
        cursor: String?,
        limit: Int,
    ): LibraryPageDto = error("not used")

    override suspend fun registerPushTarget(request: RegisterTargetRequest): PushTargetDto {
        registerFailure?.let { throw it }
        registrations += request
        return PushTargetDto(
            id = nextId,
            transport = request.transport,
            label = request.label,
            target = request.target,
            createdAt = "2026-08-30T21:04:50Z",
        )
    }

    override suspend fun deletePushTarget(id: String) {
        deleteFailure?.let { throw it }
        deletions += id
    }
}

/**
 * An in-memory stand-in for the DataStore-backed store. Constructing the real one needs a
 * `Context` and a file, and what these tests are about is the repository's ORDERING around it —
 * write only after the POST succeeds, clear only after the DELETE does — not DataStore's
 * persistence, which is not ours to test.
 */
private class FakeStore : PushRegistrationStore {
    var record: PushRegistration? = null

    override suspend fun read(): PushRegistration? = record

    override suspend fun write(registration: PushRegistration) {
        record = registration
    }

    override suspend fun clear() {
        record = null
    }
}

/**
 * Robolectric because [PushRepositoryImpl.onLoggedOut] logs a failed DELETE through
 * `android.util.Log`, which a plain JVM test answers with "not mocked" — and THROWS. Without a
 * shadow, the device-handover test below would fail for the opposite of the reason it exists.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PushRepositoryImplTest {
    private fun repository(
        api: ShowTrackApi,
        store: PushRegistrationStore,
    ) = PushRepositoryImpl(api, store, Json { ignoreUnknownKeys = true })

    @Test
    fun `a real captured payload decodes to the domain type`() {
        val decoded = repository(FakeApi(), FakeStore()).decodeMessage(REAL_PAYLOAD.toByteArray())

        assertEquals("Cowboy Bebop", decoded?.title)
        assertEquals("Episode 12 airs soon", decoded?.body)
        // THE field the whole transport exists for. A `media_id`/`mediaId` @SerialName slip makes
        // this null, the notification loses its deep link, and nothing else in the app notices.
        assertEquals("11111111-2222-3333-4444-555555555555", decoded?.mediaId)
        assertEquals(12, decoded?.episodeNumber)
    }

    @Test
    fun `a payload that is not ours yields null rather than throwing`() {
        // This runs inside a BroadcastReceiver. An exception here is a process death for a
        // message another app chose the contents of.
        val push = repository(FakeApi(), FakeStore())

        assertNull(push.decodeMessage("not json at all".toByteArray()))
        assertNull(push.decodeMessage("""{"title":"only half of it"}""".toByteArray()))
        assertNull(push.decodeMessage(byteArrayOf(0x00, 0x01, 0x02)))
    }

    @Test
    fun `registering sends the endpoint as a unifiedpush target`() =
        runTest {
            val api = FakeApi()

            repository(api, FakeStore()).register(ENDPOINT)

            assertEquals(1, api.registrations.size)
            assertEquals("unifiedpush", api.registrations.single().transport)
            assertEquals(ENDPOINT, api.registrations.single().target)
        }

    @Test
    fun `re-registering the same endpoint does not repeat the call`() =
        runTest {
            val api = FakeApi()
            val store = FakeStore()
            val push = repository(api, store)

            push.register(ENDPOINT)
            push.register(ENDPOINT)

            // The LOCAL half of decision A-O. It is an optimisation, not the guarantee — clearing
            // app data defeats it — which is why the server is idempotent too. Both halves matter:
            // without the server's, a reinstall silently doubles every notification.
            assertEquals(1, api.registrations.size)
        }

    @Test
    fun `a changed endpoint is re-registered`() =
        runTest {
            val api = FakeApi()
            val store = FakeStore()
            val push = repository(api, store)

            push.register(ENDPOINT)
            push.register("$ENDPOINT-rotated")

            // The skip must key on the endpoint VALUE, not on "have we ever registered". A
            // distributor that rotates the endpoint would otherwise leave the app registered at
            // the old one forever, and every notification would go nowhere.
            assertEquals(2, api.registrations.size)
        }

    @Test
    fun `a failed registration records nothing`() =
        runTest {
            val api = FakeApi().apply { registerFailure = IllegalStateException("500") }
            val store = FakeStore()

            runCatching { repository(api, store).register(ENDPOINT) }

            // Recording an id the server never issued would make the next unregister() DELETE a
            // fabricated id — a 404 nobody can diagnose — while the real row lived on.
            assertNull(store.record)
        }

    @Test
    fun `unregistering deletes the stored target and forgets it`() =
        runTest {
            val api = FakeApi()
            val store = FakeStore()
            val push = repository(api, store)
            push.register(ENDPOINT)

            push.unregister()

            assertEquals(listOf("target-1"), api.deletions)
            assertNull(store.record)
        }

    @Test
    fun `unregistering with nothing stored is a no-op`() =
        runTest {
            val api = FakeApi()

            repository(api, FakeStore()).unregister()

            assertTrue(api.deletions.isEmpty())
        }

    @Test
    fun `a failed delete keeps the record so it can be retried`() =
        runTest {
            val api = FakeApi()
            val store = FakeStore()
            val push = repository(api, store)
            push.register(ENDPOINT)
            api.deleteFailure = IllegalStateException("503")

            runCatching { push.unregister() }

            // Clearing first would leak a server row with no local trace of it, permanently.
            assertEquals("target-1", store.record?.targetId)
        }

    @Test
    fun `logging out deletes the target and forgets it`() =
        runTest {
            val api = FakeApi()
            val store = FakeStore()
            val push = repository(api, store)
            push.register(ENDPOINT)

            push.onLoggedOut()

            assertEquals(listOf("target-1"), api.deletions)
            assertNull(store.record)
        }

    @Test
    fun `logging out forgets the target even when the DELETE fails`() =
        runTest {
            // The COMMONEST logout path: the app learns it is logged out from a terminal refresh
            // failure, so the token is already dead and the DELETE cannot succeed. Keeping the
            // record on failure — which is right for unregister() — would leave the next user
            // permanently blocked by our own local skip.
            val api = FakeApi().apply { deleteFailure = IllegalStateException("401") }
            val store = FakeStore()
            val push = repository(api, store)
            push.register(ENDPOINT)

            push.onLoggedOut()

            assertNull(store.record)
        }

    @Test
    fun `a second user on the same device can register the same endpoint`() =
        runTest {
            // THE device-handover test. The distributor is unchanged across a logout, so
            // `onNewEndpoint` hands the SAME endpoint to the next account. Without the logout
            // clear, register()'s local skip posts nothing, the previous user's row still points
            // at this device, and this user receives their notifications.
            val api = FakeApi()
            val store = FakeStore()
            val push = repository(api, store)
            push.register(ENDPOINT) // user A
            api.nextId = "target-2"

            push.onLoggedOut()
            push.register(ENDPOINT) // user B, same device, same endpoint

            assertEquals(2, api.registrations.size)
            assertEquals("target-2", store.record?.targetId)
        }

    @Test
    fun `logging out with nothing registered is a no-op`() =
        runTest {
            val api = FakeApi()

            repository(api, FakeStore()).onLoggedOut()

            assertTrue(api.deletions.isEmpty())
        }
}
