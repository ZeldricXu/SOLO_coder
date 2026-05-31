from __future__ import annotations

import re
from typing import Any

from streamsql.core.exceptions import SchemaExtractionError
from streamsql.core.models import ColumnInfo, ColumnType, SchemaInfo, TableSchema


TYPE_MAP: dict[str, ColumnType] = {
    "int": ColumnType.INTEGER,
    "integer": ColumnType.INTEGER,
    "bigint": ColumnType.BIGINT,
    "long": ColumnType.BIGINT,
    "float": ColumnType.FLOAT,
    "double": ColumnType.DOUBLE,
    "real": ColumnType.FLOAT,
    "varchar": ColumnType.STRING,
    "char": ColumnType.STRING,
    "text": ColumnType.STRING,
    "string": ColumnType.STRING,
    "boolean": ColumnType.BOOLEAN,
    "bool": ColumnType.BOOLEAN,
    "date": ColumnType.DATE,
    "datetime": ColumnType.DATETIME,
    "timestamp": ColumnType.TIMESTAMP,
    "binary": ColumnType.BINARY,
    "blob": ColumnType.BINARY,
    "json": ColumnType.JSON,
    "array": ColumnType.ARRAY,
    "list": ColumnType.ARRAY,
}


class SchemaExtractor:
    @staticmethod
    def map_type(sql_type: str) -> ColumnType:
        base_type = re.sub(r"\(.*\)", "", sql_type.lower()).strip()
        for pattern, col_type in TYPE_MAP.items():
            if base_type.startswith(pattern):
                return col_type
        return ColumnType.UNKNOWN

    @staticmethod
    def from_sql(sql: str) -> TableSchema:
        try:
            match = re.search(
                r"CREATE TABLE\s+(?:IF NOT EXISTS\s+)?([\w.]+",
                sql,
                re.IGNORECASE,
            )
            if not match:
                raise ValueError("Cannot extract table name")

            table_full = match.group(1)
            if "." in table_full:
                database, table = table_full.split(".", 1)
            else:
                database, table = "default", table_full

            columns_match = re.search(r"\((.*)\)", sql, re.DOTALL)
            if not columns_match:
                raise ValueError("Cannot extract columns")

            columns_sql = columns_match.group(1)
            column_defs = SchemaExtractor._parse_columns(columns_sql)
            primary_keys = SchemaExtractor._extract_primary_keys(columns_sql)

            return TableSchema(
                database=database,
                table=table,
                columns=column_defs,
                primary_key=primary_keys,
            )
        except Exception as e:
            raise SchemaExtractionError("sql", f"Failed to parse CREATE TABLE: {e}") from e

    @staticmethod
    def _parse_columns(columns_sql: str) -> list[ColumnInfo]:
        columns: list[ColumnInfo] = []
        lines = [line.strip() for line in columns_sql.split(",") if line.strip()]

        for line in lines:
            if line.upper().startswith("PRIMARY KEY") or line.upper().startswith("CONSTRAINT"):
                continue
            if line.upper().startswith("FOREIGN KEY") or line.upper().startswith("UNIQUE"):
                continue
            if line.upper().startswith("INDEX") or line.upper().startswith("KEY"):
                continue

            parts = line.split(None, 2)
            if len(parts) < 2:
                continue

            name = parts[0].strip("`\"'")
            type_str = parts[1].strip()

            nullable = "NOT NULL" not in line.upper()
            is_primary = "PRIMARY KEY" in line.upper()
            is_unique = "UNIQUE" in line.upper()

            default_match = re.search(r"DEFAULT\s+(.+?)(?:\s+|$)", line, re.IGNORECASE)
            default_value = default_match.group(1).strip() if default_match else None

            comment_match = re.search(r"COMMENT\s+'(.+?)'", line, re.IGNORECASE)
            comment = comment_match.group(1) if comment_match else None

            columns.append(
                ColumnInfo(
                    name=name,
                    type=SchemaExtractor.map_type(type_str),
                    nullable=nullable,
                    primary_key=is_primary,
                    unique=is_unique,
                    default_value=default_value,
                    comment=comment,
                )
            )

        return columns

    @staticmethod
    def _extract_primary_keys(columns_sql: str) -> list[str]:
        pk_match = re.search(
            r"PRIMARY KEY\s*\((.+?)\)", columns_sql, re.IGNORECASE
        )
        if pk_match:
            pk_cols = [c.strip().strip("`\"'") for c in pk_match.group(1).split(",")]
            return pk_cols
        return []

    @staticmethod
    def from_dataframe(df: "pandas.DataFrame", database: str, table: str) -> TableSchema:
        import pandas as pd

        columns: list[ColumnInfo] = []

        for col_name, dtype in df.dtypes.items():
            col_type = SchemaExtractor._map_pandas_type(str(dtype))
            sample_values = df[col_name].dropna().head(5).tolist()

            stats: dict[str, Any] = {}
            if pd.api.types.is_numeric_dtype(dtype):
                stats["min"] = float(df[col_name].min()) if not df[col_name].empty else None
                stats["max"] = float(df[col_name].max()) if not df[col_name].empty else None
                stats["mean"] = float(df[col_name].mean()) if not df[col_name].empty else None
            elif pd.api.types.is_string_dtype(dtype):
                stats["unique_count"] = int(df[col_name].nunique())
                stats["avg_length"] = float(df[col_name].str.len().mean()) if not df[col_name].empty else 0.0

            columns.append(
                ColumnInfo(
                    name=col_name,
                    type=col_type,
                    nullable=bool(df[col_name].isnull().any()),
                    sample_values=sample_values,
                    stats=stats,
                )
            )

        return TableSchema(
            database=database,
            table=table,
            columns=columns,
            row_count=len(df),
        )

    @staticmethod
    def _map_pandas_type(dtype: str) -> ColumnType:
        if dtype.startswith("int"):
            return ColumnType.INTEGER
        elif dtype.startswith("float"):
            return ColumnType.FLOAT
        elif dtype == "bool":
            return ColumnType.BOOLEAN
        elif dtype == "datetime64[ns]":
            return ColumnType.DATETIME
        elif dtype == "object" or dtype == "string":
            return ColumnType.STRING
        return ColumnType.UNKNOWN

    @staticmethod
    def infer_from_samples(samples: list[dict[str, Any]], database: str, table: str) -> TableSchema:
        if not samples:
            raise SchemaExtractionError("samples", "No samples provided")

        column_types: dict[str, set[str]] = {}
        column_nullable: dict[str, bool] = {}
        column_samples: dict[str, list[Any]] = {}

        for row in samples:
            for key, value in row.items():
                if key not in column_types:
                    column_types[key] = set()
                    column_nullable[key] = False
                    column_samples[key] = []

                if value is None:
                    column_nullable[key] = True
                else:
                    type_name = type(value).__name__
                    column_types[key].add(type_name)
                    if len(column_samples[key]) < 5:
                        column_samples[key].append(value)

        columns: list[ColumnInfo] = []
        for name in column_types.keys():
            inferred_type = SchemaExtractor._infer_column_type(column_types[name])
            columns.append(
                ColumnInfo(
                    name=name,
                    type=inferred_type,
                    nullable=column_nullable[name],
                    sample_values=column_samples[name],
                    stats={"sample_count": len(samples)},
                )
            )

        return TableSchema(
            database=database,
            table=table,
            columns=columns,
            row_count=len(samples),
        )

    @staticmethod
    def _infer_column_type(type_names: set[str]) -> ColumnType:
        if "int" in str(type_names):
            return ColumnType.INTEGER
        if "float" in str(type_names):
            return ColumnType.FLOAT
        if "bool" in str(type_names):
            return ColumnType.BOOLEAN
        if "str" in str(type_names):
            return ColumnType.STRING
        if "datetime" in str(type_names):
            return ColumnType.DATETIME
        if "dict" in str(type_names):
            return ColumnType.JSON
        if "list" in str(type_names):
            return ColumnType.ARRAY
        return ColumnType.UNKNOWN

    @staticmethod
    def merge_schemas(schemas: list[TableSchema]) -> TableSchema:
        if not schemas:
            raise SchemaExtractionError("merge", "No schemas to merge")

        base = schemas[0]
        all_columns: dict[str, ColumnInfo] = {c.name: c for c in base.columns}

        for schema in schemas[1:]:
            for col in schema.columns:
                if col.name in all_columns:
                    existing = all_columns[col.name]
                    existing.sample_values = list(
                        set(existing.sample_values + col.sample_values)
                    )[:10]
                    existing.nullable = existing.nullable or col.nullable
                    if existing.type != col.type:
                        existing.type = ColumnType.UNKNOWN
                else:
                    all_columns[col.name] = col

        return TableSchema(
            database=base.database,
            table=base.table,
            columns=list(all_columns.values()),
            primary_key=base.primary_key,
            row_count=max(s.row_count or 0 for s in schemas),
        )
