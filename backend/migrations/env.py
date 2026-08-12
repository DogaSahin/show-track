import asyncio
from logging.config import fileConfig

from alembic import context
from sqlalchemy import pool
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


def _include_object(object: object, name: str, type_: str, reflected: bool, compare_to: object) -> bool:
    """Exclude reflected CHECK constraints that only exist because `enum_column()` put them there.

    `Enum(create_constraint=True)` ties its CHECK constraint to the column's type
    (SQLAlchemy's `SchemaType`, `_type_bound=True`) rather than adding it as a regular
    schema object. Alembic's own check-constraint comparator
    (`alembic/autogenerate/compare/check_constraints.py`) filters these out of the
    *model* side of every diff for that reason, but still picks them up on the
    *reflected* side once the table exists in the database — so every `enum_column()`
    constraint is reported as "removed" on every run, regardless of whether it actually
    changed. Confirmed by testing directly against this table: adding a new enum member
    (an actual constraint change) produced the identical generic removal message as no
    change at all — the comparator gives no real signal for this constraint type once
    the table exists, only a constant false positive. This is a known upstream gap
    (alembic issue #363, open since 2016). `reflected and compare_to is None` is exactly
    that no-model-side-match case; type_-checking `"check_constraint"` keeps this from
    touching PK/UQ/FK/index comparisons, which are unaffected.
    """
    if type_ == "check_constraint" and reflected and compare_to is None:
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
