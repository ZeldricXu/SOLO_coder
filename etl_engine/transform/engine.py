from __future__ import annotations

import logging

import dask.dataframe as dd
import pandas as pd

from etl_engine.transform.schema_inference import infer_schema
from etl_engine.transform.sql_transform import SQLTransform
from etl_engine.transform.udf_transform import UDFTransform

logger = logging.getLogger(__name__)


class TransformEngine:
    def __init__(self, use_dask: bool = False, dask_n_workers: int = 4) -> None:
        self.use_dask = use_dask
        self.dask_n_workers = dask_n_workers
        self._sql = SQLTransform()
        self._udf = UDFTransform()

    def apply(
        self,
        df: pd.DataFrame,
        transformations: list[dict],
    ) -> pd.DataFrame:
        if not transformations:
            return df

        current = df
        if self.use_dask:
            current = self._to_dask(current)

        for i, t in enumerate(transformations):
            t_type = t.get("type")
            expression = t.get("expression")
            params = t.get("params")

            if t_type == "sql":
                if self.use_dask:
                    current = current.map_partitions(
                        lambda partition: self._sql.apply_sql(partition, expression, params),
                        meta=current._meta,
                    )
                else:
                    current = self._apply_sql(current, expression, params)
            elif t_type == "udf":
                if self.use_dask:
                    current = current.map_partitions(
                        lambda partition: self._udf.apply_udf(partition, expression, params),
                        meta=current._meta,
                    )
                else:
                    current = self._apply_udf(current, expression, params)
            else:
                raise ValueError(
                    f"Unknown transformation type '{t_type}' at index {i}. "
                    f"Supported types: 'sql', 'udf'"
                )

            logger.info("Applied transformation %d: type=%s", i, t_type)

        if self.use_dask:
            current = self._from_dask(current)

        return current

    def _apply_sql(
        self,
        df: pd.DataFrame,
        expression: str,
        params: dict | None,
    ) -> pd.DataFrame:
        return self._sql.apply_sql(df, expression, params)

    def _apply_udf(
        self,
        df: pd.DataFrame,
        expression: str,
        params: dict | None,
    ) -> pd.DataFrame:
        udf_config = expression if isinstance(expression, dict) else {"inline_code": expression}
        return self._udf.apply_udf(df, udf_config, params)

    def infer_output_schema(
        self,
        df: pd.DataFrame,
        transformations: list[dict],
    ) -> dict:
        sample = df.head(100)
        result = self.apply(sample, transformations)
        return infer_schema(result)

    def _to_dask(self, df: pd.DataFrame) -> dd.DataFrame:
        ddf = dd.from_pandas(df, npartitions=self.dask_n_workers)
        logger.debug("Converted to Dask DataFrame with %d partitions", self.dask_n_workers)
        return ddf

    def _from_dask(self, ddf: dd.DataFrame) -> pd.DataFrame:
        result = ddf.compute()
        logger.debug("Computed Dask DataFrame back to Pandas")
        return result
