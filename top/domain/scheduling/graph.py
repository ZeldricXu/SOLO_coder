from __future__ import annotations

from abc import ABC, abstractmethod
from collections import defaultdict, deque
from typing import Any, Dict, List, Optional, Set

from top.core.models import TaskDefinition


class DependencyResolver(ABC):
    @abstractmethod
    def has_cycle(self) -> bool:
        pass

    @abstractmethod
    def topological_order(self) -> List[str]:
        pass

    @abstractmethod
    def get_ready_tasks(self, completed: Set[str], failed: Set[str]) -> List[str]:
        pass

    @abstractmethod
    def get_task(self, task_id: str) -> Optional[TaskDefinition]:
        pass

    @abstractmethod
    def all_tasks(self) -> List[TaskDefinition]:
        pass

    @abstractmethod
    def task_count(self) -> int:
        pass


class DependencyGraph(DependencyResolver):
    def __init__(self, tasks: List[TaskDefinition]):
        self._tasks: Dict[str, TaskDefinition] = {t.task_id: t for t in tasks}
        self._dependents: Dict[str, List[str]] = defaultdict(list)
        self._indegree: Dict[str, int] = defaultdict(int)

        for task in tasks:
            self._indegree[task.task_id] = 0

        for task in tasks:
            for dep_id in task.dependencies:
                if dep_id in self._tasks:
                    self._dependents[dep_id].append(task.task_id)
                    self._indegree[task.task_id] += 1

    def has_cycle(self) -> bool:
        visited = set()
        rec_stack = set()

        def dfs(node: str) -> bool:
            if node in rec_stack:
                return True
            if node in visited:
                return False

            visited.add(node)
            rec_stack.add(node)

            for dependent in self._dependents[node]:
                if dfs(dependent):
                    return True

            rec_stack.discard(node)
            return False

        for task_id in self._tasks:
            if task_id not in visited:
                if dfs(task_id):
                    return True

        return False

    def topological_order(self) -> List[str]:
        order = []
        indegree = dict(self._indegree)
        queue = deque([t for t, d in indegree.items() if d == 0])

        while queue:
            node = queue.popleft()
            order.append(node)

            for dependent in self._dependents[node]:
                indegree[dependent] -= 1
                if indegree[dependent] == 0:
                    queue.append(dependent)

        return order

    def get_ready_tasks(
        self,
        completed: Set[str],
        failed: Set[str],
    ) -> List[str]:
        ready = []

        for task_id, task in self._tasks.items():
            if task_id in completed or task_id in failed:
                continue

            deps = [d for d in task.dependencies if d in self._tasks]
            if not deps:
                ready.append(task_id)
            else:
                all_deps_succeeded = all(d in completed for d in deps)
                if all_deps_succeeded:
                    ready.append(task_id)

        return ready

    def get_task(self, task_id: str) -> Optional[TaskDefinition]:
        return self._tasks.get(task_id)

    def all_tasks(self) -> List[TaskDefinition]:
        return list(self._tasks.values())

    def task_count(self) -> int:
        return len(self._tasks)

    def get_dependents(self, task_id: str) -> List[str]:
        return list(self._dependents.get(task_id, []))

    def get_dependencies(self, task_id: str) -> List[str]:
        task = self._tasks.get(task_id)
        if not task:
            return []
        return [d for d in task.dependencies if d in self._tasks]


class TaskGraph(DependencyGraph):
    pass
