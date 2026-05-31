import logging
from typing import Any, Dict, List, Optional
from datetime import datetime

from src.infrastructure.db.connection_pool import ConnectionPool

logger = logging.getLogger(__name__)


class TimeseriesStore:
    def __init__(self, pool: ConnectionPool):
        self._pool = pool

    def initialize(self) -> None:
        self._pool.execute("""
            CREATE TABLE IF NOT EXISTS ts_data_points (
                id BIGSERIAL PRIMARY KEY,
                metric_name VARCHAR(255) NOT NULL,
                tags JSONB DEFAULT '{}',
                timestamp BIGINT NOT NULL,
                value DOUBLE PRECISION NOT NULL,
                resolution VARCHAR(20) DEFAULT 'raw',
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )
        """)
        self._pool.execute("""
            CREATE INDEX IF NOT EXISTS idx_ts_metric_time
            ON ts_data_points (metric_name, timestamp)
        """)
        self._pool.execute("""
            CREATE TABLE IF NOT EXISTS ts_compressed_blocks (
                id BIGSERIAL PRIMARY KEY,
                metric_name VARCHAR(255) NOT NULL,
                resolution VARCHAR(20) NOT NULL,
                start_timestamp BIGINT NOT NULL,
                end_timestamp BIGINT NOT NULL,
                compression_algo VARCHAR(50) NOT NULL,
                data BYTEA NOT NULL,
                point_count INTEGER NOT NULL DEFAULT 0,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )
        """)
        self._pool.execute("""
            CREATE INDEX IF NOT EXISTS idx_ts_compressed_metric
            ON ts_compressed_blocks (metric_name, resolution, start_timestamp)
        """)

    def write_point(
        self,
        metric_name: str,
        timestamp: int,
        value: float,
        tags: Optional[Dict[str, str]] = None,
        resolution: str = "raw",
    ) -> None:
        import json
        self._pool.execute(
            """
            INSERT INTO ts_data_points (metric_name, tags, timestamp, value, resolution)
            VALUES (:metric, :tags, :ts, :value, :res)
            """,
            {
                "metric": metric_name,
                "tags": json.dumps(tags or {}),
                "ts": timestamp,
                "value": value,
                "res": resolution,
            },
        )

    def write_points(self, points: List[Dict[str, Any]]) -> None:
        import json
        for pt in points:
            self._pool.execute(
                """
                INSERT INTO ts_data_points (metric_name, tags, timestamp, value, resolution)
                VALUES (:metric, :tags, :ts, :value, :res)
                """,
                {
                    "metric": pt["metric_name"],
                    "tags": json.dumps(pt.get("tags", {})),
                    "ts": pt["timestamp"],
                    "value": pt["value"],
                    "res": pt.get("resolution", "raw"),
                },
            )

    def query_range(
        self,
        metric_name: str,
        start_ts: int,
        end_ts: int,
        resolution: str = "raw",
        tags: Optional[Dict[str, str]] = None,
        limit: int = 10000,
    ) -> List[Dict[str, Any]]:
        import json
        if tags:
            result = self._pool.execute(
                """
                SELECT metric_name, tags, timestamp, value, resolution
                FROM ts_data_points
                WHERE metric_name = :metric AND timestamp BETWEEN :start AND :end
                      AND resolution = :res AND tags @> :tags
                ORDER BY timestamp ASC
                LIMIT :limit
                """,
                {
                    "metric": metric_name,
                    "start": start_ts,
                    "end": end_ts,
                    "res": resolution,
                    "tags": json.dumps(tags),
                    "limit": limit,
                },
            )
        else:
            result = self._pool.execute(
                """
                SELECT metric_name, tags, timestamp, value, resolution
                FROM ts_data_points
                WHERE metric_name = :metric AND timestamp BETWEEN :start AND :end
                      AND resolution = :res
                ORDER BY timestamp ASC
                LIMIT :limit
                """,
                {
                    "metric": metric_name,
                    "start": start_ts,
                    "end": end_ts,
                    "res": resolution,
                    "limit": limit,
                },
            )
        return [
            {
                "metric_name": row[0],
                "tags": row[1] if isinstance(row[1], dict) else json.loads(row[1]),
                "timestamp": row[2],
                "value": row[3],
                "resolution": row[4],
            }
            for row in result.fetchall()
        ]

    def save_compressed_block(
        self,
        metric_name: str,
        resolution: str,
        start_timestamp: int,
        end_timestamp: int,
        compression_algo: str,
        data: bytes,
        point_count: int,
    ) -> None:
        self._pool.execute(
            """
            INSERT INTO ts_compressed_blocks
            (metric_name, resolution, start_timestamp, end_timestamp, compression_algo, data, point_count)
            VALUES (:metric, :res, :start, :end, :algo, :data, :count)
            """,
            {
                "metric": metric_name,
                "res": resolution,
                "start": start_timestamp,
                "end": end_timestamp,
                "algo": compression_algo,
                "data": data,
                "count": point_count,
            },
        )

    def get_compressed_blocks(
        self,
        metric_name: str,
        resolution: str,
        start_ts: int,
        end_ts: int,
    ) -> List[Dict[str, Any]]:
        result = self._pool.execute(
            """
            SELECT id, metric_name, resolution, start_timestamp, end_timestamp,
                   compression_algo, data, point_count
            FROM ts_compressed_blocks
            WHERE metric_name = :metric AND resolution = :res
                  AND start_timestamp <= :end AND end_timestamp >= :start
            ORDER BY start_timestamp ASC
            """,
            {"metric": metric_name, "res": resolution, "start": start_ts, "end": end_ts},
        )
        return [
            {
                "id": row[0],
                "metric_name": row[1],
                "resolution": row[2],
                "start_timestamp": row[3],
                "end_timestamp": row[4],
                "compression_algo": row[5],
                "data": bytes(row[6]) if not isinstance(row[6], bytes) else row[6],
                "point_count": row[7],
            }
            for row in result.fetchall()
        ]

    def delete_raw_data(self, metric_name: str, before_ts: int, resolution: str = "raw") -> int:
        result = self._pool.execute(
            """
            DELETE FROM ts_data_points
            WHERE metric_name = :metric AND timestamp < :before AND resolution = :res
            """,
            {"metric": metric_name, "before": before_ts, "res": resolution},
        )
        return result.rowcount
