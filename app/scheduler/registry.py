"""
Task registry.
"""

from typing import Dict, List, Optional

from app.scheduler.models import Task


class TaskRegistry:
    def __init__(self):
        self._tasks: Dict[str, Task] = {}
    
    def register(self, task: Task):
        self._tasks[task.task_id] = task
    
    def get(self, task_id: str) -> Optional[Task]:
        return self._tasks.get(task_id)
    
    def list_all(self) -> List[Task]:
        return list(self._tasks.values())
    
    def remove(self, task_id: str):
        if task_id in self._tasks:
            del self._tasks[task_id]
    
    @property
    def tasks(self) -> Dict[str, Task]:
        return self._tasks
