import logging
from collections.abc import Iterable, Mapping

logger = logging.getLogger(__name__)

# One vocabulary, owned by us. Provider strings never reach the database: Phase 7 scores
# recommendations with the Postgres && array-overlap operator, and on raw strings
# {'Sci-Fi'} && {'Sci-Fi & Fantasy'} is false — so cross-provider recommendation would
# silently never happen, with no error to notice.
CANONICAL_GENRES = frozenset(
    {
        "action",
        "adventure",
        "comedy",
        "crime",
        "documentary",
        "drama",
        "ecchi",
        "family",
        "fantasy",
        "horror",
        "kids",
        "magical_girl",
        "mecha",
        "music",
        "mystery",
        "psychological",
        "reality",
        "romance",
        "sci_fi",
        "slice_of_life",
        "soap",
        "sports",
        "supernatural",
        "talk",
        "thriller",
        "war",
        "western",
    }
)

# Keyed by AniList's exact genre string. `Hentai` maps to nothing, but the real defence is
# `isAdult: false` on every query — a dropped genre still returns the title, just uncategorised.
# The entry exists so tests/test_genre_mapping.py sees a complete table rather than reporting
# an unmapped upstream genre.
ANILIST_GENRES: dict[str, frozenset[str]] = {
    "Action": frozenset({"action"}),
    "Adventure": frozenset({"adventure"}),
    "Comedy": frozenset({"comedy"}),
    "Drama": frozenset({"drama"}),
    "Ecchi": frozenset({"ecchi"}),
    "Fantasy": frozenset({"fantasy"}),
    "Hentai": frozenset(),
    "Horror": frozenset({"horror"}),
    "Mahou Shoujo": frozenset({"magical_girl"}),
    "Mecha": frozenset({"mecha"}),
    "Music": frozenset({"music"}),
    "Mystery": frozenset({"mystery"}),
    "Psychological": frozenset({"psychological"}),
    "Romance": frozenset({"romance"}),
    "Sci-Fi": frozenset({"sci_fi"}),
    "Slice of Life": frozenset({"slice_of_life"}),
    "Sports": frozenset({"sports"}),
    "Supernatural": frozenset({"supernatural"}),
    "Thriller": frozenset({"thriller"}),
}

# Keyed by TMDB's integer genre ID, not its name: /search/tv returns `genre_ids` as ints and
# never returns names, so a name-keyed table would need an extra /genre/tv/list call per search
# or a second lookup table to maintain. The ids are stable and documented; the names are
# comments so the table stays readable.
TMDB_GENRES: dict[int, frozenset[str]] = {
    10759: frozenset({"action", "adventure"}),  # Action & Adventure — one upstream genre, two of ours
    16: frozenset(),  # Animation — a medium, not a genre; MediaType already carries it
    35: frozenset({"comedy"}),  # Comedy
    80: frozenset({"crime"}),  # Crime
    99: frozenset({"documentary"}),  # Documentary
    18: frozenset({"drama"}),  # Drama
    10751: frozenset({"family"}),  # Family
    10762: frozenset({"kids"}),  # Kids
    9648: frozenset({"mystery"}),  # Mystery
    10763: frozenset(),  # News
    10764: frozenset({"reality"}),  # Reality
    10765: frozenset({"sci_fi", "fantasy"}),  # Sci-Fi & Fantasy — fans out
    10766: frozenset({"soap"}),  # Soap
    10767: frozenset({"talk"}),  # Talk
    10768: frozenset({"war"}),  # War & Politics — "politics" gets no canonical genre
    37: frozenset({"western"}),  # Western
}


def map_genres(table: Mapping[object, frozenset[str]], raw: Iterable[object]) -> tuple[str, ...]:
    """Fan raw provider genre keys out to canonical names, dropping anything unmapped.

    Sorted output, deliberately: the result lands in a Postgres ARRAY column that Phase 7
    compares, and an order that depended on the upstream's ordering would make otherwise
    identical rows compare unequal and make tests flaky.
    """
    canonical: set[str] = set()
    unmapped: list[str] = []
    for value in raw:
        mapped = table.get(value)
        if mapped is None:
            unmapped.append(str(value))
            continue
        canonical.update(mapped)
    if unmapped:
        logger.warning("dropping unmapped provider genres: %s", ", ".join(unmapped))
    return tuple(sorted(canonical))
