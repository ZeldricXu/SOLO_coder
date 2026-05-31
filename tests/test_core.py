import pytest
import asyncio
from app.core.service import TaskExecutionService, EventBus, ResourceManager, ExecutionContext


class TestExecutionContext:
    def test_elapsed_ms(self):
        ctx = ExecutionContext("test-trace")
        import time
        time.sleep(0.001)
        elapsed = ctx.elapsed_ms()
        assert elapsed > 0

    def test_add_error(self):
        ctx = ExecutionContext("test-trace")
        assert not ctx.has_errors()
        ctx.add_error(ValueError("test"))
        assert ctx.has_errors()
        assert len(ctx.errors) == 1


class TestEventBus:
    @pytest.mark.asyncio
    async def test_subscribe_and_emit(self):
        bus = EventBus()
        received = []

        async def handler(payload):
            received.append(payload)

        await bus.subscribe("test.event", handler)
        await bus.emit("test.event", {"data": "test"})

        assert len(received) == 1
        assert received[0]["data"] == "test"

    @pytest.mark.asyncio
    async def test_unsubscribe(self):
        bus = EventBus()
        received = []

        async def handler(payload):
            received.append(payload)

        await bus.subscribe("test.event2", handler)
        await bus.unsubscribe("test.event2", handler)
        await bus.emit("test.event2", {"data": "test"})

        assert len(received) == 0

    @pytest.mark.asyncio
    async def test_handler_error_does_not_stop_others(self):
        bus = EventBus()
        received = []

        async def bad_handler(payload):
            raise ValueError("Boom!")

        async def good_handler(payload):
            received.append(payload)

        await bus.subscribe("test.error", bad_handler)
        await bus.subscribe("test.error", good_handler)
        await bus.emit("test.error", {"data": "test"})

        assert len(received) == 1


class TestResourceManager:
    @pytest.mark.asyncio
    async def test_acquire_and_release(self):
        manager = ResourceManager()
        resource = await manager.acquire("gpu", "node1")
        assert resource["type"] == "gpu"
        assert resource["id"] == "node1"
        assert manager.is_acquired("gpu", "node1")

        await manager.release("gpu", "node1")
        assert not manager.is_acquired("gpu", "node1")

    @pytest.mark.asyncio
    async def test_concurrent_access(self):
        manager = ResourceManager()
        results = []

        async def worker(name):
            await manager.acquire("test", "resource")
            results.append(name)
            await asyncio.sleep(0.01)
            await manager.release("test", "resource")

        await asyncio.gather(
            worker("worker1"),
            worker("worker2"),
            worker("worker3"),
        )

        assert len(results) == 3


class TestTaskExecutionService:
    @pytest.mark.asyncio
    async def test_execute_handler_success(self):
        service = TaskExecutionService()
        result = await service.execute_handler(
            task_type="test",
            namespace="default",
            payload={"key": "value"},
        )

        assert result["status"] == "completed"
        assert "task_id" in result
        assert "run_id" in result
        assert "result" in result
        assert result["result"]["processed"] is True

    @pytest.mark.asyncio
    async def test_execute_handler_validation_error(self):
        service = TaskExecutionService()
        result = await service.execute_handler(
            task_type="test",
            namespace="default",
            payload="not a dict",
        )

        assert result["status"] == "failed"
        assert result["error"]["code"] == 422

    @pytest.mark.asyncio
    async def test_create_resource(self):
        service = TaskExecutionService()
        resource = await service.create_resource(
            resource_type="workflow",
            config={"timeout": 30},
            labels={"env": "test"},
        )

        assert "id" in resource
        assert resource["type"] == "workflow"
        assert resource["status"] == "active"
        assert resource["config"]["timeout"] == 30

    @pytest.mark.asyncio
    async def test_batch_operations(self):
        service = TaskExecutionService()
        operations = [
            {"action": "start", "id": "rsc_001"},
            {"action": "stop", "id": "rsc_002"},
            {"action": "invalid", "id": "rsc_003"},
        ]

        result = await service.batch_operations(operations)

        assert "batch_id" in result
        assert result["total_count"] == 3
        assert result["success_count"] == 2
        assert result["failed_count"] == 1
        assert len(result["results"]) == 3

    @pytest.mark.asyncio
    async def test_get_task_result(self):
        service = TaskExecutionService()
        result = await service.execute_handler(
            task_type="test",
            namespace="default",
            payload={},
        )

        task_id = result["task_id"]
        retrieved = service.get_task_result(task_id)
        assert retrieved is not None
        assert retrieved["task_id"] == task_id

        assert service.get_task_result("non_existent") is None

    @pytest.mark.asyncio
    async def test_list_tasks(self):
        service = TaskExecutionService()
        await service.execute_handler(task_type="type1", namespace="default", payload={})
        await service.execute_handler(task_type="type2", namespace="default", payload={})

        all_tasks = await service.list_tasks()
        assert len(all_tasks) >= 2

        type1_tasks = await service.list_tasks(task_type="type1")
        assert len(type1_tasks) >= 1
