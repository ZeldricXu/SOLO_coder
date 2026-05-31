"""
Dependency resolver.
"""

from collections import defaultdict
from heapq import heappop, heappush
from typing import Dict, List, Set, Tuple

from app.scheduler.models import Task, TaskStatus


class DependencyResolver:
    def __init__(self, tasks: Dict[str, Task]):
        self.tasks = tasks
    
    def check_circular_dependency(self) -> bool:
        visited: Set[str] = set()
        rec_stack: Set[str] = set()
        
        def dfs(task_id: str) -> bool:
            if task_id in rec_stack:
                return True
            if task_id in visited:
                return False
            
            visited.add(task_id)
            rec_stack.add(task_id)
            
            task = self.tasks.get(task_id)
            if task:
                for dep_id in task.dependencies:
                    if dfs(dep_id):
                        return True
            
            rec_stack.remove(task_id)
            return False
        
        for task_id in self.tasks:
            if dfs(task_id):
                return True
        return False
    
    def get_execution_order(self) -> List[str]:
        in_degree: Dict[str, int] = {tid: 0 for tid in self.tasks}
        adj_list: Dict[str, List[str]] = defaultdict(list)
        
        for task_id, task in self.tasks.items():
            for dep_id in task.dependencies:
                if dep_id in self.tasks:
                    in_degree[task_id] += 1
                    adj_list[dep_id].append(task_id)
        
        queue: List[Tuple[int, str]] = []
        for task_id, degree in in_degree.items():
            if degree == 0:
                task = self.tasks[task_id]
                heappush(queue, (-task.priority, task_id))
        
        order: List[str] = []
        while queue:
            _, task_id = heappop(queue)
            order.append(task_id)
            
            for next_id in adj_list[task_id]:
                in_degree[next_id] -= 1
                if in_degree[next_id] == 0:
                    next_task = self.tasks[next_id]
                    heappush(queue, (-next_task.priority, next_id))
        
        return order
    
    def get_ready_tasks(self) -> List[str]:
        ready: List[str] = []
        for task_id, task in self.tasks.items():
            if task.status != TaskStatus.PENDING:
                continue
            
            all_deps_done = True
            for dep_id in task.dependencies:
                dep_task = self.tasks.get(dep_id)
                if not dep_task or dep_task.status != TaskStatus.COMPLETED:
                    all_deps_done = False
                    break
            
            if all_deps_done:
                ready.append(task_id)
        
        return sorted(
            ready,
            key=lambda tid: (-self.tasks[tid].priority, tid)
        )
