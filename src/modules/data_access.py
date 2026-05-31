import asyncio
import uuid
import time
from abc import ABC, abstractmethod
from datetime import datetime
from typing import Any, Dict, List, Optional, Type, TypeVar, Generic, AsyncIterator, Callable, Tuple
from enum import Enum
from contextlib import asynccontextmanager
from collections import OrderedDict
from sqlalchemy import (
    Column, String, Integer, Boolean, DateTime, JSON, Text, ForeignKey, Index,
    MetaData, UniqueConstraint, text, select, func
)
from sqlalchemy.ext.asyncio import (
    AsyncSession, AsyncEngine, create_async_engine, async_sessionmaker
)
from sqlalchemy.orm import declarative_base, relationship, Session
from sqlalchemy.exc import SQLAlchemyError, IntegrityError
from sqlalchemy.ext.declarative import declared_attr
from pydantic import BaseModel, Field
from tenacity import retry, stop_after_attempt, wait_exponential, retry_if_exception_type

from .logging_module import get_logger
from .config_module import get_app_config

logger = get_logger(__name__)

metadata = MetaData()
Base = declarative_base(metadata=metadata)

ModelType = TypeVar('ModelType', bound=Base)


class CacheEntry:
    __slots__ = ('value', 'expires_at', 'hits')

    def __init__(self, value: Any, ttl: int):
        self.value = value
        self.expires_at = time.time() + ttl
        self.hits = 0

    def is_expired(self) -> bool:
        return time.time() > self.expires_at


class LRUCache:
    def __init__(self, max_size: int = 1000, default_ttl: int = 300):
        self._cache: OrderedDict[str, CacheEntry] = OrderedDict()
        self._max_size = max_size
        self._default_ttl = default_ttl
        self._lock = asyncio.Lock()
        self._hits = 0
        self._misses = 0

    async def get(self, key: str) -> Tuple[Optional[Any], bool]:
        async with self._lock:
            entry = self._cache.get(key)
            if entry:
                if entry.is_expired():
                    del self._cache[key]
                    self._misses += 1
                    return None, False
                entry.hits += 1
                self._hits += 1
                self._cache.move_to_end(key)
                return entry.value, True
            self._misses += 1
            return None, False

    async def set(self, key: str, value: Any, ttl: Optional[int] = None) -> None:
        async with self._lock:
            if key in self._cache:
                self._cache.move_to_end(key)
            elif len(self._cache) >= self._max_size:
                self._cache.popitem(last=False)
            self._cache[key] = CacheEntry(value, ttl or self._default_ttl)

    async def delete(self, key: str) -> bool:
        async with self._lock:
            if key in self._cache:
                del self._cache[key]
                return True
            return False

    async def clear(self) -> None:
        async with self._lock:
            self._cache.clear()

    async def delete_pattern(self, pattern: str) -> int:
        async with self._lock:
            keys_to_delete = [k for k in self._cache.keys() if pattern in k]
            for k in keys_to_delete:
                del self._cache[k]
            return len(keys_to_delete)

    def get_stats(self) -> Dict[str, Any]:
        total = self._hits + self._misses
        hit_rate = self._hits / total if total > 0 else 0
        return {
            "size": len(self._cache),
            "max_size": self._max_size,
            "hits": self._hits,
            "misses": self._misses,
            "hit_rate": hit_rate,
            "top_hits": sorted(
                [(k, v.hits) for k, v in self._cache.items()],
                key=lambda x: x[1],
                reverse=True
            )[:10],
        }


class CacheBackend(ABC):
    @abstractmethod
    async def get(self, key: str) -> Tuple[Optional[Any], bool]:
        pass

    @abstractmethod
    async def set(self, key: str, value: Any, ttl: Optional[int] = None) -> None:
        pass

    @abstractmethod
    async def delete(self, key: str) -> bool:
        pass

    @abstractmethod
    async def clear(self) -> None:
        pass

    @abstractmethod
    async def delete_pattern(self, pattern: str) -> int:
        pass


