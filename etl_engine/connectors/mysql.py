import asyncio
import logging
from contextlib import contextmanager

import pandas as pd
import pymysql
from dbutils.pooled_db import PooledDB

from .base import BaseSource, register_source

logger = logging.getLogger(__name__)


@register_source("mysql")
class MySQLSource(BaseSource):
    def __init__(self, config: dict) -> None:
        super().__init__(config)
        self._pool: PooledDB | None = None

    def _get_pool_params(self) -> dict:
        params = self.config.get("connection_params", {})
        return {
            "host": params.get("host", "localhost"),
            "port": params.get("port", 3306),
            "user": params.get("user", "root"),
            "password": params.get("password", ""),
            "database": params.get("database", ""),
            "pool_size": self.config.get("pool_size", 5),
        }

    @contextmanager
    def _get_connection(self):
        if self._pool is None:
            raise RuntimeError("Connection pool not initialized. Call connect() first.")
        conn = self._pool.connection()
        try:
            yield conn
        finally:
            conn.close()

    async def connect(self) -> None:
        params = self._get_pool_params()
        pool_size = params.pop("pool_size")
        last_error: Exception | None = None
        for attempt in range(3):
            try:
                self._pool = PooledDB(
                    creator=pymysql,
                    maxconnections=pool_size,
                    **params,
                )
                with self._get_connection() as conn:
                    with conn.cursor() as cursor:
                        cursor.execute("SELECT 1")
                self._connected = True
                logger.info("MySQL connection pool created successfully")
                return
            except Exception as e:
                last_error = e
                wait_time = 2 ** attempt
                logger.warning(
                    "MySQL connect attempt %d/3 failed: %s. Retrying in %ds...",
                    attempt + 1, e, wait_time,
                )
                await asyncio.sleep(wait_time)
        if last_error is not None:
            raise ConnectionError(
                f"Failed to connect to MySQL after 3 attempts. Last error: {last_error}"
            ) from last_error
        raise ConnectionError("Failed to connect to MySQL after 3 attempts")

    async def disconnect(self) -> None:
        if self._pool is not None:
            self._pool.close()
            self._pool = None
        self._connected = False
        logger.info("MySQL connection pool closed")

    async def read(self, query: str | None = None, **kwargs) -> pd.DataFrame:
        if not query:
            raise ValueError("SQL query is required for MySQLSource.read()")
        if not self.is_connected:
            await self._reconnect()
        try:
            with self._get_connection() as conn:
                df = pd.read_sql(query, conn)
            logger.info("MySQL query executed, returned %d rows", len(df))
            return df
        except pymysql.MySQLError as e:
            logger.error("MySQL query failed: %s", e)
            self._connected = False
            raise

    async def test_connection(self) -> bool:
        try:
            with self._get_connection() as conn:
                with conn.cursor() as cursor:
                    cursor.execute("SELECT 1")
            return True
        except Exception as e:
            logger.error("MySQL connection test failed: %s", e)
            return False

    async def _reconnect(self) -> None:
        logger.info("Attempting MySQL reconnection...")
        for attempt in range(3):
            try:
                await self.disconnect()
                await self.connect()
                return
            except Exception as e:
                wait_time = 2 ** attempt
                logger.warning(
                    "Reconnect attempt %d/3 failed: %s. Retrying in %ds...",
                    attempt + 1, e, wait_time,
                )
                await asyncio.sleep(wait_time)
        raise ConnectionError("Failed to reconnect to MySQL after 3 attempts")
