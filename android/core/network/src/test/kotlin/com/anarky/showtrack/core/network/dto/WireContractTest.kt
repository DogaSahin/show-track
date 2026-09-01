package com.anarky.showtrack.core.network.dto

import com.anarky.showtrack.core.network.di.NetworkModule
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Decodes bytes captured from a REAL backend, not from a mock.
 *
 * The fixtures under `src/test/resources/wire/` were recorded with curl against
 * `docker compose up` on 2026-08-26 — register, log in, add two AniList titles, score one, then
 * `GET /v1/library` — with only the credentials in `token_pair.json` replaced. A MockWebServer
 * body written by hand proves the DTOs match the author's assumptions; this proves they match
 * the server. Re-record them whenever the API contract moves.
 *
 * `push_target_created.json` and `push_target_existing.json` were recorded the same way on
 * 2026-08-31, against a backend run with `NTFY_BASE_URL=http://localhost:8080`: register, log in,
 * then POST the same endpoint twice. They were the LAST endpoints to get fixtures and the FIRST
 * that should have — `POST /v1/notifications/targets` is the one contract this phase actually
 * changed, and it was the only one pinned to nothing but the author's reading of the server.
 * The endpoint in them is a made-up topic pointing at localhost; a real one is a bearer secret
 * and would not belong in the repository.
 */
class WireContractTest {
    // THE instance production uses, taken from the module rather than reconstructed. A private
    // copy would keep passing after `ignoreUnknownKeys` was deleted from NetworkModule — which
    // is exactly the shape of regression these fixtures exist to catch.
    private val json = NetworkModule.json()

    private fun fixture(name: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("wire/$name")) {
            "missing fixture wire/$name"
        }.use { it.readBytes().decodeToString() }

    @Test
    fun `a real library page decodes`() {
        val page = json.decodeFromString<LibraryPageDto>(fixture("library_page.json"))

        assertEquals(2, page.items.size)
        // Last page of a cursor-paginated list: `next_cursor` is present and null, not absent.
        assertNull(page.nextCursor)

        val scored = page.items.first()
        // THE field that catches a skim. The backend sends a JSON STRING (decision 4-N) because
        // a JSON number is an IEEE 754 double. A `Double?` here would not have parsed this line.
        assertEquals("8.5", scored.score)
        assertEquals("watching", scored.status)
        assertEquals(12, scored.progress)
        assertTrue(scored.favorite)
        assertEquals("2026-08-26T13:41:10.558339Z", scored.updatedAt)

        val finished = scored.media
        assertEquals("anilist", finished.source)
        assertEquals("anime", finished.type)
        assertEquals("Cowboy Bebop", finished.title)
        assertEquals("1", finished.externalId)
        assertEquals(1998, finished.year)
        assertEquals(listOf("action", "adventure", "drama", "sci_fi"), finished.genres)
        assertNotNull(finished.coverImageUrl)
        // A finished title carries all four airing fields as explicit nulls.
        assertEquals("finished", finished.status)
        assertNull(finished.nextEpisodeSeason)
        assertNull(finished.nextEpisodeNumber)
        assertNull(finished.nextEpisodeDate)
        assertNull(finished.daysUntilNextEpisode)

        // And an airing one carries all four populated — the case that would go unnoticed if the
        // fixture held only completed shows.
        val airing = page.items[1].media
        assertNull(page.items[1].score)
        assertEquals("airing", airing.status)
        assertEquals(1, airing.nextEpisodeSeason)
        assertEquals(1176, airing.nextEpisodeNumber)
        assertEquals("2026-08-30T14:16:00Z", airing.nextEpisodeDate)
        assertEquals(4, airing.daysUntilNextEpisode)
    }

    @Test
    fun `a truncated page carries an opaque cursor`() {
        val page = json.decodeFromString<LibraryPageDto>(fixture("library_page_cursor.json"))

        assertEquals(1, page.items.size)
        // Opaque to the client by contract — asserted as "present and non-empty", never decoded.
        assertTrue(page.nextCursor.orEmpty().isNotEmpty())
    }

