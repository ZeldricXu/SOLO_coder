from typing import AsyncGenerator

from sqlalchemy import create_engine
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine
from sqlalchemy.orm import DeclarativeBase, sessionmaker

from core.settings import get_settings

settings = get_settings()

DATABASE_URL = settings.database_url
SYNC_DATABASE_URL = settings.sync_database_url


class Base(DeclarativeBase):
    pass


def _get_engine_kwargs(url: str) -> dict:
    is_sqlite = "sqlite" in url
    if is_sqlite:
        return {
            "echo": settings.db_echo,
            "future": True,
        }
    return {
        "echo": settings.db_echo,
        "future": True,
        "pool_size": settings.db_pool_size,
        "max_overflow": settings.db_max_overflow,
        "pool_recycle": settings.db_pool_recycle,
    }


async_kwargs = _get_engine_kwargs(DATABASE_URL)
sync_kwargs = _get_engine_kwargs(SYNC_DATABASE_URL)

engine = create_async_engine(DATABASE_URL, **async_kwargs)
async_session = async_sessionmaker(engine, class_=AsyncSession, expire_on_commit=False)

sync_engine = create_engine(SYNC_DATABASE_URL, **sync_kwargs)
SessionLocal = sessionmaker(autocommit=False, autoflush=False, bind=sync_engine)


async def get_db() -> AsyncGenerator[AsyncSession, None]:
    async with async_session() as session:
        try:
            yield session
            await session.commit()
        except Exception:
            await session.rollback()
            raise
        finally:
            await session.close()
