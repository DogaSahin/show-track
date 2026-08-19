"""AniList GraphQL documents.

The field list is written out twice rather than shared through a fragment: two constants that
each read top-to-bottom are easier to check against a recorded fixture than one assembled by
string interpolation, and the duplication is five lines.

`isAdult: false` on the search query is the real defence against adult titles — genres.py maps
AniList's `Hentai` genre to nothing, but a dropped genre still returns the title.

MEDIA_QUERY deliberately carries no such filter, and a title fetched by id therefore persists with
no adult marker of any kind. That holds only because ids reach `get_by_id` from search results,
which are already filtered — the id is never user-supplied. A future caller that accepts an
arbitrary AniList id (a deep link, a manual add) breaks that assumption and needs the filter here,
or an explicit `isAdult` field mapped onto the media row.
"""

SEARCH_QUERY = """
query Search($search: String, $page: Int, $perPage: Int) {
  Page(page: $page, perPage: $perPage) {
    pageInfo { hasNextPage }
    media(search: $search, type: ANIME, isAdult: false, sort: SEARCH_MATCH) {
      id
      title { romaji english }
      startDate { year }
      genres
      coverImage { large }
    }
  }
}
"""

MEDIA_QUERY = """
query MediaById($id: Int) {
  Media(id: $id, type: ANIME) {
    id
    title { romaji english }
    startDate { year }
    genres
    coverImage { large }
    status
    nextAiringEpisode { episode airingAt }
  }
}
"""
