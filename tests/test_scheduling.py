import pytest
import asyncio
import sys
import os

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from modules.scheduling_module import get_scheduler, TaskStatus, TaskType


@pytest.mark.asyncio
async def test_add_task():
    scheduler = get_scheduler()
    task = scheduler.add_task("test-task", payload={"key": "value"})
    assert task is not None
    assert task.task_id is not None
    assert task.name == "test-task"
    assert task.status in [TaskStatus.QUEUED, TaskStatus.PENDING]


@pytest.mark.asyncio
async def test_task_with_dependencies():
    scheduler = get_scheduler()
    task1 = scheduler.add_task("task-1")
    task2 = scheduler.add_task("task-2", dependencies=[task1.task_id])
    assert task2.dependencies == [task1.task_id]
    assert task2.status == TaskStatus.BLOCKED


@pytest.mark.asyncio
async def test_cancel_task():
    scheduler = get_scheduler()
    task = scheduler.add_task("test-cancel")
    success = scheduler.cancel_task(task.task_id)
    assert success is True


def test_list_tasks():
    scheduler = get_scheduler()
    scheduler.add_task("list-test-1")
    scheduler.add_task("list-test-2")
    tasks = scheduler.list_tasks()
    assert len(tasks) >= 2


def test_get_task():
    scheduler = get_scheduler()
    task = scheduler.add_task("get-test")
    fetched = scheduler.get_task(task.task_id)
    assert fetched is not None
    assert fetched.task_id == task.task_id


def test_task_stats():
    scheduler = get_scheduler()
    stats = scheduler.get_stats()
    assert "total_tasks" in stats
    assert "completed" in stats
    assert "failed" in stats