class RedisCacheBackend(CacheBackend):
    def __init__(self, redis_url: str, default_ttl: int = 300):
        try:
            import redis.asyncio as redis
            self._client = redis.from_url(redis_url)
            self._available = True
        except ImportError:
            logger.warning("Redis not available, falling back to memory cache")
            self._client = None
            self._available = False
        self._default_ttl = default_ttl

    @property
    def is_available(self) -> bool:
        return self._available and self._client is not None

    async def get(self, key: str) -> Tuple[Optional[Any], bool]:
        if not self.is_available:
            return None, False
        try:
            value = await self._client.get(key)
            if value:
                import pickle
                return pickle.loads(value), True
        except Exception as e:
            logger.warning("Redis get failed", error=str(e))
        return None, False

    async def set(self, key: str, value: Any, ttl: Optional[int] = None) -> None:
        if not self.is_available:
            return
        try:
            import pickle
            serialized = pickle.dumps(value)
            await self._client.setex(key, ttl or self._default_ttl, serialized)
        except Exception as e:
            logger.warning("Redis set failed", error=str(e))

    async def delete(self, key: str) -> bool:
        if not self.is_available:
            return False
        try:
            result = await self._client.delete(key)
            return result > 0
        except Exception as e:
            logger.warning("Redis delete failed", error=str(e))
            return False

    async def clear(self) -> None:
        if not self.is_available:
            return
        try:
            await self._client.flushdb()
        except Exception as e:
            logger.warning("Redis clear failed", error=str(e))

    async def delete_pattern(self, pattern: str) -> int:
        if not self.is_available:
            return 0
        try:
            keys = []
            async for key in self._client.scan_iter(match=pattern):
                keys.append(key)
            if keys:
                await self._client.delete(*keys)
            return len(keys)
        except Exception as e:
            logger.warning("Redis delete pattern failed", error=str(e))
            return 0


class MultiLevelCache:
    def __init__(
        self,
        l1_max_size: int = 1000,
        l1_ttl: int = 60,
        l2_backend: Optional[CacheBackend] = None,
        l2_ttl: int = 300,
    ):
        self._l1 = LRUCache(max_size=l1_max_size, default_ttl=l1_ttl)
        self._l2 = l2_backend
        self._l2_ttl = l2_ttl
        self._enabled = True

    @property
    def has_l2(self) -> bool:
        return self._l2 is not None

    def disable(self) -> None:
        self._enabled = False

    def enable(self) -> None:
        self._enabled = True

    async def get(self, key: str) -> Tuple[Optional[Any], bool]:
        if not self._enabled:
            return None, False

        value, found = await self._l1.get(key)
        if found:
            return value, True

        if self._l2:
            value, found = await self._l2.get(key)
            if found:
                await self._l1.set(key, value)
                return value, True

        return None, False

    async def set(self, key: str, value: Any, ttl: Optional[int] = None) -> None:
        if not self._enabled:
            return

        await self._l1.set(key, value, ttl)
        if self._l2:
            await self._l2.set(key, value, ttl or self._l2_ttl)

    async def delete(self, key: str) -> None:
        if not self._enabled:
            return

        await self._l1.delete(key)
        if self._l2:
            await self._l2.delete(key)

    async def invalidate(self, pattern: str) -> int:
        if not self._enabled:
            return 0

        l1_count = await self._l1.delete_pattern(pattern)
        l2_count = 0
        if self._l2:
            l2_count = await self._l2.delete_pattern(pattern)
        return l1_count + l2_count

    async def clear(self) -> None:
        await self._l1.clear()
        if self._l2:
            await self._l2.clear()

    def get_stats(self) -> Dict[str, Any]:
        stats = {
            "enabled": self._enabled,
            "l1": self._l1.get_stats(),
            "l2_available": self._l2 is not None,
        }
        if self._l2 and isinstance(self._l2, RedisCacheBackend):
            stats["l2"] = {"available": self._l2.is_available}
        return stats


class CacheStrategy(str, Enum):
    CACHE_ASIDE = "cache_aside"
    WRITE_THROUGH = "write_through"
    WRITE_BEHIND = "write_behind"


