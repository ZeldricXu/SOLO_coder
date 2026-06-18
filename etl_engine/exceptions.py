from __future__ import annotations


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
