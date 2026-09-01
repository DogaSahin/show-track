import re

import pytest
from sqlalchemy import Enum, text
from sqlalchemy.ext.asyncio import AsyncSession

from app.db import Base

# These imports are what put the tables into Base.metadata — the same requirement, and the
# same silent failure mode, as the import block in migrations/env.py. The snapshot below is
# built at import time, so a domain module missing from here would simply drop its enum columns
# out of the parametrisation. `test_every_enum_check_constraint_in_the_database_is_covered`
# is the backstop that turns that omission into a failure instead of a gap.
from app.library import models as _library_models  # noqa: F401
from app.media import models as _media_models  # noqa: F401
from app.notifications import models as _notifications_models  # noqa: F401
from app.recommendations import models as _recommendations_models  # noqa: F401
from app.sync import models as _sync_models  # noqa: F401
from app.users import models as _users_models  # noqa: F401

# Matches the value literals in a CHECK rendered by `Enum(create_constraint=True)`, which
# Postgres stores as `CHECK (((status)::text = ANY ((ARRAY['airing'::character varying, ...
_QUOTED = re.compile(r"'([^']*)'")

# The shapes that same CHECK can take, used to find these constraints from the database side.
# An `Enum(create_constraint=True)` with two or more members renders as `x = ANY (ARRAY[...])`.
# One member — `PushTransport`, so far — collapses under Postgres's own IN-list simplification
# into a plain `x = 'value'`: no ANY, no ARRAY. Measured directly against `push_targets`. Both
# shapes are covered below; the one hand-written CHECK in the schema
# (`ck_user_media_score_range`) matches neither.
_ENUM_CHECK_SQL_SHAPES = ("%= ANY ((ARRAY[%", "%::text = '%'::text)%")


def _enum_check_constraints() -> list[tuple[str, frozenset[str]]]:
    """Every `Enum(create_constraint=True)` CHECK in the model, as (name, admitted values).

    Derived from the metadata rather than hardcoded so a new enum column is covered the
    moment it exists. The name is built the same way `migrations/env.py` builds it, and for
    the same reason: the CHECK's name comes from the Enum's own `name=` with the naming
    convention's `ck_<table>_` wrapped around it.
    """
    return sorted(
        (
            (
                Base.metadata.naming_convention["ck"] % {"table_name": table.name, "constraint_name": column.type.name},
                frozenset(column.type.enums),
            )
            for table in Base.metadata.tables.values()
            for column in table.columns
            if isinstance(column.type, Enum) and column.type.create_constraint
        ),
        key=lambda pair: pair[0],
    )


# Snapshotted at this module's import, before pytest imports the test modules that would fill
# `Base.metadata` in behind this file's back. Both the parametrisation and the backstop read
# this one snapshot rather than re-deriving, so the two cannot disagree about what is covered.
_DERIVED_AT_IMPORT = _enum_check_constraints()


@pytest.mark.parametrize(("constraint_name", "expected_values"), _DERIVED_AT_IMPORT)
async def test_enum_check_constraint_admits_exactly_the_python_enum_values(
    db_session: AsyncSession, constraint_name: str, expected_values: frozenset[str]
) -> None:
    """Closes the one drift `alembic check` cannot see.

    Alembic diffs columns, not type definitions, so adding a member to a StrEnum with no
    migration reports `No new upgrade operations detected` and exits 0 — measured. Nothing
    else in the suite notices either, because the ORM happily sends the new value and only
    Postgres rejects it. This is the assertion that fails instead.

    `scalar_one()` covers the other half: a brand-new enum column with no migration has no
    constraint in the database at all, so this raises `NoResultFound` rather than passing on
    an empty comparison. (`alembic check` does catch that one — a column reaches the diff.)
    """
    definition = (
        await db_session.execute(
            text("SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = :name"),
            {"name": constraint_name},
        )
    ).scalar_one()

    assert set(_QUOTED.findall(definition)) == expected_values


async def test_every_enum_check_constraint_in_the_database_is_covered(db_session: AsyncSession) -> None:
    """Guards the parametrisation above against under-collection.

    The set it runs on is only as complete as the model imports at the top of this file, and a
    missing import removes cases rather than failing. Comparing it against what the database
    actually carries makes that omission loud.

    It compares `_DERIVED_AT_IMPORT`, the snapshot taken when this module was imported, rather
    than re-deriving here. Re-deriving is vacuous: by the time any test *runs*, pytest has
    imported every other test module, and `tests/factories.py` imports
    `app.notifications.models`, so the fresh set is complete even when this file's imports are
    not. Measured with the `app.notifications` import below deleted — re-deriving gave
    `1 failed, 4 passed` for this module alone but a green `51 passed` under the full `pytest`,
    the two dropped cases vanishing in silence; against the snapshot the same deletion gives
    `1 failed, 50 passed` under the full `pytest`.

    Two limits worth stating rather than over-claiming. A module imported by *nothing* is out
    of reach: autogenerate emits an empty migration for it, so its constraints are missing from
    the database and from the snapshot alike and the two still agree. And the snapshot only
    leads the rest of the suite while this file's imports are what register these models first
    — `conftest.py` does `from main import app`, whose route modules are still stubs. Measured:
    make `app/notifications/routes.py` import its models and delete the import below, and the
    full suite is green at `53 passed`, parametrisation complete and this assertion proving
    nothing. What it does catch is the realistic omission today: a module `migrations/env.py`
    imports, so its CHECK constraints reach the database, that this file forgot.
    `migrations/env.py:21-29` documents the same import-order hazard for
    `_TYPE_BOUND_CHECK_NAMES`, which derives these names the same way.
    """
    found = (
        await db_session.execute(
            text(
                "SELECT conname FROM pg_constraint "
                "WHERE contype = 'c' AND connamespace = 'public'::regnamespace "
                "AND (pg_get_constraintdef(oid) LIKE :shape_multi OR pg_get_constraintdef(oid) LIKE :shape_single)"
            ),
            {"shape_multi": _ENUM_CHECK_SQL_SHAPES[0], "shape_single": _ENUM_CHECK_SQL_SHAPES[1]},
        )
    ).scalars()

    assert set(found) == {name for name, _ in _DERIVED_AT_IMPORT}
