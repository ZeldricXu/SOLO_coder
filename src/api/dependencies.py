from typing import Generator

from src.core.handler import CoreHandler, TaskOrchestrator
from src.registry.registry import ServiceRegistry
from src.scheduler.scheduler import TaskScheduler


def get_core_handler() -> CoreHandler:
    return CoreHandler()


def get_task_orchestrator() -> TaskOrchestrator:
    return TaskOrchestrator()


def get_task_scheduler() -> TaskScheduler:
    return TaskScheduler()


def get_service_registry() -> Generator[ServiceRegistry, None, None]:
    registry = ServiceRegistry()
    try:
        yield registry
    finally:
        registry.close()
