import uuid
from datetime import datetime
from decimal import Decimal
from enum import StrEnum
from typing import Annotated

from pydantic import BaseModel, ConfigDict, Field, StringConstraints, model_validator

from app.library.models import UserMediaStatus
from app.media.models import MediaSource
from app.media.schemas import MediaDetail

# Named aliases because both constraints are reused and both encode a database fact: the bounds
# mirror the score_range CHECK, and decimal_places mirrors NUMERIC(3,1) — without which a client
# sending 8.25 is silently rounded by Postgres and reads back a number it never sent. The inline
# `Decimal | None = Field(ge=1, ...)` form also works on current Pydantic (measured on 2.13.4);
# this is a readability choice, not a workaround.
#
# `le` on Progress is not cosmetic: user_media.progress is int4, so an unbounded int passes
# validation and then fails at asyncpg encode time as a 500. 100_000 is far past any real series
# and far under the 2**31 column ceiling.
Score = Annotated[Decimal, Field(ge=1, le=10, decimal_places=1)]
Progress = Annotated[int, Field(ge=0, le=100_000)]


class LibrarySort(StrEnum):
    """The `sort=` vocabulary.

    A StrEnum rather than a validated string so an unknown value is FastAPI's own 422 listing the
    legal ones, and so OpenAPI documents them. Direction is a fixed property of each field
    (decision 4-J) — there is no `order=` parameter.
    """

    SCORE = "score"
    NEXT_EPISODE_DATE = "next_episode_date"
    TITLE = "title"


class LibraryEntry(BaseModel):
    """Media is embedded rather than referenced by id: a list screen needs titles and cover art,
    and an id-only response makes rendering one page N+1 requests. Reusing MediaDetail rather
    than flattening its fields gives the client one media model across search, detail and library.

    `score` serialises as a JSON STRING ("8.5"), not a number — decision 4-N. A JSON number is an
    IEEE 754 double, and routing a score through one reintroduces exactly the drift NUMERIC(3,1)
    exists to prevent.
    """

    id: uuid.UUID
    status: UserMediaStatus
    score: Decimal | None
    progress: int
    favorite: bool
    updated_at: datetime
    media: MediaDetail


class LibraryPage(BaseModel):
    items: list[LibraryEntry]
    next_cursor: str | None


class AddLibraryEntryRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    source: MediaSource
    # ^(0|[1-9][0-9]*)$, not ^[0-9]+$: bare digits still admit "0012", which int() folds to 12,
    # so "12" and "0012" would be two rows in `media` for one upstream title. The conflict key
    # only canonicalises what the pattern canonicalises.
    #
    # A pattern at all, because TMDBProvider.get_by_id builds f"{BASE}/tv/{external_id}" and
    # httpx NORMALIZES "../.." out of a path (measured), so an unconstrained value lets any
    # authenticated caller point the server's own TMDB key at arbitrary v3 endpoints.
    #
    # max_length matches Media.external_id's String(64).
    external_id: str = Field(min_length=1, max_length=64, pattern=r"^(0|[1-9][0-9]*)$")


class UpdateLibraryEntryRequest(BaseModel):
    # extra="forbid", not the default "ignore": on a partial-update endpoint the ABSENCE of a
    # field is meaningful, so a typo'd `{"scores": 9}` would otherwise return 200 having changed
    # nothing. It also makes tenant isolation structural rather than incidental — update_entry
    # setattr()s whatever survives validation, so `{"user_id": "<someone else>"}` must not.
    model_config = ConfigDict(extra="forbid")

    status: UserMediaStatus | None = None
    score: Score | None = None
    progress: Progress | None = None
    favorite: bool | None = None

    @model_validator(mode="after")
    def _reject_explicit_nulls(self) -> "UpdateLibraryEntryRequest":
        """`score: null` unrates a title and is legal. The other three columns are NOT NULL in
        `user_media`, so an explicit null would reach the flush as an IntegrityError 500. This
        turns each into a 422 naming the field.
        """
        nulled = [
            field
            for field in ("status", "progress", "favorite")
            if field in self.model_fields_set and getattr(self, field) is None
        ]
        if nulled:
            raise ValueError(f"{', '.join(nulled)} cannot be null")
        return self


class ImportRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    # strip_whitespace inside the constraint, so min_length applies to the STRIPPED value.
    # Validating first and stripping in the route lets "   " pass as a valid username and reach
    # AniList as an empty name — an upstream round trip to produce what should have been a 422.
    #
    # No character-class pattern: narrowing to ^[A-Za-z0-9_]+$ would 422 any legitimate AniList
    # username outside that class with no workaround, and this plan has no recorded evidence for
    # that charset. external_id's pattern is different — there the evidence was measured.
    username: Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=50)]


class ImportSummary(BaseModel):
    imported: int
    skipped: int
    failed: int
    # Decision 4-L. Without this, a list truncated at the chunk cap returns a body byte-identical
    # to a complete import and the caller cannot tell it got a prefix.
    truncated: bool = False


# 4000 characters is a review, not an essay. Capped here rather than in the database (S-N):
# nothing writes a review except this endpoint, unlike UserMedia.score, whose CHECK exists
# because the AniList importer bypasses the API schema entirely.
ReviewBody = Annotated[str, Field(min_length=1, max_length=4000)]


class CreateReviewRequest(BaseModel):
    # extra="forbid" throughout, matching AddLibraryEntryRequest/UpdateLibraryEntryRequest above:
    # update_review setattr()s whatever survives validation, and on a partial update the ABSENCE
    # of a field is meaningful, so a typo'd key must 422 rather than return 200 having done
    # nothing.
    model_config = ConfigDict(extra="forbid")

    media_id: uuid.UUID
    body: ReviewBody
    contains_spoilers: bool = False


class UpdateReviewRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    body: ReviewBody | None = None
    contains_spoilers: bool | None = None

    @model_validator(mode="after")
    def _reject_explicit_nulls(self) -> "UpdateReviewRequest":
        """Both columns are NOT NULL in `reviews`, and unlike UserMedia.score neither has a
        "clear it" meaning — so an explicit null has nothing to express and would otherwise
        reach the flush as an uncaught IntegrityError 500 (measured: `PATCH {"body": null}` ->
        NotNullViolationError). Same shape as UpdateLibraryEntryRequest's validator above.
        """
        nulled = [
            field
            for field in ("body", "contains_spoilers")
            if field in self.model_fields_set and getattr(self, field) is None
        ]
        if nulled:
            raise ValueError(f"{', '.join(nulled)} cannot be null")
        return self


class ReviewRead(BaseModel):
    id: uuid.UUID
    user_id: uuid.UUID
    media_id: uuid.UUID
    body: str
    contains_spoilers: bool
    created_at: datetime
    updated_at: datetime
