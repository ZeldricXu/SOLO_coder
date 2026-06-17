import logging
import time

import pandas as pd
from clickhouse_driver import Client

from .base import BaseWriter, WriteResult, register_writer

logger = logging.getLogger(__name__)

_PANDAS_TO_CH = {
    "int64": "Int64",
    "int32": "Int32",
    "int16": "Int16",
    "float64": "Float64",
    "float32": "Float32",
    "bool": "UInt8",
    "datetime64[ns]": "DateTime",
    "object": "String",
}


@register_writer("clickhouse")
class ClickHouseWriter(BaseWriter):
    def _validate_config(self) -> None:
        required = {"host"}
        missing = required - set(self.config.keys())
        if missing:
            raise ValueError(f"Missing required ClickHouse config keys: {missing}")

    def _get_client(self) -> Client:
        return Client(
            host=self.config["host"],
            port=self.config.get("port", 9000),
            user=self.config.get("user", "default"),
            password=self.config.get("password", ""),
            database=self.config.get("database", "default"),
            **{
                k: v
                for k, v in self.config.items()
                if k not in {"host", "port", "user", "password", "database"}
            },
        )

    async def test_connection(self) -> bool:
        try:
            client = self._get_client()
            client.execute("SELECT 1")
            return True
        except Exception:
            logger.exception("ClickHouse connection test failed")
            return False

    async def write(
        self, df: pd.DataFrame, table: str, strategy: str = "insert", **kwargs
    ) -> WriteResult:
        start = time.monotonic()
        database = kwargs.get("database", self.config.get("database", "default"))
        cluster = kwargs.get("cluster", self.config.get("cluster"))
        full_table = f"`{database}`.`{table}`"
        try:
            client = self._get_client()
            self._ensure_table_exists(client, df, table, database, cluster)
            if strategy == "insert":
                result = self._write_insert(client, df, full_table, **kwargs)
            elif strategy == "upsert":
                result = self._write_upsert(client, df, full_table, table, database, **kwargs)
            elif strategy == "partition_overwrite":
                result = self._write_partition_overwrite(
                    client, df, full_table, table, database, cluster, **kwargs
                )
            else:
                raise ValueError(f"Unknown write strategy: {strategy}")
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
            logger.exception("ClickHouse write failed for %s", full_table)
            return WriteResult(
                rows_written=0,
                table=full_table,
                strategy=strategy,
                duration_seconds=round(duration, 3),
                success=False,
                error=str(exc),
            )

    def _write_insert(self, client, df: pd.DataFrame, full_table: str, **kwargs) -> int:
        batch_size = kwargs.get("batch_size", 10000)
        columns = list(df.columns)
        rows_written = 0
        for offset in range(0, len(df), batch_size):
            batch = df.iloc[offset : offset + batch_size]
            data = [tuple(row) for row in batch.itertuples(index=False, name=None)]
            client.insert(table=full_table, data=data, column_names=columns)
            rows_written += len(data)
        return rows_written

    def _write_upsert(
        self, client, df: pd.DataFrame, full_table: str, table: str, database: str, **kwargs
    ) -> int:
        key_columns = kwargs.get("key_columns")
        if not key_columns:
            raise ValueError("key_columns is required for upsert strategy")
        temp_table = f"`{database}`.`tmp_upsert_{table}_{id(df)}`"
        ddl = self._generate_ddl(df, f"tmp_upsert_{table}_{id(df)}", database)
        client.execute(ddl)
        try:
            columns = list(df.columns)
            data = [tuple(row) for row in df.itertuples(index=False, name=None)]
            client.insert(table=temp_table, data=data, column_names=columns)
            col_list = ", ".join(f"`{c}`" for c in columns)
            key_clause = " AND ".join(
                f"t.`{c}` = s.`{c}`" for c in key_columns
            )
            insert_cols = ", ".join(f"`{c}`" for c in columns)
            insert_sql = (
                f"INSERT INTO {full_table} ({insert_cols}) "
                f"SELECT {col_list} FROM {temp_table} s "
                f"WHERE NOT EXISTS ("
                f"SELECT 1 FROM {full_table} t WHERE {key_clause}"
                f")"
            )
            client.execute(insert_sql)
            return len(df)
        finally:
            client.execute(f"DROP TABLE IF EXISTS {temp_table}")

    def _write_partition_overwrite(
        self,
        client,
        df: pd.DataFrame,
        full_table: str,
        table: str,
        database: str,
        cluster: str | None,
        **kwargs,
    ) -> int:
        partition_column = kwargs.get("partition_column")
        partition_value = kwargs.get("partition_value")
        if not partition_column or not partition_value:
            raise ValueError(
                "partition_column and partition_value are required for partition_overwrite strategy"
            )
        on_cluster = f" ON CLUSTER `{cluster}`" if cluster else ""
        client.execute(
            f"ALTER TABLE {full_table}{on_cluster} DROP PARTITION '{partition_value}'"
        )
        return self._write_insert(client, df, full_table, **kwargs)

    def _ensure_table_exists(
        self,
        client,
        df: pd.DataFrame,
        table: str,
        database: str,
        cluster: str | None,
    ) -> None:
        result = client.execute(
            "SELECT count() FROM system.tables WHERE database = %(db)s AND name = %(name)s",
            {"db": database, "name": table},
        )
        if result[0][0] == 0:
            ddl = self._generate_ddl(df, table, database, cluster)
            client.execute(ddl)
            logger.info("Created ClickHouse table %s.%s", database, table)

    def _generate_ddl(
        self, df: pd.DataFrame, table: str, database: str, cluster: str | None = None
    ) -> str:
        columns = []
        for col in df.columns:
            dtype = str(df[col].dtype)
            ch_type = _PANDAS_TO_CH.get(dtype, "String")
            columns.append(f"`{col}` {ch_type}")
        col_defs = ", ".join(columns)
        order_by = self.config.get("order_by", list(df.columns)[:1])
        order_clause = ", ".join(f"`{c}`" for c in order_by)
        on_cluster = f" ON CLUSTER `{cluster}`" if cluster else ""
        return (
            f"CREATE TABLE IF NOT EXISTS `{database}`.`{table}`{on_cluster} "
            f"({col_defs}) "
            f"ENGINE = MergeTree() "
            f"ORDER BY ({order_clause})"
        )
