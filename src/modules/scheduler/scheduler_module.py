"""Scheduler module for dependency task orchestration."""
from __future__ import annotations

import asyncio
from dataclasses import dataclass, field
from datetime import datetime, timedelta
from typing import Any, AsyncGenerator, Callable, Dict, List, Optional
from uuid import UUID, uuid4

from ...domain.errors.common import ValidationError
from ...domain.models.common import EventMessage, ProcessingResult, ProcessingStatus
from ...infrastructure.logging.structured_logger import LogManager
from ...infrastructure.config.settings import Settings
from .task_manager import Task, TaskManager, TaskStatus
from .dependency_solver import DependencySolver, SolverResult


class SchedulerModule:
    def __init__(self, settings: Optional[Settings] = None) -> None:
        self._settings = settings or get_default_settings()
        self._task_manager = TaskManager()
        self._dependency_solver = DependencySolver()
        self._running = False
        self._max_parallel = 4
        self._logger = LogManager().get_logger(__name__)

    @property
    def task_manager(self) -> TaskManager:
        return self._task_manager

    @property
    def dependency_solver(self) -> DependencySolver:
        return self._dependency_solver

    def set_max_parallel(self, max_parallel: int) -> None:
        if max_parallel < 1:
            raise ValidationError(
                message="max_parallel must be at least 1",
                suggestion="Set max_parallel to a value >= 1.",
            )
        self._max_parallel = max_parallel

    async def process_event(self, event: EventMessage) -> ProcessingResult:
        result = ProcessingResult(
            started_at=datetime.utcnow(),
            status=ProcessingStatus.PROCESSING,
        )

        try:
            event_type = event.event_type
            payload = event.payload

            if event_type == "task.create":
                create_result = self._handle_task_create(payload)
                result.results = [create_result]
                result.status = ProcessingStatus.SUCCESS
                result.message = "Task created successfully"

            elif event_type == "task.run":
                run_result = await self._handle_task_run(payload)
                result.results = [run_result]
                result.status = ProcessingStatus.SUCCESS
                result.message = "Task executed successfully"

            elif event_type == "task.schedule":
                schedule_result = self._handle_task_schedule(payload)
                result.results = [schedule_result]
                result.status = ProcessingStatus.SUCCESS
                result.message = "Task scheduled successfully"

            elif event_type == "tasks.run_all":
                run_all_result = await self._handle_run_all(payload)
                result.results = [run_all_result]
                result.status = ProcessingStatus.SUCCESS
                result.message = "All tasks completed"

            elif event_type == "task.status":
                status_result = self._handle_get_status(payload)
                result.results = [status_result]
                result.status = ProcessingStatus.SUCCESS
                result.message = "Task status retrieved"

            elif event_type == "task.cancel":
                cancel_result = self._handle_task_cancel(payload)
                result.results = [cancel_result]
                result.status = ProcessingStatus.SUCCESS
                result.message = "Task cancelled successfully"

            elif event_type == "task.retry":
                retry_result = self._handle_task_retry(payload)
                result.results = [retry_result]
                result.status = ProcessingStatus.SUCCESS
                result.message = "Task retry initiated"

            elif event_type == "scheduler.solve":
                solve_result = self._handle_solve(payload)
                result.results = [solve_result]
                result.status = ProcessingStatus.SUCCESS
                result.message = "Dependency solving completed"

            else:
                raise ValidationError(
                    message=f"Unknown event type: {event_type}",
                    suggestion="Check the event type and try again.",
                )

        except Exception as e:
            result.status = ProcessingStatus.FAILED
            result.message = f"Scheduler event processing failed: {str(e)}"
            result.errors.append({"error": str(e)})

            self._logger.error(
                "Scheduler event processing failed",
                event_type=event.event_type,
                error=str(e),
            )

        result.completed_at = datetime.utcnow()
        result.calculate_duration()

        return result

    def _handle_task_create(self, payload: Dict[str, Any]) -> Dict[str, Any]:
        name = payload.get("name")
        func_ref = payload.get("func")
        args = payload.get("args")
        kwargs = payload.get("kwargs")
        dependencies = payload.get("dependencies")
        priority = payload.get("priority", 0)
        max_retries = payload.get("max_retries", 3)
        timeout = payload.get("timeout")
        retry_delay = payload.get("retry_delay", 1.0)

        if not name or not func_ref:
            raise ValidationError(
                message="Task name and function are required",
                suggestion="Provide 'name' and 'func' in the payload.",
            )

        if not callable(func_ref):
            raise ValidationError(
                message="'func' must be callable",
                suggestion="Provide a callable function.",
            )

        dep_ids = [UUID(d) for d in (dependencies or [])]

        task = self._task_manager.create_task(
            name=name,
            func=func_ref,
            args=args,
            kwargs=kwargs,
            dependencies=dep_ids,
            priority=priority,
            max_retries=max_retries,
            timeout=timeout,
            retry_delay=retry_delay,
        )

        return {
            "task_id": str(task.id),
            "name": task.name,
            "status": task.status.value,
            "dependencies_count": len(task.dependencies),
        }

    async def _handle_task_run(self, payload: Dict[str, Any]) -> Dict[str, Any]:
        task_id = payload.get("task_id")
        if not task_id:
            raise ValidationError(
                message="Task ID is required",
                suggestion="Provide task_id in the payload.",
            )

        task_uuid = UUID(task_id)
        task = self._task_manager.get_task(task_uuid)
        if not task:
            raise ValidationError(
                message=f"Task not found: {task_id}",
                suggestion="Check that the task ID is correct.",
            )

        result = await self._execute_task(task)
        return result

    def _handle_task_schedule(self, payload: Dict[str, Any]) -> Dict[str, Any]:
        task_id = payload.get("task_id")
        schedule_time = payload.get("schedule_time")

        if not task_id:
            raise ValidationError(
                message="Task ID is required",
                suggestion="Provide task_id in the payload.",
            )

        task_uuid = UUID(task_id)
        task = self._task_manager.get_task(task_uuid)
        if not task:
            raise ValidationError(
                message=f"Task not found: {task_id}",
                suggestion="Check that the task ID is correct.",
            )

        if isinstance(schedule_time, str):
            schedule_time = datetime.fromisoformat(schedule_time)

        task.scheduled_at = schedule_time
        self._task_manager.update_task_status(task_uuid, TaskStatus.SCHEDULED)

        return {
            "task_id": task_id,
            "scheduled_at": schedule_time.isoformat() if schedule_time else None,
            "status": task.status.value,
        }

    async def _handle_run_all(self, payload: Dict[str, Any]) -> Dict[str, Any]:
        stop_on_failure = payload.get("stop_on_failure", True)
        max_parallel = payload.get("max_parallel", self._max_parallel)

        valid, errors = self._dependency_solver.validate_dependencies(self._task_manager)
        if not valid:
            raise ValidationError(
                message="Dependency validation failed",
                suggestion="\n".join(errors),
            )

        solver_result = self._dependency_solver.solve(self._task_manager)
        if solver_result.has_cycle:
            cycle_names = [
                self._task_manager.get_task(tid).name for tid in solver_result.cycle_path
            ]
            raise ValidationError(
                message=f"Cycle detected in task dependencies",
                suggestion=f"Cycle: {' -> '.join(cycle_names)}",
            )

        results = await self._execute_parallel(solver_result, stop_on_failure, max_parallel)

        return {
            "total_tasks": len(self._task_manager.list_tasks()),
            "completed_tasks": len(self._task_manager.get_completed_tasks()),
            "failed_tasks": len(self._task_manager.get_failed_tasks()),
            "results": results,
        }

    def _handle_get_status(self, payload: Dict[str, Any]) -> Dict[str, Any]:
        task_id = payload.get("task_id")

        if task_id:
            task = self._task_manager.get_task(UUID(task_id))
            if not task:
                raise ValidationError(
                    message=f"Task not found: {task_id}",
                    suggestion="Check that the task ID is correct.",
                )
            return self._task_to_dict(task)

        return {
            "total_tasks": len(self._task_manager.list_tasks()),
            "pending": len(self._task_manager.list_tasks(TaskStatus.PENDING)),
            "running": len(self._task_manager.list_tasks(TaskStatus.RUNNING)),
            "completed": len(self._task_manager.get_completed_tasks()),
            "failed": len(self._task_manager.get_failed_tasks()),
            "cancelled": len(self._task_manager.list_tasks(TaskStatus.CANCELLED)),
            "tasks": [self._task_to_dict(t) for t in self._task_manager.list_tasks()],
        }

    def _handle_task_cancel(self, payload: Dict[str, Any]) -> Dict[str, Any]:
        task_id = payload.get("task_id")
        if not task_id:
            raise ValidationError(
                message="Task ID is required",
                suggestion="Provide task_id in the payload.",
            )

        task_uuid = UUID(task_id)
        success = self._task_manager.cancel_task(task_uuid)

        return {
            "task_id": task_id,
            "cancelled": success,
            "status": self._task_manager.get_task(task_uuid).status.value if self._task_manager.get_task(task_uuid) else None,
        }

    def _handle_task_retry(self, payload: Dict[str, Any]) -> Dict[str, Any]:
        task_id = payload.get("task_id")
        if not task_id:
            raise ValidationError(
                message="Task ID is required",
                suggestion="Provide task_id in the payload.",
            )

        task_uuid = UUID(task_id)
        success = self._task_manager.retry_task(task_uuid)

        return {
            "task_id": task_id,
            "retried": success,
            "retry_count": self._task_manager.get_task(task_uuid).retries if self._task_manager.get_task(task_uuid) else 0,
            "max_retries": self._task_manager.get_task(task_uuid).max_retries if self._task_manager.get_task(task_uuid) else 0,
            "status": self._task_manager.get_task(task_uuid).status.value if self._task_manager.get_task(task_uuid) else None,
        }

    def _handle_solve(self, payload: Dict[str, Any]) -> Dict[str, Any]:
        solver_result = self._dependency_solver.solve(self._task_manager)

        return {
            "has_cycle": solver_result.has_cycle,
            "cycle_path": [str(tid) for tid in solver_result.cycle_path],
            "execution_order": [str(tid) for tid in solver_result.execution_order],
            "parallel_groups": [[str(tid) for tid in group] for group in solver_result.parallel_groups],
            "total_groups": len(solver_result.parallel_groups),
        }

    async def _execute_task(self, task: Task) -> Dict[str, Any]:
        self._task_manager.update_task_status(task.id, TaskStatus.RUNNING)

        try:
            if asyncio.iscoroutinefunction(task.func):
                if task.timeout:
                    result = await asyncio.wait_for(
                        task.func(*task.args, **task.kwargs),
                        timeout=task.timeout,
                    )
                else:
                    result = await task.func(*task.args, **task.kwargs)
            else:
                if task.timeout:
                    result = await asyncio.wait_for(
                        asyncio.to_thread(task.func, *task.args, **task.kwargs),
                        timeout=task.timeout,
                    )
                else:
                    result = task.func(*task.args, **task.kwargs)

            self._task_manager.set_task_result(task.id, result)
            self._task_manager.update_task_status(task.id, TaskStatus.COMPLETED)

            return {
                "task_id": str(task.id),
                "name": task.name,
                "status": TaskStatus.COMPLETED.value,
                "result": result,
                "duration_ms": (task.completed_at - task.started_at).total_seconds() * 1000 if task.started_at and task.completed_at else None,
            }

        except asyncio.TimeoutError as e:
            error_msg = f"Task timed out after {task.timeout} seconds"
            self._task_manager.set_task_error(task.id, error_msg)

            if task.retries < task.max_retries:
                await asyncio.sleep(task.retry_delay)
                self._task_manager.retry_task(task.id)
                return await self._execute_task(task)

            self._task_manager.update_task_status(task.id, TaskStatus.FAILED)

            return {
                "task_id": str(task.id),
                "name": task.name,
                "status": TaskStatus.FAILED.value,
                "error": error_msg,
                "retries": task.retries,
            }

        except Exception as e:
            error_msg = str(e)
            self._task_manager.set_task_error(task.id, error_msg)

            if task.retries < task.max_retries:
                await asyncio.sleep(task.retry_delay)
                self._task_manager.retry_task(task.id)
                return await self._execute_task(task)

            self._task_manager.update_task_status(task.id, TaskStatus.FAILED)

            return {
                "task_id": str(task.id),
                "name": task.name,
                "status": TaskStatus.FAILED.value,
                "error": error_msg,
                "retries": task.retries,
            }

    async def _execute_parallel(
        self,
        solver_result: SolverResult,
        stop_on_failure: bool,
        max_parallel: int,
    ) -> List[Dict[str, Any]]:
        all_results: List[Dict[str, Any]] = []

        for group in solver_result.parallel_groups:
            group_tasks = [self._task_manager.get_task(tid) for tid in group]
            group_results = []

            for i in range(0, len(group_tasks), max_parallel):
                batch = group_tasks[i:i + max_parallel]
                batch_results = await asyncio.gather(
                    *[self._execute_task(task) for task in batch],
                    return_exceptions=True,
                )

                for result in batch_results:
                    if isinstance(result, Exception):
                        result = {
                            "error": str(result),
                            "status": TaskStatus.FAILED.value,
                        }
                    group_results.append(result)
                    all_results.append(result)

                    if stop_on_failure and result.get("status") == TaskStatus.FAILED.value:
                        failed_tasks = [t for t in group_tasks if t.status == TaskStatus.PENDING]
                        for t in failed_tasks:
                            self._task_manager.update_task_status(t.id, TaskStatus.SKIPPED)
                            all_results.append({
                                "task_id": str(t.id),
                                "name": t.name,
                                "status": TaskStatus.SKIPPED.value,
                            })
                        return all_results

        return all_results

    async def run_task(self, task_id: str) -> Dict[str, Any]:
        event = EventMessage(
            event_type="task.run",
            payload={"task_id": task_id},
            source="scheduler",
        )
        result = await self.process_event(event)
        return result.results[0] if result.results else {}

    async def run_all(self, stop_on_failure: bool = True, max_parallel: Optional[int] = None) -> Dict[str, Any]:
        event = EventMessage(
            event_type="tasks.run_all",
            payload={
                "stop_on_failure": stop_on_failure,
                "max_parallel": max_parallel or self._max_parallel,
            },
            source="scheduler",
        )
        result = await self.process_event(event)
        return result.results[0] if result.results else {}

    def create_task(
        self,
        name: str,
        func: Callable,
        **kwargs: Any,
    ) -> str:
        event = EventMessage(
            event_type="task.create",
            payload={
                "name": name,
                "func": func,
                **kwargs,
            },
            source="scheduler",
        )
        result = asyncio.run(self.process_event(event))
        return result.results[0]["task_id"] if result.results else ""

    def get_task_status(self, task_id: Optional[str] = None) -> Dict[str, Any]:
        event = EventMessage(
            event_type="task.status",
            payload={"task_id": task_id} if task_id else {},
            source="scheduler",
        )
        result = asyncio.run(self.process_event(event))
        return result.results[0] if result.results else {}

    def solve_dependencies(self) -> Dict[str, Any]:
        event = EventMessage(
            event_type="scheduler.solve",
            payload={},
            source="scheduler",
        )
        result = asyncio.run(self.process_event(event))
        return result.results[0] if result.results else {}

    async def run_with_progress(self, max_parallel: Optional[int] = None) -> AsyncGenerator[Dict[str, Any], None]:
        max_parallel = max_parallel or self._max_parallel

        valid, errors = self._dependency_solver.validate_dependencies(self._task_manager)
        if not valid:
            raise ValidationError(
                message="Dependency validation failed",
                suggestion="\n".join(errors),
            )

        solver_result = self._dependency_solver.solve(self._task_manager)
        if solver_result.has_cycle:
            raise ValidationError(
                message="Cycle detected in task dependencies",
                suggestion="Check task dependencies for cycles.",
            )

        for group in solver_result.parallel_groups:
            group_tasks = [self._task_manager.get_task(tid) for tid in group]

            for i in range(0, len(group_tasks), max_parallel):
                batch = group_tasks[i:i + max_parallel]
                batch_tasks = [asyncio.create_task(self._execute_task(task)) for task in batch]

                for task, task_obj in zip(batch_tasks, batch):
                    result = await task
                    yield {
                        "task_id": str(task_obj.id),
                        "name": task_obj.name,
                        **result,
                    }

    def get_execution_estimate(self, task_durations: Optional[Dict[str, float]] = None) -> float:
        durations = {}
        if task_durations:
            durations = {UUID(k): v for k, v in task_durations.items()}

        return self._dependency_solver.get_execution_estimate(
            self._task_manager, durations)

    def get_optimizations(self, task_durations: Optional[Dict[str, float]] = None) -> List[Dict[str, Any]]:
        durations = {}
        if task_durations:
            durations = {UUID(k): v for k, v in task_durations.items()}

        return self._dependency_solver.suggest_optimizations(
            self._task_manager, durations)

    def get_task_graph(self) -> Dict[str, Any]:
        return self._task_manager.get_task_graph()

    def get_critical_path(self) -> List[Dict[str, Any]]:
        path = self._task_manager.get_critical_path()
        return [self._task_to_dict(t) for t in path]

    def clear_completed(self) -> int:
        return self._task_manager.clear_completed()

    def _task_to_dict(self, task: Task) -> Dict[str, Any]:
        return {
            "task_id": str(task.id),
            "name": task.name,
            "status": task.status.value,
            "priority": task.priority,
            "retries": task.retries,
            "max_retries": task.max_retries,
            "timeout": task.timeout,
            "scheduled_at": task.scheduled_at.isoformat() if task.scheduled_at else None,
            "started_at": task.started_at.isoformat() if task.started_at else None,
            "completed_at": task.completed_at.isoformat() if task.completed_at else None,
            "error_message": task.error_message,
            "has_result": task.result is not None,
            "dependencies": [str(d) for d in task.dependencies],
        }
