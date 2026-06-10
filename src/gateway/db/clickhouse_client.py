from typing import Any, Dict, List, Optional
import clickhouse_connect
from clickhouse_connect.driver.asyncclient import AsyncClient

from gateway.config import get_settings
from gateway.logger import get_logger

logger = get_logger("clickhouse")

_client: Optional[AsyncClient] = None


def get_clickhouse() -> AsyncClient:
    global _client
    if _client is None:
        settings = get_settings()
        ch_settings = settings.clickhouse

        _client = clickhouse_connect.get_async_client(
            host=ch_settings.host,
            port=ch_settings.port,
            username=ch_settings.user,
            password=ch_settings.password,
            database=ch_settings.database,
            secure=ch_settings.secure,
            connect_timeout=ch_settings.connect_timeout,
            send_receive_timeout=ch_settings.send_receive_timeout,
        )
    return _client


async def init_clickhouse() -> None:
    logger.info("Initializing ClickHouse connection...")
    client = get_clickhouse()
    try:
        result = await client.query("SELECT 1")
        if result.result_rows:
            await _create_tables()
            logger.info("ClickHouse connection initialized successfully")
    except Exception as e:
        logger.error("Failed to connect to ClickHouse", error=str(e))
        raise


async def _create_tables() -> None:
    client = get_clickhouse()

    await client.command("""
        CREATE TABLE IF NOT EXISTS api_requests (
            timestamp DateTime64(3) DEFAULT now64(3),
            request_id String,
            user_id String,
            tenant_id String,
            api_key String,
            api_path String,
            api_method String,
            route_name String,
            status_code Int32,
            latency_ms Int64,
            upstream_latency_ms Int64,
            client_ip String,
            user_agent String,
            error_type String,
            rate_limited Bool DEFAULT false,
            circuit_broken Bool DEFAULT false,
            tags Map(String, String)
        ) ENGINE = MergeTree()
        PARTITION BY toYYYYMM(timestamp)
        ORDER BY (timestamp, api_path, user_id)
        TTL timestamp + INTERVAL 90 DAY
        SETTINGS index_granularity = 8192
    """)

    await client.command("""
        CREATE MATERIALIZED VIEW IF NOT EXISTS api_requests_hourly_mv
        ENGINE = SummingMergeTree()
        PARTITION BY toYYYYMM(hour)
        ORDER BY (hour, api_path, user_id, status_code)
        AS SELECT
            toStartOfHour(timestamp) AS hour,
            api_path,
            user_id,
            status_code,
            count() AS request_count,
            sum(latency_ms) AS total_latency_ms,
            quantiles(0.5, 0.9, 0.95, 0.99)(latency_ms) AS latency_quantiles
        FROM api_requests
        GROUP BY hour, api_path, user_id, status_code
    """)

    logger.info("ClickHouse tables created/verified")


async def close_clickhouse() -> None:
    global _client
    if _client:
        await _client.close()
        _client = None
    logger.info("ClickHouse connection closed")


async def insert_batch(table: str, data: List[Dict[str, Any]]) -> None:
    if not data:
        return

    client = get_clickhouse()
    try:
        await client.insert(table, data, column_names=list(data[0].keys()))
    except Exception as e:
        logger.error("Failed to insert batch into ClickHouse", table=table, error=str(e))
        raise
