from top.domain.scheduling.models import (
    ExecutionStatus,
    ExecutionPhase,
    RetryPolicy,
    ExecutionContext,
    TaskResult,
    ExecutionResult,
    utc_now,
)
from top.domain.scheduling.tasks import (
    TaskExecutor,
    TaskHandler,
)
from top.domain.scheduling.graph import (
    DependencyResolver,
    DependencyGraph,
    TaskGraph,
)
from top.domain.scheduling.workflow import (
    WorkflowRunner,
    WorkflowEngine,
)
from top.domain.scheduling.scheduler import (
    TaskScheduler,
)
from top.domain.scheduling.service import (
    get_workflow_engine,
)


def generate_id(prefix: str) -> str:
    from uuid import uuid4
    return f"{prefix}_{uuid4().hex[:12]}"


Scheduler = TaskScheduler
WorkflowRunner = WorkflowRunner


__all__ = [
    "ExecutionStatus",
    "ExecutionPhase",
    "RetryPolicy",
    "ExecutionContext",
    "TaskResult",
    "ExecutionResult",
    "TaskExecutor",
    "TaskHandler",
    "DependencyResolver",
    "DependencyGraph",
    "TaskGraph",
    "WorkflowRunner",
    "WorkflowEngine",
    "TaskScheduler",
    "Scheduler",
    "get_workflow_engine",
    "utc_now",
    "generate_id",
]