class CachedRepositoryMixin:
    def __init__(self, cache: Optional[MultiLevelCache] = None):
        self._cache = cache
        self._cache_strategy = CacheStrategy.CACHE_ASIDE
        self._cache_namespace = self.__class__.__name__.lower().replace("repository", "")
        self._warmup_complete = False

    @property
    def has_cache(self) -> bool:
        return self._cache is not None

    def _get_cache_key(self, *parts: str) -> str:
        return f"{self._cache_namespace}:{':'.join(str(p) for p in parts)}"

    async def _cache_get(self, key: str) -> Tuple[Optional[Any], bool]:
        if not self._cache:
            return None, False
        return await self._cache.get(key)

    async def _cache_set(self, key: str, value: Any, ttl: Optional[int] = None) -> None:
        if self._cache:
            await self._cache.set(key, value, ttl)

    async def _cache_delete(self, key: str) -> None:
        if self._cache:
            await self._cache.delete(key)

    async def _cache_invalidate(self, pattern: str) -> int:
        if self._cache:
            return await self._cache.invalidate(f"{self._cache_namespace}:{pattern}")
        return 0

    async def warmup(self, loader: Callable[[], AsyncIterator[Tuple[str, Any]]]) -> int:
        if not self._cache:
            return 0

        count = 0
        async for key, value in loader():
            await self._cache.set(self._get_cache_key(key), value)
            count += 1

        self._warmup_complete = True
        logger.info("Cache warmup complete", count=count, namespace=self._cache_namespace)
        return count

    def get_cache_stats(self) -> Dict[str, Any]:
        if not self._cache:
            return {"enabled": False}
        return {
            "strategy": self._cache_strategy.value,
            "namespace": self._cache_namespace,
            "warmup_complete": self._warmup_complete,
            **self._cache.get_stats(),
        }


class EntityStatus(str, Enum):
    PENDING = "pending"
    PROVISIONING = "provisioning"
    RUNNING = "running"
    COMPLETED = "completed"
    FAILED = "failed"
    CANCELLED = "cancelled"


class BaseModelMixin:
    @declared_attr
    def __tablename__(cls):
        return cls.__name__.lower() + "s"

    id = Column(String(36), primary_key=True, default=lambda: str(uuid.uuid4()))
    created_at = Column(DateTime, default=datetime.utcnow, nullable=False)
    updated_at = Column(DateTime, default=datetime.utcnow, onupdate=datetime.utcnow, nullable=False)
    is_deleted = Column(Boolean, default=False, nullable=False)


class Entity(Base, BaseModelMixin):
    __tablename__ = "entities"

    type = Column(String(64), nullable=False, index=True)
    status = Column(String(32), nullable=False, default=EntityStatus.PENDING, index=True)
    attributes = Column(JSON, default=dict, nullable=False)
    labels = Column(JSON, default=dict, nullable=False)

    runs = relationship("Run", back_populates="entity", cascade="all, delete-orphan")
    commands = relationship("Command", back_populates="entity", cascade="all, delete-orphan")

    __table_args__ = (
        Index('idx_entity_type_status', 'type', 'status'),
    )


class ConfigDefinition(Base, BaseModelMixin):
    __tablename__ = "config_definitions"

    config_id = Column(String(64), nullable=False, index=True)
    namespace = Column(String(128), nullable=False, index=True)
    version = Column(Integer, nullable=False, default=1)
    parameters = Column(JSON, default=dict, nullable=False)
    enabled = Column(Boolean, default=True, nullable=False)
    applied_at = Column(DateTime)
    description = Column(Text)

    __table_args__ = (
        UniqueConstraint('config_id', 'namespace', 'version', name='uix_config_namespace_version'),
    )


