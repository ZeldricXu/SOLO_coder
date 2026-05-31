from __future__ import annotations

import re
import uuid
from dataclasses import dataclass, field
from datetime import datetime, timedelta
from typing import Any, Dict, List, Optional
from .exceptions import (
    ValidationError,
    NotFoundError,
    TaskDisabledError,
    InvalidCronExpressionError,
    DatabaseError,
)

CRON_PATTERN = re.compile(
    r'^[^\s]+(\s+[^\s]+){4}$'
)

SECOND_CRON_PATTERN = re.compile(
    r'^[^\s]+(\s+[^\s]+){5}$'
)

@dataclass
class Task:
    id: str
    name: str
    cron_expr: str
    command: str
    parameters: Dict[str, Any] = field(default_factory=dict)
    status: str = "idle"
    next_run: Optional[datetime] = None
    last_run: Optional[datetime] = None
    enabled: bool = True
    created_at: datetime = field(default_factory=datetime.utcnow)
    updated_at: datetime = field(default_factory=datetime.utcnow)

@dataclass
class RunInstance:
    run_id: str
    entity_id: str
    phase: str = "initializing"
    progress: float = 0.0
    started_at: datetime = field(default_factory=datetime.utcnow)
    completed_at: Optional[datetime] = None
    error_detail: Optional[str] = None

class Scheduler:
    def __init__(self, db_session=None):
        self.db_session = db_session
        self._tasks: Dict[str, Task] = {}
        self._runs: Dict[str, RunInstance] = {}
        self._task_runs: Dict[str, List[RunInstance]] = {}

    def _validate_cron_expr(self, cron_expr: str, with_seconds: bool = False) -> bool:
        if not cron_expr or not isinstance(cron_expr, str):
            return False
        pattern = SECOND_CRON_PATTERN if with_seconds else CRON_PATTERN
        return bool(pattern.match(cron_expr.strip()))

    def _validate_task(self, task: Task, for_update: bool = False) -> None:
        if not for_update:
            if not task.name or not isinstance(task.name, str):
                raise ValidationError("name", "Task name is required and must be a string")
            if len(task.name) > 255:
                raise ValidationError("name", "Task name must be less than 255 characters")
            if not task.command or not isinstance(task.command, str):
                raise ValidationError("command", "Task command is required and must be a string")

        if task.cron_expr is None or not self._validate_cron_expr(task.cron_expr, with_seconds=True):
            raise InvalidCronExpressionError(str(task.cron_expr))

        if not isinstance(task.parameters, dict):
            raise ValidationError("parameters", "Parameters must be a dictionary")

        if not isinstance(task.enabled, bool):
            raise ValidationError("enabled", "Enabled must be a boolean")

    def create_task(self, task_data: Dict[str, Any]) -> Task:
        task = Task(
            id=task_data.get("id") or str(uuid.uuid4()),
            name=task_data["name"],
            cron_expr=task_data["cron_expr"],
            command=task_data["command"],
            parameters=task_data.get("parameters", {}),
            enabled=task_data.get("enabled", True),
        )

        self._validate_task(task)

        if task.id in self._tasks:
            raise ValidationError("id", f"Task with id {task.id} already exists")

        if self.db_session:
            try:
                self.db_session.add(task)
                self.db_session.commit()
            except Exception as e:
                self.db_session.rollback()
                raise DatabaseError("create task", e)

        self._tasks[task.id] = task
        self._task_runs[task.id] = []
        return task

    def get_task(self, task_id: str) -> Task:
        task = self._tasks.get(task_id)
        if not task:
            raise NotFoundError("Task", task_id)
        return task

    def list_tasks(self, status: Optional[str] = None, limit: int = 100) -> List[Task]:
        tasks = list(self._tasks.values())
        if status:
            tasks = [t for t in tasks if t.status == status]
        return sorted(tasks, key=lambda t: t.created_at, reverse=True)[:limit]

    def update_task(self, task_id: str, update_data: Dict[str, Any]) -> Task:
        task = self.get_task(task_id)

        for key, value in update_data.items():
            if hasattr(task, key):
                setattr(task, key, value)

        if "cron_expr" in update_data or "enabled" in update_data:
            self._validate_task(task, for_update=True)

        task.updated_at = datetime.utcnow()

        if self.db_session:
            try:
                self.db_session.commit()
            except Exception as e:
                self.db_session.rollback()
                raise DatabaseError("update task", e)

        return task

    def delete_task(self, task_id: str) -> None:
        task = self.get_task(task_id)

        if self.db_session:
            try:
                self.db_session.delete(task)
                self.db_session.commit()
            except Exception as e:
                self.db_session.rollback()
                raise DatabaseError("delete task", e)

        del self._tasks[task_id]
        if task_id in self._task_runs:
            del self._task_runs[task_id]

    def trigger_task(self, task_id: str) -> RunInstance:
        task = self.get_task(task_id)

        if not task.enabled:
            raise TaskDisabledError(task_id)

        run = RunInstance(
            run_id=str(uuid.uuid4()),
            entity_id=task_id,
            phase="running",
            progress=0.0,
        )

        self._runs[run.run_id] = run
        self._task_runs[task_id].append(run)

        task.last_run = datetime.utcnow()
        task.status = "running"
        task.updated_at = datetime.utcnow()

        if self.db_session:
            try:
                self.db_session.add(run)
                self.db_session.commit()
            except Exception as e:
                self.db_session.rollback()
                raise DatabaseError("trigger task", e)

        return run

    def complete_run(self, run_id: str, success: bool = True, error_detail: Optional[str] = None) -> RunInstance:
        run = self._runs.get(run_id)
        if not run:
            raise NotFoundError("RunInstance", run_id)

        run.phase = "completed" if success else "failed"
        run.progress = 1.0
        run.completed_at = datetime.utcnow()
        run.error_detail = error_detail

        task = self._tasks.get(run.entity_id)
        if task:
            task.status = "idle"
            task.updated_at = datetime.utcnow()

        if self.db_session:
            try:
                self.db_session.commit()
            except Exception as e:
                self.db_session.rollback()
                raise DatabaseError("complete run", e)

        return run

    def get_run_history(self, task_id: str, limit: int = 10) -> List[RunInstance]:
        self.get_task(task_id)
        runs = self._task_runs.get(task_id, [])
        return sorted(runs, key=lambda r: r.started_at, reverse=True)[:limit]

    def _create_task_instance(self, task_data: Dict[str, Any]) -> Task:
        return Task(
            id=task_data.get("id") or str(uuid.uuid4()),
            name=task_data["name"],
            cron_expr=task_data["cron_expr"],
            command=task_data["command"],
            parameters=task_data.get("parameters", {}),
            enabled=task_data.get("enabled", True),
        )

    def calculate_next_run(self, cron_expr: str, from_time: Optional[datetime] = None) -> datetime:
        if not self._validate_cron_expr(cron_expr, with_seconds=True):
            raise InvalidCronExpressionError(cron_expr)

        base_time = from_time or datetime.utcnow()
        return base_time + timedelta(minutes=1)
