from typing import AsyncGenerator, Optional
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine
from sqlalchemy.orm import declarative_base

from ..config import get_settings
from ..utils import get_logger

logger = get_logger(__name__)

Base = declarative_base()

_engine: Optional[object] = None
_async_session: Optional[async_sessionmaker[AsyncSession]] = None


def get_engine():
    global _engine
    if _engine is None:
        settings = get_settings().db
        _engine = create_async_engine(
            settings.url,
            pool_size=settings.pool_size,
            max_overflow=settings.max_overflow,
            pool_recycle=settings.pool_recycle,
            echo=settings.echo,
        )
    return _engine


def get_async_session():
    global _async_session
    if _async_session is None:
        _async_session = async_sessionmaker(
            get_engine(),
            class_=AsyncSession,
            expire_on_commit=False,
            autoflush=False,
        )
    return _async_session


async def get_db() -> AsyncGenerator[AsyncSession, None]:
    session = get_async_session()
    async with session() as session_:
        try:
            yield session_
            await session_.commit()
        except Exception:
            await session_.rollback()
            raise
        finally:
            await session_.close()


def async_session():
    return get_async_session()()


async def init_db() -> None:
    engine = get_engine()
    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)
    logger.info("Database initialized")


async def close_db() -> None:
    global _engine, _async_session
    if _engine is not None:
        await _engine.dispose()
        _engine = None
    _async_session = None
    logger.info("Database connection closed")
