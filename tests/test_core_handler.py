import asyncio
from datetime import datetime
from typing import Any, Dict

import pytest

from src.core.handler import CoreHandler, TaskOrchestrator
from src.models import BaseEntity, ConfigDefinition, EntityStatus, EntityType, Task, TaskGraph
from src.utils.helpers import ExecutionContext


class TestTaskOrchestrator:
    def test_create_entity(self):
        orchestrator = TaskOrchestrator()
        entity = orchestrator.create_entity(
            entity_type="job",
            config={"timeout": 30},
            labels={"env": "test"},
        )

        assert entity.id is not None
        assert entity.type == EntityType.JOB
        assert entity.status == EntityStatus.PENDING
        assert entity.attributes["config"]["timeout"] == 30
        assert entity.attributes["labels"]["env"] == "test"

    def test_get_entity(self):
        orchestrator = TaskOrchestrator()
        entity = orchestrator.create_entity("task", {}, {})

        fetched = orchestrator.get_entity(entity.id)
        assert fetched.id == entity.id

    def test_get_nonexistent_entity(self):
        orchestrator = TaskOrchestrator()
        from src.utils.errors import ResourceNotFoundError

        with pytest.raises(ResourceNotFoundError):
            orchestrator.get_entity("nonexistent")

    def test_update_entity_status(self):
        orchestrator = TaskOrchestrator()
        entity = orchestrator.create_entity("job", {}, {})

        updated = orchestrator.update_entity_status(entity.id, "running")
        assert updated.status == EntityStatus.RUNNING
        assert updated.updated_at >= entity.created_at

    def test_list_entities(self):
        orchestrator = TaskOrchestrator()
        orchestrator.create_entity("job", {}, {"team": "a"})
        orchestrator.create_entity("job", {}, {"team": "b"})
        orchestrator.create_entity("service", {}, {})

        all_entities = orchestrator.list_entities()
        assert len(all_entities) == 3

        jobs = orchestrator.list_entities(entity_type="job")
        assert len(jobs) == 2

    def test_load_config(self):
        orchestrator = TaskOrchestrator()
        config = orchestrator.load_config("production")

        assert config.namespace == "production"
        assert config.parameters["timeout"] == 30
        assert config.parameters["retries"] == 3

    def test_update_config(self):
        orchestrator = TaskOrchestrator()
        orchestrator.load_config("staging")

        updated = orchestrator.update_config(
            "staging",
            parameters={"timeout": 60, "retries": 5},
        )

        assert updated.version == 2
        assert updated.parameters["timeout"] == 60
        assert updated.enabled is True

    @pytest.mark.asyncio
    async def test_process_core(self):
        orchestrator = TaskOrchestrator()
        context = ExecutionContext()

        result = await orchestrator.process_core(
            payload={"data": "test"},
            rules={"rule1": "value1"},
            context=context,
        )

        assert result["processed"] is True
        assert result["rules_applied"] == ["rule1"]
        assert "payload_hash" in result

    @pytest.mark.asyncio
    async def test_execute_graph(self):
        orchestrator = TaskOrchestrator()

        task_a = Task(task_id="a", name="handler_a", dependencies=[])
        task_b = Task(task_id="b", name="handler_b", dependencies=["a"])
        graph = TaskGraph(name="test", tasks=[task_a, task_b])

        async def handler_a(task, params, ctx):
            return {"result": "a"}

        async def handler_b(task, params, ctx):
            return {"result": "b"}

        orchestrator.scheduler.register_handler("handler_a", handler_a)
        orchestrator.scheduler.register_handler("handler_b", handler_b)

        results = await orchestrator.execute_graph(graph)

        assert len(results) == 2
        assert results["a"].success is True
        assert results["b"].success is True

    def test_record_and_get_metrics(self):
        orchestrator = TaskOrchestrator()
        context = ExecutionContext()
        context.add_metric("throughput", 1000)
        context.add_error("test_error")

        orchestrator.record_metrics(context)
        metrics = orchestrator.get_metrics()

        assert len(metrics) == 1
        assert metrics[0].metrics["throughput"] == 1000
        assert metrics[0].metrics["error_count"] == 1

    def test_event_emitter(self):
        orchestrator = TaskOrchestrator()
        events = []

        def listener(event_data):
            events.append(event_data)

        orchestrator.on_event("test.event", listener)
        orchestrator._event_emitter.emit("test.event", {"key": "value"})

        assert len(events) == 1
        assert events[0]["key"] == "value"


