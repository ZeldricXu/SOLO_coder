import asyncio
import re
import threading
import time
from contextlib import asynccontextmanager, contextmanager
from dataclasses import dataclass, field
from typing import Any, AsyncGenerator, Callable, Dict, Generator, List, Optional, Tuple, Union

from sqlalchemy import create_engine, event, text
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine
from sqlalchemy.orm import Session, sessionmaker

from src.config import get_settings
from src.logging_ import get_logger
from src.utils.errors import DatabaseError

logger = get_logger(__name__)


@dataclass
class QueryMetrics:
    query: str
    execution_time: float
    rows_affected: int = 0
    timestamp: float = field(default_factory=time.time)
    has_index: bool = True
    full_table_scan: bool = False


class QueryOptimizer:
    def __init__(self, enable_query_analysis: bool = True):
        self.enable_query_analysis = enable_query_analysis
        self.query_cache: Dict[str, str] = {}
        self.query_history: List[QueryMetrics] = []
        self._index_hints = {
            "SELECT": "/*+ INDEX_SCAN */",
            "INSERT": "/*+ APPEND */",
            "UPDATE": "/*+ BATCH_UPDATE */",
        }
        self._slow_query_threshold = 1.0

    def analyze_query(self, query: str) -> Dict[str, Any]:
        analysis = {
            "has_where": "WHERE" in query.upper(),
            "has_limit": "LIMIT" in query.upper(),
            "has_order_by": "ORDER BY" in query.upper(),
            "has_group_by": "GROUP BY" in query.upper(),
            "has_join": re.search(r"\bJOIN\b", query, re.IGNORECASE) is not None,
            "uses_select_all": re.search(r"SELECT\s+\*", query, re.IGNORECASE) is not None,
            "estimated_complexity": self._estimate_complexity(query),
        }
        return analysis

    def _estimate_complexity(self, query: str) -> str:
        complexity = "simple"
        query_upper = query.upper()

        if "JOIN" in query_upper and ("GROUP BY" in query_upper or "ORDER BY" in query_upper):
            complexity = "complex"
        elif "JOIN" in query_upper or "SUBQUERY" in query_upper:
            complexity = "moderate"

        subquery_count = query_upper.count("(SELECT")
        if subquery_count > 2:
            complexity = "complex"

        return complexity

    def optimize_query(self, query: str, params: Optional[Dict[str, Any]] = None) -> str:
        if query in self.query_cache:
            return self.query_cache[query]

        optimized = query

        if not re.search(r"LIMIT\s+\d+", optimized, re.IGNORECASE):
            if "SELECT" in optimized.upper() and "INSERT" not in optimized.upper():
                if "LIMIT" not in optimized.upper():
                    pass

        if params and self.enable_query_analysis:
            analysis = self.analyze_query(query)
            if analysis["uses_select_all"]:
                logger.warning("Query uses SELECT *, consider specifying columns: %s", query)
            if not analysis["has_where"] and "SELECT" in query.upper():
                logger.warning("Query missing WHERE clause, may return large result set: %s", query)

        self.query_cache[query] = optimized
        return optimized

    def add_query_hint(self, query: str, operation: str) -> str:
        hint = self._index_hints.get(operation.upper(), "")
        if hint:
            query = query.replace("SELECT", f"SELECT {hint}", 1) if operation.upper() == "SELECT" else query
        return query

    def record_query_metrics(self, metrics: QueryMetrics) -> None:
        self.query_history.append(metrics)

        if metrics.execution_time > self._slow_query_threshold:
            logger.warning(
                "Slow query detected (%.2fs): %s",
                metrics.execution_time,
                metrics.query[:200],
            )

        if len(self.query_history) > 10000:
            self.query_history = self.query_history[-5000:]

    def get_slow_queries(self, threshold: Optional[float] = None) -> List[QueryMetrics]:
        threshold = threshold or self._slow_query_threshold
        return [m for m in self.query_history if m.execution_time > threshold]

    def get_query_statistics(self) -> Dict[str, Any]:
        if not self.query_history:
            return {"total_queries": 0}

        avg_time = sum(m.execution_time for m in self.query_history) / len(self.query_history)
        slow_count = sum(
            1 for m in self.query_history if m.execution_time > self._slow_query_threshold
        )

        return {
            "total_queries": len(self.query_history),
            "average_execution_time": avg_time,
            "max_execution_time": max(m.execution_time for m in self.query_history),
            "min_execution_time": min(m.execution_time for m in self.query_history),
            "slow_query_count": slow_count,
            "slow_query_percentage": (slow_count / len(self.query_history)) * 100,
            "cached_queries": len(self.query_cache),
        }

    def clear_cache(self) -> None:
        self.query_cache.clear()
        logger.info("Query cache cleared")


