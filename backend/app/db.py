import enum
import uuid
from collections.abc import AsyncGenerator

from sqlalchemy import Enum, MetaData, Uuid, text
from sqlalchemy.ext.asyncio import AsyncEngine, AsyncSession, async_sessionmaker, create_async_engine
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column

from app.config import get_settings

# Alembic writes `op.drop_constraint("<name>", ...)` into migrations. Without a convention
# that name is whatever Postgres generated, which can differ between machines — so the
# "drop and recreate the CHECK" path that adding an enum value depends on becomes a
# name-lookup archaeology exercise. With one, every name is derivable from the model.
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

    `create_constraint=True` is not optional: SQLAlchemy defaults it to False, which would
    emit a bare VARCHAR with no constraint at all. `length=32` stops the column being sized
    to today's longest value. `values_callable` stores the member value ('watching') rather
    than its name ('WATCHING'), and lets `24h` exist as a value despite not being a legal
    Python identifier.
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
