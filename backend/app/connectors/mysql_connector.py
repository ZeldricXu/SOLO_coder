from typing import Dict, Any, Optional
from datetime import datetime
import asyncio
import logging

import aiomysql
from aiomysql.cursors import DictCursor

from app.connectors.base import BaseConnector
from app.core.models import DataSourceConfig, DataSourceType

logger = logging.getLogger(__name__)


class MySQLConnector(BaseConnector):
    def __init__(self, config: DataSourceConfig):
        super().__init__(config)
        self.source_type = DataSourceType.MYSQL
        self._db_config = {
            'host': config.config.get('host', 'localhost'),
            'port': config.config.get('port', 3306),
            'user': config.config.get('user', 'root'),
            'password': config.config.get('password', ''),
            'db': config.config.get('database', ''),
            'charset': 'utf8mb4',
        }
        self._polling_tables = config.config.get('polling_tables', [])
        self._polling_interval = config.config.get('polling_interval', 5)
        self._last_id: Dict[str, int] = {}
        self._last_updated: Dict[str, datetime] = {}
        self._connection_pool: Optional[aiomysql.Pool] = None
        self._polling_task: Optional[asyncio.Task] = None

    async def connect(self) -> bool:
        try:
            self._connection_pool = await aiomysql.create_pool(
                **self._db_config,
                minsize=1,
                maxsize=5,
                cursorclass=DictCursor
            )
            self.is_connected = True
            self._reconnect_attempts = 0
            logger.info(f"Connected to MySQL: {self.source_id}")

            for table in self._polling_tables:
                await self._init_table_state(table)

            return True
        except Exception as e:
            logger.error(f"Failed to connect to MySQL {self.source_id}: {e}")
            self.is_connected = False
            return await self._reconnect()

    async def _init_table_state(self, table_name: str):
        try:
            async with self._connection_pool.acquire() as conn:
                async with conn.cursor() as cursor:
                    await cursor.execute(
                        f"SELECT MAX(id) as max_id FROM {table_name}"
                    )
                    result = await cursor.fetchone()
                    self._last_id[table_name] = result.get('max_id', 0) if result else 0

                    await cursor.execute(
                        f"SELECT MAX(updated_at) as max_updated FROM {table_name}"
                    )
                    result = await cursor.fetchone()
                    if result and result.get('max_updated'):
                        self._last_updated[table_name] = result['max_updated']
        except Exception as e:
            logger.warning(f"Could not initialize table state for {table_name}: {e}")
            self._last_id[table_name] = 0

    async def disconnect(self):
        await self.stop_listening()
        if self._connection_pool:
            self._connection_pool.close()
            await self._connection_pool.wait_closed()
            self._connection_pool = None
        self.is_connected = False
        logger.info(f"Disconnected from MySQL: {self.source_id}")

    async def start_listening(self):
        if not self.is_connected:
            await self.connect()

        self.is_running = True
        self._polling_task = asyncio.create_task(self._poll_loop())
        logger.info(f"Started MySQL listener for: {self.source_id}")

    async def stop_listening(self):
        self.is_running = False
        if self._polling_task and not self._polling_task.done():
            self._polling_task.cancel()
            try:
                await self._polling_task
            except asyncio.CancelledError:
                pass
        logger.info(f"Stopped MySQL listener for: {self.source_id}")

    async def _poll_loop(self):
        while self.is_running:
            try:
                for table in self._polling_tables:
                    await self._poll_table(table)
            except Exception as e:
                logger.error(f"Polling error for {self.source_id}: {e}")
                if not self.is_connected:
                    await self._reconnect()

            await asyncio.sleep(self._polling_interval)

    async def _poll_table(self, table_name: str):
        try:
            async with self._connection_pool.acquire() as conn:
                async with conn.cursor() as cursor:
                    last_id = self._last_id.get(table_name, 0)

                    await cursor.execute(
                        f"SELECT * FROM {table_name} WHERE id > %s ORDER BY id ASC",
                        (last_id,)
                    )
                    rows = await cursor.fetchall()

                    for row in rows:
                        row_dict = dict(row)
                        if 'id' in row_dict and row_dict['id'] > last_id:
                            last_id = row_dict['id']

                        self._emit_data(
                            data=row_dict,
                            event_type="insert"
                        )

                    self._last_id[table_name] = last_id
                    logger.debug(f"Polled {table_name}: {len(rows)} new rows")

        except Exception as e:
            logger.error(f"Error polling table {table_name}: {e}")

    async def execute_query(self, query: str, params: tuple = None) -> list:
        if not self._connection_pool:
            return []

        async with self._connection_pool.acquire() as conn:
            async with conn.cursor() as cursor:
                await cursor.execute(query, params or ())
                return await cursor.fetchall()
