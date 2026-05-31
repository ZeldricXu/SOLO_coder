import pytest
import asyncio
from datetime import datetime, timezone
from modules.core_processing.task_executor import (
    Task,
    TaskExecutor,
    TaskStatus,
    TaskPriority,
)


@pytest.mark.asyncio
async def test_task_creation():
    task = Task(
        task_type="test",
        payload={"data": "test"},
        priority=TaskPriority.NORMAL,
    )
    assert task.task_id is not None
    assert task.status == TaskStatus.PENDING
    assert task.priority == TaskPriority.NORMAL


@pytest.mark.asyncio
async def test_task_to_dict():
    task = Task(
        task_type="test",
        payload={"key": "value"},
        priority=TaskPriority.HIGH,
    )
    task_dict = task.to_dict()
    assert task_dict["task_id"] == task.task_id
    assert task_dict["task_type"] == "test"
    assert task_dict["priority"] == TaskPriority.HIGH
    assert task_dict["status"] == TaskStatus.PENDING


@pytest.mark.asyncio
async def test_task_executor_init():
    executor = TaskExecutor(max_workers=2)
    assert executor._task_handlers == {}
    assert executor._running_tasks == {}


@pytest.mark.asyncio
async def test_task_executor_register_handler():
    executor = TaskExecutor()
    
    async def sample_handler(payload, metadata):
        return {"result": "success"}
    
    executor.register_handler("test", sample_handler)
    assert "test" in executor._task_handlers


@pytest.mark.asyncio
async def test_task_status_constants():
    assert TaskStatus.PENDING == "pending"
    assert TaskStatus.RUNNING == "running"
    assert TaskStatus.SUCCESS == "success"
    assert TaskStatus.FAILED == "failed"


@pytest.mark.asyncio
async def test_task_priority_constants():
    assert TaskPriority.LOW == 10
    assert TaskPriority.NORMAL == 50
    assert TaskPriority.HIGH == 100
    assert TaskPriority.CRITICAL == 200


@pytest.mark.asyncio
async def test_task_with_metadata():
    task = Task(
        task_type="test",
        payload={"data": "test"},
        metadata={"user": "admin"},
    )
    assert task.metadata == {"user": "admin"}


@pytest.mark.asyncio
async def test_task_with_dependencies():
    task = Task(
        task_type="test",
        payload={"data": "test"},
        dependencies=["dep1", "dep2"],
    )
    assert task.dependencies == ["dep1", "dep2"]
