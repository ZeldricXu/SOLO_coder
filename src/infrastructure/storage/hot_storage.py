import logging
from typing import Any, Dict, List, Optional

from src.infrastructure.db.connection_pool import ConnectionPool

logger = logging.getLogger(__name__)


class HotStorage:
    def __init__(self, pool: ConnectionPool):
        self._pool = pool

    def write(self, table_name: str, records: List[Dict[str, Any]]) -> int:
        if not records:
            return 0
        columns = list(records[0].keys())
        col_str = ", ".join(columns)
        param_str = ", ".join(f":{c}" for c in columns)
        sql = f"INSERT INTO {table_name} ({col_str}) VALUES ({param_str})"
        count = 0
        for record in records:
            try:
                self._pool.execute(sql, record)
                count += 1
            except Exception as e:
                logger.error(f"Failed to write record to hot storage table '{table_name}': {e}")
        return count

    def read(self, table_name: str, where: Optional[str] = None, params: Optional[Dict] = None, limit: int = 10000) -> List[Dict[str, Any]]:
        sql = f"SELECT * FROM {table_name}"
        if where:
            sql += f" WHERE {where}"
        sql += f" LIMIT {limit}"
        result = self._pool.execute(sql, params or {})
        columns = list(result.keys())
        return [dict(zip(columns, row)) for row in result.fetchall()]

    def delete(self, table_name: str, where: str, params: Optional[Dict] = None) -> int:
        sql = f"DELETE FROM {table_name} WHERE {where}"
        result = self._pool.execute(sql, params or {})
        return result.rowcount

    def update(self, table_name: str, updates: Dict[str, Any], where: str, params: Optional[Dict] = None) -> int:
        set_clause = ", ".join(f"{k} = :set_{k}" for k in updates.keys())
        sql = f"UPDATE {table_name} SET {set_clause} WHERE {where}"
        merged_params = {f"set_{k}": v for k, v in updates.items()}
        if params:
            merged_params.update(params)
        result = self._pool.execute(sql, merged_params)
        return result.rowcount

    def count(self, table_name: str, where: Optional[str] = None, params: Optional[Dict] = None) -> int:
        sql = f"SELECT COUNT(*) FROM {table_name}"
        if where:
            sql += f" WHERE {where}"
        result = self._pool.execute(sql, params or {})
        return result.fetchone()[0]

    def table_exists(self, table_name: str) -> bool:
        result = self._pool.execute(
            "SELECT EXISTS (SELECT FROM information_schema.tables WHERE table_name = :name)",
            {"name": table_name},
        )
        return result.fetchone()[0]
