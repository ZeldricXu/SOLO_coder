from __future__ import annotations

import asyncio
import logging
from typing import TYPE_CHECKING

import dask.dataframe as dd
import pandas as pd

from etl_engine.exceptions import OnlineValidationError, TransformStepError
from etl_engine.transform.schema_inference import infer_schema
from etl_engine.transform.sql_transform import SQLTransform
from etl_engine.transform.udf_transform import UDFTransform

if TYPE_CHECKING:
    from etl_engine.quality.online_checkpoint import OnlineQualityChecker

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
        online_checker: "OnlineQualityChecker | None" = None,
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
            expr_str = str(expression) if expression is not None else ""

            try:
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
                elif t_type == "quality_checkpoint":
                    if online_checker is None:
                        logger.warning(
                            "Quality checkpoint step found at index %d but no online_checker provided, skipping",
                            i,
                        )
                    else:
                        config = t.get("config", {})
                        checkpoint_id = config.get("checkpoint_id")

                        if checkpoint_id is None:
                            logger.warning(
                                "Quality checkpoint at index %d has no checkpoint_id, skipping",
                                i,
                            )
                        else:
                            checkpoint_config = online_checker.get_checkpoint_config(checkpoint_id)

                            if self.use_dask:
                                current_df = current.compute()
                            else:
                                current_df = current

                            result = asyncio.run(
                                online_checker.run_checkpoint(checkpoint_id, current_df)
                            )

                            if not result.passed and checkpoint_config and checkpoint_config.on_failure == "abort":
                                raise OnlineValidationError(checkpoint_id, result.validation_result)

                            if self.use_dask:
                                current = dd.from_pandas(current_df, npartitions=self.dask_n_workers)

                            logger.info(
                                "Quality checkpoint '%s' completed: passed=%s, action=%s, duration=%.3fs",
                                checkpoint_id,
                                result.passed,
                                result.action_taken,
                                result.duration_seconds,
                            )
                else:
                    raise ValueError(
                        f"Unknown transformation type '{t_type}' at index {i}. "
                        f"Supported types: 'sql', 'udf', 'quality_checkpoint'"
                    )
            except Exception as e:
                raise TransformStepError(
                    step_index=i,
                    step_type=t_type or "unknown",
                    expression=expr_str,
                    cause=e,
                ) from e

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
        result = self.apply(sample, transformations, online_checker=None)
        return infer_schema(result)

    def _to_dask(self, df: pd.DataFrame) -> dd.DataFrame:
        ddf = dd.from_pandas(df, npartitions=self.dask_n_workers)
        logger.debug("Converted to Dask DataFrame with %d partitions", self.dask_n_workers)
        return ddf

    def _from_dask(self, ddf: dd.DataFrame) -> pd.DataFrame:
        result = ddf.compute()
        logger.debug("Computed Dask DataFrame back to Pandas")
        return result