class Run(Base, BaseModelMixin):
    __tablename__ = "runs"

    run_id = Column(String(64), nullable=False, unique=True, index=True)
    entity_id = Column(String(36), ForeignKey("entities.id"), nullable=False, index=True)
    phase = Column(String(64), nullable=False, default="initializing")
    progress = Column(Integer, default=0)
    started_at = Column(DateTime, default=datetime.utcnow)
    completed_at = Column(DateTime)
    error_detail = Column(JSON)
    metrics = Column(JSON, default=dict)

    entity = relationship("Entity", back_populates="runs")

    __table_args__ = (
        Index('idx_run_entity_phase', 'entity_id', 'phase'),
    )


class Snapshot(Base, BaseModelMixin):
    __tablename__ = "snapshots"

    snapshot_id = Column(String(64), nullable=False, unique=True, index=True)
    timestamp = Column(DateTime, default=datetime.utcnow, nullable=False, index=True)
    metrics = Column(JSON, default=dict, nullable=False)
    dimensions = Column(JSON, default=dict, nullable=False)


class MigrationVersion(Base, BaseModelMixin):
    __tablename__ = "migration_versions"

    version = Column(String(64), nullable=False, unique=True, index=True)
    name = Column(String(255), nullable=False)
    applied_at = Column(DateTime, default=datetime.utcnow)
    checksum = Column(String(64))


class DatabaseManager:
    _instance: Optional['DatabaseManager'] = None
    _initialized: bool = False

    def __new__(cls, *args, **kwargs):
        if cls._instance is None:
            cls._instance = super().__new__(cls)
        return cls._instance

    def __init__(self, database_url: Optional[str] = None):
        if self._initialized:
            return

        config = get_app_config()
        self.database_url = database_url or config.database.url
        self.sync_database_url = config.database.sync_url
        self.pool_size = config.database.pool_size
        self.max_overflow = config.database.max_overflow
        self.pool_recycle = config.database.pool_recycle
        self.echo = config.database.echo

        self._engine: Optional[AsyncEngine] = None
        self._async_session_factory: Optional[async_sessionmaker] = None
        self._create_engine()
        self._initialized = True

    def _create_engine(self):
        engine_kwargs = {
            "pool_recycle": self.pool_recycle,
            "echo": self.echo,
            "future": True,
        }
        
        if "sqlite" not in self.database_url:
            engine_kwargs.update({
                "pool_size": self.pool_size,
                "max_overflow": self.max_overflow,
            })
        
        self._engine = create_async_engine(
            self.database_url,
            **engine_kwargs,
        )
        self._async_session_factory = async_sessionmaker(
            self._engine,
            class_=AsyncSession,
            expire_on_commit=False,
        )

    @property
    def engine(self) -> AsyncEngine:
        if self._engine is None:
            self._create_engine()
        return self._engine

    @asynccontextmanager
    async def get_session(self) -> AsyncIterator[AsyncSession]:
        async with self._async_session_factory() as session:
            try:
                yield session
                await session.commit()
            except Exception as e:
                await session.rollback()
                logger.error("Database session error", error=str(e))
                raise
            finally:
                await session.close()

    @retry(
        stop=stop_after_attempt(3),
        wait=wait_exponential(multiplier=1, min=1, max=10),
        retry=retry_if_exception_type(SQLAlchemyError),
    )
    async def execute_with_retry(self, session: AsyncSession, operation: callable):
        try:
            return await operation(session)
        except IntegrityError as e:
            logger.warning("Integrity error, retrying", error=str(e))
            raise
        except SQLAlchemyError as e:
            logger.error("SQLAlchemy error", error=str(e))
            raise

    async def create_tables(self):
        async with self.engine.begin() as conn:
            await conn.run_sync(metadata.create_all)
        logger.info("Database tables created successfully")

    async def drop_tables(self):
        async with self.engine.begin() as conn:
            await conn.run_sync(metadata.drop_all)
        logger.info("Database tables dropped successfully")

    async def close(self):
        if self._engine:
            await self._engine.dispose()
            self._engine = None
            logger.info("Database connection closed")


