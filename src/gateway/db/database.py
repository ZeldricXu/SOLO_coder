from typing import AsyncGenerator, Optional
from sqlalchemy.ext.asyncio import AsyncSession, create_async_engine, async_sessionmaker
from sqlalchemy.orm import declarative_base
from sqlalchemy import text

from gateway.config import get_settings
from gateway.logger import get_logger

logger = get_logger("db")

Base = declarative_base()

_engine: Optional[create_async_engine] = None
_async_session: Optional[async_sessionmaker] = None


def get_engine():
    global _engine
    if _engine is None:
        settings = get_settings()
        _engine = create_async_engine(
            settings.db.dsn,
            pool_size=settings.db.pool_size,
            max_overflow=settings.db.max_overflow,
            pool_pre_ping=True,
            pool_recycle=300,
            echo=False,
        )
    return _engine


def get_session_factory() -> async_sessionmaker:
    global _async_session
    if _async_session is None:
        engine = get_engine()
        _async_session = async_sessionmaker(
            engine,
            class_=AsyncSession,
            expire_on_commit=False,
            autoflush=False,
        )
    return _async_session


async def get_db() -> AsyncGenerator[AsyncSession, None]:
    session_factory = get_session_factory()
    async with session_factory() as session:
        try:
            yield session
            await session.commit()
        except Exception:
            await session.rollback()
            raise
        finally:
            await session.close()


async def init_db() -> None:
    from gateway.db import models

    engine = get_engine()
    logger.info("Initializing database...")

    async with engine.begin() as conn:
        await conn.execute(text("CREATE EXTENSION IF NOT EXISTS \"uuid-ossp\""))
        await conn.run_sync(models.Base.metadata.create_all)

    logger.info("Database initialized successfully")
