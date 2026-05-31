"""Task manager for scheduler module."""
from __future__ import annotations

import asyncio
from dataclasses import dataclass, field
from datetime import datetime, timedelta
from enum import Enum
from typing import Any, Callable, Dict, List, Optional
from uuid import UUID, uuid4

from ...domain.errors.common import ValidationError
from ...infrastructure.logging.structured_logger import LogManager


class TaskStatus(Enum):
    PENDING = "pending"
    SCHEDULED = "scheduled"
    RUNNING = "running"
    COMPLETED = "completed"
    FAILED = "failed"
    CANCELLED = "cancelled"
    SKIPPED = "skipped"


@dataclass
class Task:
    id: UUID = field(default_factory=uuid4)
    name: str
    func: Callable
    args: tuple = field(default_factory=tuple)
    kwargs: Dict[str, Any] = field(default_factory=dict)
    dependencies: List[UUID] = field(default_factory=list)
    status: TaskStatus = TaskStatus.PENDING
    priority: int = 0
    retries: int = 0
    max_retries: int = 3
    timeout: Optional[float] = None
    scheduled_at: Optional[datetime] = None
    started_at: Optional[datetime] = None
    completed_at: Optional[datetime] = None
    error_message: Optional[str] = None
    result: Any = None
    retry_delay: float = 1.0

    def can_run(self, completed_tasks: Dict[UUID, Any]) -> bool:
        return all(dep_id in completed_tasks for dep_id in self.dependencies)


