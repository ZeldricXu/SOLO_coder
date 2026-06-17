import logging
import time
from contextlib import contextmanager

import pandas as pd
import redshift_connector

from .base import BaseWriter, WriteResult, register_writer

logger = logging.getLogger(__name__)

_PANDAS_TO_REDSHIFT = {
    "int64": "BIGINT",
    "int32": "INTEGER",
    "int16": "SMALLINT",
    "float64": "DOUBLE PRECISION",
    "float32": "REAL",
    "bool": "BOOLEAN",
    "datetime64[ns]": "TIMESTAMP",
    "object": "VARCHAR(256)",
}


@register_writer("redshift")
class RedshiftWriter(BaseWriter):
    def _validate_config(self) -> None:
        required = {"host", "database", "user", "password"}
        missing = required - set(self.config.keys())
        if missing:
            raise ValueError(f"Missing required Redshift config keys: {missing}")

    @contextmanager
    def _get_connection(self):
        conn = redshift_connector.connect(
            host=self.config["host"],
            database=self.config["database"],
            user=self.config["user"],
            password=self.config["password"],
            port=self.config.get("port", 5439),
        )
        try:
            yield conn
        finally:
            conn.close()

    async def test_connection(self) -> bool:
        try:
            with self._get_connection() as conn:
                with conn.cursor() as cur:
                    cur.execute("SELECT 1")
                    cur.fetchone()
            return True
        except Exception:
            logger.exception("Redshift connection test failed")
            return False

    async def write(
        self, df: pd.DataFrame, table: str, strategy: str = "insert", **kwargs
    ) -> WriteResult:
        start = time.monotonic()
        schema = kwargs.get("schema", "public")
        full_table = f'"{schema}"."{table}"'
        try:
            with self._get_connection() as conn:
                self._ensure_table_exists(conn, df, table, schema)
                if strategy == "insert":
                    result = self._write_insert(conn, df, full_table, **kwargs)
                elif strategy == "upsert":
                    result = self._write_upsert(conn, df, full_table, table, schema, **kwargs)
                elif strategy == "partition_overwrite":
                    result = self._write_partition_overwrite(conn, df, full_table, **kwargs)
                else:
                    raise ValueError(f"Unknown write strategy: {strategy}")
                conn.commit()
            duration = time.monotonic() - start
            return WriteResult(
                rows_written=result,
                table=full_table,
                strategy=strategy,
                duration_seconds=round(duration, 3),
                success=True,
            )
        except Exception as exc:
            duration = time.monotonic() - start
            logger.exception("Redshift write failed for %s", full_table)
            return WriteResult(
                rows_written=0,
                table=full_table,
                strategy=strategy,
                duration_seconds=round(duration, 3),
                success=False,
                error=str(exc),
            )

    def _write_insert(self, conn, df: pd.DataFrame, full_table: str, **kwargs) -> int:
        batch_size = kwargs.get("batch_size", 1000)
        columns = list(df.columns)
        placeholders = ", ".join(["%s"] * len(columns))
        col_list = ", ".join(f'"{c}"' for c in columns)
        sql = f"INSERT INTO {full_table} ({col_list}) VALUES ({placeholders})"
        rows_written = 0
        with conn.cursor() as cur:
            for offset in range(0, len(df), batch_size):
                batch = df.iloc[offset : offset + batch_size]
                data = [tuple(row) for row in batch.itertuples(index=False, name=None)]
                cur.executemany(sql, data)
                rows_written += len(data)
        return rows_written

    def _write_upsert(
        self, conn, df: pd.DataFrame, full_table: str, table: str, schema: str, **kwargs
    ) -> int:
        key_columns = kwargs.get("key_columns")
        if not key_columns:
            raise ValueError("key_columns is required for upsert strategy")
        temp_table = self._create_temp_table(conn, df, table, schema)
        try:
            columns = list(df.columns)
            col_list = ", ".join(f'"{c}"' for c in columns)
            update_cols = [c for c in columns if c not in key_columns]
            update_clause = ", ".join(f'"{c}" = EXCLUDED."{c}"' for c in update_cols)
            key_clause = ", ".join(f'"{c}"' for c in key_columns)
            sql = (
                f"INSERT INTO {full_table} ({col_list}) "
                f"SELECT {col_list} FROM \"{schema}\".\"{temp_table}\" "
                f"ON CONFLICT ({key_clause}) DO UPDATE SET {update_clause}"
            )
            with conn.cursor() as cur:
                cur.execute(sql)
            return len(df)
        finally:
            self._drop_temp_table(conn, temp_table, schema)

    def _write_partition_overwrite(
        self, conn, df: pd.DataFrame, full_table: str, **kwargs
    ) -> int:
        partition_column = kwargs.get("partition_column")
        if not partition_column:
            raise ValueError("partition_column is required for partition_overwrite strategy")
        partition_values = df[partition_column].unique().tolist()
        with conn.cursor() as cur:
            for val in partition_values:
                cur.execute(f'DELETE FROM {full_table} WHERE "{partition_column}" = %s', (val,))
        self._write_insert(conn, df, full_table, **kwargs)
        return len(df)

    def _create_temp_table(self, conn, df: pd.DataFrame, table: str, schema: str) -> str:
        temp_name = f"tmp_upsert_{table}_{id(df)}"
        ddl = self._generate_ddl(df, temp_name, schema)
        with conn.cursor() as cur:
            cur.execute(ddl)
        self._write_insert(conn, df, f'"{schema}"."{temp_name}"')
        return temp_name

    def _drop_temp_table(self, conn, temp_table: str, schema: str) -> None:
        with conn.cursor() as cur:
            cur.execute(f'DROP TABLE IF EXISTS "{schema}"."{temp_table}"')

    def _ensure_table_exists(self, conn, df: pd.DataFrame, table: str, schema: str) -> None:
        with conn.cursor() as cur:
            cur.execute(
                "SELECT 1 FROM information_schema.tables WHERE table_schema = %s AND table_name = %s",
                (schema, table),
            )
            if cur.fetchone() is None:
                ddl = self._generate_ddl(df, table, schema)
                cur.execute(ddl)
                logger.info("Created table %s.%s", schema, table)

    def _generate_ddl(self, df: pd.DataFrame, table: str, schema: str) -> str:
        columns = []
        for col in df.columns:
            dtype = str(df[col].dtype)
            rs_type = _PANDAS_TO_REDSHIFT.get(dtype, "VARCHAR(256)")
            columns.append(f'"{col}" {rs_type}')
        col_defs = ", ".join(columns)
        return f'CREATE TABLE IF NOT EXISTS "{schema}"."{table}" ({col_defs})'
