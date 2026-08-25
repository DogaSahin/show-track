from decimal import Decimal

import pytest

from app.library.activity import kind_for, payload_for
from app.library.models import ActivityKind, UserMediaStatus


def test_completing_outranks_everything_else_in_the_same_patch():
    """One request, one row (S-B). Completing is a verdict; a score is an opinion; progress is
    bookkeeping. If this returned RATED the feed would report the smaller thing that happened."""
    changes = {"status": UserMediaStatus.COMPLETED, "score": Decimal("9.0"), "progress": 12}

    assert kind_for(changes) is ActivityKind.COMPLETED


def test_dropping_outranks_a_score_and_progress():
    changes = {"status": UserMediaStatus.DROPPED, "score": Decimal("3.0"), "progress": 2}

    assert kind_for(changes) is ActivityKind.DROPPED


def test_a_score_outranks_progress():
    assert kind_for({"score": Decimal("8.0"), "progress": 5}) is ActivityKind.RATED


def test_progress_alone_is_progress():
    assert kind_for({"progress": 5}) is ActivityKind.PROGRESSED


@pytest.mark.parametrize(
    "changes",
    [
        {"favorite": True},
        {"status": UserMediaStatus.WATCHING},
        {"status": UserMediaStatus.PAUSED},
        {"status": UserMediaStatus.PLANNED},
        {},
    ],
)
def test_changes_that_are_not_feed_worthy_return_none(changes):
    """A real branch, deliberately (S-F). `favorite` is invisible in the feed despite being the
    strongest taste signal in the schema, and "started watching" never appears. Both are recorded
    follow-ups, not oversights."""
    assert kind_for(changes) is None


def test_unrating_a_title_still_counts_as_rating_it():
    """`{"score": None}` comes from an explicit null, which un-rates. The change set carries the
    key, so it is feed-worthy — the payload is what says the score was cleared."""
    assert kind_for({"score": None}) is ActivityKind.RATED


def test_a_score_serialises_as_a_string_not_a_number():
    """Decision 4-N. A JSON number is an IEEE 754 double, and routing a NUMERIC(3,1) through one
    reintroduces exactly the drift that column exists to prevent. JSONB would do it silently."""
    payload = payload_for({"score": Decimal("8.5")})

    assert payload == {"score": "8.5"}
    assert isinstance(payload["score"], str)


def test_an_enum_serialises_as_its_value():
    assert payload_for({"status": UserMediaStatus.COMPLETED}) == {"status": "completed"}


def test_an_explicit_null_survives_into_the_payload():
    assert payload_for({"score": None}) == {"score": None}
