package com.anarky.showtrack.image

import coil3.Uri
import coil3.map.Mapper
import coil3.request.Options
import coil3.toUri
import okhttp3.HttpUrl

/**
 * Turns a server-relative cover path into an absolute URL on the ShowTrack server.
 *
 * TMDB posters are relayed by the backend (the owner's network cannot reach TMDB directly), so the
 * API sends `/v1/images/tmdb/w500/abc.jpg` rather than a CDN URL. AniList covers stay absolute
 * `https://…` and are returned untouched here (`null` means "not mine, carry on").
 *
 * Registered in `components` so it runs BEFORE Coil's built-in String-to-Uri step; that step would
 * read a leading `/` as a local file path. The `/` is stripped before resolving so a base URL with
 * a path prefix (`https://host/showtrack/`) is kept rather than replaced. Images load through the
 * unauthenticated client, so the relay endpoint must not need a token.
 */
class ApiRelativeUrlMapper(
    private val baseUrl: HttpUrl,
) : Mapper<String, Uri> {
    override fun map(
        data: String,
        options: Options,
    ): Uri? {
        if (!data.startsWith("/")) return null
        return baseUrl.resolve(data.removePrefix("/"))?.toString()?.toUri()
    }
}
