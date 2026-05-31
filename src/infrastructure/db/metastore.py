import json
import logging
from typing import Any, Dict, List, Optional
from datetime import datetime

from src.infrastructure.db.connection_pool import ConnectionPool

logger = logging.getLogger(__name__)


class Metastore:
    def __init__(self, pool: ConnectionPool):
        self._pool = pool

    def initialize(self) -> None:
        self._pool.execute("""
            CREATE TABLE IF NOT EXISTS metastore_tables (
                id SERIAL PRIMARY KEY,
                database_name VARCHAR(255) NOT NULL,
                table_name VARCHAR(255) NOT NULL,
                schema_json TEXT NOT NULL,
                stats_json TEXT,
                sample_json TEXT,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                UNIQUE(database_name, table_name)
            )
        """)
        self._pool.execute("""
            CREATE TABLE IF NOT EXISTS metastore_columns (
                id SERIAL PRIMARY KEY,
                database_name VARCHAR(255) NOT NULL,
                table_name VARCHAR(255) NOT NULL,
                column_name VARCHAR(255) NOT NULL,
                data_type VARCHAR(100) NOT NULL,
                nullable BOOLEAN DEFAULT TRUE,
                default_value TEXT,
                comment TEXT,
                ordinal_position INTEGER NOT NULL,
                stats_json TEXT,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                UNIQUE(database_name, table_name, column_name)
            )
        """)
        self._pool.execute("""
            CREATE TABLE IF NOT EXISTS metastore_lineage (
                id SERIAL PRIMARY KEY,
                source_db VARCHAR(255) NOT NULL,
                source_table VARCHAR(255) NOT NULL,
                source_column VARCHAR(255),
                target_db VARCHAR(255) NOT NULL,
                target_table VARCHAR(255) NOT NULL,
                target_column VARCHAR(255),
                transformation TEXT,
                sql_text TEXT,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )
        """)

    def save_table_metadata(
        self,
        database_name: str,
        table_name: str,
        schema_json: Dict[str, Any],
        stats_json: Optional[Dict[str, Any]] = None,
        sample_json: Optional[Dict[str, Any]] = None,
    ) -> None:
        self._pool.execute(
            """
            INSERT INTO metastore_tables (database_name, table_name, schema_json, stats_json, sample_json, updated_at)
            VALUES (:db, :tbl, :schema, :stats, :sample, :now)
            ON CONFLICT (database_name, table_name)
            DO UPDATE SET schema_json = :schema, stats_json = :stats,
                          sample_json = :sample, updated_at = :now
            """,
            {
                "db": database_name,
                "tbl": table_name,
                "schema": json.dumps(schema_json, ensure_ascii=False),
                "stats": json.dumps(stats_json, ensure_ascii=False) if stats_json else None,
                "sample": json.dumps(sample_json, ensure_ascii=False) if sample_json else None,
                "now": datetime.utcnow(),
            },
        )

    def get_table_metadata(self, database_name: str, table_name: str) -> Optional[Dict[str, Any]]:
        result = self._pool.execute(
            """
            SELECT database_name, table_name, schema_json, stats_json, sample_json, created_at, updated_at
            FROM metastore_tables
            WHERE database_name = :db AND table_name = :tbl
            """,
            {"db": database_name, "tbl": table_name},
        )
        row = result.fetchone()
        if row is None:
            return None
        return {
            "database_name": row[0],
            "table_name": row[1],
            "schema": json.loads(row[2]) if row[2] else {},
            "stats": json.loads(row[3]) if row[3] else None,
            "sample": json.loads(row[4]) if row[4] else None,
            "created_at": row[5],
            "updated_at": row[6],
        }

    def list_tables(self, database_name: Optional[str] = None) -> List[Dict[str, Any]]:
        if database_name:
            result = self._pool.execute(
                "SELECT database_name, table_name, updated_at FROM metastore_tables WHERE database_name = :db",
                {"db": database_name},
            )
        else:
            result = self._pool.execute("SELECT database_name, table_name, updated_at FROM metastore_tables")
        return [
            {"database_name": row[0], "table_name": row[1], "updated_at": row[2]}
            for row in result.fetchall()
        ]

    def save_lineage(
        self,
        source_db: str,
        source_table: str,
        source_column: Optional[str],
        target_db: str,
        target_table: str,
        target_column: Optional[str],
        transformation: Optional[str] = None,
        sql_text: Optional[str] = None,
    ) -> None:
        self._pool.execute(
            """
            INSERT INTO metastore_lineage
            (source_db, source_table, source_column, target_db, target_table, target_column, transformation, sql_text)
            VALUES (:sdb, :stbl, :scol, :tdb, :ttbl, :tcol, :transform, :sql)
            """,
            {
                "sdb": source_db,
                "stbl": source_table,
                "scol": source_column,
                "tdb": target_db,
                "ttbl": target_table,
                "tcol": target_column,
                "transform": transformation,
                "sql": sql_text,
            },
        )

    def get_lineage(self, database_name: str, table_name: str, direction: str = "upstream") -> List[Dict[str, Any]]:
        if direction == "upstream":
            result = self._pool.execute(
                """
                SELECT source_db, source_table, source_column, target_db, target_table, target_column, transformation
                FROM metastore_lineage
                WHERE target_db = :db AND target_table = :tbl
                """,
                {"db": database_name, "tbl": table_name},
            )
        else:
            result = self._pool.execute(
                """
                SELECT source_db, source_table, source_column, target_db, target_table, target_column, transformation
                FROM metastore_lineage
                WHERE source_db = :db AND source_table = :tbl
                """,
                {"db": database_name, "tbl": table_name},
            )
        return [
            {
                "source_db": row[0],
                "source_table": row[1],
                "source_column": row[2],
                "target_db": row[3],
                "target_table": row[4],
                "target_column": row[5],
                "transformation": row[6],
            }
            for row in result.fetchall()
        ]

    def save_column_metadata(
        self,
        database_name: str,
        table_name: str,
        column_name: str,
        data_type: str,
        nullable: bool = True,
        default_value: Optional[str] = None,
        comment: Optional[str] = None,
        ordinal_position: int = 0,
        stats_json: Optional[Dict[str, Any]] = None,
    ) -> None:
        self._pool.execute(
            """
            INSERT INTO metastore_columns
            (database_name, table_name, column_name, data_type, nullable, default_value, comment, ordinal_position, stats_json)
            VALUES (:db, :tbl, :col, :dtype, :nullable, :default, :comment, :pos, :stats)
            ON CONFLICT (database_name, table_name, column_name)
            DO UPDATE SET data_type = :dtype, nullable = :nullable, default_value = :default,
                          comment = :comment, ordinal_position = :pos, stats_json = :stats
            """,
            {
                "db": database_name,
                "tbl": table_name,
                "col": column_name,
                "dtype": data_type,
                "nullable": nullable,
                "default": default_value,
                "comment": comment,
                "pos": ordinal_position,
                "stats": json.dumps(stats_json, ensure_ascii=False) if stats_json else None,
            },
        )

    def delete_table_metadata(self, database_name: str, table_name: str) -> None:
        self._pool.execute(
            "DELETE FROM metastore_tables WHERE database_name = :db AND table_name = :tbl",
            {"db": database_name, "tbl": table_name},
        )
        self._pool.execute(
            "DELETE FROM metastore_columns WHERE database_name = :db AND table_name = :tbl",
            {"db": database_name, "tbl": table_name},
        )
