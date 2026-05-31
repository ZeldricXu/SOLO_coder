import logging
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional, Tuple

from src.infrastructure.db.connection_pool import ConnectionPool

logger = logging.getLogger(__name__)


@dataclass
class ColumnSchema:
    name: str
    data_type: str
    nullable: bool = True
    default_value: Optional[str] = None
    comment: Optional[str] = None
    ordinal_position: int = 0
    is_primary_key: bool = False
    is_unique: bool = False
    is_indexed: bool = False
    character_maximum_length: Optional[int] = None
    numeric_precision: Optional[int] = None
    numeric_scale: Optional[int] = None


@dataclass
class TableSchema:
    database_name: str
    table_name: str
    schema_name: str = "public"
    table_type: str = "TABLE"
    comment: Optional[str] = None
    columns: List[ColumnSchema] = field(default_factory=list)
    primary_keys: List[str] = field(default_factory=list)
    indexes: List[Dict[str, Any]] = field(default_factory=list)
    foreign_keys: List[Dict[str, Any]] = field(default_factory=list)
    partition_info: Optional[Dict[str, Any]] = None

    def to_dict(self) -> Dict[str, Any]:
        return {
            "database_name": self.database_name,
            "table_name": self.table_name,
            "schema_name": self.schema_name,
            "table_type": self.table_type,
            "comment": self.comment,
            "columns": [
                {
                    "name": c.name,
                    "data_type": c.data_type,
                    "nullable": c.nullable,
                    "default_value": c.default_value,
                    "is_primary_key": c.is_primary_key,
                    "ordinal_position": c.ordinal_position,
                }
                for c in self.columns
            ],
            "primary_keys": self.primary_keys,
            "indexes": self.indexes,
            "foreign_keys": self.foreign_keys,
        }


class SchemaExtractor:
    def __init__(self, pool: ConnectionPool):
        self._pool = pool

    def extract_database_list(self) -> List[str]:
        result = self._pool.execute(
            "SELECT datname FROM pg_database WHERE datistemplate = false"
        )
        return [row[0] for row in result.fetchall()]

    def extract_table_list(self, database_name: str, schema_name: str = "public") -> List[Dict[str, Any]]:
        result = self._pool.execute(
            """
            SELECT table_name, table_type
            FROM information_schema.tables
            WHERE table_schema = :schema
            ORDER BY table_name
            """,
            {"schema": schema_name},
        )
        return [
            {"database_name": database_name, "table_name": row[0], "table_type": row[1]}
            for row in result.fetchall()
        ]

    def extract_table_schema(self, database_name: str, table_name: str, schema_name: str = "public") -> Optional[TableSchema]:
        columns = self._extract_columns(database_name, table_name, schema_name)
        if not columns:
            return None

        primary_keys = self._extract_primary_keys(table_name, schema_name)
        indexes = self._extract_indexes(table_name, schema_name)
        foreign_keys = self._extract_foreign_keys(table_name, schema_name)
        table_comment = self._extract_table_comment(table_name, schema_name)

        for col in columns:
            if col.name in primary_keys:
                col.is_primary_key = True
            for idx in indexes:
                if col.name in idx.get("columns", []):
                    col.is_indexed = True

        return TableSchema(
            database_name=database_name,
            table_name=table_name,
            schema_name=schema_name,
            comment=table_comment,
            columns=columns,
            primary_keys=primary_keys,
            indexes=indexes,
            foreign_keys=foreign_keys,
        )

    def _extract_columns(self, database_name: str, table_name: str, schema_name: str) -> List[ColumnSchema]:
        result = self._pool.execute(
            """
            SELECT column_name, data_type, is_nullable, column_default,
                   ordinal_position, character_maximum_length,
                   numeric_precision, numeric_scale
            FROM information_schema.columns
            WHERE table_schema = :schema AND table_name = :table
            ORDER BY ordinal_position
            """,
            {"schema": schema_name, "table": table_name},
        )
        columns = []
        for row in result.fetchall():
            columns.append(ColumnSchema(
                name=row[0],
                data_type=row[1],
                nullable=row[2] == "YES",
                default_value=row[3],
                ordinal_position=row[4],
                character_maximum_length=row[5],
                numeric_precision=row[6],
                numeric_scale=row[7],
            ))
        return columns

    def _extract_primary_keys(self, table_name: str, schema_name: str) -> List[str]:
        result = self._pool.execute(
            """
            SELECT kcu.column_name
            FROM information_schema.table_constraints tc
            JOIN information_schema.key_column_usage kcu
                ON tc.constraint_name = kcu.constraint_name
                AND tc.table_schema = kcu.table_schema
            WHERE tc.constraint_type = 'PRIMARY KEY'
                AND tc.table_schema = :schema
                AND tc.table_name = :table
            ORDER BY kcu.ordinal_position
            """,
            {"schema": schema_name, "table": table_name},
        )
        return [row[0] for row in result.fetchall()]

    def _extract_indexes(self, table_name: str, schema_name: str) -> List[Dict[str, Any]]:
        result = self._pool.execute(
            """
            SELECT indexname, indexdef
            FROM pg_indexes
            WHERE schemaname = :schema AND tablename = :table
            """,
            {"schema": schema_name, "table": table_name},
        )
        indexes = []
        for row in result.fetchall():
            index_def = row[1]
            import re
            col_match = re.search(r"\((.+?)\)", index_def)
            cols = [c.strip().strip('"') for c in col_match.group(1).split(",")] if col_match else []
            indexes.append({
                "name": row[0],
                "definition": index_def,
                "columns": cols,
                "is_unique": "UNIQUE" in index_def.upper(),
            })
        return indexes

    def _extract_foreign_keys(self, table_name: str, schema_name: str) -> List[Dict[str, Any]]:
        result = self._pool.execute(
            """
            SELECT
                kcu.column_name,
                ccu.table_name AS foreign_table_name,
                ccu.column_name AS foreign_column_name,
                tc.constraint_name
            FROM information_schema.table_constraints tc
            JOIN information_schema.key_column_usage kcu
                ON tc.constraint_name = kcu.constraint_name
            JOIN information_schema.constraint_column_usage ccu
                ON ccu.constraint_name = tc.constraint_name
            WHERE tc.constraint_type = 'FOREIGN KEY'
                AND tc.table_schema = :schema
                AND tc.table_name = :table
            """,
            {"schema": schema_name, "table": table_name},
        )
        return [
            {
                "column": row[0],
                "foreign_table": row[1],
                "foreign_column": row[2],
                "constraint_name": row[3],
            }
            for row in result.fetchall()
        ]

    def _extract_table_comment(self, table_name: str, schema_name: str) -> Optional[str]:
        try:
            result = self._pool.execute(
                """
                SELECT obj_description((:schema || '.' || :table)::regclass, 'pg_class')
                """,
                {"schema": schema_name, "table": table_name},
            )
            row = result.fetchone()
            return row[0] if row and row[0] else None
        except Exception:
            return None

    def extract_all_schemas(self, database_name: str, schema_name: str = "public") -> List[TableSchema]:
        tables = self.extract_table_list(database_name, schema_name)
        schemas = []
        for tbl_info in tables:
            schema = self.extract_table_schema(database_name, tbl_info["table_name"], schema_name)
            if schema:
                schemas.append(schema)
        return schemas
