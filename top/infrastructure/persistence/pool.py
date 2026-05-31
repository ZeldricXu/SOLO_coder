from __future__ import annotations

import asyncio
import time
from abc import ABC, abstractmethod
from contextlib import asynccontextmanager
from dataclasses import dataclass, field
from typing import Any, AsyncGenerator, Dict, Optional

from sqlalchemy.ext.asyncio import (
    AsyncEngine,
    AsyncSession,
    async_sessionmaker,
    create_async_engine,
)
from sqlalchemy.sql import text


@dataclass
class DatabasePoolMetrics:
    total_connections: int = 0
    used_connections: int = 0
    checkout_time_ms: float = 0.0
    query_count: int = 0
    slow_query_count: int = 0


@dataclass
class DatabasePoolConfig:
    database_url: str
    pool_size: int = 10
    max_overflow: int = 5
    pool_recycle: int = 3600
    pool_pre_ping: bool = True
    echo: bool = False
    query_timeout: int = 30


class DatabasePool(ABC):
    @property
    @abstractmethod
    def is_initialized(self) -> bool:
        pass

    @abstractmethod
    async def initialize(self) -> None:
        pass

    @abstractmethod
    async def close(self) -> None:
        pass

    @abstractmethod
    def get_session(self) -> AsyncGenerator[AsyncSession, None]:
        pass

    @abstractmethod
    def transaction(self) -> AsyncGenerator[AsyncSession, None]:
        pass

    @abstractmethod
    async def execute_query(
        self,
        query: str,
        params: Optional[Dict[str, Any]] = None,
        fetch_all: bool = True,
    ) -> Any:
        pass

    @abstractmethod
    async def check_health(self) -> bool:
        pass

    @abstractmethod
    def get_stats(self) -> Dict[str, Any]:
        pass

    @abstractmethod
    def get_metrics(self) -> DatabasePoolMetrics:
        pass


class SQLAlchemyDatabasePool(DatabasePool):
    def __init__(self, config: DatabasePoolConfig):
        self._config = config
        self._engine: Optional[AsyncEngine] = None
        self._session_factory: Optional[async_sessionmaker] = None
        self._metrics = DatabasePoolMetrics()
        self._initialized = False
        self._lock = asyncio.Lock()

    @property
    def is_initialized(self) -> bool:
        return self._initialized

    @property
    def engine(self) -> AsyncEngine:
        if self._engine is None:
            raise RuntimeError("Database pool not initialized")
        return self._engine

    async def initialize(self) -> None:
        if self._initialized:
            return

        async with self._lock:
            if self._initialized:
                return

            connect_args = self._get_connect_args()

            self._engine = create_async_engine(
                self._config.database_url,
                pool_size=self._config.pool_size,
                max_overflow=self._config.max_overflow,
                pool_recycle=self._config.pool_recycle,
                pool_pre_ping=self._config.pool_pre_ping,
                echo=self._config.echo,
                connect_args=connect_args,
            )

            self._session_factory = async_sessionmaker(
                self._engine,
                class_=AsyncSession,
                expire_on_commit=False,
            )

            self._metrics.total_connections = self._config.pool_size
            self._initialized = True

    def _get_connect_args(self) -> Dict[str, Any]:
        args: Dict[str, Any] = {}
        url = self._config.database_url
        if url.startswith(("postgresql", "postgresql+asyncpg")):
            args["server_settings"] = {"application_name": "task_orchestrator"}
            args["timeout"] = self._config.query_timeout
        return args

    async def close(self) -> None:
        if self._engine:
            await self._engine.dispose()
            self._engine = None
            self._session_factory = None
            self._initialized = False

    @asynccontextmanager
    async def get_session(self) -> AsyncGenerator[AsyncSession, None]:
        if self._session_factory is None:
            raise RuntimeError("Database pool not initialized")

        async with self._session_factory() as session:
            self._metrics.used_connections += 1
            try:
                yield session
            finally:
                self._metrics.used_connections -= 1

    @asynccontextmanager
    async def transaction(self) -> AsyncGenerator[AsyncSession, None]:
        async with self.get_session() as session:
            async with session.begin():
                yield session

    async def execute_query(
        self,
        query: str,
        params: Optional[Dict[str, Any]] = None,
        fetch_all: bool = True,
    ) -> Any:
        async with self.get_session() as session:
            self._metrics.query_count += 1

            start = time.time()
            try:
                result = await session.execute(text(query), params or {})
                if fetch_all:
                    return [dict(row._mapping) for row in result.all()]
                return result.rowcount
            finally:
                elapsed = (time.time() - start) * 1000
                if elapsed > 1000:
                    self._metrics.slow_query_count += 1
                self._metrics.checkout_time_ms = elapsed

    async def check_health(self) -> bool:
        try:
            async with self.get_session() as session:
                result = await session.execute(text("SELECT 1"))
                return result.scalar() == 1
        except Exception:
            return False

    def get_stats(self) -> Dict[str, Any]:
        return {
            "pool_size": self._config.pool_size,
            "max_overflow": self._config.max_overflow,
            "total_connections": self._metrics.total_connections,
            "used_connections": self._metrics.used_connections,
            "available_connections": self._metrics.total_connections - self._metrics.used_connections,
            "query_count": self._metrics.query_count,
            "slow_query_count": self._metrics.slow_query_count,
            "last_checkout_time_ms": self._metrics.checkout_time_ms,
            "initialized": self._initialized,
        }

    def get_metrics(self) -> DatabasePoolMetrics:
        return self._metrics


_pool_instance: Optional[DatabasePool] = None


def configure_pool(
    database_url: str,
    pool_size: int = 10,
    max_overflow: int = 5,
    **kwargs,
) -> DatabasePool:
    global _pool_instance
    config = DatabasePoolConfig(
        database_url=database_url,
        pool_size=pool_size,
        max_overflow=max_overflow,
        **kwargs,
    )
    _pool_instance = SQLAlchemyDatabasePool(config)
    return _pool_instance


def get_pool() -> DatabasePool:
    if _pool_instance is None:
        raise RuntimeError("Database pool not configured. Call configure_pool first.")
    return _pool_instance
