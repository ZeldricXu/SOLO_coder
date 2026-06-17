from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine

from etl_engine.config import settings
from etl_engine.models.base import Base

engine = create_async_engine(settings.DATABASE_URL, pool_size=20, max_overflow=10)

async_session_factory = async_sessionmaker(engine, class_=AsyncSession, expire_on_commit=False)


async def get_session():
    async with async_session_factory() as session:
        yield session


async def init_db():
    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)