class BaseRepository(Generic[ModelType], CachedRepositoryMixin):
    def __init__(self, model: Type[ModelType], cache: Optional[MultiLevelCache] = None):
        self.model = model
        self.db = DatabaseManager()
        CachedRepositoryMixin.__init__(self, cache)

    async def create(self, session: AsyncSession, **kwargs) -> ModelType:
        instance = self.model(**kwargs)
        session.add(instance)
        await session.flush()

        if self.has_cache:
            cache_key = self._get_cache_key('by_id', instance.id)
            await self._cache_set(cache_key, instance)

        return instance

    async def get_by_id(self, session: AsyncSession, id: str) -> Optional[ModelType]:
        if self.has_cache:
            cache_key = self._get_cache_key('by_id', id)
            cached, found = await self._cache_get(cache_key)
            if found:
                return cached

        instance = await session.get(self.model, id)

        if instance and self.has_cache:
            cache_key = self._get_cache_key('by_id', id)
            await self._cache_set(cache_key, instance)

        return instance

    async def update(self, session: AsyncSession, id: str, **kwargs) -> Optional[ModelType]:
        instance = await self.get_by_id(session, id)
        if instance:
            for key, value in kwargs.items():
                setattr(instance, key, value)
            instance.updated_at = datetime.utcnow()
            await session.flush()

            if self.has_cache:
                cache_key = self._get_cache_key('by_id', id)
                await self._cache_set(cache_key, instance)
                await self._cache_invalidate(f"list:*")

        return instance

    async def delete(self, session: AsyncSession, id: str, soft_delete: bool = True) -> bool:
        instance = await self.get_by_id(session, id)
        if instance:
            if soft_delete and hasattr(instance, 'is_deleted'):
                instance.is_deleted = True
                instance.updated_at = datetime.utcnow()
            else:
                await session.delete(instance)

            if self.has_cache:
                cache_key = self._get_cache_key('by_id', id)
                await self._cache_delete(cache_key)
                await self._cache_invalidate(f"list:*")

            return True
        return False

    async def list(
        self,
        session: AsyncSession,
        skip: int = 0,
        limit: int = 100,
        filters: Optional[Dict[str, Any]] = None,
        order_by: Optional[str] = None,
        use_cache: bool = True,
    ) -> List[ModelType]:
        cache_key = None
        if self.has_cache and use_cache:
            filter_key = "_".join(f"{k}={v}" for k, v in (filters or {}).items())
            cache_key = self._get_cache_key('list', f"skip={skip}", f"limit={limit}", f"filters={filter_key}", f"order={order_by}")
            cached, found = await self._cache_get(cache_key)
            if found:
                return cached

        query = select(self.model).where(self.model.is_deleted == False)

        if filters:
            for key, value in filters.items():
                if hasattr(self.model, key):
                    query = query.where(getattr(self.model, key) == value)

        if order_by and hasattr(self.model, order_by):
            query = query.order_by(getattr(self.model, order_by))
        else:
            query = query.order_by(self.model.created_at.desc())

        result = await session.execute(query.offset(skip).limit(limit))
        instances = result.scalars().all()

        if cache_key and self.has_cache:
            await self._cache_set(cache_key, instances, ttl=60)

        return instances

    async def count(self, session: AsyncSession, filters: Optional[Dict[str, Any]] = None, use_cache: bool = True) -> int:
        cache_key = None
        if self.has_cache and use_cache:
            filter_key = "_".join(f"{k}={v}" for k, v in (filters or {}).items())
            cache_key = self._get_cache_key('count', f"filters={filter_key}")
            cached, found = await self._cache_get(cache_key)
            if found:
                return cached

        query = select(func.count()).select_from(self.model).where(self.model.is_deleted == False)
        if filters:
            for key, value in filters.items():
                if hasattr(self.model, key):
                    query = query.where(getattr(self.model, key) == value)
        result = await session.execute(query)
        count = result.scalar_one()

        if cache_key and self.has_cache:
            await self._cache_set(cache_key, count, ttl=30)

        return count

    async def bulk_create(self, session: AsyncSession, data_list: List[Dict[str, Any]]) -> List[ModelType]:
        instances = [self.model(**data) for data in data_list]
        session.add_all(instances)
        await session.flush()

        if self.has_cache:
            for instance in instances:
                cache_key = self._get_cache_key('by_id', instance.id)
                await self._cache_set(cache_key, instance)
            await self._cache_invalidate(f"list:*")
            await self._cache_invalidate(f"count:*")

        return instances

    async def bulk_update(self, session: AsyncSession, updates: List[Tuple[str, Dict[str, Any]]]) -> int:
        count = 0
        for id, kwargs in updates:
            instance = await self.get_by_id(session, id)
            if instance:
                for key, value in kwargs.items():
                    setattr(instance, key, value)
                instance.updated_at = datetime.utcnow()
                count += 1

                if self.has_cache:
                    cache_key = self._get_cache_key('by_id', id)
                    await self._cache_set(cache_key, instance)

        if count > 0 and self.has_cache:
            await self._cache_invalidate(f"list:*")
            await self._cache_invalidate(f"count:*")

        return count

    async def bulk_delete(self, session: AsyncSession, ids: List[str], soft_delete: bool = True) -> int:
        count = 0
        for id in ids:
            if await self.delete(session, id, soft_delete):
                count += 1
        return count


