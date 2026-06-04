from typing import Optional, Any, List, Dict, Tuple
import asyncio
import json
from loguru import logger
import asyncpg

from config import settings


class PostgresClient:
    _instance: Optional["PostgresClient"] = None
    _pool: Optional[asyncpg.Pool] = None

    def __new__(cls) -> "PostgresClient":
        if cls._instance is None:
            cls._instance = super().__new__(cls)
        return cls._instance

    async def initialize(self) -> None:
        if self._pool is not None:
            return
        try:
            self._pool = await asyncpg.create_pool(
                host=settings.pg_host,
                port=settings.pg_port,
                user=settings.pg_user,
                password=settings.pg_password,
                database=settings.pg_database,
                min_size=settings.pg_pool_min_size,
                max_size=settings.pg_pool_max_size,
                command_timeout=30,
            )
            async with self._pool.acquire() as conn:
                await conn.fetchval("SELECT 1")
            logger.info(
                f"PostgreSQL connected successfully to {settings.pg_host}:{settings.pg_port}/{settings.pg_database}"
            )
        except Exception as e:
            logger.error(f"Failed to connect to PostgreSQL: {e}")
            raise

    async def close(self) -> None:
        if self._pool is not None:
            await self._pool.close()
            self._pool = None
        logger.info("PostgreSQL connection pool closed")

    def _get_pool(self) -> asyncpg.Pool:
        if self._pool is None:
            raise RuntimeError("PostgreSQL pool not initialized")
        return self._pool

    async def execute(self, query: str, *args: Any) -> str:
        pool = self._get_pool()
        async with pool.acquire() as conn:
            result = await conn.execute(query, *args)
            return result

    async def fetchrow(self, query: str, *args: Any) -> Optional[Dict[str, Any]]:
        pool = self._get_pool()
        async with pool.acquire() as conn:
            row = await conn.fetchrow(query, *args)
            return dict(row) if row else None

    async def fetch(self, query: str, *args: Any) -> List[Dict[str, Any]]:
        pool = self._get_pool()
        async with pool.acquire() as conn:
            rows = await conn.fetch(query, *args)
            return [dict(row) for row in rows]

    async def fetchval(self, query: str, *args: Any) -> Optional[Any]:
        pool = self._get_pool()
        async with pool.acquire() as conn:
            return await conn.fetchval(query, *args)

    async def executemany(self, query: str, args: List[Tuple[Any, ...]]) -> str:
        pool = self._get_pool()
        async with pool.acquire() as conn:
            return await conn.executemany(query, args)

    async def insert(
        self, table: str, data: Dict[str, Any], return_id: bool = False
    ) -> Optional[Any]:
        columns = ", ".join(data.keys())
        placeholders = ", ".join(f"${i+1}" for i in range(len(data)))
        values = list(data.values())
        query = f"INSERT INTO {table} ({columns}) VALUES ({placeholders})"
        if return_id:
            query += " RETURNING id"
            return await self.fetchval(query, *values)
        await self.execute(query, *values)
        return None

    async def upsert(
        self,
        table: str,
        data: Dict[str, Any],
        conflict_columns: List[str],
        update_columns: Optional[List[str]] = None,
    ) -> Optional[Any]:
        columns = ", ".join(data.keys())
        placeholders = ", ".join(f"${i+1}" for i in range(len(data)))
        conflict_cols = ", ".join(conflict_columns)
        values = list(data.values())

        if update_columns is None:
            update_columns = list(data.keys())

        update_clause = ", ".join(
            f"{col} = EXCLUDED.{col}" for col in update_columns
        )

        query = f"""
            INSERT INTO {table} ({columns})
            VALUES ({placeholders})
            ON CONFLICT ({conflict_cols})
            DO UPDATE SET {update_clause}
        """
        await self.execute(query, *values)

    async def transaction(self, queries: List[Tuple[str, List[Any]]]) -> None:
        pool = self._get_pool()
        async with pool.acquire() as conn:
            async with conn.transaction():
                for query, args in queries:
                    await conn.execute(query, *args)

    async def health_check(self) -> bool:
        try:
            result = await self.fetchval("SELECT 1")
            return result == 1
        except Exception:
            return False

    async def load_json(self, data: Any) -> str:
        return json.dumps(data, ensure_ascii=False)

    async def init_tables(self) -> None:
        schema_queries = [
            """
            CREATE TABLE IF NOT EXISTS abtest_experiments (
                experiment_id VARCHAR(64) PRIMARY KEY,
                name VARCHAR(128) NOT NULL,
                layer VARCHAR(64) NOT NULL,
                version VARCHAR(32) NOT NULL DEFAULT 'v1',
                status VARCHAR(16) NOT NULL,
                traffic_percentage INTEGER NOT NULL,
                control_group VARCHAR(64) NOT NULL,
                experiment_groups TEXT[] NOT NULL,
                config JSONB NOT NULL DEFAULT '{}'::jsonb,
                created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
            );
            CREATE INDEX IF NOT EXISTS idx_abtest_experiments_layer_status ON abtest_experiments(layer, status);
            """,
            """
            CREATE TABLE IF NOT EXISTS content_items (
                content_id VARCHAR(64) PRIMARY KEY,
                title TEXT,
                content_type VARCHAR(32) NOT NULL,
                categories TEXT[] NOT NULL DEFAULT '{}',
                tags TEXT[] NOT NULL DEFAULT '{}',
                author VARCHAR(128),
                publish_time TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                popularity_score FLOAT NOT NULL DEFAULT 0.0,
                metadata JSONB,
                embedding vector(768),
                created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
            );
            CREATE INDEX IF NOT EXISTS idx_content_items_type_time ON content_items(content_type, publish_time DESC);
            """,
            """
            CREATE TABLE IF NOT EXISTS user_offline_tags (
                user_id VARCHAR(64) NOT NULL,
                tag_id VARCHAR(64) NOT NULL,
                tag_name VARCHAR(128) NOT NULL,
                weight FLOAT NOT NULL,
                version VARCHAR(32) NOT NULL,
                updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                PRIMARY KEY (user_id, tag_id)
            );
            CREATE INDEX IF NOT EXISTS idx_user_offline_tags_user ON user_offline_tags(user_id);
            """,
            """
            CREATE TABLE IF NOT EXISTS user_profile_versions (
                user_id VARCHAR(64) NOT NULL,
                profile_version INTEGER NOT NULL,
                profile_data JSONB NOT NULL,
                created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                PRIMARY KEY (user_id, profile_version)
            );
            CREATE INDEX IF NOT EXISTS idx_user_profile_versions_user_time ON user_profile_versions(user_id, created_at DESC);
            """,
            """
            CREATE TABLE IF NOT EXISTS model_versions (
                model_name VARCHAR(128) NOT NULL,
                model_version VARCHAR(32) NOT NULL,
                backend VARCHAR(32) NOT NULL,
                model_path VARCHAR(256) NOT NULL,
                status VARCHAR(16) NOT NULL DEFAULT 'active',
                metadata JSONB,
                created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                PRIMARY KEY (model_name, model_version)
            );
            CREATE INDEX IF NOT EXISTS idx_model_versions_status ON model_versions(status);
            """,
        ]

        for query in schema_queries:
            try:
                await self.execute(query)
            except Exception as e:
                logger.warning(f"Schema initialization warning: {e}")

        logger.info("PostgreSQL tables initialized")


_postgres_client: Optional[PostgresClient] = None


async def get_postgres_client() -> PostgresClient:
    global _postgres_client
    if _postgres_client is None:
        _postgres_client = PostgresClient()
        await _postgres_client.initialize()
    return _postgres_client


async def close_postgres_client() -> None:
    global _postgres_client
    if _postgres_client is not None:
        await _postgres_client.close()
        _postgres_client = None
