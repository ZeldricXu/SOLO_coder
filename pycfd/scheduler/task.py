import numpy as np
import time
import uuid
import traceback
from dataclasses import dataclass, field
from enum import Enum
from typing import Callable, Dict, Any, Optional, List
from datetime import datetime

class TaskStatus(Enum):
    PENDING = 'pending'
    RUNNING = 'running'
    COMPLETED = 'completed'
    FAILED = 'failed'
    CANCELLED = 'cancelled'
    PAUSED = 'paused'

class TaskPriority(Enum):
    LOW = 10
    NORMAL = 20
    HIGH = 30
    CRITICAL = 40

@dataclass
class TaskResult:
    task_id: str
    name: str
    status: TaskStatus
    start_time: Optional[float] = None
    end_time: Optional[float] = None
    duration: Optional[float] = None
    result: Any = None
    error: Optional[str] = None
    traceback: Optional[str] = None
    checkpoint_path: Optional[str] = None
    metadata: Dict[str, Any] = field(default_factory=dict)

    @property
    def is_success(self) -> bool:
        return self.status == TaskStatus.COMPLETED

    def to_dict(self) -> Dict[str, Any]:
        return {
            'task_id': self.task_id,
            'name': self.name,
            'status': self.status.value,
            'start_time': self.start_time,
            'end_time': self.end_time,
            'duration': self.duration,
            'result': self.result,
            'error': self.error,
            'traceback': self.traceback,
            'checkpoint_path': self.checkpoint_path,
            'metadata': self.metadata
        }

    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> 'TaskResult':
        return cls(
            task_id=data['task_id'],
            name=data['name'],
            status=TaskStatus(data['status']),
            start_time=data.get('start_time'),
            end_time=data.get('end_time'),
            duration=data.get('duration'),
            result=data.get('result'),
            error=data.get('error'),
            traceback=data.get('traceback'),
            checkpoint_path=data.get('checkpoint_path'),
            metadata=data.get('metadata', {})
        )

class Task:
    def __init__(self, func: Callable, args: tuple = (), kwargs: Dict[str, Any] = None,
                 name: str = None, priority: TaskPriority = TaskPriority.NORMAL,
                 dependencies: List[str] = None, max_retries: int = 0,
                 save_checkpoint: bool = False, checkpoint_interval: float = 300.0):
        self.task_id = str(uuid.uuid4())
        self.name = name or f"task_{self.task_id[:8]}"
        self.func = func
        self.args = args
        self.kwargs = kwargs or {}
        self.priority = priority
        self.dependencies = dependencies or []
        self.max_retries = max_retries
        self.retries = 0
        self.status = TaskStatus.PENDING
        self.save_checkpoint = save_checkpoint
        self.checkpoint_interval = checkpoint_interval
        self.result = None
        self.error = None
        self.start_time = None
        self.end_time = None
        self.metadata = {}

    def execute(self) -> TaskResult:
        self.status = TaskStatus.RUNNING
        self.start_time = time.time()
        try:
            result = self.func(*self.args, **self.kwargs)
            self.status = TaskStatus.COMPLETED
            self.result = result
        except Exception as e:
            self.status = TaskStatus.FAILED
            self.error = str(e)
            self.traceback = traceback.format_exc()
            if self.retries < self.max_retries:
                self.retries += 1
                self.status = TaskStatus.PENDING
                raise e
        finally:
            self.end_time = time.time()
        return TaskResult(
            task_id=self.task_id,
            name=self.name,
            status=self.status,
            start_time=self.start_time,
            end_time=self.end_time,
            duration=self.end_time - self.start_time if self.end_time else None,
            result=self.result,
            error=self.error,
            traceback=getattr(self, 'traceback', None),
            metadata=self.metadata
        )

    def __lt__(self, other: 'Task') -> bool:
        return self.priority.value > other.priority.value

    def to_dict(self) -> Dict[str, Any]:
        return {
            'task_id': self.task_id,
            'name': self.name,
            'priority': self.priority.value,
            'dependencies': self.dependencies,
            'max_retries': self.max_retries,
            'retries': self.retries,
            'status': self.status.value,
            'start_time': self.start_time,
            'end_time': self.end_time,
            'metadata': self.metadata
        }

class ParameterSweepTask(Task):
    def __init__(self, base_func: Callable, param_sets: List[Dict[str, Any]],
                 name: str = "param_sweep", **kwargs):
        self.base_func = base_func
        self.param_sets = param_sets
        self.sub_tasks = []
        for i, params in enumerate(param_sets):
            task_name = f"{name}_{i}"
            sub_task = Task(
                func=base_func,
                kwargs=params,
                name=task_name,
                **kwargs
            )
            self.sub_tasks.append(sub_task)
        super().__init__(
            func=self._aggregate_results,
            name=name,
            **kwargs
        )

    def _aggregate_results(self) -> List[TaskResult]:
        results = []
        for task in self.sub_tasks:
            if task.status == TaskStatus.COMPLETED:
                results.append(task.result)
        return results

    def get_all_tasks(self) -> List[Task]:
        return self.sub_tasks + [self]