    @Test
    fun `a real token pair decodes despite the field we do not model`() {
        // `token_type` is on the wire and absent from TokenPairDto. This is the test that keeps
        // `ignoreUnknownKeys` honest: drop it from the Json config and this fails.
        val pair = json.decodeFromString<TokenPairDto>(fixture("token_pair.json"))

        assertEquals("SCRUBBED_ACCESS_TOKEN", pair.accessToken)
        assertEquals("SCRUBBED_REFRESH_TOKEN", pair.refreshToken)
    }

    @Test
    fun `a real push target creation decodes`() {
        val created = json.decodeFromString<PushTargetDto>(fixture("push_target_created.json"))

        assertEquals("efc74ca0-0359-4cea-9674-449737947621", created.id)
        // A plain String and not an enum, deliberately — the backend's PushTransport is a VARCHAR
        // + CHECK it can widen in a migration, and it just did to add this very value.
        assertEquals("unifiedpush", created.transport)
        // THE field that makes this response different from every other one on this API: the
        // creation response is the only place `target` is ever returned. `id` is what the client
        // stores, because DELETE takes an id and the list shape withholds `target`.
        assertEquals("http://localhost:8080/upFixtureTopicAbC123", created.target)
        // Present and null, not absent — the client sends no label.
        assertNull(created.label)
        assertEquals("2026-08-30T23:21:37.082496Z", created.createdAt)
        // Null until the first successful send, which is how a UI can say "registered, never used".
        assertNull(created.lastSeenAt)
    }

    @Test
    fun `a repeat registration returns the same body as the creation`() {
        // Decision A-O: `onNewEndpoint` fires on every app start, so the second POST is a 200 and
        // not a 409. The STATUS differs and the BODY does not — Retrofit hands the client the same
        // shape either way, which is why `PushRepositoryImpl` reads `created.id` without caring
        // which of the two it got. A server that started returning a different shape on the 200
        // would break that silently; this is what notices.
        val created = json.decodeFromString<PushTargetDto>(fixture("push_target_created.json"))
        val existing = json.decodeFromString<PushTargetDto>(fixture("push_target_existing.json"))

        assertEquals(created, existing)
    }

    @Test
    fun `request bodies encode the snake_case keys the server requires`() {
        // The failure this catches is silent: a missing @SerialName sends `refreshToken`, the
        // server 422s, and nothing in the client says why.
        assertEquals(
            """{"refresh_token":"r-1"}""",
            json.encodeToString(RefreshRequest(refreshToken = "r-1")),
        )
        assertEquals(
            """{"email":"a@b.example","password":"p"}""",
            json.encodeToString(LoginRequest(email = "a@b.example", password = "p")),
        )
    }

    @Test
    fun `the push registration body is byte-identical to the one the server accepted`() {
        // The exact bytes curl sent to get push_target_created.json back. Encoding the DTO and
        // comparing to that string is what makes this a CONTRACT test rather than a restatement
        // of the DTO: it pins the field names, their order, and the two omissions below.
        assertEquals(
            """{"transport":"unifiedpush","target":"http://localhost:8080/upFixtureTopicAbC123"}""",
            json.encodeToString(
                RegisterTargetRequest(
                    transport = "unifiedpush",
                    target = "http://localhost:8080/upFixtureTopicAbC123",
                    label = null,
                ),
            ),
        )
    }

    @Test
    fun `an ntfy registration omits target rather than sending null`() {
        // NOT cosmetic. The server REJECTS an ntfy registration that supplies a target with a 422
        // (6-L: it mints the topic itself), and `encodeDefaults` is false, so a null-valued
        // default is dropped. Turn `encodeDefaults` on in NetworkModule.json and this body becomes
        // `{"transport":"ntfy","target":null,"label":null}` — which is a different request. This
        // test is the tripwire on that config, the way the token-pair test is on
        // `ignoreUnknownKeys`.
        assertEquals(
            """{"transport":"ntfy"}""",
            json.encodeToString(RegisterTargetRequest(transport = "ntfy")),
        )
    }
}
