from datetime import UTC, datetime, timedelta
from decimal import Decimal

from app.library.models import ActivityKind, UserMediaStatus
from tests.factories import make_activity, make_media, make_user, make_user_media


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


async def test_stats_sum_progress_and_count_favorites_across_the_library(auth_client, db_session, auth_user):
    """episodes_watched is SUM(progress) over every entry regardless of status — a paused title's
    twelve watched episodes were still watched. favorites rides the same scan via FILTER.
    """
    rows = [
        (UserMediaStatus.WATCHING, 12, True),
        (UserMediaStatus.COMPLETED, 24, False),
        (UserMediaStatus.PAUSED, 3, True),
        # A planned title contributes nothing: progress defaults to 0.
        (UserMediaStatus.PLANNED, 0, False),
    ]
    for i, (entry_status, progress, favorite) in enumerate(rows):
        media = make_media(external_id=str(i), title=f"Title {i}")
        db_session.add(media)
        await db_session.flush()
        db_session.add(
            make_user_media(auth_user.id, media.id, status=entry_status, progress=progress, favorite=favorite)
        )
    await db_session.flush()

    body = (await auth_client.get("/v1/library/stats")).json()

    assert body["episodes_watched"] == 39
    assert body["favorites"] == 2


async def test_stats_rank_genres_by_how_many_library_titles_carry_them(auth_client, db_session, auth_user):
    """One title with three genres contributes to three counts — the `unnest` expansion is the
    whole point, and a plain COUNT over `media` would report titles, not genres.
    """
    rows = [
        ["action", "drama"],
        ["action", "comedy"],
        ["action"],
        ["drama"],
    ]
    for i, genres in enumerate(rows):
        media = make_media(external_id=str(i), title=f"Title {i}", genres=genres)
        db_session.add(media)
        await db_session.flush()
        db_session.add(make_user_media(auth_user.id, media.id))
    await db_session.flush()

    body = (await auth_client.get("/v1/library/stats")).json()

    assert body["top_genres"] == [
        {"genre": "action", "count": 3},
        {"genre": "drama", "count": 2},
        {"genre": "comedy", "count": 1},
    ]


async def test_stats_cap_the_genre_ranking_and_break_ties_deterministically(auth_client, db_session, auth_user):
    """Six genres on one count each must come back as five, in a stable order — without the
    alphabetical tie-break the plan decides, and the ranking shuffles between identical libraries.
    """
    media = make_media(
        external_id="0",
        title="Everything",
        genres=["fantasy", "action", "drama", "comedy", "beta", "alpha"],
    )
    db_session.add(media)
    await db_session.flush()
    db_session.add(make_user_media(auth_user.id, media.id))
    await db_session.flush()

    body = (await auth_client.get("/v1/library/stats")).json()

    assert [row["genre"] for row in body["top_genres"]] == ["action", "alpha", "beta", "comedy", "drama"]


async def test_stats_count_this_months_adds_and_ignore_imports_and_older_months(auth_client, db_session, auth_user):
    """added_this_month reads the activity log, so it must skip an IMPORTED row (one row for N
    titles, decision S-A) and anything written before the month boundary.
    """
    now = datetime.now(UTC)
    last_month = (now.replace(day=1) - timedelta(days=1)).replace(hour=12)
    db_session.add_all(
        [
            make_activity(auth_user.id, kind=ActivityKind.ADDED),
            make_activity(auth_user.id, kind=ActivityKind.ADDED),
            # Not an add: one row standing in for a whole import.
            make_activity(auth_user.id, kind=ActivityKind.IMPORTED, payload={"count": 400}),
            # An add, but last month.
            make_activity(auth_user.id, kind=ActivityKind.ADDED, created_at=last_month),
            # An add by someone else, this month.
        ]
    )
    other = make_user(username="other-adder", email="other-adder@example.com")
    db_session.add(other)
    await db_session.flush()
    db_session.add(make_activity(other.id, kind=ActivityKind.ADDED))
    await db_session.flush()

    body = (await auth_client.get("/v1/library/stats")).json()

    assert body["added_this_month"] == 2


async def test_stats_on_an_empty_library_report_zero_rather_than_null_aggregates(auth_client, auth_user):
    """SUM over zero rows is NULL in SQL, not 0 — without the COALESCE this endpoint would 500 on
    a brand-new account, which is precisely when it is first opened.
    """
    body = (await auth_client.get("/v1/library/stats")).json()

    assert body["episodes_watched"] == 0
    assert body["favorites"] == 0
    assert body["added_this_month"] == 0
    assert body["top_genres"] == []
