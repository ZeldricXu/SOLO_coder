import logging
import time

from google.cloud import bigquery
from google.cloud.bigquery import WriteDisposition

from .base import BaseWriter, WriteResult, register_writer

logger = logging.getLogger(__name__)

_PANDAS_TO_BQ = {
    "int64": "INT64",
    "int32": "INT64",
    "int16": "INT64",
    "float64": "FLOAT64",
    "float32": "FLOAT64",
    "bool": "BOOL",
    "datetime64[ns]": "TIMESTAMP",
    "object": "STRING",
}


@register_writer("bigquery")
class BigQueryWriter(BaseWriter):
    def _validate_config(self) -> None:
        if "project_id" not in self.config:
            raise ValueError("Missing required BigQuery config key: project_id")

    def _get_client(self) -> bigquery.Client:
        return bigquery.Client(
            project=self.config["project_id"],
            credentials=self.config.get("credentials"),
        )

    async def test_connection(self) -> bool:
        try:
            client = self._get_client()
            list(client.datasets(max_results=1))
            return True
        except Exception:
            logger.exception("BigQuery connection test failed")
            return False

    async def write(
        self, df, table: str, strategy: str = "insert", **kwargs
    ) -> WriteResult:
        start = time.monotonic()
        project_id = self.config["project_id"]
        dataset = kwargs.get("dataset", self.config.get("dataset", "default"))
        full_table = f"{project_id}.{dataset}.{table}"
        try:
            client = self._get_client()
            if strategy == "insert":
                result = self._write_insert(client, df, full_table, **kwargs)
            elif strategy == "upsert":
                result = self._write_upsert(client, df, full_table, table, dataset, **kwargs)
            elif strategy == "partition_overwrite":
                result = self._write_partition_overwrite(client, df, full_table, **kwargs)
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
            logger.exception("BigQuery write failed for %s", full_table)
            return WriteResult(
                rows_written=0,
                table=full_table,
                strategy=strategy,
                duration_seconds=round(duration, 3),
                success=False,
                error=str(exc),
            )

    def _write_insert(self, client, df, full_table: str, **kwargs) -> int:
        table_ref = client.get_table(full_table)
        errors = client.insert_rows_from_dataframe(
            table_ref,
            df,
            selected_fields=self._infer_schema(df),
        )
        all_errors = [e for batch in errors for e in batch]
        if all_errors:
            raise RuntimeError(f"BigQuery insert errors: {all_errors}")
        return len(df)

    def _write_upsert(
        self, client, df, full_table: str, table: str, dataset: str, **kwargs
    ) -> int:
        key_columns = kwargs.get("key_columns")
        if not key_columns:
            raise ValueError("key_columns is required for upsert strategy")
        temp_table = f"{full_table}_tmp_upsert"
        job_config = bigquery.LoadJobConfig(
            write_disposition=WriteDisposition.WRITE_TRUNCATE,
            autodetect=True,
        )
        load_job = client.load_table_from_dataframe(df, temp_table, job_config=job_config)
        load_job.result()
        try:
            columns = list(df.columns)
            col_list = ", ".join(f"T.`{c}`" for c in columns)
            update_cols = [c for c in columns if c not in key_columns]
            update_clause = ", ".join(f"`{c}` = T.`{c}`" for c in update_cols)
            key_clause = " AND ".join(f"S.`{c}` = T.`{c}`" for c in key_columns)
            insert_cols = ", ".join(f"`{c}`" for c in columns)
            insert_vals = ", ".join(f"T.`{c}`" for c in columns)
            merge_sql = (
                f"MERGE `{full_table}` S "
                f"USING `{temp_table}` T "
                f"ON {key_clause} "
                f"WHEN MATCHED THEN UPDATE SET {update_clause} "
                f"WHEN NOT MATCHED THEN INSERT ({insert_cols}) VALUES ({insert_vals})"
            )
            query_job = client.query(merge_sql)
            query_job.result()
            return len(df)
        finally:
            client.delete_table(temp_table, not_found_ok=True)

    def _write_partition_overwrite(self, client, df, full_table: str, **kwargs) -> int:
        partition_column = kwargs.get("partition_column")
        if not partition_column:
            raise ValueError("partition_column is required for partition_overwrite strategy")
        job_config = bigquery.LoadJobConfig(
            write_disposition=WriteDisposition.WRITE_TRUNCATE,
            autodetect=True,
            time_partitioning=bigquery.TimePartitioning(field=partition_column),
        )
        load_job = client.load_table_from_dataframe(df, full_table, job_config=job_config)
        load_job.result()
        return len(df)

    @staticmethod
    def _infer_schema(df):
        from google.cloud.bigquery import SchemaField

        fields = []
        for col in df.columns:
            dtype = str(df[col].dtype)
            bq_type = _PANDAS_TO_BQ.get(dtype, "STRING")
            fields.append(SchemaField(col, bq_type))
        return fields
