from collections.abc import AsyncGenerator

from sqlalchemy.ext.asyncio import AsyncEngine, AsyncSession, async_sessionmaker, create_async_engine
from sqlalchemy.orm import DeclarativeBase

from app.config import get_settings


class Base(DeclarativeBase):
    """Declarative base. Every model in every domain module inherits this."""


engine: AsyncEngine = create_async_engine(get_settings().database_url, echo=False)

# expire_on_commit=False: the default (True) expires ORM objects on commit, so a later
# attribute access triggers a lazy reload — which raises MissingGreenlet in async code
# instead of quietly re-querying. Trade-off: a route can return a post-commit object, but
# that object's attributes may be stale if the row changed elsewhere since the commit.
async_session_factory = async_sessionmaker(engine, class_=AsyncSession, expire_on_commit=False)


async def get_session() -> AsyncGenerator[AsyncSession, None]:
    async with async_session_factory() as session:
        yield session
