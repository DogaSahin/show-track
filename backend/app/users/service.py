from sqlalchemy.ext.asyncio import AsyncSession

from app.users.models import User


async def create_user(session: AsyncSession, *, username: str, email: str, hashed_password: str) -> User:
    """Insert a user and return it with its generated id populated.

    Takes an already-hashed password — Phase 2.1 owns hashing. Flushes rather than commits:
    the caller owns the transaction boundary, which is what lets a route compose several
    writes into one atomic unit.
    """
    user = User(username=username, email=email, hashed_password=hashed_password)
    session.add(user)
    await session.flush()
    return user
