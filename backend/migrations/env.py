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
    )

    with context.begin_transaction():
        context.run_migrations()


def do_run_migrations(connection: Connection) -> None:
    context.configure(connection=connection, target_metadata=target_metadata)

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