class ConnectionPool:
    def __init__(
        self,
        database_url: str,
        pool_size: int = 20,
        max_overflow: int = 10,
        pool_recycle: int = 3600,
        pool_pre_ping: bool = True,
        is_async: bool = True,
    ):
        self.database_url = database_url
        self.pool_size = pool_size
        self.max_overflow = max_overflow
        self.pool_recycle = pool_recycle
        self.pool_pre_ping = pool_pre_ping
        self.is_async = is_async
        self._pool: Optional[Any] = None
        self._sync_pool: Optional[Any] = None
        self._initialize_pool()

    def _initialize_pool(self) -> None:
        try:
            if self.is_async:
                self._pool = create_async_engine(
                    self.database_url,
                    pool_size=self.pool_size,
                    max_overflow=self.max_overflow,
                    pool_recycle=self.pool_recycle,
                    pool_pre_ping=self.pool_pre_ping,
                    echo=False,
                )

                @event.listens_for(self._pool.sync_engine, "connect")
                def _on_connect(dbapi_connection, connection_record):
                    logger.debug("New database connection established")

                @event.listens_for(self._pool.sync_engine, "checkout")
                def _on_checkout(dbapi_connection, connection_record, connection_proxy):
                    logger.debug("Database connection checked out from pool")

            else:
                self._sync_pool = create_engine(
                    self.database_url,
                    pool_size=self.pool_size,
                    max_overflow=self.max_overflow,
                    pool_recycle=self.pool_recycle,
                    pool_pre_ping=self.pool_pre_ping,
                    echo=False,
                )

            logger.info(
                "Database connection pool initialized: size=%d, max_overflow=%d",
                self.pool_size,
                self.max_overflow,
            )

        except Exception as e:
            raise DatabaseError(f"Failed to initialize connection pool: {e}") from e

    @property
    def engine(self):
        if self.is_async:
            return self._pool
        return self._sync_pool

    def get_pool_status(self) -> Dict[str, Any]:
        if self.is_async and self._pool:
            pool = self._pool.pool
            return {
                "pool_size": pool.size(),
                "checked_in": pool.checkedin(),
                "checked_out": pool.checkedout(),
                "overflow": pool.overflow(),
            }
        elif self._sync_pool:
            pool = self._sync_pool.pool
            return {
                "pool_size": pool.size(),
                "checked_in": pool.checkedin(),
                "checked_out": pool.checkedout(),
                "overflow": pool.overflow(),
            }
        return {}

    async def close_async(self) -> None:
        if self._pool:
            await self._pool.dispose()
            logger.info("Async connection pool closed")

    def close_sync(self) -> None:
        if self._sync_pool:
            self._sync_pool.dispose()
            logger.info("Sync connection pool closed")


class TransactionManager:
    def __init__(self, is_async: bool = True):
        self.is_async = is_async
        self._savepoints: List[str] = []
        self._transaction_depth = 0

    @asynccontextmanager
    async def transaction(self, session: AsyncSession) -> AsyncGenerator[None, None]:
        self._transaction_depth += 1
        try:
            async with session.begin():
                yield
            if self._transaction_depth == 1:
                await session.commit()
        except Exception as e:
            if self._transaction_depth == 1:
                await session.rollback()
                logger.error("Transaction rolled back due to error: %s", e)
            raise
        finally:
            self._transaction_depth -= 1

    @asynccontextmanager
    async def savepoint(self, session: AsyncSession, name: str) -> AsyncGenerator[None, None]:
        self._savepoints.append(name)
        try:
            await session.execute(text(f"SAVEPOINT {name}"))
            yield
            await session.execute(text(f"RELEASE SAVEPOINT {name}"))
        except Exception as e:
            await session.execute(text(f"ROLLBACK TO SAVEPOINT {name}"))
            logger.error("Savepoint %s rolled back: %s", name, e)
            raise
        finally:
            self._savepoints.pop()

    @contextmanager
    def sync_transaction(self, session: Session) -> Generator[None, None, None]:
        try:
            with session.begin():
                yield
            session.commit()
        except Exception as e:
            session.rollback()
            logger.error("Sync transaction rolled back: %s", e)
            raise