class TestCoreHandler:
    @pytest.mark.asyncio
    async def test_create_resource(self):
        handler = CoreHandler()
        request = {"type": "job", "config": {"k": "v"}, "labels": {"env": "test"}}

        response = await handler.create_resource(request)

        assert response.success is True
        assert response.code == 201
        assert "id" in response.data
        assert response.data["status"] == "pending"

    @pytest.mark.asyncio
    async def test_create_resource_validation_error(self):
        handler = CoreHandler()
        response = await handler.create_resource({})

        assert response.success is False
        assert response.code == 422

    @pytest.mark.asyncio
    async def test_get_resource_status(self):
        handler = CoreHandler()
        entity = handler.orchestrator.create_entity("job", {}, {})

        response = await handler.get_resource_status(entity.id)

        assert response.success is True
        assert response.code == 200
        assert response.data["id"] == entity.id
        assert response.data["status"] == "pending"

    @pytest.mark.asyncio
    async def test_get_nonexistent_resource(self):
        handler = CoreHandler()
        response = await handler.get_resource_status("nonexistent")

        assert response.success is False
        assert response.code == 404

    @pytest.mark.asyncio
    async def test_batch_operation(self):
        handler = CoreHandler()
        entity1 = handler.orchestrator.create_entity("job", {}, {})
        entity2 = handler.orchestrator.create_entity("job", {}, {})

        operations = [
            {"action": "stop", "id": entity1.id},
            {"action": "start", "id": entity2.id},
            {"action": "unknown", "id": "fake"},
        ]

        response = await handler.batch_operation(operations)

        assert response.success is True
        assert len(response.data["results"]) == 3
        assert response.data["results"][0]["success"] is True
        assert response.data["results"][1]["success"] is True
        assert response.data["results"][2]["success"] is False

    @pytest.mark.asyncio
    async def test_execute_handler_success(self):
        handler = CoreHandler()
        handler.resource_manager.register("pool_test", "test_resource")

        request = {
            "traceId": "trace_123",
            "params": {"key": "value"},
            "namespace": "test",
            "payload": {"data": "test"},
        }

        response = await handler.execute_handler(request)

        assert response.success is True
        assert response.code == 200
        assert response.data["processed"] is True

    @pytest.mark.asyncio
    async def test_execute_handler_validation_error(self):
        handler = CoreHandler()
        response = await handler.execute_handler({})

        assert response.success is False
        assert response.code == 422

    def test_get_statistics(self):
        handler = CoreHandler()
        handler.orchestrator.create_entity("job", {}, {})
        handler.orchestrator.create_entity("service", {}, {})

        stats = handler.get_statistics()

        assert stats["entities"] == 2
        assert "resource_usage" in stats

    def test_wal_write_and_replay(self, temp_dir):
        from src.core.handler import WALManager

        wal = WALManager(wal_dir=temp_dir)
        entries_written = []

        for i in range(5):
            entry = {"type": "test", "index": i, "data": f"value_{i}"}
            wal.write(entry)
            entries_written.append(entry)

        entries_read = []

        def replay_handler(entry):
            entries_read.append(entry)

        count = wal.replay(replay_handler)

        assert count == 5
        assert len(entries_read) == 5
        assert entries_read[0]["index"] == 0
        assert entries_read[4]["index"] == 4

    def test_resource_manager(self):
        from src.core.handler import ResourceManager

        manager = ResourceManager(max_resources=5)
        manager.register("db_pool", {"connection": "test"})

        import asyncio

        async def test_acquire():
            resource = await manager.acquire("db_pool", timeout=5.0)
            assert resource == {"connection": "test"}
            manager.release("db_pool")

        asyncio.get_event_loop().run_until_complete(test_acquire())

        stats = manager.get_usage_stats("db_pool")
        assert stats["usage_count"] == 1

    def test_event_emitter_async(self):
        from src.core.handler import EventEmitter

        emitter = EventEmitter()
        sync_events = []
        async_events = []

        def sync_listener(data):
            sync_events.append(data)

        async def async_listener(data):
            await asyncio.sleep(0.01)
            async_events.append(data)

        emitter.on("test", sync_listener)
        emitter.on_async("test", async_listener)

        async def test_emit():
            await emitter.emit_async("test", {"value": 42})

        asyncio.get_event_loop().run_until_complete(test_emit())

        assert len(sync_events) == 1
        assert len(async_events) == 1
