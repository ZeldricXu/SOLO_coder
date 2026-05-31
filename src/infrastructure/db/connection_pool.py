import threading
import logging
from queue import Queue, Empty
from typing import Any, Optional, Dict
from contextlib import contextmanager

import sqlalchemy
from sqlalchemy import create_engine
from sqlalchemy.engine import Engine
from sqlalchemy.pool import QueuePool

from src.infrastructure.config.settings import DatabaseConfig, get_settings

logger = logging.getLogger(__name__)


class ConnectionPool:
    _pools: Dict[str, "ConnectionPool"] = {}
    _lock = threading.Lock()

    def __init__(self, config: DatabaseConfig):
        self._config = config
        self._engine: Optional[Engine] = None

    def _get_engine(self) -> Engine:
        if self._engine is None:
            self._engine = create_engine(
                self._config.dsn,
                pool_size=self._config.pool_size,
                max_overflow=self._config.max_overflow,
                pool_pre_ping=True,
                pool_recycle=3600,
                echo=False,
            )
        return self._engine

    @contextmanager
    def connection(self):
        engine = self._get_engine()
        conn = engine.connect()
        try:
            yield conn
            conn.commit()
        except Exception:
            conn.rollback()
            raise
        finally:
            conn.close()

    @contextmanager
    def session(self):
        from sqlalchemy.orm import sessionmaker
        engine = self._get_engine()
        Session = sessionmaker(bind=engine)
        session = Session()
        try:
            yield session
            session.commit()
        except Exception:
            session.rollback()
            raise
        finally:
            session.close()

    def execute(self, query: str, params: Optional[Dict[str, Any]] = None) -> Any:
        with self.connection() as conn:
            result = conn.execute(sqlalchemy.text(query), params or {})
            return result

    def close(self) -> None:
        if self._engine is not None:
            self._engine.dispose()
            self._engine = None

    @classmethod
    def get_pool(cls, name: str, config: Optional[DatabaseConfig] = None) -> "ConnectionPool":
        with cls._lock:
            if name not in cls._pools:
                if config is None:
                    settings = get_settings()
                    if name == "metastore":
                        config = settings.metastore
                    elif name == "timeseries":
                        config = settings.timeseries_db
                    else:
                        raise ValueError(f"Unknown pool name: {name}")
                cls._pools[name] = cls(config)
            return cls._pools[name]

    @classmethod
    def close_all(cls) -> None:
        with cls._lock:
            for pool in cls._pools.values():
                pool.close()
            cls._pools.clear()