class DatabaseSession:
    def __init__(self, connection_pool: ConnectionPool):
        self.connection_pool = connection_pool
        self.is_async = connection_pool.is_async

        if self.is_async:
            self._async_session_factory = async_sessionmaker(
                self.connection_pool.engine,
                class_=AsyncSession,
                expire_on_commit=False,
            )
        else:
            self._sync_session_factory = sessionmaker(
                self.connection_pool.engine,
                class_=Session,
                expire_on_commit=False,
            )

    @asynccontextmanager
    async def get_async_session(self) -> AsyncGenerator[AsyncSession, None]:
        session = self._async_session_factory()
        try:
            yield session
        finally:
            await session.close()

    @contextmanager
    def get_sync_session(self) -> Generator[Session, None, None]:
        session = self._sync_session_factory()
        try:
            yield session
        finally:
            session.close()


class DatabaseManager:
    _instance: Optional["DatabaseManager"] = None
    _lock = threading.Lock()

    def __new__(cls) -> "DatabaseManager":
        if cls._instance is None:
            with cls._lock:
                if cls._instance is None:
                    cls._instance = super().__new__(cls)
        return cls._instance

    def __init__(self):
        if not hasattr(self, "initialized"):
            self.settings = get_settings()
            self._async_pool: Optional[ConnectionPool] = None
            self._sync_pool: Optional[ConnectionPool] = None
            self._async_session: Optional[DatabaseSession] = None
            self._sync_session: Optional[DatabaseSession] = None
            self.query_optimizer = QueryOptimizer()
            self.transaction_manager = TransactionManager()
            self._initialize_pools()
            self.initialized = True

    def _initialize_pools(self) -> None:
        try:
            self._async_pool = ConnectionPool(
                database_url=self.settings.DATABASE_URL,
                pool_size=self.settings.DATABASE_POOL_SIZE,
                max_overflow=self.settings.DATABASE_MAX_OVERFLOW,
                pool_recycle=self.settings.DATABASE_POOL_RECYCLE,
                is_async=True,
            )
            self._async_session = DatabaseSession(self._async_pool)

            sync_url = self.settings.DATABASE_URL.replace("+asyncpg", "")
            self._sync_pool = ConnectionPool(
                database_url=sync_url,
                pool_size=self.settings.DATABASE_POOL_SIZE,
                max_overflow=self.settings.DATABASE_MAX_OVERFLOW,
                pool_recycle=self.settings.DATABASE_POOL_RECYCLE,
                is_async=False,
            )
            self._sync_session = DatabaseSession(self._sync_pool)

            logger.info("Database manager initialized successfully")

        except Exception as e:
            logger.error("Failed to initialize database manager: %s", e)
            raise DatabaseError(f"Database initialization failed: {e}") from e

    @property
    def async_session(self) -> DatabaseSession:
        if self._async_session is None:
            raise DatabaseError("Async session not initialized")
        return self._async_session

    @property
    def sync_session(self) -> DatabaseSession:
        if self._sync_session is None:
            raise DatabaseError("Sync session not initialized")
        return self._sync_session

    async def execute_query_async(
        self,
        query: str,
        params: Optional[Dict[str, Any]] = None,
        optimize: bool = True,
    ) -> List[Dict[str, Any]]:
        if optimize:
            query = self.query_optimizer.optimize_query(query, params)

        start_time = time.time()
        try:
            async with self.async_session.get_async_session() as session:
                result = await session.execute(text(query), params or {})
                rows = [dict(row._mapping) for row in result.fetchall()]

                execution_time = time.time() - start_time
                self.query_optimizer.record_query_metrics(
                    QueryMetrics(
                        query=query,
                        execution_time=execution_time,
                        rows_affected=len(rows),
                    )
                )

                return rows

        except Exception as e:
            execution_time = time.time() - start_time
            self.query_optimizer.record_query_metrics(
                QueryMetrics(
                    query=query,
                    execution_time=execution_time,
                    rows_affected=0,
                )
            )
            raise DatabaseError(f"Query execution failed: {e}", {"query": query}) from e

    def execute_query_sync(
        self,
        query: str,
        params: Optional[Dict[str, Any]] = None,
        optimize: bool = True,
    ) -> List[Dict[str, Any]]:
        if optimize:
            query = self.query_optimizer.optimize_query(query, params)

        start_time = time.time()
        try:
            with self.sync_session.get_sync_session() as session:
                result = session.execute(text(query), params or {})
                rows = [dict(row._mapping) for row in result.fetchall()]

                execution_time = time.time() - start_time
                self.query_optimizer.record_query_metrics(
                    QueryMetrics(
                        query=query,
                        execution_time=execution_time,
                        rows_affected=len(rows),
                    )
                )

                return rows

        except Exception as e:
            execution_time = time.time() - start_time
            self.query_optimizer.record_query_metrics(
                QueryMetrics(
                    query=query,
                    execution_time=execution_time,
                    rows_affected=0,
                )
            )
            raise DatabaseError(f"Query execution failed: {e}", {"query": query}) from e

    async def execute_update_async(
        self,
        query: str,
        params: Optional[Union[Dict[str, Any], List[Dict[str, Any]]]] = None,
    ) -> int:
        start_time = time.time()
        async with self.async_session.get_async_session() as session:
            try:
                if isinstance(params, list):
                    result = await session.execute(text(query), params)
                else:
                    result = await session.execute(text(query), params or {})
                await session.commit()

                rows_affected = result.rowcount or 0
                execution_time = time.time() - start_time
                self.query_optimizer.record_query_metrics(
                    QueryMetrics(
                        query=query,
                        execution_time=execution_time,
                        rows_affected=rows_affected,
                    )
                )

                return rows_affected

            except Exception as e:
                await session.rollback()
                raise DatabaseError(f"Update failed: {e}") from e

    async def execute_batch_async(
        self,
        operations: List[Tuple[str, Dict[str, Any]]],
        transaction: bool = True,
    ) -> List[int]:
        results: List[int] = []
        async with self.async_session.get_async_session() as session:
            try:
                if transaction:
                    async with self.transaction_manager.transaction(session):
                        for query, params in operations:
                            result = await session.execute(text(query), params)
                            results.append(result.rowcount or 0)
                else:
                    for query, params in operations:
                        result = await session.execute(text(query), params)
                        results.append(result.rowcount or 0)
                    await session.commit()

                return results

            except Exception as e:
                if transaction:
                    await session.rollback()
                raise DatabaseError(f"Batch execution failed: {e}") from e

    async def health_check_async(self) -> bool:
        try:
            result = await self.execute_query_async("SELECT 1 as health")
            return len(result) > 0 and result[0].get("health") == 1
        except Exception as e:
            logger.error("Database health check failed: %s", e)
            return False

    def health_check_sync(self) -> bool:
        try:
            result = self.execute_query_sync("SELECT 1 as health")
            return len(result) > 0 and result[0].get("health") == 1
        except Exception as e:
            logger.error("Database health check failed: %s", e)
            return False

    def get_pool_status(self) -> Dict[str, Any]:
        return {
            "async_pool": self._async_pool.get_pool_status() if self._async_pool else {},
            "sync_pool": self._sync_pool.get_pool_status() if self._sync_pool else {},
        }

    def get_statistics(self) -> Dict[str, Any]:
        return {
            "pool_status": self.get_pool_status(),
            "query_statistics": self.query_optimizer.get_query_statistics(),
        }

    async def close_async(self) -> None:
        if self._async_pool:
            await self._async_pool.close_async()
        logger.info("Database manager async resources closed")

    def close_sync(self) -> None:
        if self._sync_pool:
            self._sync_pool.close_sync()
        logger.info("Database manager sync resources closed")
