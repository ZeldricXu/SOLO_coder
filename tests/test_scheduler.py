import pytest
import asyncio
from datetime import datetime

from edge_platform.scheduler import TaskScheduler, Task, TaskStatus, TaskPriority
from edge_platform.common.event_bus import EventBus


@pytest.fixture
def scheduler(event_bus):
    return TaskScheduler(event_bus)


@pytest.mark.asyncio
async def test_create_task(scheduler):
    task = await scheduler.create_task(
        name="test_task",
        payload={"data": "test"},
        description="Test task"
    )

    assert task.task_id is not None
    assert task.name == "test_task"
    assert task.status == TaskStatus.PENDING
    assert task.priority == TaskPriority.MEDIUM


@pytest.mark.asyncio
async def test_get_task(scheduler):
    created = await scheduler.create_task(
        name="test_task",
        payload={"data": "test"}
    )

    retrieved = scheduler.get_task(created.task_id)
    assert retrieved.task_id == created.task_id
    assert retrieved.name == "test_task"


@pytest.mark.asyncio
async def test_list_tasks(scheduler):
    for i in range(5):
        await scheduler.create_task(
            name=f"task_{i}",
            payload={"index": i}
        )

    tasks = scheduler.list_tasks(limit=10)
    assert len(tasks) == 5


@pytest.mark.asyncio
async def test_update_task_status(scheduler):
    task = await scheduler.create_task(
        name="test_task",
        payload={"data": "test"}
    )

    updated = await scheduler.update_task_status(
        task.task_id,
        TaskStatus.RUNNING,
        task.version
    )

    assert updated.status == TaskStatus.RUNNING
    assert updated.version == task.version + 1


@pytest.mark.asyncio
async def test_cancel_task(scheduler):
    task = await scheduler.create_task(
        name="test_task",
        payload={"data": "test"}
    )

    cancelled = await scheduler.cancel_task(task.task_id)
    assert cancelled.status == TaskStatus.CANCELLED


def test_scheduler_stats(scheduler):
    stats = scheduler.get_stats()
    assert "total" in stats
    assert "by_status" in stats
