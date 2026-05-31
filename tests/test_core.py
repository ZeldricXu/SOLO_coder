import pytest


class TestCoreModels:
    def test_core_entity_creation(self):
        from platform_engineer.core.models import CoreEntity

        entity = CoreEntity(type="event", status="active", attributes={"key": "value"})
        assert entity.id.startswith("ent_")
        assert entity.type == "event"
        assert entity.status == "active"
        assert entity.attributes["key"] == "value"

    def test_config_definition(self):
        from platform_engineer.core.models import ConfigDefinition

        config = ConfigDefinition(namespace="staging", parameters={"timeout": 30})
        assert config.config_id.startswith("cfg_")
        assert config.namespace == "staging"
        assert config.version == 1
        assert config.parameters["timeout"] == 30

    def test_config_bump_version(self):
        from platform_engineer.core.models import ConfigDefinition

        config = ConfigDefinition(namespace="staging", parameters={})
        original_version = config.version
        config.bump_version()
        assert config.version == original_version + 1

    def test_run_instance(self):
        from platform_engineer.core.models import RunInstance

        run = RunInstance(entity_id="ent_001", phase="running", progress=0.5)
        assert run.run_id.startswith("run_")
        assert run.entity_id == "ent_001"
        assert run.phase == "running"
        assert run.progress == 0.5

    def test_run_instance_progress_clamped(self):
        from platform_engineer.core.models import RunInstance

        run = RunInstance(entity_id="ent_001", phase="running", progress=0.5)
        run.update_progress(1.5)
        assert run.progress == 1.0
        run.update_progress(-0.5)
        assert run.progress == 0.0

    def test_stats_snapshot(self):
        from platform_engineer.core.models import StatsSnapshot

        snapshot = StatsSnapshot(
            metrics={"throughput": 1500, "latency_p99": 250},
            dimensions={"host": "node-1"},
        )
        assert snapshot.snapshot_id.startswith("snap_")
        assert snapshot.get_metric("throughput") == 1500
        assert snapshot.get_metric("missing") is None


class TestEventBus:
    @pytest.mark.asyncio
    async def test_publish_subscribe(self, event_bus):
        received = []

        async def handler(event):
            received.append(event)

        event_bus.subscribe("test.event", handler)

        from platform_engineer.core.events import DomainEvent

        event = DomainEvent(event_type="test.event", payload={"data": "test"}, source="test")
        await event_bus.publish(event)

        assert len(received) == 1
        assert received[0].payload["data"] == "test"

    @pytest.mark.asyncio
    async def test_unsubscribe(self, event_bus):
        received = []

        async def handler(event):
            received.append(event)

        event_bus.subscribe("test.event", handler)
        event_bus.unsubscribe("test.event", handler)

        from platform_engineer.core.events import DomainEvent

        event = DomainEvent(event_type="test.event", payload={"data": "test"}, source="test")
        await event_bus.publish(event)

        assert len(received) == 0