class EntityRepository(BaseRepository[Entity]):
    def __init__(self, cache: Optional[MultiLevelCache] = None):
        super().__init__(Entity, cache)

    async def get_by_type(self, session: AsyncSession, entity_type: str, skip: int = 0, limit: int = 100, use_cache: bool = True) -> List[Entity]:
        return await self.list(session, skip=skip, limit=limit, filters={"type": entity_type}, use_cache=use_cache)

    async def update_status(self, session: AsyncSession, entity_id: str, status: EntityStatus) -> Optional[Entity]:
        return await self.update(session, entity_id, status=status.value)


class ConfigRepository(BaseRepository[ConfigDefinition]):
    def __init__(self, cache: Optional[MultiLevelCache] = None):
        super().__init__(ConfigDefinition, cache)

    async def get_latest_version(
        self, session: AsyncSession, config_id: str, namespace: str, use_cache: bool = True
    ) -> Optional[ConfigDefinition]:
        cache_key = None
        if self.has_cache and use_cache:
            cache_key = self._get_cache_key('latest', config_id, namespace)
            cached, found = await self._cache_get(cache_key)
            if found:
                return cached

        query = (
            select(ConfigDefinition)
            .where(
                ConfigDefinition.config_id == config_id,
                ConfigDefinition.namespace == namespace,
                ConfigDefinition.is_deleted == False
            )
            .order_by(ConfigDefinition.version.desc())
        )
        result = await session.execute(query)
        config = result.scalars().first()

        if cache_key and config and self.has_cache:
            await self._cache_set(cache_key, config, ttl=300)

        return config

    async def create_new_version(
        self, session: AsyncSession, config_id: str, namespace: str, parameters: Dict[str, Any], **kwargs
    ) -> ConfigDefinition:
        latest = await self.get_latest_version(session, config_id, namespace, use_cache=False)
        new_version = (latest.version + 1) if latest else 1
        config = await self.create(
            session,
            config_id=config_id,
            namespace=namespace,
            version=new_version,
            parameters=parameters,
            applied_at=datetime.utcnow(),
            **kwargs,
        )

        if self.has_cache:
            cache_key = self._get_cache_key('latest', config_id, namespace)
            await self._cache_set(cache_key, config, ttl=300)

        return config


class RunRepository(BaseRepository[Run]):
    def __init__(self, cache: Optional[MultiLevelCache] = None):
        super().__init__(Run, cache)

    async def get_by_run_id(self, session: AsyncSession, run_id: str, use_cache: bool = True) -> Optional[Run]:
        cache_key = None
        if self.has_cache and use_cache:
            cache_key = self._get_cache_key('by_run_id', run_id)
            cached, found = await self._cache_get(cache_key)
            if found:
                return cached

        query = select(Run).where(
            Run.run_id == run_id,
            Run.is_deleted == False
        )
        result = await session.execute(query)
        run = result.scalars().first()

        if cache_key and run and self.has_cache:
            await self._cache_set(cache_key, run, ttl=60)

        return run

    async def get_active_runs(self, session: AsyncSession, entity_id: Optional[str] = None, use_cache: bool = True) -> List[Run]:
        cache_key = None
        if self.has_cache and use_cache:
            cache_key = self._get_cache_key('active_runs', entity_id or 'all')
            cached, found = await self._cache_get(cache_key)
            if found:
                return cached

        query = select(Run).where(
            Run.completed_at.is_(None),
            Run.is_deleted == False
        )
        if entity_id:
            query = query.where(Run.entity_id == entity_id)
        result = await session.execute(query)
        runs = result.scalars().all()

        if cache_key and self.has_cache:
            await self._cache_set(cache_key, runs, ttl=30)

        return runs


