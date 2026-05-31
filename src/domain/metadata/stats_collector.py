import logging
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional

from src.infrastructure.db.connection_pool import ConnectionPool

logger = logging.getLogger(__name__)


@dataclass
class ColumnStats:
    column_name: str
    null_count: int = 0
    null_ratio: float = 0.0
    distinct_count: int = 0
    min_value: Optional[Any] = None
    max_value: Optional[Any] = None
    avg_value: Optional[float] = None
    std_dev: Optional[float] = None
    top_values: List[Dict[str, Any]] = field(default_factory=list)
    histogram: List[Dict[str, Any]] = field(default_factory=list)

    def to_dict(self) -> Dict[str, Any]:
        return {
            "column_name": self.column_name,
            "null_count": self.null_count,
            "null_ratio": round(self.null_ratio, 4),
            "distinct_count": self.distinct_count,
            "min_value": self.min_value,
            "max_value": self.max_value,
            "avg_value": self.avg_value,
            "std_dev": self.std_dev,
            "top_values": self.top_values,
        }


@dataclass
class TableStats:
    database_name: str
    table_name: str
    row_count: int = 0
    size_bytes: int = 0
    size_mb: float = 0.0
    column_stats: Dict[str, ColumnStats] = field(default_factory=dict)
    last_analyzed: Optional[str] = None

    def to_dict(self) -> Dict[str, Any]:
        return {
            "database_name": self.database_name,
            "table_name": self.table_name,
            "row_count": self.row_count,
            "size_bytes": self.size_bytes,
            "size_mb": round(self.size_mb, 2),
            "column_stats": {k: v.to_dict() for k, v in self.column_stats.items()},
        }


class StatsCollector:
    def __init__(self, pool: ConnectionPool, max_sample_rows: int = 1000):
        self._pool = pool
        self._max_sample_rows = max_sample_rows

    def collect_table_stats(self, database_name: str, table_name: str, schema_name: str = "public") -> TableStats:
        stats = TableStats(database_name=database_name, table_name=table_name)

        row_count = self._get_row_count(table_name, schema_name)
        stats.row_count = row_count

        size_info = self._get_table_size(table_name, schema_name)
        stats.size_bytes = size_info.get("size_bytes", 0)
        stats.size_mb = size_info.get("size_mb", 0.0)

        columns = self._get_column_names(table_name, schema_name)
        for col_name in columns:
            col_stats = self._collect_column_stats(table_name, col_name, schema_name, row_count)
            stats.column_stats[col_name] = col_stats

        from datetime import datetime
        stats.last_analyzed = datetime.utcnow().isoformat()

        return stats

    def _get_row_count(self, table_name: str, schema_name: str) -> int:
        try:
            result = self._pool.execute(
                f"SELECT reltuples::bigint FROM pg_class c "
                f"JOIN pg_namespace n ON n.oid = c.relnamespace "
                f"WHERE c.relname = :table AND n.nspname = :schema",
                {"table": table_name, "schema": schema_name},
            )
            row = result.fetchone()
            if row and row[0] > 0:
                return int(row[0])
        except Exception:
            pass

        try:
            result = self._pool.execute(f"SELECT COUNT(*) FROM {schema_name}.{table_name}")
            return result.fetchone()[0]
        except Exception as e:
            logger.error(f"Failed to get row count for {table_name}: {e}")
            return 0

    def _get_table_size(self, table_name: str, schema_name: str) -> Dict[str, Any]:
        try:
            result = self._pool.execute(
                "SELECT pg_total_relation_size(:qualified_name)",
                {"qualified_name": f"{schema_name}.{table_name}"},
            )
            size_bytes = result.fetchone()[0]
            return {"size_bytes": size_bytes, "size_mb": size_bytes / (1024 * 1024)}
        except Exception as e:
            logger.error(f"Failed to get table size for {table_name}: {e}")
            return {"size_bytes": 0, "size_mb": 0.0}

    def _get_column_names(self, table_name: str, schema_name: str) -> List[str]:
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
        except Exception as e:
            logger.error(f"Failed to get columns for {table_name}: {e}")
            return []

    def _collect_column_stats(self, table_name: str, column_name: str, schema_name: str, total_rows: int) -> ColumnStats:
        stats = ColumnStats(column_name=column_name)

        qualified = f"{schema_name}.{table_name}"
        quoted_col = f'"{column_name}"'

        try:
            null_result = self._pool.execute(
                f"SELECT COUNT(*) FROM {qualified} WHERE {quoted_col} IS NULL"
            )
            stats.null_count = null_result.fetchone()[0]
            stats.null_ratio = stats.null_count / max(total_rows, 1)
        except Exception:
            pass

        try:
            distinct_result = self._pool.execute(
                f"SELECT COUNT(DISTINCT {quoted_col}) FROM {qualified}"
            )
            stats.distinct_count = distinct_result.fetchone()[0]
        except Exception:
            pass

        try:
            minmax_result = self._pool.execute(
                f"SELECT MIN({quoted_col}), MAX({quoted_col}) FROM {qualified}"
            )
            row = minmax_result.fetchone()
            if row:
                stats.min_value = row[0]
                stats.max_value = row[1]
        except Exception:
            pass

        try:
            avg_result = self._pool.execute(
                f"SELECT AVG({quoted_col}::numeric), STDDEV({quoted_col}::numeric) FROM {qualified} WHERE {quoted_col} IS NOT NULL"
            )
            row = avg_result.fetchone()
            if row:
                stats.avg_value = float(row[0]) if row[0] is not None else None
                stats.std_dev = float(row[1]) if row[1] is not None else None
        except Exception:
            pass

        try:
            top_result = self._pool.execute(
                f"SELECT {quoted_col}, COUNT(*) as cnt FROM {qualified} "
                f"WHERE {quoted_col} IS NOT NULL "
                f"GROUP BY {quoted_col} ORDER BY cnt DESC LIMIT 10"
            )
            stats.top_values = [
                {"value": row[0], "count": row[1]}
                for row in top_result.fetchall()
            ]
        except Exception:
            pass

        return stats

    def collect_all_tables_stats(self, database_name: str, schema_name: str = "public") -> List[TableStats]:
        result = self._pool.execute(
            """
            SELECT table_name FROM information_schema.tables
            WHERE table_schema = :schema AND table_type = 'BASE TABLE'
            ORDER BY table_name
            """,
            {"schema": schema_name},
        )
        all_stats = []
        for row in result.fetchall():
            stats = self.collect_table_stats(database_name, row[0], schema_name)
            all_stats.append(stats)
        return all_stats
