"""AniList GraphQL documents.

The field list is written out twice rather than shared through a fragment: two constants that
each read top-to-bottom are easier to check against a recorded fixture than one assembled by
string interpolation, and the duplication is five lines.

`isAdult: false` on the search query is the real defence against adult titles — genres.py maps
AniList's `Hentai` genre to nothing, but a dropped genre still returns the title.
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