class SchemaMigration:
    def __init__(self, db_manager: DatabaseManager):
        self.db = db_manager
        self.migration_repo = BaseRepository(MigrationVersion)

    async def initialize(self):
        async with self.db.get_session() as session:
            await session.execute(text("""
                CREATE TABLE IF NOT EXISTS migration_versions (
                    id VARCHAR(36) PRIMARY KEY,
                    version VARCHAR(64) NOT NULL UNIQUE,
                    name VARCHAR(255) NOT NULL,
                    applied_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    checksum VARCHAR(64),
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    is_deleted BOOLEAN DEFAULT FALSE
                )
            """))
            await session.commit()
        logger.info("Migration table initialized")

    async def apply_migration(self, version: str, name: str, migration_sql: str, checksum: Optional[str] = None):
        async with self.db.get_session() as session:
            await session.execute(text(migration_sql))

            existing = await session.execute(
                text("SELECT * FROM migration_versions WHERE version = :version"),
                {"version": version}
            )
            if not existing.scalars().first():
                await session.execute(
                    text("""
                        INSERT INTO migration_versions (id, version, name, checksum)
                        VALUES (:id, :version, :name, :checksum)
                    """),
                    {
                        "id": str(uuid.uuid4()),
                        "version": version,
                        "name": name,
                        "checksum": checksum,
                    }
                )
            logger.info("Migration applied", version=version, name=name)

    async def get_applied_versions(self) -> List[str]:
        async with self.db.get_session() as session:
            result = await session.execute(
                text("SELECT version FROM migration_versions WHERE is_deleted = FALSE ORDER BY applied_at")
            )
            return [row[0] for row in result.fetchall()]

    async def rollback(self, to_version: str):
        applied = await self.get_applied_versions()
        if to_version not in applied:
            raise ValueError(f"Version {to_version} not found in applied migrations")

        to_rollback = applied[applied.index(to_version) + 1:]
        for version in reversed(to_rollback):
            logger.info("Rolling back migration", version=version)


def get_db_manager() -> DatabaseManager:
    return DatabaseManager()


_default_cache: Optional[MultiLevelCache] = None


def get_default_cache() -> MultiLevelCache:
    global _default_cache
    if _default_cache is None:
        config = get_app_config()
        l2_backend = None
        if config.cache.redis_url:
            l2_backend = RedisCacheBackend(
                redis_url=config.cache.redis_url,
                default_ttl=config.cache.l2_ttl,
            )
        _default_cache = MultiLevelCache(
            l1_max_size=config.cache.l1_max_size,
            l1_ttl=config.cache.l1_ttl,
            l2_backend=l2_backend,
            l2_ttl=config.cache.l2_ttl,
        )
    return _default_cache


def get_entity_repository(use_cache: bool = True) -> EntityRepository:
    cache = get_default_cache() if use_cache else None
    return EntityRepository(cache=cache)


def get_config_repository(use_cache: bool = True) -> ConfigRepository:
    cache = get_default_cache() if use_cache else None
    return ConfigRepository(cache=cache)


def get_run_repository(use_cache: bool = True) -> RunRepository:
    cache = get_default_cache() if use_cache else None
    return RunRepository(cache=cache)


def get_cache_stats() -> Dict[str, Any]:
    if _default_cache is None:
        return {"enabled": False}
    return _default_cache.get_stats()
