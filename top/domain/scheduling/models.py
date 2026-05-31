from __future__ import annotations

from datetime import datetime, timezone
from dataclasses import dataclass, field
from enum import Enum
from typing import Any, Dict, List, Optional


def utc_now() -> datetime:
    return datetime.now(timezone.utc)


class ExecutionStatus(str, Enum):
    PENDING = "pending"
    RUNNING = "running"
    SUCCESS = "success"
    FAILED = "failed"
    SKIPPED = "skipped"
    TIMEOUT = "timeout"
    CANCELLED = "cancelled"


class ExecutionPhase(str, Enum):
    INITIALIZING = "initializing"
    VALIDATING = "validating"
    EXECUTING = "executing"
    FINALIZING = "finalizing"
    COMPLETED = "completed"
    ROLLBACK = "rollback"


@dataclass(frozen=True)
class RetryPolicy:
    max_retries: int = 3
    initial_delay_ms: int = 1000
    backoff_factor: float = 2.0
    max_delay_ms: int = 30000
    retry_on_exceptions: tuple = (Exception,)

    @classmethod
    def default(cls) -> "RetryPolicy":
        return cls()

    def is_retryable(self, exception: Exception) -> bool:
        return isinstance(exception, self.retry_on_exceptions)


@dataclass
class ExecutionContext:
    run_id: str
    trace_id: str
    workflow_id: str
    task_id: str
    phase: ExecutionPhase = ExecutionPhase.INITIALIZING
    status: ExecutionStatus = ExecutionStatus.PENDING
    context_data: Dict[str, Any] = field(default_factory=dict)
    started_at: datetime = field(default_factory=utc_now)
    completed_at: Optional[datetime] = None
    error: Optional[str] = None
    attempt: int = 0
    max_attempts: int = 1

    def update(
        self,
        phase: Optional[ExecutionPhase] = None,
        status: Optional[ExecutionStatus] = None,
        error: Optional[str] = None,
        **data,
    ) -> None:
        if phase:
            self.phase = phase
        if status:
            self.status = status
        if error:
            self.error = error
        if status in (ExecutionStatus.SUCCESS, ExecutionStatus.FAILED, ExecutionStatus.CANCELLED):
            self.completed_at = utc_now()
        self.context_data.update(data)

    def set(self, key: str, value: Any) -> None:
        self.context_data[key] = value

    def get(self, key: str, default: Any = None) -> Any:
        return self.context_data.get(key, default)


@dataclass
class TaskResult:
    task_id: str
    status: ExecutionStatus
    result: Optional[Any] = None
    error: Optional[str] = None
    started_at: datetime = field(default_factory=utc_now)
    completed_at: Optional[datetime] = None
    duration_ms: float = 0.0
    attempt: int = 1

    @property
    def is_success(self) -> bool:
        return self.status == ExecutionStatus.SUCCESS

    @property
    def is_failed(self) -> bool:
        return self.status in (
            ExecutionStatus.FAILED,
            ExecutionStatus.TIMEOUT,
            ExecutionStatus.CANCELLED,
        )

    @property
    def is_skipped(self) -> bool:
        return self.status == ExecutionStatus.SKIPPED


@dataclass
class ExecutionResult:
    run_id: str
    workflow_id: str
    status: ExecutionStatus
    task_results: List[TaskResult] = field(default_factory=list)
    started_at: datetime = field(default_factory=utc_now)
    completed_at: Optional[datetime] = None
    total_duration_ms: float = 0.0
    error: Optional[str] = None

    def failed_tasks(self) -> List[TaskResult]:
        return [t for t in self.task_results if t.is_failed]

    def successful_tasks(self) -> List[TaskResult]:
        return [t for t in self.task_results if t.is_success]

    def get_task_result(self, task_id: str) -> Optional[TaskResult]:
        for t in self.task_results:
            if t.task_id == task_id:
                return t
        return None

    @property
    def is_success(self) -> bool:
        return self.status == ExecutionStatus.SUCCESS

    @property
    def is_partial_failure(self) -> bool:
        return (
            self.status == ExecutionStatus.FAILED
            and len(self.successful_tasks()) > 0
        )
