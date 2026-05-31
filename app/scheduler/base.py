"""
Abstract base classes for scheduler module.
"""

from abc import ABC, abstractmethod
from typing import Dict, List, Optional

from app.scheduler.models import Task, Schedule


class TaskExecutor(ABC):
    @abstractmethod
    async def execute(self, task: Task) -> Task:
        pass


class WorkflowScheduler(ABC):
    @abstractmethod
    def add_task(self, task: Task):
        pass
    
    @abstractmethod
    def add_schedule(self, schedule: Schedule):
        pass
    
    @abstractmethod
    async def run_workflow(self) -> Dict[str, Task]:
        pass
    
    @abstractmethod
    def start(self):
        pass
    
    @abstractmethod
    def stop(self):
        pass
    
    @abstractmethod
    def get_task_status(self, task_id: str) -> Optional[Task]:
        pass
    
    @abstractmethod
    def list_schedules(self) -> List[Schedule]:
        pass
