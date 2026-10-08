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

    /** A body shaped exactly like the backend's GroupSummary. A misspelled @SerialName fails here. */
    @Test
    fun `a group summary decodes every field`() {
        val group =
            json.decodeFromString<GroupDto>(
                """
                {"id": "g-1", "name": "Home", "created_at": "2026-09-01T10:00:00Z", "my_role": "owner",
                 "member_count": 4, "member_preview": [{"id": "u-1", "username": "mira"}],
                 "watchlist_count": 6, "watchlist_preview": [{"media_id": "m-1", "cover_image_url": null}]}
                """.trimIndent(),
            )

        assertEquals("owner", group.myRole)
        assertEquals(4, group.memberCount)
        assertEquals(listOf(GroupActorDto(id = "u-1", username = "mira")), group.memberPreview)
        assertEquals(6, group.watchlistCount)
        assertEquals(listOf(WatchlistPreviewDto(mediaId = "m-1", coverImageUrl = null)), group.watchlistPreview)
    }

    /** A server from before the summary fields still decodes, with every summary field "unknown". */
    @Test
    fun `a group without the summary fields still decodes`() {
        val group =
            json.decodeFromString<GroupDto>(
                """{"id": "g-1", "name": "Home", "created_at": "2026-09-01T10:00:00Z"}""",
            )

        assertNull(group.myRole)
        assertNull(group.memberCount)
        assertEquals(emptyList<GroupActorDto>(), group.memberPreview)
        assertNull(group.watchlistCount)
        assertEquals(emptyList<WatchlistPreviewDto>(), group.watchlistPreview)
    }

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

    @Test
    fun `a real registration response decodes`() {
        // Captured from the real POST /v1/auth/register route (task 9a.2's stubbed-provider
        // pytest, not curl) rather than hand-written, so this pins the server's actual UserOut
        // shape: field set and the snake_case `created_at` that @SerialName maps back from.
        val user = json.decodeFromString<UserDto>(fixture("register_user.json"))

        assertEquals("a22a65d5-2348-4bce-b35f-e173d3b45bf2", user.id)
        assertEquals("someone", user.username)
        assertEquals("someone@example.com", user.email)
        assertEquals("2026-09-01T08:58:37.582626Z", user.createdAt)
    }

    /** A body shaped exactly like the backend's SearchItem. A misspelled @SerialName fails here. */
    @Test
    fun `a search item decodes what the caller already has of it`() {
        val item =
            json.decodeFromString<SearchItemDto>(
                """
                {"source": "anilist", "external_id": "154587", "type": "anime", "title": "Frieren",
                 "year": 2023, "genres": ["drama"], "cover_image_url": null,
                 "media_id": "m-1", "library_entry": {"id": "e-1", "status": "planned"}}
                """.trimIndent(),
            )

        assertEquals("m-1", item.mediaId)
        assertEquals(LibraryEntryRefDto(id = "e-1", status = "planned"), item.libraryEntry)
    }

    /** A server from before these fields still decodes: the title is simply unknown to the client. */
    @Test
    fun `a search item without the library fields still decodes`() {
        val item =
            json.decodeFromString<SearchItemDto>(
                """
                {"source": "tmdb", "external_id": "95396", "type": "tv", "title": "Severance",
                 "year": 2022, "genres": [], "cover_image_url": null}
                """.trimIndent(),
            )

        assertNull(item.mediaId)
        assertNull(item.libraryEntry)
    }

    @Test
    fun `a resolve request is sent with the server's field names`() {
        assertEquals(
            """{"source":"anilist","external_id":"154587"}""",
            json.encodeToString(ResolveMediaRequestDto(source = "anilist", externalId = "154587")),
        )
    }

    /** A body shaped exactly like the backend's EpisodeList. A misspelled @SerialName fails here. */
    @Test
    fun `an episode list decodes every field`() {
        val list =
            json.decodeFromString<EpisodeListDto>(
                """
                {"synced_at": "2026-10-01T08:00:00Z", "total_episodes": 19,
                 "seasons": [{"number": 1, "episode_count": 1,
                   "episodes": [{"id": "e-1", "number": 1, "title": "Good News About Hell",
                                 "air_date": "2022-02-18", "aired": true}]}]}
                """.trimIndent(),
            )

        assertEquals(19, list.totalEpisodes)
        assertEquals(1, list.seasons.single().episodeCount)
        assertEquals(
            EpisodeDto(id = "e-1", number = 1, title = "Good News About Hell", airDate = "2022-02-18", aired = true),
            list.seasons
                .single()
                .episodes
                .single(),
        )
    }

    /** total_episodes rides on every media object; a server without it still decodes. */
    @Test
    fun `a media object carries its episode total when the server sends one`() {
        val base =
            """"id": "m-1", "source": "tmdb", "external_id": "95396", "type": "tv", "title": "Severance",
               "year": 2022, "genres": [], "cover_image_url": null, "status": "airing",
               "next_episode_season": null, "next_episode_number": null, "next_episode_date": null,
               "days_until_next_episode": null"""

        assertEquals(19, json.decodeFromString<MediaDto>("{$base, \"total_episodes\": 19}").totalEpisodes)
        assertNull(json.decodeFromString<MediaDto>("{$base}").totalEpisodes)
    }

    @Test
    fun `watched episodes travel with the server's field names`() {
        assertEquals(
            """{"episode_ids":["e-4","e-5"],"watched":true}""",
            json.encodeToString(SetWatchedRequestDto(episodeIds = listOf("e-4", "e-5"), watched = true)),
        )
        assertEquals(
            listOf("e-1"),
            json.decodeFromString<WatchedEpisodesDto>("""{"episode_ids": ["e-1"]}""").episodeIds,
        )
    }
}
