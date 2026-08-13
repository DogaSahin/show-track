import re

import pytest
from sqlalchemy import Enum, text
from sqlalchemy.ext.asyncio import AsyncSession

from app.db import Base

# These imports are what put the tables into Base.metadata — the same requirement, and the
# same silent failure mode, as the import block in migrations/env.py. The list below is built
# at collection time, so a domain module missing from here would simply drop its enum columns
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

# The shape that same CHECK always takes, used to find these constraints from the database
# side. The one hand-written CHECK in the schema (`ck_user_media_score_range`) does not match.
_ENUM_CHECK_SQL_SHAPE = "%= ANY ((ARRAY[%"


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


@pytest.mark.parametrize(("constraint_name", "expected_values"), _enum_check_constraints())
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

    The list it runs on is only as complete as the model imports at the top of this file, and
    a missing import removes cases rather than failing. Comparing it against what the database
    actually carries makes that omission loud.
    """
    found = (
        await db_session.execute(
            text(
                "SELECT conname FROM pg_constraint "
                "WHERE contype = 'c' AND connamespace = 'public'::regnamespace "
                "AND pg_get_constraintdef(oid) LIKE :shape"
            ),
            {"shape": _ENUM_CHECK_SQL_SHAPE},
        )
    ).scalars()

    assert set(found) == {name for name, _ in _enum_check_constraints()}