class TaskManager:
    def __init__(self) -> None:
        self._tasks: Dict[UUID, Task] = {}
        self._task_results: Dict[UUID, Any] = {}
        self._logger = LogManager().get_logger(__name__)

    def create_task(
        self,
        name: str,
        func: Callable,
        args: Optional[tuple] = None,
        kwargs: Optional[Dict[str, Any]] = None,
        dependencies: Optional[List[UUID]] = None,
        priority: int = 0,
        max_retries: int = 3,
        timeout: Optional[float] = None,
        retry_delay: float = 1.0,
    ) -> Task:
        task = Task(
            name=name,
            func=func,
            args=args or (),
            kwargs=kwargs or {},
            dependencies=dependencies or [],
            priority=priority,
            max_retries=max_retries,
            timeout=timeout,
            retry_delay=retry_delay,
        )

        self._tasks[task.id] = task
        self._logger.info(
            f"Created task: {name}",
            task_id=str(task.id),
            dependencies_count=len(task.dependencies),
        )

        return task

    def add_dependency(self, task_id: UUID, dependency_id: UUID) -> None:
        task = self._tasks.get(task_id)
        if not task:
            raise ValidationError(
                message=f"Task not found: {task_id}",
                suggestion="Check that the task ID is correct.",
            )

        if dependency_id not in self._tasks:
            raise ValidationError(
                message=f"Dependency task not found: {dependency_id}",
                suggestion="Check that the dependency task ID is correct.",
            )

        if dependency_id == task_id:
            raise ValidationError(
                message="Task cannot depend on itself",
                suggestion="Remove the self-dependency.",
            )

        if dependency_id not in task.dependencies:
            task.dependencies.append(dependency_id)
            self._logger.info(
                f"Added dependency to task {task.name}",
                task_id=str(task_id),
                dependency_id=str(dependency_id),
            )

    def remove_dependency(self, task_id: UUID, dependency_id: UUID) -> None:
        task = self._tasks.get(task_id)
        if not task:
            raise ValidationError(
                message=f"Task not found: {task_id}",
                suggestion="Check that the task ID is correct.",
            )

        if dependency_id in task.dependencies:
            task.dependencies.remove(dependency_id)
            self._logger.info(
                f"Removed dependency from task {task.name}",
                task_id=str(task_id),
                dependency_id=str(dependency_id),
            )

    def get_task(self, task_id: UUID) -> Optional[Task]:
        return self._tasks.get(task_id)

    def list_tasks(self, status: Optional[TaskStatus] = None) -> List[Task]:
        tasks = list(self._tasks.values())
        if status:
            tasks = [t for t in tasks if t.status == status]
        return tasks

    def get_ready_tasks(self) -> List[Task]:
        ready = [
            task
            for task in self._tasks.values()
            if task.status == TaskStatus.PENDING and task.can_run(self._task_results)
        ]
        return sorted(ready, key=lambda t: (-t.priority, t.scheduled_at or datetime.max))

    def update_task_status(self, task_id: UUID, status: TaskStatus) -> None:
        task = self._tasks.get(task_id)
        if not task:
            raise ValidationError(
                message=f"Task not found: {task_id}",
                suggestion="Check that the task ID is correct.",
            )

        old_status = task.status
        task.status = status

        if status == TaskStatus.RUNNING:
            task.started_at = datetime.utcnow()
        elif status in [TaskStatus.COMPLETED, TaskStatus.FAILED, TaskStatus.CANCELLED]:
            task.completed_at = datetime.utcnow()

        self._logger.info(
            f"Task {task.name} status changed: {old_status.value} -> {status.value}",
            task_id=str(task_id),
        )

    def set_task_result(self, task_id: UUID, result: Any) -> None:
        self._task_results[task_id] = result
        task = self._tasks.get(task_id)
        if task:
            task.result = result

    def set_task_error(self, task_id: UUID, error_message: str) -> None:
        task = self._tasks.get(task_id)
        if task:
            task.error_message = error_message

    def get_task_result(self, task_id: UUID) -> Optional[Any]:
        return self._task_results.get(task_id)

    def get_all_results(self) -> Dict[UUID, Any]:
        return self._task_results.copy()

    def get_failed_tasks(self) -> List[Task]:
        return [t for t in self._tasks.values() if t.status == TaskStatus.FAILED]

    def get_completed_tasks(self) -> List[Task]:
        return [t for t in self._tasks.values() if t.status == TaskStatus.COMPLETED]

    def cancel_task(self, task_id: UUID) -> bool:
        task = self._tasks.get(task_id)
        if not task or task.status in [TaskStatus.COMPLETED, TaskStatus.FAILED, TaskStatus.CANCELLED]:
            return False

        self.update_task_status(task_id, TaskStatus.CANCELLED)
        return True

    def retry_task(self, task_id: UUID) -> bool:
        task = self._tasks.get(task_id)
        if not task or task.status != TaskStatus.FAILED:
            return False

        if task.retries >= task.max_retries:
            return False

        task.retries += 1
        task.status = TaskStatus.PENDING
        task.error_message = None
        task.started_at = None
        task.completed_at = None

        self._logger.info(
            f"Retrying task {task.name}",
            task_id=str(task_id),
            retry_count=task.retries,
            max_retries=task.max_retries,
        )

        return True

    def clear_completed(self) -> int:
        completed_ids = [
            t.id for t in self._tasks.values()
            if t.status in [TaskStatus.COMPLETED, TaskStatus.FAILED, TaskStatus.CANCELLED, TaskStatus.SKIPPED]
        ]

        for task_id in completed_ids:
            del self._tasks[task_id]
            if task_id in self._task_results:
                del self._task_results[task_id]

        return len(completed_ids)

    def get_task_graph(self) -> Dict[str, Any]:
        nodes = []
        edges = []

        for task in self._tasks.values():
            nodes.append({
                "id": str(task.id),
                "name": task.name,
                "status": task.status.value,
                "priority": task.priority,
            })

            for dep_id in task.dependencies:
                edges.append({
                    "from": str(dep_id),
                    "to": str(task.id),
                })

        return {"nodes": nodes, "edges": edges}

    def get_critical_path(self) -> List[Task]:
        task_durations: Dict[UUID, float] = {}

        for task in self._tasks.values():
            if task.started_at and task.completed_at:
                duration = (task.completed_at - task.started_at).total_seconds()
            else:
                duration = 0
            task_durations[task.id] = duration

        topo_order = self._topological_sort()

        longest_paths: Dict[UUID, float] = {}
        predecessors: Dict[UUID, Optional[UUID]] = {}

        for task_id in topo_order:
            task = self._tasks[task_id]
            max_dep_duration = 0
            best_pred = None

            for dep_id in task.dependencies:
                if dep_id in longest_paths and longest_paths[dep_id] > max_dep_duration:
                    max_dep_duration = longest_paths[dep_id]
                    best_pred = dep_id

            longest_paths[task_id] = max_dep_duration + task_durations[task_id]
            predecessors[task_id] = best_pred

        end_task_id = max(longest_paths, key=longest_paths.get) if longest_paths else None

        if not end_task_id:
            return []

        critical_path = []
        current_id: Optional[UUID] = end_task_id
        while current_id:
            critical_path.append(self._tasks[current_id])
            current_id = predecessors[current_id]

        return list(reversed(critical_path))

    def _topological_sort(self) -> List[UUID]:
        in_degree = {task_id: 0 for task_id in self._tasks}
        adjacency = {task_id: [] for task_id in self._tasks}

        for task_id, task in self._tasks.items():
            for dep_id in task.dependencies:
                in_degree[task_id] += 1
                adjacency[dep_id].append(task_id)

        queue = [task_id for task_id, deg in in_degree.items() if deg == 0]
        result = []

        while queue:
            task_id = queue.pop(0)
            result.append(task_id)

            for next_id in adjacency[task_id]:
                in_degree[next_id] -= 1
                if in_degree[next_id] == 0:
                    queue.append(next_id)

        if len(result) != len(self._tasks):
            raise ValidationError(
                message="Cycle detected in task dependencies",
                suggestion="Check task dependencies for cycles.",
            )

        return result
