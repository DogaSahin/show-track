import asyncio
from logging.config import fileConfig

from alembic import context
from sqlalchemy import Enum, pool
from sqlalchemy.engine import Connection
from sqlalchemy.ext.asyncio import create_async_engine

# this is the Alembic Config object, which provides
# access to the values within the .ini file in use.
config = context.config

# Interpret the config file for Python logging.
# This line sets up loggers basically.
if config.config_file_name is not None:
    fileConfig(config.config_file_name)

from app.config import get_settings  # noqa: E402
from app.db import Base  # noqa: E402

# IMPORTANT: Alembic autogenerate only sees models that have been imported somewhere —
# a model nothing imports produces a silently EMPTY migration (exit 0, no error). The
# imports below are what make autogenerate work; every new domain module must be added
# to this list when it grows real models.
from app.library import models as _library_models  # noqa: F401,E402
from app.media import models as _media_models  # noqa: F401,E402
from app.notifications import models as _notifications_models  # noqa: F401,E402
from app.recommendations import models as _recommendations_models  # noqa: F401,E402
from app.sync import models as _sync_models  # noqa: F401,E402
from app.users import models as _users_models  # noqa: F401,E402

target_metadata = Base.metadata

# The constraint names `enum_column()` produces, e.g. {"ck_media_type", "ck_media_source", ...}.
# Derived from the model's own Enum columns and its own naming convention — not hardcoded and
# not imported from Alembic's private internals — so it tracks the model automatically and stays
# correct if NAMING_CONVENTION in app/db.py ever changes. Computed once at import time since
# target_metadata doesn't change within a process.
_TYPE_BOUND_CHECK_NAMES = {
    target_metadata.naming_convention["ck"] % {"table_name": table.name, "constraint_name": column.type.name}
    for table in target_metadata.tables.values()
    for column in table.columns
    if isinstance(column.type, Enum) and column.type.create_constraint
}


def _include_object(object: object, name: str, type_: str, reflected: bool, compare_to: object) -> bool:
    """Exclude, specifically, the CHECK constraints `enum_column()` generates from Alembic's
    "removed constraint" diff — nothing else.

    `Enum(create_constraint=True)` ties its CHECK constraint to the column's type
    (SQLAlchemy's `SchemaType`, an internal `_type_bound` flag) rather than adding it as a
    regular schema object. Alembic's own check-constraint comparator
    (`alembic/autogenerate/compare/check_constraints.py`) filters these out of the *model*
    side of every diff for that reason, but still picks them up on the *reflected* side once
    the table exists in the database — so every `enum_column()` constraint is reported as
    "removed" on every run, regardless of whether it actually changed. Confirmed directly
    against this table: adding a new enum member (an actual constraint change) produced the
    identical generic removal message as no change at all — the comparator gives no real
    signal for this constraint type once the table exists, only a constant false positive.
    This is a known upstream gap (alembic issue #363, open since 2016).

    `reflected and compare_to is None` alone is *not* specific to type-bound constraints — a
    plain, hand-written `CheckConstraint` deleted from the model reflects the same way, and a
    blanket exclusion on that condition alone would silently swallow that real drift too.
    `_TYPE_BOUND_CHECK_NAMES` narrows the exclusion to exactly the names `enum_column()`
    produces, so a hand-written CHECK constraint added directly to a table stays fully subject
    to normal drift detection: its deletion or addition still surfaces. Verified on a scratch
    table carrying both kinds of CHECK (see the Task 4 fix report) — a plain constraint's SQL
    text changing under the same name is the one case that still doesn't surface, but that's a
    pre-existing Alembic limitation independent of this filter: its own comparator can't
    reliably diff reflected vs. model SQL text and logs "assuming equal and skipping" rather
    than reporting a change, for any named CHECK constraint, filtered or not.
    """
    if type_ == "check_constraint" and reflected and compare_to is None and name in _TYPE_BOUND_CHECK_NAMES:
        return False
    return True


# other values from the config, defined by the needs of env.py,
# can be acquired:
# my_important_option = config.get_main_option("my_important_option")
# ... etc.


def run_migrations_offline() -> None:
    """Run migrations in 'offline' mode.

    This configures the context with just a URL
    and not an Engine, though an Engine is acceptable
    here as well.  By skipping the Engine creation
    we don't even need a DBAPI to be available.

    Calls to context.execute() here emit the given string to the
    script output.

    """
    url = get_settings().database_url
    context.configure(
        url=url,
        target_metadata=target_metadata,
        literal_binds=True,
        dialect_opts={"paramstyle": "named"},
        include_object=_include_object,
    )

    with context.begin_transaction():
        context.run_migrations()


def do_run_migrations(connection: Connection) -> None:
    context.configure(connection=connection, target_metadata=target_metadata, include_object=_include_object)

    with context.begin_transaction():
        context.run_migrations()


async def run_async_migrations() -> None:
    """In this scenario we need to create an Engine
    and associate a connection with the context.

    """

    # Built directly from Settings rather than via async_engine_from_config(), which
    # round-trips the URL through ConfigParser (config.set_main_option / get_section) and
    # applies "%" interpolation on read — a URL containing a percent-encoded character
    # (e.g. a password with "%40" for "@") raises ValueError: invalid interpolation syntax.
    connectable = create_async_engine(get_settings().database_url, poolclass=pool.NullPool)

    async with connectable.connect() as connection:
        await connection.run_sync(do_run_migrations)

    await connectable.dispose()


def run_migrations_online() -> None:
    """Run migrations in 'online' mode."""

    asyncio.run(run_async_migrations())


if context.is_offline_mode():
    run_migrations_offline()
else:
    run_migrations_online()
