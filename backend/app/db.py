import enum
import uuid
from collections.abc import AsyncGenerator, Iterator, Sequence
from typing import TypeVar

from sqlalchemy import Enum, MetaData, Uuid, text
from sqlalchemy.ext.asyncio import AsyncEngine, AsyncSession, async_sessionmaker, create_async_engine
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column

from app.config import get_settings

# Without this convention, PK/UNIQUE constraints are anonymous in SQLAlchemy's own
# metadata (`.name` is `None` on the Python object) even though Postgres assigns them a
# name at creation time using its own deterministic algorithm (`<table>_pkey`,
# `<table>_<column>_key` — confirmed identical for separately-created tables, not
# something that varies by machine). Alembic's autogenerate still works without this
# convention: it reflects that Postgres-assigned name from the live database at diff
# time and renders a working `op.drop_constraint(op.f('<reflected name>'), ...)`.
#
# What the convention actually buys is a name readable off the model itself — the way
# `tests/test_users_model.py` asserts on `uq_users_username` — without needing to query
# Postgres or trust what autogenerate happened to reflect. For a CHECK
# constraint from `enum_column`, the name always comes from that function's own `name=`
# argument regardless of this convention; the convention's contribution there is the
# `ck_<table>_` prefix it wraps around whatever `name=` value is passed in.
NAMING_CONVENTION = {
    "ix": "ix_%(table_name)s_%(column_0_name)s",
    "uq": "uq_%(table_name)s_%(column_0_N_name)s",
    "ck": "ck_%(table_name)s_%(constraint_name)s",
    "fk": "fk_%(table_name)s_%(column_0_name)s_%(referred_table_name)s",
    "pk": "pk_%(table_name)s",
}


class Base(DeclarativeBase):
    """Declarative base. Every model in every domain module inherits this."""

    metadata = MetaData(naming_convention=NAMING_CONVENTION)


class UUIDPrimaryKeyMixin:
    """The identical UUID primary key every table carries.

    `default=uuid.uuid4` runs when SQLAlchemy compiles the INSERT during flush, not at
    construction: `user.id` is `None` right after `User(...)` and still `None` right
    after `session.add(user)`, populated only once `await session.flush()` actually
    runs (checked at each of those three points). The emitted INSERT includes `id` as a
    bound parameter and only puts `created_at` — which has no client-side default — in
    the `RETURNING` clause, so `id` doesn't need a round-trip back from Postgres to be
    known.

    `server_default=text("gen_random_uuid()")` covers INSERTs issued as raw SQL that
    bypass the ORM: a raw `INSERT` with no `id` column still returns a generated UUID.
    `gen_random_uuid()` is built into Postgres 13+, so no pgcrypto extension is needed.
    `sort_order=-1` puts `id` first in the DDL, ahead of the subclass's own columns —
    visible in the generated migration's column order.
    """

    id: Mapped[uuid.UUID] = mapped_column(
        Uuid,
        primary_key=True,
        default=uuid.uuid4,
        server_default=text("gen_random_uuid()"),
        sort_order=-1,
    )


def enum_column(enum_cls: type[enum.Enum], name: str) -> Enum:
    """A VARCHAR + CHECK enum, never a native Postgres ENUM type.

    Native enums cannot be altered by autogenerate (Alembic diffs columns, not type
    definitions, so adding a value produces a silently empty migration) and removing a
    value means creating a new type and rewriting the column. A CHECK is a transactional
    drop-and-recreate.

    `name` is the bare identifier, not the finished constraint name: `NAMING_CONVENTION["ck"]`
    wraps `ck_<table>_` around it, so `enum_column(MediaStatus, "status")` on `media` yields
    `ck_media_status` — and passing `name="ck_media_status"` would yield
    `ck_media_ck_media_status`.

    `create_constraint=True` is not optional: SQLAlchemy defaults it to False, which would
    emit a bare VARCHAR with no constraint at all. `length=32` stops the column being sized
    to today's longest value. Without `values_callable`, SQLAlchemy's default is to store
    the member *name* ('WATCHING'); `values_callable` makes it store the member *value*
    ('watching') instead — so the CHECK admits `'24h'` rather than the member name
    `'TWENTY_FOUR_HOURS'`, which is what lets a value that isn't a legal Python identifier
    be the stored string.
    """
    return Enum(
        enum_cls,
        native_enum=False,
        create_constraint=True,
        length=32,
        name=name,
        values_callable=lambda enum_type: [member.value for member in enum_type],
    )


