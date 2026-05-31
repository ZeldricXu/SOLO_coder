import pytest
import asyncio
from src.modules import (
    Task, TaskStatus, TaskPriority, TaskScheduler, TaskResult,
    TaskContext
)


@pytest.fixture
def scheduler():
    return TaskScheduler(max_concurrent=10)


async def sample_task(x: int, y: int, context: TaskContext = None) -> int:
    await asyncio.sleep(0.01)
    return x + y


async def failing_task(context: TaskContext = None) -> None:
    raise ValueError("Task failed")


@pytest.mark.asyncio
async def test_task_execution():
    task = Task(
        name="add",
        func=sample_task,
        args=(2, 3),
        priority=TaskPriority.HIGH,
    )

    result = await task.execute()
    assert result.status == TaskStatus.COMPLETED
    assert result.result == 5


@pytest.mark.asyncio
async def test_task_failure():
    task = Task(
        name="fail",
        func=failing_task,
        max_retries=1,
        retry_delay=0,
    )

    result = await task.execute()
    assert result.status == TaskStatus.FAILED
    assert "Task failed" in result.error


@pytest.mark.asyncio
async def test_task_timeout():
    async def slow_task(context: TaskContext = None):
        await asyncio.sleep(1)
        return "done"

    task = Task(
        name="slow",
        func=slow_task,
        timeout=0.05,
        max_retries=1,
    )

    result = await task.execute()
    assert result.status == TaskStatus.TIMEOUT


@pytest.mark.asyncio
async def test_scheduler_submit_and_wait(scheduler):
    await scheduler.start()

    task_id = await scheduler.create_and_submit(
        name="add",
        func=sample_task,
        args=(5, 7),
    )

    result = await scheduler.wait_for_task(task_id, timeout=5)
    assert result is not None
    assert result.status == TaskStatus.COMPLETED
    assert result.result == 12

    await scheduler.stop()


@pytest.mark.asyncio
async def test_scheduler_cancel(scheduler):
    async def long_task(context: TaskContext = None):
        await asyncio.sleep(10)
        return "done"

    await scheduler.start()

    task_id = await scheduler.create_and_submit(
        name="long",
        func=long_task,
    )

    await asyncio.sleep(0.01)
    cancelled = scheduler.cancel_task(task_id)
    assert cancelled

    await scheduler.stop()


def test_scheduler_list_tasks(scheduler):
    task1 = Task(name="t1", func=sample_task, args=(1, 2))
    task2 = Task(name="t2", func=sample_task, args=(3, 4))

    scheduler._tasks[task1.task_id] = task1
    scheduler._tasks[task2.task_id] = task2

    tasks = scheduler.list_tasks()
    assert len(tasks) == 2
