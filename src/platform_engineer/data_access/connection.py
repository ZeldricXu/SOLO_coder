import asyncio
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone
from typing import Any, Callable, Dict, List, Optional

from ..core.exceptions import InternalError


@dataclass
class ConnectionConfig:
    host: str = "localhost"
    port: int = 5432
    database: str = "postgres"
    username: str = "postgres"
    password: str = ""
    connection_timeout: float = 10.0
    max_connections: int = 10
    min_connections: int = 1
    idle_timeout: float = 300.0
    connect_args: Dict[str, Any] = field(default_factory=dict)


@dataclass
class ConnectionPoolStats:
    total_connections: int
    idle_connections: int
    in_use_connections: int
    waiting_requests: int
    max_connections: int


class ConnectionPool:
    def __init__(self, config: ConnectionConfig, create_connection: Optional[Callable] = None, logger=None):
        self._config = config
        self._create_connection = create_connection
        self._logger = logger
        self._connections: List[Any] = []
        self._in_use: List[Any] = []
        self._semaphore = asyncio.Semaphore(config.max_connections)
        self._lock = asyncio.Lock()
        self._stats = {
            "acquisitions": 0,
            "releases": 0,
            "timeouts": 0,
            "errors": 0,
        }
        self._initialized = False

    async def initialize(self) -> None:
        if self._initialized:
            return
        async with self._lock:
            for _ in range(self._config.min_connections):
                conn = await self._create_new_connection()
                if conn:
                    self._connections.append(conn)
            self._initialized = True
            if self._logger:
                self._logger.info(f"Connection pool initialized with {len(self._connections)} connections")

    async def _create_new_connection(self) -> Optional[Any]:
        if self._create_connection:
            try:
                conn = self._create_connection(self._config)
                if asyncio.iscoroutine(conn):
                    conn = await asyncio.wait_for(conn, timeout=self._config.connection_timeout)
                return conn
            except asyncio.TimeoutError:
                if self._logger:
                    self._logger.error("Connection creation timeout")
                self._stats["timeouts"] += 1
            except Exception as e:
                if self._logger:
                    self._logger.error(f"Connection creation failed: {e}")
                self._stats["errors"] += 1
        return None

    async def acquire(self) -> Any:
        if not self._initialized:
            await self.initialize()
        try:
            await asyncio.wait_for(self._semaphore.acquire(), timeout=self._config.connection_timeout)
        except asyncio.TimeoutError:
            self._stats["timeouts"] += 1
            raise InternalError("Connection pool timeout")
        async with self._lock:
            if self._connections:
                conn = self._connections.pop()
                self._in_use.append(conn)
                self._stats["acquisitions"] += 1
                return conn
        conn = await self._create_new_connection()
        if conn is None:
            self._semaphore.release()
            raise InternalError("Failed to create connection")
        async with self._lock:
            self._in_use.append(conn)
            self._stats["acquisitions"] += 1
        return conn

    async def release(self, conn: Any) -> None:
        async with self._lock:
            if conn in self._in_use:
                self._in_use.remove(conn)
            if len(self._connections) < self._config.max_connections:
                self._connections.append(conn)
            self._semaphore.release()
            self._stats["releases"] += 1

    def get_stats(self) -> ConnectionPoolStats:
        return ConnectionPoolStats(
            total_connections=len(self._connections) + len(self._in_use),
            idle_connections=len(self._connections),
            in_use_connections=len(self._in_use),
            waiting_requests=self._config.max_connections - self._semaphore._value,
            max_connections=self._config.max_connections,
        )

    async def close(self) -> None:
        async with self._lock:
            for conn in self._connections + self._in_use:
                if hasattr(conn, "close"):
                    try:
                        close = conn.close()
                        if asyncio.iscoroutine(close):
                            await close
                    except Exception:
                        pass
            self._connections.clear()
            self._in_use.clear()
            self._initialized = False
            if self._logger:
                self._logger.info("Connection pool closed")


class ConnectionManager:
    def __init__(self, logger=None):
        self._pools: Dict[str, ConnectionPool] = {}
        self._logger = logger
        self._default_pool_name: Optional[str] = None

    def create_pool(
        self,
        name: str,
        config: ConnectionConfig,
        create_connection: Optional[Callable] = None,
        set_as_default: bool = False,
    ) -> ConnectionPool:
        pool = ConnectionPool(config, create_connection, self._logger)
        self._pools[name] = pool
        if set_as_default or not self._default_pool_name:
            self._default_pool_name = name
        return pool

    def get_pool(self, name: Optional[str] = None) -> Optional[ConnectionPool]:
        pool_name = name or self._default_pool_name
        return self._pools.get(pool_name) if pool_name else None

    async def get_connection(self, pool_name: Optional[str] = None) -> Any:
        pool = self.get_pool(pool_name)
        if not pool:
            raise InternalError(f"Connection pool not found: {pool_name}")
        return await pool.acquire()

    async def release_connection(self, conn: Any, pool_name: Optional[str] = None) -> None:
        pool = self.get_pool(pool_name)
        if pool:
            await pool.release(conn)

    async def initialize_all(self) -> None:
        for pool in self._pools.values():
            await pool.initialize()

    async def close_all(self) -> None:
        for pool in self._pools.values():
            await pool.close()

    def get_pool_stats(self, name: Optional[str] = None) -> Dict[str, ConnectionPoolStats]:
        if name:
            pool = self.get_pool(name)
            return {name: pool.get_stats()} if pool else {}
        return {n: p.get_stats() for n, p in self._pools.items()}


_global_connection_manager: Optional[ConnectionManager] = None


def get_connection_manager() -> ConnectionManager:
    global _global_connection_manager
    if _global_connection_manager is None:
        _global_connection_manager = ConnectionManager()
    return _global_connection_manager


def set_connection_manager(manager: ConnectionManager) -> None:
    global _global_connection_manager
    _global_connection_manager = manager