_engine: AsyncEngine | None = None
_sessionmaker: async_sessionmaker[AsyncSession] | None = None


def get_engine() -> AsyncEngine:
    """Build the engine on first use, inside the running event loop.

    Not at import time: an AsyncEngine binds its pool to the loop that first uses it, so a
    module-level engine is unusable from any second loop. Not on `app.state` either — the
    Phase 5 scheduler job and any CLI script have no request to reach the app through, and
    would each end up with their own pool.

    The lazy check is safe without a lock only because there is no `await` between the test
    and the assignment, so no other coroutine can interleave. That stops being true the
    moment anything here needs to await.
    """
    global _engine
    if _engine is None:
        _engine = create_async_engine(get_settings().database_url, echo=False)
    return _engine


def get_sessionmaker() -> async_sessionmaker[AsyncSession]:
    # expire_on_commit=False: the default (True) expires ORM objects on commit, so a later
    # attribute access triggers a lazy reload — which raises MissingGreenlet in async code
    # instead of quietly re-querying. Trade-off: a route can return a post-commit object, but
    # that object's attributes may be stale if the row changed elsewhere since the commit.
    global _sessionmaker
    if _sessionmaker is None:
        _sessionmaker = async_sessionmaker(get_engine(), class_=AsyncSession, expire_on_commit=False)
    return _sessionmaker


async def dispose_engine() -> None:
    """Release the pool and clear both memos, so a later `get_engine()` rebuilds."""
    global _engine, _sessionmaker
    if _engine is not None:
        await _engine.dispose()
    _engine = None
    _sessionmaker = None


async def get_session() -> AsyncGenerator[AsyncSession, None]:
    async with get_sessionmaker()() as session:
        yield session


# Postgres' Bind message encodes the parameter count as an int16, so one statement carries at
# most 32,767 bound parameters, and asyncpg enforces that. `media` binds twelve parameters per
# row — eleven columns plus the client-side `id` from UUIDPrimaryKeyMixin's uuid4 default, which
# SQLAlchemy includes in the INSERT — putting the wall near 2,730 rows. Reachable by a large
# AniList import, and invisible until it fires.
BULK_INSERT_CHUNK_SIZE = 500

# Postgres SQLSTATEs, not constraint names. Both were measured as available on `exc.orig.sqlstate`
# (asyncpg's DBAPI adapter also spells it `pgcode`), and both are Postgres standards that survive a
# rename — whereas `uq_reviews_user_id_media_id` and `fk_group_watchlist_media_id_media` are
# generated by NAMING_CONVENTION above, so matching on them couples a service to that dict.
#
# They live here rather than in the first service that needed them because there are now two:
# `library.create_review` and `groups.propose_title` both take a CLIENT-SUPPLIED media_id and so
# must tell "already there" apart from "no such title". One definition, because a second copy of
# a discrimination is how two call sites end up disagreeing about what an error means.
UNIQUE_VIOLATION = "23505"
FOREIGN_KEY_VIOLATION = "23503"

T = TypeVar("T")


def chunked(items: Sequence[T], size: int) -> Iterator[Sequence[T]]:  # noqa: UP047
    """`itertools.batched` does this in one line but is 3.12+, and this project's supported
    floor is 3.11.

    The noqa is that same floor showing up a second way. ruff's `target-version = "py312"` makes
    UP047 demand PEP 695 syntax (`def chunked[T](...)`), which is 3.12-only — taking its advice
    would break the floor this docstring exists to respect. If the floor ever moves to 3.12,
    delete the TypeVar, the noqa and this paragraph, and let ruff have its way.
    """
    for start in range(0, len(items), size):
        yield items[start : start + size]
