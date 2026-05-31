import logging
import random
from dataclasses import dataclass
from typing import Any, Dict, List, Optional

from src.infrastructure.db.connection_pool import ConnectionPool
from src.infrastructure.config.settings import MetadataConfig

logger = logging.getLogger(__name__)


@dataclass
class SampleData:
    database_name: str
    table_name: str
    columns: List[str]
    rows: List[Dict[str, Any]]
    total_rows: int
    sample_method: str
    sample_size: int

    def to_dict(self) -> Dict[str, Any]:
        return {
            "database_name": self.database_name,
            "table_name": self.table_name,
            "columns": self.columns,
            "rows": self.rows,
            "total_rows": self.total_rows,
            "sample_method": self.sample_method,
            "sample_size": self.sample_size,
        }


class SampleFetcher:
    def __init__(self, pool: ConnectionPool, config: Optional[MetadataConfig] = None):
        self._pool = pool
        self._config = config or MetadataConfig()

    def fetch_sample(
        self,
        database_name: str,
        table_name: str,
        schema_name: str = "public",
        limit: Optional[int] = None,
        method: Optional[str] = None,
        where_clause: Optional[str] = None,
    ) -> SampleData:
        sample_limit = limit or self._config.max_sample_rows
        sample_method = method or self._config.sample_method

        qualified = f"{schema_name}.{table_name}"

        columns = self._get_columns(table_name, schema_name)
        if not columns:
            return SampleData(
                database_name=database_name,
                table_name=table_name,
                columns=[],
                rows=[],
                total_rows=0,
                sample_method=sample_method,
                sample_size=0,
            )

        total_rows = self._get_row_count(qualified)
        rows = self._execute_sample_query(qualified, columns, sample_limit, sample_method, where_clause)

        return SampleData(
            database_name=database_name,
            table_name=table_name,
            columns=columns,
            rows=rows,
            total_rows=total_rows,
            sample_method=sample_method,
            sample_size=len(rows),
        )

    def _execute_sample_query(
        self,
        qualified: str,
        columns: List[str],
        limit: int,
        method: str,
        where_clause: Optional[str],
    ) -> List[Dict[str, Any]]:
        col_str = ", ".join(f'"{c}"' for c in columns)

        if method == "random":
            sql = f"SELECT {col_str} FROM {qualified}"
            if where_clause:
                sql += f" WHERE {where_clause}"
            sql += f" ORDER BY RANDOM() LIMIT {limit}"
        elif method == "system":
            sample_pct = min(100, max(1, (limit / max(self._get_row_count(qualified), 1)) * 100))
            sql = f"SELECT {col_str} FROM {qualified} TABLESAMPLE SYSTEM({sample_pct:.1f})"
            if where_clause:
                sql += f" WHERE {where_clause}"
            sql += f" LIMIT {limit}"
        elif method == "bernoulli":
            sample_pct = min(100, max(1, (limit / max(self._get_row_count(qualified), 1)) * 100))
            sql = f"SELECT {col_str} FROM {qualified} TABLESAMPLE BERNOULLI({sample_pct:.1f})"
            if where_clause:
                sql += f" WHERE {where_clause}"
            sql += f" LIMIT {limit}"
        else:
            sql = f"SELECT {col_str} FROM {qualified}"
            if where_clause:
                sql += f" WHERE {where_clause}"
            sql += f" LIMIT {limit}"

        try:
            result = self._pool.execute(sql)
            return [dict(zip(columns, row)) for row in result.fetchall()]
        except Exception as e:
            logger.error(f"Sample query failed for {qualified}: {e}")
            try:
                fallback_sql = f"SELECT {col_str} FROM {qualified}"
                if where_clause:
                    fallback_sql += f" WHERE {where_clause}"
                fallback_sql += f" LIMIT {limit}"
                result = self._pool.execute(fallback_sql)
                return [dict(zip(columns, row)) for row in result.fetchall()]
            except Exception as e2:
                logger.error(f"Fallback sample query also failed: {e2}")
                return []

    def _get_columns(self, table_name: str, schema_name: str) -> List[str]:
        try:
            result = self._pool.execute(
                """
                SELECT column_name FROM information_schema.columns
                WHERE table_schema = :schema AND table_name = :table
                ORDER BY ordinal_position
                """,
                {"schema": schema_name, "table": table_name},
            )
            return [row[0] for row in result.fetchall()]
        except Exception:
            return []

    def _get_row_count(self, qualified: str) -> int:
        try:
            result = self._pool.execute(f"SELECT COUNT(*) FROM {qualified}")
            return result.fetchone()[0]
        except Exception:
            return 0

    def fetch_multiple_samples(
        self,
        database_name: str,
        table_names: List[str],
        schema_name: str = "public",
        limit: Optional[int] = None,
    ) -> Dict[str, SampleData]:
        results = {}
        for table_name in table_names:
            try:
                sample = self.fetch_sample(database_name, table_name, schema_name, limit)
                results[table_name] = sample
            except Exception as e:
                logger.error(f"Failed to fetch sample for {table_name}: {e}")
        return results
