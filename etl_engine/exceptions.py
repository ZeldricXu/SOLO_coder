from __future__ import annotations

from typing import TYPE_CHECKING, Literal

if TYPE_CHECKING:
    from etl_engine.quality.result import ValidationResult


class ETLError(Exception):
    pass


class ConnectTimeoutError(ETLError):
    def __init__(self, source_name: str, host: str, timeout: float | None = None):
        self.source_name = source_name
        self.host = host
        self.timeout = timeout
        msg = f"Connection to source '{source_name}' at {host} timed out"
        if timeout is not None:
            msg += f" after {timeout}s"
        super().__init__(msg)


class TransformStepError(ETLError):
    def __init__(self, step_index: int, step_type: str, expression: str, cause: Exception):
        self.step_index = step_index
        self.step_type = step_type
        self.expression = expression
        self.cause = cause
        msg = (
            f"Transform step {step_index} (type={step_type}) failed: {cause}. "
            f"Expression: {expression!r}"
        )
        super().__init__(msg)


class CyclicDependencyError(ETLError):
    def __init__(self, cycle: list[str]):
        self.cycle = cycle
        pair = " -> ".join(cycle)
        if cycle:
            pair = pair + f" -> {cycle[0]}"
        super().__init__(
            f"Cyclic dependency detected: {pair}. "
            f"Tasks forming the cycle: {cycle}"
        )


class SchemaMismatchError(ETLError):
    def __init__(
        self,
        expected_columns: list[str],
        actual_columns: list[str],
        rule_column: str | None = None,
    ):
        self.expected_columns = expected_columns
        self.actual_columns = actual_columns
        self.rule_column = rule_column
        expected_set = set(expected_columns)
        actual_set = set(actual_columns)
        missing = sorted(expected_set - actual_set)
        extra = sorted(actual_set - expected_set)
        parts: list[str] = []
        if rule_column:
            parts.append(f"Rule references column '{rule_column}'")
        if missing:
            parts.append(f"missing columns: {missing}")
        if extra:
            parts.append(f"unexpected columns: {extra}")
        if not missing and not extra:
            parts.append("type mismatch detected")
        super().__init__("; ".join(parts) if parts else "schema mismatch")


class DuplicateExecutionError(ETLError):
    def __init__(self, pipeline_id: str, execution_key: str):
        self.pipeline_id = pipeline_id
        self.execution_key = execution_key
        super().__init__(
            f"Pipeline '{pipeline_id}' already has a running execution "
            f"(key={execution_key})"
        )


class PartitionRetryError(ETLError):
    def __init__(self, failed_partitions: list[str], cause: Exception | None = None):
        self.failed_partitions = failed_partitions
        self.cause = cause
        super().__init__(
            f"Failed to write partitions after retries: {failed_partitions}"
        )


class StreamingPipelineError(ETLError):
    def __init__(
        self,
        pipeline_name: str,
        topic: str,
        stage: Literal["consume", "transform", "aggregate", "sink"],
        cause: Exception | None = None,
    ):
        self.pipeline_name = pipeline_name
        self.topic = topic
        self.stage = stage
        self.cause = cause
        msg = (
            f"Streaming pipeline '{pipeline_name}' failed at {stage} stage "
            f"while processing topic '{topic}'"
        )
        if cause is not None:
            msg += f": {cause}"
        super().__init__(msg)


class WindowAggregationError(ETLError):
    def __init__(
        self,
        window_type: str,
        window_size: int,
        field: str | None = None,
        cause: Exception | None = None,
    ):
        self.window_type = window_type
        self.window_size = window_size
        self.field = field
        self.cause = cause
        msg = (
            f"Window aggregation failed for {window_type} window "
            f"(size={window_size}s)"
        )
        if field is not None:
            msg += f" on field '{field}'"
        if cause is not None:
            msg += f": {cause}"
        super().__init__(msg)


class SinkWriteError(ETLError):
    def __init__(
        self,
        sink_type: str,
        operation: str,
        record_count: int = 1,
        cause: Exception | None = None,
    ):
        self.sink_type = sink_type
        self.operation = operation
        self.record_count = record_count
        self.cause = cause
        msg = (
            f"Failed to {operation} {record_count} records "
            f"to {sink_type} sink"
        )
        if cause is not None:
            msg += f": {cause}"
        super().__init__(msg)


class DocumentQueryError(ETLError):
    def __init__(self, message: str, cause: Exception | None = None):
        self.message = message
        self.cause = cause
        super().__init__(message)


class AggregationError(ETLError):
    def __init__(self, message: str, cause: Exception | None = None):
        self.message = message
        self.cause = cause
        super().__init__(message)


class QualityCheckTimeoutError(ETLError):
    def __init__(self, checkpoint_id: str, timeout: float):
        self.checkpoint_id = checkpoint_id
        self.timeout = timeout
        super().__init__(f"Quality checkpoint '{checkpoint_id}' timed out after {timeout}s")


class OnlineValidationError(ETLError):
    def __init__(self, checkpoint_id: str, validation_result: "ValidationResult | None" = None):
        self.checkpoint_id = checkpoint_id
        self.validation_result = validation_result
        super().__init__(f"Online validation at checkpoint '{checkpoint_id}' failed - task aborted")
