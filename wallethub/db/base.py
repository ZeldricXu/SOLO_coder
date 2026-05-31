from contextlib import asynccontextmanager
from typing import AsyncGenerator, Optional
from sqlalchemy.ext.asyncio import (
    AsyncEngine,
    AsyncSession,
    async_sessionmaker,
    create_async_engine,
)
from sqlalchemy.orm import DeclarativeBase

from wallethub.config import get_settings


class Base(DeclarativeBase):
    pass


_engine: Optional[AsyncEngine] = None
_async_session: Optional[async_sessionmaker[AsyncSession]] = None


def init_db() -> None:
    global _engine, _async_session
    settings = get_settings()

    _engine = create_async_engine(
        settings.database.url,
        echo=settings.database.echo,
        pool_size=settings.database.pool_size,
        max_overflow=settings.database.max_overflow,
    )

    _async_session = async_sessionmaker(
        _engine,
        class_=AsyncSession,
        expire_on_commit=False,
        autoflush=False,
    )


async def create_tables() -> None:
    if _engine is None:
        init_db()
    async with _engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)


async def drop_tables() -> None:
    if _engine is None:
        init_db()
    async with _engine.begin() as conn:
        await conn.run_sync(Base.metadata.drop_all)


async def get_db_session() -> AsyncGenerator[AsyncSession, None]:
    if _async_session is None:
        init_db()
    async with _async_session() as session:
        try:
            yield session
            await session.commit()
        except Exception:
            await session.rollback()
            raise


@asynccontextmanager
async def db_session_context() -> AsyncGenerator[AsyncSession, None]:
    async for session in get_db_session():
        yield session
