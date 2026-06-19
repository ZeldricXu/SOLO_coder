from __future__ import annotations

import asyncio
import logging
import time
from typing import Any, Literal

import pandas as pd
from pydantic import BaseModel

from etl_engine.exceptions import OnlineValidationError, QualityCheckTimeoutError

from .result import ValidationResult
from .rules import QualityRule
from .validator import QualityValidator

logger = logging.getLogger(__name__)

LIGHTWEIGHT_RULE_TYPES = {"null_rate", "value_range"}


class CheckpointConfig(BaseModel):
    checkpoint_id: str
    position: Literal["pre_transform", "post_transform", "pre_load", "mid_batch"]
    rules: list[QualityRule]
    timeout_seconds: float = 3.0
    on_failure: Literal["abort", "alert_only"] = "alert_only"
    sample_fraction: float = 0.1
    max_sample_rows: int = 10000


class CheckpointResult(BaseModel):
    checkpoint_id: str
    passed: bool
    duration_seconds: float
    sample_rows_checked: int
    validation_result: ValidationResult | None = None
    action_taken: Literal["continued", "aborted", "alert_sent"]
    error: str | None = None


class OnlineQualityChecker:
    def __init__(self, checkpoints: list[CheckpointConfig]) -> None:
        self._checkpoints: dict[str, CheckpointConfig] = {
            cp.checkpoint_id: cp for cp in checkpoints
        }
        self._alert_manager = None

    async def run_checkpoint(
        self,
        checkpoint_id: str,
        df: pd.DataFrame,
        context: dict | None = None,
    ) -> CheckpointResult:
        start_time = time.time()
        config = self.get_checkpoint_config(checkpoint_id)

        if config is None:
            return CheckpointResult(
                checkpoint_id=checkpoint_id,
                passed=False,
                duration_seconds=time.time() - start_time,
                sample_rows_checked=0,
                action_taken="alert_sent",
                error=f"Checkpoint '{checkpoint_id}' not found",
            )

        sample_df = self._take_sample(df, config)
        sample_rows = len(sample_df)

        lightweight_rules = [
            rule for rule in config.rules
            if rule.rule_type in LIGHTWEIGHT_RULE_TYPES
        ]

        validator = QualityValidator(lightweight_rules)

        try:
            validation_result = await asyncio.wait_for(
                asyncio.to_thread(validator.validate, sample_df),
                timeout=config.timeout_seconds,
            )
        except asyncio.TimeoutError:
            timeout_error = QualityCheckTimeoutError(checkpoint_id, config.timeout_seconds)
            logger.error(str(timeout_error))

            if config.on_failure == "abort":
                raise timeout_error

            return CheckpointResult(
                checkpoint_id=checkpoint_id,
                passed=False,
                duration_seconds=time.time() - start_time,
                sample_rows_checked=sample_rows,
                action_taken="alert_sent",
                error=str(timeout_error),
            )
        except Exception as e:
            logger.exception("Quality checkpoint '%s' failed with error", checkpoint_id)

            if config.on_failure == "abort":
                raise OnlineValidationError(checkpoint_id, None) from e

            return CheckpointResult(
                checkpoint_id=checkpoint_id,
                passed=False,
                duration_seconds=time.time() - start_time,
                sample_rows_checked=sample_rows,
                action_taken="alert_sent",
                error=str(e),
            )

        duration = time.time() - start_time

        if not validation_result.passed:
            if config.on_failure == "abort":
                raise OnlineValidationError(checkpoint_id, validation_result)

            await self._send_alert(checkpoint_id, validation_result, context)
            logger.warning(
                "Quality checkpoint '%s' failed - alert sent. Passed: %d/%d rules",
                checkpoint_id,
                validation_result.passed_rules,
                validation_result.total_rules,
            )

            return CheckpointResult(
                checkpoint_id=checkpoint_id,
                passed=False,
                duration_seconds=duration,
                sample_rows_checked=sample_rows,
                validation_result=validation_result,
                action_taken="alert_sent",
            )

        logger.info(
            "Quality checkpoint '%s' passed in %.3fs - %d rows checked, %d/%d rules passed",
            checkpoint_id,
            duration,
            sample_rows,
            validation_result.passed_rules,
            validation_result.total_rules,
        )

        return CheckpointResult(
            checkpoint_id=checkpoint_id,
            passed=True,
            duration_seconds=duration,
            sample_rows_checked=sample_rows,
            validation_result=validation_result,
            action_taken="continued",
        )

    def get_checkpoint_config(self, checkpoint_id: str) -> CheckpointConfig | None:
        return self._checkpoints.get(checkpoint_id)

    def _take_sample(self, df: pd.DataFrame, config: CheckpointConfig) -> pd.DataFrame:
        total_rows = len(df)
        if total_rows <= config.max_sample_rows:
            return df.head(total_rows)

        sample_size = min(
            int(total_rows * config.sample_fraction),
            config.max_sample_rows,
        )

        return df.sample(n=sample_size, random_state=42)

    async def _send_alert(
        self,
        checkpoint_id: str,
        validation_result: ValidationResult,
        context: dict | None,
    ) -> None:
        if self._alert_manager is None:
            return

        from etl_engine.alerts.channels import Alert

        context = context or {}
        alert = Alert(
            alert_type="quality_check_failed",
            severity="warning",
            pipeline_name=context.get("pipeline_name", "unknown"),
            task_name=context.get("task_name", "unknown"),
            message=f"Quality checkpoint '{checkpoint_id}' failed",
            details={
                "checkpoint_id": checkpoint_id,
                "passed_rules": validation_result.passed_rules,
                "failed_rules": validation_result.failed_rules,
                "total_rules": validation_result.total_rules,
                "rule_results": [r.model_dump() for r in validation_result.rule_results],
            },
        )

        try:
            await self._alert_manager.notify(alert)
        except Exception:
            logger.exception("Failed to send alert for checkpoint '%s'", checkpoint_id)

    def set_alert_manager(self, alert_manager: Any) -> None:
        self._alert_manager = alert_manager
