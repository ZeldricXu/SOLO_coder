"""Dependency solver for scheduler module."""
from __future__ import annotations

from dataclasses import dataclass
from typing import Dict, List, Optional, Set, Tuple
from uuid import UUID

from ...domain.errors.common import ValidationError
from ...infrastructure.logging.structured_logger import LogManager
from .task_manager import Task, TaskManager


@dataclass
class SolverResult:
    execution_order: List[UUID]
    parallel_groups: List[List[UUID]]
    has_cycle: bool
    cycle_path: List[UUID]


class DependencySolver:
    def __init__(self) -> None:
        self._logger = LogManager().get_logger(__name__)

    def solve(self, task_manager: TaskManager) -> SolverResult:
        tasks = task_manager.list_tasks()
        return self._solve(tasks)

    def _solve(self, tasks: List[Task]) -> SolverResult:
        task_map = {t.id: t for t in tasks}

        has_cycle, cycle_path = self._detect_cycle(tasks)
        if has_cycle:
            return SolverResult(
                execution_order=[],
                parallel_groups=[],
                has_cycle=True,
                cycle_path=cycle_path,
            )

        execution_order = self._topological_sort(tasks)
        parallel_groups = self._group_parallel_tasks(tasks, execution_order)

        return SolverResult(
            execution_order=execution_order,
            parallel_groups=parallel_groups,
            has_cycle=False,
            cycle_path=[],
        )

    def _detect_cycle(self, tasks: List[Task]) -> Tuple[bool, List[UUID]]:
        WHITE, GRAY, BLACK = 0, 1, 2

        color: Dict[UUID, int] = {t.id: WHITE for t in tasks}
        parent: Dict[UUID, Optional[UUID]] = {t.id: None for t in tasks}
        cycle_start: Optional[UUID] = None
        cycle_end: Optional[UUID] = None

        def dfs(u: UUID) -> bool:
            nonlocal cycle_start, cycle_end
            color[u] = GRAY

            task = next(t for t in tasks if t.id == u)
            for v in task.dependencies:
                if color.get(v, WHITE) == GRAY:
                    cycle_start = v
                    cycle_end = u
                    return True
                if color.get(v, WHITE) == WHITE:
                    parent[v] = u
                    if dfs(v):
                        return True

            color[u] = BLACK
            return False

        for task in tasks:
            if color[task.id] == WHITE:
                if dfs(task.id):
                    break

        if cycle_start is not None and cycle_end is not None:
            cycle = []
            current: Optional[UUID] = cycle_end
            while current is not None:
                cycle.append(current)
                if current == cycle_start:
                    break
                current = parent[current]
            cycle.reverse()
            return True, cycle

        return False, []

    def _topological_sort(self, tasks: List[Task]) -> List[UUID]:
        in_degree = {t.id: len(t.dependencies) for t in tasks}
        adjacency: Dict[UUID, List[UUID]] = {t.id: [] for t in tasks}

        for task in tasks:
            for dep_id in task.dependencies:
                adjacency[dep_id].append(task.id)

        queue = sorted(
            [t.id for t in tasks if in_degree[t.id] == 0],
            key=lambda tid: (-next(t.priority for t in tasks if t.id == tid),
                            next(t.scheduled_at for t in tasks if t.id == tid) or float('inf'))
        )

        result = []

        while queue:
            queue.sort(key=lambda tid: (
                -next(t.priority for t in tasks if t.id == tid),
                next(t.scheduled_at for t in tasks if t.id == tid) or float('inf')
            ))

            task_id = queue.pop(0)
            result.append(task_id)

            for next_id in adjacency[task_id]:
                in_degree[next_id] -= 1
                if in_degree[next_id] == 0:
                    queue.append(next_id)

        return result

    def _group_parallel_tasks(self, tasks: List[Task], execution_order: List[UUID]) -> List[List[UUID]]:
        task_map = {t.id: t for t in tasks}
        completed: Set[UUID] = set()
        groups: List[List[UUID]] = []
        remaining = execution_order.copy()

        while remaining:
            current_group: List[UUID] = []
            new_remaining: List[UUID] = []

            for task_id in remaining:
                task = task_map[task_id]
                can_run = all(dep_id in completed for dep_id in task.dependencies)

                if can_run:
                    current_group.append(task_id)
                else:
                    new_remaining.append(task_id)

            if not current_group:
                break

            current_group.sort(key=lambda tid: (
                -task_map[tid].priority,
                task_map[tid].scheduled_at or float('inf')
            ))

            groups.append(current_group)
            completed.update(current_group)
            remaining = new_remaining

        return groups

    def validate_dependencies(self, task_manager: TaskManager) -> Tuple[bool, List[str]]:
        errors: List[str] = []
        tasks = task_manager.list_tasks()

        has_cycle, cycle_path = self._detect_cycle(tasks)
        if has_cycle:
            cycle_names = [
                next(t.name for t in tasks if t.id == tid)
                for tid in cycle_path
            ]
            errors.append(f"Cycle detected: {' -> '.join(cycle_names)}")

        task_ids = {t.id for t in tasks}
        for task in tasks:
            for dep_id in task.dependencies:
                if dep_id not in task_ids:
                    errors.append(
                        f"Task '{task.name}' has missing dependency: {dep_id}"
                    )

        return len(errors) == 0, errors

    def get_dependents(self, task_id: UUID, task_manager: TaskManager) -> List[UUID]:
        dependents = []
        for task in task_manager.list_tasks():
            if task_id in task.dependencies:
                dependents.append(task.id)
        return dependents

    def get_dependencies_recursive(self, task_id: UUID, task_manager: TaskManager) -> Set[UUID]:
        all_deps: Set[UUID] = set()
        task = task_manager.get_task(task_id)
        if not task:
            return all_deps

        stack = list(task.dependencies)
        while stack:
            dep_id = stack.pop()
            if dep_id not in all_deps:
                all_deps.add(dep_id)
                dep_task = task_manager.get_task(dep_id)
                if dep_task:
                    stack.extend(dep_task.dependencies)

        return all_deps

    def get_dependents_recursive(self, task_id: UUID, task_manager: TaskManager) -> Set[UUID]:
        all_deps: Set[UUID] = set()

        stack = self.get_dependents(task_id, task_manager)
        while stack:
            dep_id = stack.pop()
            if dep_id not in all_deps:
                all_deps.add(dep_id)
                stack.extend(self.get_dependents(dep_id, task_manager))

        return all_deps

    def get_execution_estimate(
        self,
        task_manager: TaskManager,
        task_durations: Optional[Dict[UUID, float]] = None,
        max_parallel: int = 4,
    ) -> float:
        task_durations = task_durations or {}
        tasks = task_manager.list_tasks()
        task_map = {t.id: t for t in tasks}

        durations = {
            t.id: task_durations.get(t.id, 1.0)
            for t in tasks
        }

        result = self._solve(tasks)
        if result.has_cycle:
            raise ValidationError(
                message="Cannot estimate execution time: cycle detected in dependencies",
                suggestion="Fix the dependency cycle before estimating execution time.",
            )

        earliest_start: Dict[UUID, float] = {}
        latest_finish: Dict[UUID, float] = {}

        for group in result.parallel_groups:
            for task_id in group:
                task = task_map[task_id]
                dep_finish_times = [
                    earliest_start.get(dep_id, 0) + durations[dep_id]
                    for dep_id in task.dependencies
                ]
                earliest_start[task_id] = max(dep_finish_times) if dep_finish_times else 0

        for task_id in reversed(result.execution_order):
            task = task_map[task_id]
            dependents = self.get_dependents(task_id, task_manager)

            if not dependents:
                latest_finish[task_id] = earliest_start[task_id] + durations[task_id]
            else:
                latest_finish[task_id] = min(
                    earliest_start[dep_id]
                    for dep_id in dependents
                )

        total_time = max(
            earliest_start[t.id] + durations[t.id]
            for t in tasks
        )

        return total_time

    def suggest_optimizations(
        self,
        task_manager: TaskManager,
        task_durations: Optional[Dict[UUID, float]] = None,
    ) -> List[Dict[str, Any]]:
        suggestions: List[Dict[str, Any]] = []

        tasks = task_manager.list_tasks()
        task_map = {t.id: t for t in tasks}

        result = self._solve(tasks)
        if result.has_cycle:
            return suggestions

        critical_path = task_manager.get_critical_path()

        for task in critical_path:
            suggestions.append({
                "type": "critical_path",
                "task_id": str(task.id),
                "task_name": task.name,
                "message": f"Task '{task.name}' is on the critical path. Optimizing it will reduce total execution time.",
                "priority": "high",
            })

        long_tasks = sorted(
            tasks,
            key=lambda t: task_durations.get(t.id, 1.0),
            reverse=True
        )[:5]

        for task in long_tasks:
            duration = task_durations.get(task.id, 1.0)
            if duration > 5.0:
                suggestions.append({
                    "type": "long_running",
                    "task_id": str(task.id),
                    "task_name": task.name,
                    "duration": duration,
                    "message": f"Task '{task.name}' takes {duration:.1f}s. Consider splitting it into smaller tasks.",
                    "priority": "medium",
                })

        for task in tasks:
            dependents = self.get_dependents(task.id, task_manager)
            if len(dependents) > 3:
                suggestions.append({
                    "type": "high_fanout",
                    "task_id": str(task.id),
                    "task_name": task.name,
                    "dependents_count": len(dependents),
                    "message": f"Task '{task.name}' has {len(dependents)} dependents. Consider if dependencies can be reduced.",
                    "priority": "low",
                })

        return suggestions
