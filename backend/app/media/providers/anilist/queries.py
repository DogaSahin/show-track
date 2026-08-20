"""AniList GraphQL documents.

The `Media` field selections live in two fragments rather than being written out per query.
Phase 3 deliberately duplicated them, on the grounds that two constants each reading
top-to-bottom are easier to check against a recorded fixture than one assembled by
interpolation. Phase 4.5 adds a THIRD query over the same type through the same mapper, and at
three copies "these field lists agree" stops being observable and becomes a claim needing a
test. Fragments make it structural instead, and
`test_every_query_defines_the_fragments_it_spreads` guards the one new failure mode.

The split mirrors the Python types exactly: MediaSummaryFields maps to ProviderMediaSummary,
MediaDetailFields to ProviderMedia. Search spreads only the summary because it maps to the
thinner type, and over-fetching costs real budget against a rate-limited upstream.

`isAdult: false` stays on the search query alone. Fragments carry field selections, never
filters, so this refactor cannot move it by accident.

MEDIA_QUERY deliberately carries no adult filter, and a title fetched by id therefore persists
with no adult marker of any kind. That holds only because ids reach `get_by_id` from search
results, which are already filtered. A future caller accepting an arbitrary AniList id (a deep
link, a manual add) breaks that assumption and needs the filter here, or an explicit `isAdult`
field mapped onto the media row.
"""

MEDIA_SUMMARY_FRAGMENT = """
fragment MediaSummaryFields on Media {
  id
  title { romaji english }
  startDate { year }
  genres
  coverImage { large }
}
"""

MEDIA_DETAIL_FRAGMENT = (
    MEDIA_SUMMARY_FRAGMENT
    + """
fragment MediaDetailFields on Media {
  ...MediaSummaryFields
  status
  nextAiringEpisode { episode airingAt }
}
"""
)

SEARCH_QUERY = (
    """
query Search($search: String, $page: Int, $perPage: Int) {
  Page(page: $page, perPage: $perPage) {
    pageInfo { hasNextPage }
    media(search: $search, type: ANIME, isAdult: false, sort: SEARCH_MATCH) {
      ...MediaSummaryFields
    }
  }
}
"""
    + MEDIA_SUMMARY_FRAGMENT
)

MEDIA_QUERY = (
    """
query MediaById($id: Int) {
  Media(id: $id, type: ANIME) {
    ...MediaDetailFields
  }
}
"""
    + MEDIA_DETAIL_FRAGMENT
)

# `score(format: POINT_10_DECIMAL)` pins the scale. CONFIRMED against AniList's live schema by
# introspection, not assumed: MediaList.score takes a `format: ScoreFormat` argument, and
# ScoreFormat is exactly {POINT_100, POINT_10_DECIMAL, POINT_10, POINT_5, POINT_3}. Without the
# argument the value arrives in whichever scale the profile happens to use, which is a
# factor-of-ten error in every imported score.
USER_LIST_QUERY = (
    """
query UserList($name: String, $chunk: Int, $perChunk: Int) {
  MediaListCollection(userName: $name, type: ANIME, chunk: $chunk, perChunk: $perChunk) {
    hasNextChunk
    lists {
      name
      isCustomList
      entries {
        status
        score(format: POINT_10_DECIMAL)
        progress
        media { ...MediaDetailFields }
      }
    }
  }
}
"""
    + MEDIA_DETAIL_FRAGMENT
)
