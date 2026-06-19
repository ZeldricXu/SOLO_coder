import asyncio
import logging

import pandas as pd
import psycopg2
from psycopg2 import pool

from .base import BaseSource, register_source

logger = logging.getLogger(__name__)


@register_source("postgresql")
class PostgreSQLSource(BaseSource):
    def __init__(self, config: dict) -> None:
        super().__init__(config)
        self._pool: pool.SimpleConnectionPool | None = None

    def _get_pool_params(self) -> dict:
        params = self.config.get("connection_params", {})
        return {
            "host": params.get("host", "localhost"),
            "port": params.get("port", 5432),
            "user": params.get("user", "postgres"),
            "password": params.get("password", ""),
            "database": params.get("database", "postgres"),
            "pool_size": self.config.get("pool_size", 5),
        }

    async def connect(self) -> None:
        params = self._get_pool_params()
        pool_size = params.pop("pool_size")
        last_error: Exception | None = None
        for attempt in range(3):
            try:
                self._pool = pool.SimpleConnectionPool(
                    minconn=1,
                    maxconn=pool_size,
                    **params,
                )
                conn = self._pool.getconn()
                try:
                    with conn.cursor() as cursor:
                        cursor.execute("SELECT 1")
                finally:
                    self._pool.putconn(conn)
                self._connected = True
                logger.info("PostgreSQL connection pool created successfully")
                return
            except Exception as e:
                last_error = e
                wait_time = 2 ** attempt
                logger.warning(
                    "PostgreSQL connect attempt %d/3 failed: %s. Retrying in %ds...",
                    attempt + 1, e, wait_time,
                )
                await asyncio.sleep(wait_time)
        if last_error is not None:
            raise ConnectionError(
                f"Failed to connect to PostgreSQL after 3 attempts. Last error: {last_error}"
            ) from last_error
        raise ConnectionError("Failed to connect to PostgreSQL after 3 attempts")

    async def disconnect(self) -> None:
        if self._pool is not None:
            self._pool.closeall()
            self._pool = None
        self._connected = False
        logger.info("PostgreSQL connection pool closed")

    async def read(self, query: str | None = None, **kwargs) -> pd.DataFrame:
        if not query:
            raise ValueError("SQL query is required for PostgreSQLSource.read()")
        if not self.is_connected:
            await self._reconnect()
        schema = kwargs.get("schema")
        try:
            conn = self._pool.getconn()
            try:
                if schema:
                    with conn.cursor() as cursor:
                        cursor.execute(f"SET search_path TO {schema}")
                df = pd.read_sql(query, conn)
            finally:
                self._pool.putconn(conn)
            logger.info("PostgreSQL query executed, returned %d rows", len(df))
            return df
        except psycopg2.Error as e:
            logger.error("PostgreSQL query failed: %s", e)
            self._connected = False
            raise

    async def test_connection(self) -> bool:
        try:
            conn = self._pool.getconn()
            try:
                with conn.cursor() as cursor:
                    cursor.execute("SELECT 1")
            finally:
                self._pool.putconn(conn)
            return True
        except Exception as e:
            logger.error("PostgreSQL connection test failed: %s", e)
            return False

    async def _reconnect(self) -> None:
        logger.info("Attempting PostgreSQL reconnection...")
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
        raise ConnectionError("Failed to reconnect to PostgreSQL after 3 attempts")
