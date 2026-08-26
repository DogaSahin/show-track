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
}
