from decimal import Decimal

from app.library.models import UserMediaStatus
from tests.factories import make_media, make_user, make_user_media


async def test_stats_counts_by_status_and_averages_only_rated_titles(auth_client, db_session, auth_user):
    """average_score is over RATED titles only, and rated_count ships beside it so the UI can say
    what the average is OF. An average over 2 of 5 titles is a different claim from one over 5.
    """
    # three WATCHING (two rated 8.0 and 9.0, one unrated), one COMPLETED rated 7.0, one PLANNED unrated
    rows = [
        ("Watching Rated A", UserMediaStatus.WATCHING, Decimal("8.0")),
        ("Watching Rated B", UserMediaStatus.WATCHING, Decimal("9.0")),
        ("Watching Unrated", UserMediaStatus.WATCHING, None),
        ("Completed Rated", UserMediaStatus.COMPLETED, Decimal("7.0")),
        ("Planned Unrated", UserMediaStatus.PLANNED, None),
    ]
    for i, (title, entry_status, score) in enumerate(rows):
        media = make_media(external_id=str(i), title=title)
        db_session.add(media)
        await db_session.flush()
        db_session.add(make_user_media(auth_user.id, media.id, status=entry_status, score=score))
    await db_session.flush()

    body = (await auth_client.get("/v1/library/stats")).json()

    assert body["total"] == 5
    assert body["by_status"] == {"watching": 3, "completed": 1, "planned": 1}
    assert body["average_score"] == "8.0"  # (8.0 + 9.0 + 7.0) / 3
    assert body["rated_count"] == 3


async def test_stats_on_an_empty_library_are_zeros_and_a_null_average(auth_client, auth_user):
    """No titles means no average — null, never 0.0. A displayed 0.0 would read as "you rate
    everything zero", which is a different and wrong statement."""
    body = (await auth_client.get("/v1/library/stats")).json()

    assert body["total"] == 0
    assert body["average_score"] is None
    assert body["rated_count"] == 0


async def test_stats_count_only_the_callers_titles(auth_client, db_session, auth_user):
    """Same scoping failure as every other library endpoint, and the one a naive aggregate is most
    likely to miss — a GROUP BY with no user filter returns everyone's numbers."""
    shared = make_media(external_id="9", title="Someone else's title")
    other = make_user(username="someone-else", email="else@example.com")
    db_session.add_all([shared, other])
    await db_session.flush()
    db_session.add(make_user_media(other.id, shared.id, status=UserMediaStatus.WATCHING, score=Decimal("10.0")))
    await db_session.flush()

    body = (await auth_client.get("/v1/library/stats")).json()

    assert body["total"] == 0
    assert body["by_status"] == {}
    assert body["average_score"] is None
    assert body["rated_count"] == 0
