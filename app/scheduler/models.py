"""
Scheduler data models.
"""

import uuid
from dataclasses import dataclass, field
from datetime import datetime
from enum import Enum
from typing import Any, Callable, Dict, List, Optional, Tuple

from app.models import RunInstance, RunPhase


class TaskStatus(str, Enum):
    PENDING = "pending"
    READY = "ready"
    RUNNING = "running"
    COMPLETED = "completed"
    FAILED = "failed"
    SKIPPED = "skipped"
    CANCELLED = "cancelled"


class ScheduleType(str, Enum):
    ONCE = "once"
    CRON = "cron"
    INTERVAL = "interval"


@dataclass
class Task:
    task_id: str
    name: str
    func: Callable
    args: Tuple[Any, ...] = ()
    kwargs: Dict[str, Any] = field(default_factory=dict)
    dependencies: List[str] = field(default_factory=list)
    retries: int = 0
    retry_delay: float = 1.0
    timeout: Optional[float] = None
    priority: int = 0
    status: TaskStatus = TaskStatus.PENDING
    result: Optional[Any] = None
    error: Optional[str] = None
    started_at: Optional[datetime] = None
    completed_at: Optional[datetime] = None
    run_instances: List[RunInstance] = field(default_factory=list)
    
    def create_run_instance(self) -> RunInstance:
        run_id = f"run_{uuid.uuid4().hex[:8]}"
        instance = RunInstance(
            run_id=run_id,
            entity_id=self.task_id,
            phase=RunPhase.PENDING,
            progress=0.0
        )
        self.run_instances.append(instance)
        return instance


@dataclass
class Schedule:
    schedule_id: str
    task_id: str
    schedule_type: ScheduleType
    cron_expression: Optional[str] = None
    interval_seconds: Optional[float] = None
    start_time: Optional[datetime] = None
    end_time: Optional[datetime] = None
    enabled: bool = True
    last_run: Optional[datetime] = None
    next_run: Optional[datetime] = None
