import pytest
import asyncio
import json
from datetime import datetime
from pathlib import Path
import tempfile
import shutil

import sys
sys.path.insert(0, str(Path(__file__).parent.parent))

from app import data_access_module
from app.core.models import BaseEntity
from app.modules.data_access import (
    MigrationStatus, StreamMode, StreamMetrics, StreamBuffer,
    SchemaVersion, MigrationTask, StreamChunk, Checkpoint,
    StreamProcessor, CheckpointManager, BackpressureController,
    SchemaVersionController, DataMigrationService
)


@pytest.fixture
async def setup_test_env():
    yield


class TestStreamBuffer:
    def test_add_item(self):
        buffer = StreamBuffer(max_size=3)
        assert not buffer.add("item1")
        assert not buffer.add("item2")
        assert buffer.add("item3")  # Should trigger flush
        assert buffer.size() == 3

    def test_should_flush(self):
        buffer = StreamBuffer(max_size=5, flush_timeout=0.1)
        assert not buffer.should_flush()
        buffer.add("item1")
        import time
        time.sleep(0.2)  # Wait for flush timeout
        assert buffer.should_flush()

    def test_flush(self):
        buffer = StreamBuffer()
        buffer.add("item1")
        buffer.add("item2")
        items = buffer.flush()
        assert len(items) == 2
        assert buffer.size() == 0

    def test_clear(self):
        buffer = StreamBuffer()
        buffer.add("item1")
        buffer.clear()
        assert buffer.size() == 0


class TestStreamProcessor:
    async def test_stream_process(self):
        async def data_generator():
            for i in range(5):
                yield {"id": i, "data": f"test{i}"}

        processed = []
        async def writer(batch):
            processed.extend(batch)

        processor = StreamProcessor(buffer_size=2)
        count, bytes_processed, metrics = await processor.stream_process(
            data_generator(), writer
        )
        assert count == 5
        assert len(processed) == 5
        assert metrics.records_processed == 5
        assert metrics.batches_processed > 0

    async def test_concurrent_batches(self):
        async def data_generator():
            for i in range(10):
                await asyncio.sleep(0.01)  # Add small delay
                yield {"id": i, "data": f"test{i}"}

        processed = []
        async def writer(batch):
            await asyncio.sleep(0.05)  # Simulate processing time
            processed.extend(batch)

        processor = StreamProcessor(buffer_size=3, max_concurrent_batches=2)
        count, _, metrics = await processor.stream_process(
            data_generator(), writer
        )
        assert count == 10
        assert len(processed) == 10

    async def test_empty_generator(self):
        async def empty_generator():
            if False:
                yield

        processed = []
        async def writer(batch):
            processed.extend(batch)

        processor = StreamProcessor()
        count, bytes_processed, metrics = await processor.stream_process(
            empty_generator(), writer
        )
        assert count == 0
        assert bytes_processed == 0
        assert metrics.records_processed == 0


class TestCheckpointManager:
    def test_save_checkpoint(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            manager = CheckpointManager(storage_dir=temp_dir)
            checkpoint = manager.save_checkpoint("task1", 100, "last_id")
            assert checkpoint.checkpoint_id.startswith("cp_")
            assert checkpoint.task_id == "task1"
            assert checkpoint.last_processed_offset == 100
            assert checkpoint.last_processed_id == "last_id"

    def test_get_checkpoint(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            manager = CheckpointManager(storage_dir=temp_dir)
            checkpoint = manager.save_checkpoint("task1", 100)
            retrieved = manager.get_checkpoint("task1")
            assert retrieved is not None
            assert retrieved.checkpoint_id == checkpoint.checkpoint_id

    def test_load_checkpoint(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            # Create checkpoint file manually
            checkpoint_data = {
                "checkpoint_id": "cp_test",
                "task_id": "task1",
                "last_processed_offset": 200,
                "last_processed_id": "test_id",
                "timestamp": datetime.utcnow().isoformat(),
                "metadata": {"key": "value"}
            }
            checkpoint_file = Path(temp_dir) / "checkpoint_task1.json"
            with open(checkpoint_file, "w") as f:
                json.dump(checkpoint_data, f)

            manager = CheckpointManager(storage_dir=temp_dir)
            checkpoint = manager.load_checkpoint("task1")
            assert checkpoint is not None
            assert checkpoint.last_processed_offset == 200

    def test_clear_checkpoint(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            manager = CheckpointManager(storage_dir=temp_dir)
            manager.save_checkpoint("task1", 100)
            manager.clear_checkpoint("task1")
            assert manager.get_checkpoint("task1") is None


class TestBackpressureController:
    async def test_no_backpressure(self):
        controller = BackpressureController(high_watermark=100, low_watermark=50)
        await controller.wait_if_needed(30)
        stats = controller.get_stats()
        assert not stats["is_paused"]
        assert stats["backpressure_events"] == 0

    async def test_backpressure_activation(self):
        controller = BackpressureController(high_watermark=5, low_watermark=2)
        # Simulate high load
        await controller.wait_if_needed(10)  # Should activate backpressure
        stats = controller.get_stats()
        assert stats["is_paused"]
        assert stats["backpressure_events"] == 1


class TestSchemaVersionController:
    def test_create_version(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            controller = SchemaVersionController(storage_dir=temp_dir)
            schema = {"tables": {"users": {"columns": ["id", "name"]}}}
            version = controller.create_version(schema, "Test schema")
            assert version.version == 1
            assert version.description == "Test schema"

    def test_get_current_version(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            controller = SchemaVersionController(storage_dir=temp_dir)
            current = controller.get_current_version()
            assert current.version == 0

            schema = {"tables": {"users": {"columns": ["id", "name"]}}}
            controller.create_version(schema)
            current = controller.get_current_version()
            assert current.version == 1

    def test_list_versions(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            controller = SchemaVersionController(storage_dir=temp_dir)
            schema1 = {"tables": {"users": {"columns": ["id", "name"]}}}
            schema2 = {"tables": {"users": {"columns": ["id", "name", "email"]}}}
            controller.create_version(schema1)
            controller.create_version(schema2)
            versions = controller.list_versions()
            assert len(versions) == 2
            assert versions[0].version == 2

    def test_validate_version(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            controller = SchemaVersionController(storage_dir=temp_dir)
            schema = {"tables": {"users": {"columns": ["id", "name"]}}}
            version = controller.create_version(schema)
            assert controller.validate_version(1, schema)
            # Modified schema should fail validation
            modified_schema = {"tables": {"users": {"columns": ["id"]}}}
            assert not controller.validate_version(1, modified_schema)

    def test_compare_versions(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            controller = SchemaVersionController(storage_dir=temp_dir)
            schema1 = {"tables": {"users": {"columns": ["id", "name"]}}}
            schema2 = {"tables": {"users": {"columns": ["id", "name", "email"]}, "orders": {"columns": ["id", "user_id"]}}}
            controller.create_version(schema1)
            controller.create_version(schema2)
            comparison = controller.compare_versions(1, 2)
            assert "orders" in comparison["added_fields"]
            assert "email" in comparison["modified_fields"]
            assert comparison["is_compatible"]


class TestDataMigrationService:
    def test_create_migration_task(self):
        schema_controller = SchemaVersionController()
        service = DataMigrationService(schema_controller)
        task = service.create_migration_task("source", "target", "users", total_records=1000)
        assert task.task_id.startswith("mig_")
        assert task.table_name == "users"
        assert task.total_records == 1000

    def test_create_migration_task_stream_mode(self):
        schema_controller = SchemaVersionController()
        service = DataMigrationService(schema_controller)
        task = service.create_migration_task("source", "target", "users", 
                                           stream_mode=StreamMode.STREAM)
        assert task.stream_mode == StreamMode.STREAM
        assert task.stream_buffer is not None

    def test_pause_migration(self):
        schema_controller = SchemaVersionController()
        service = DataMigrationService(schema_controller)
        task = service.create_migration_task("source", "target", "users")
        task.status = MigrationStatus.RUNNING
        assert service.pause_migration(task.task_id)
        assert task.status == MigrationStatus.PAUSED

    def test_resume_migration(self):
        schema_controller = SchemaVersionController()
        service = DataMigrationService(schema_controller)
        task = service.create_migration_task("source", "target", "users")
        task.status = MigrationStatus.PAUSED
        assert service.resume_migration(task.task_id)
        assert task.status == MigrationStatus.RUNNING

    def test_cancel_migration(self):
        schema_controller = SchemaVersionController()
        service = DataMigrationService(schema_controller)
        task = service.create_migration_task("source", "target", "users")
        task.status = MigrationStatus.RUNNING
        assert service.cancel_migration(task.task_id)
        assert task.status == MigrationStatus.FAILED
        assert "Cancelled by user" in task.error_message

    async def test_execute_batch_migration(self):
        schema_controller = SchemaVersionController()
        service = DataMigrationService(schema_controller)
        task = service.create_migration_task("source", "target", "users", 
                                           total_records=5, batch_size=2)

        processed = []
        async def data_generator():
            for i in range(5):
                yield {"id": i, "data": f"test{i}"}

        async def writer(batch):
            processed.extend(batch)
            return len(batch)

        result = await service.execute_migration(task.task_id, data_generator, writer)
        assert result.status == MigrationStatus.COMPLETED
        assert result.records_processed == 5
        assert len(processed) == 5

    async def test_execute_stream_migration(self):
        schema_controller = SchemaVersionController()
        service = DataMigrationService(schema_controller)
        task = service.create_migration_task("source", "target", "users", 
                                           total_records=5, stream_mode=StreamMode.STREAM)

        processed = []
        async def data_generator():
            for i in range(5):
                yield {"id": i, "data": f"test{i}"}

        async def writer(batch):
            processed.extend(batch)

        result = await service.execute_migration(task.task_id, data_generator, writer)
        assert result.status == MigrationStatus.COMPLETED
        assert result.records_processed == 5
        assert len(processed) == 5

    async def test_migration_failure(self):
        schema_controller = SchemaVersionController()
        service = DataMigrationService(schema_controller)
        task = service.create_migration_task("source", "target", "users")

        async def data_generator():
            yield {"id": 1, "data": "test1"}
            raise Exception("Test error")

        async def writer(batch):
            pass

        result = await service.execute_migration(task.task_id, data_generator, writer)
        assert result.status == MigrationStatus.FAILED
        assert "Test error" in result.error_message


class TestDataAccessModule:
    def test_create_resource(self):
        resource = data_access_module.create_resource("workflow", {"config": {"timeout": 30}})
        assert resource.id.startswith("ent_")
        assert resource.type == "workflow"
        assert resource.attributes["config"]["timeout"] == 30

    def test_get_resource(self):
        resource = data_access_module.create_resource("test", {"key": "value"})
        retrieved = data_access_module.get_resource(resource.id)
        assert retrieved is not None
        assert retrieved.id == resource.id

    def test_update_resource(self):
        resource = data_access_module.create_resource("test", {"key": "value"})
        updated = data_access_module.update_resource(resource.id, {"key": "updated", "new_key": "new_value"})
        assert updated is not None
        assert updated.attributes["key"] == "updated"
        assert updated.attributes["new_key"] == "new_value"

    def test_delete_resource(self):
        resource = data_access_module.create_resource("test", {"key": "value"})
        assert data_access_module.delete_resource(resource.id)
        assert data_access_module.get_resource(resource.id) is None

    def test_list_resources(self):
        # Clear existing resources for test
        for resource in data_access_module.list_resources("test_type"):
            data_access_module.delete_resource(resource.id)

        data_access_module.create_resource("test_type", {"a": 1})
        data_access_module.create_resource("test_type", {"b": 2})
        resources = data_access_module.list_resources("test_type")
        assert len(resources) >= 2

    def test_get_migration_metrics(self):
        metrics = data_access_module.get_migration_metrics()
        assert "total_tasks" in metrics
        assert "tasks" in metrics
        assert "summary" in metrics

    def test_compare_schema_versions(self):
        # Create test schema versions
        schema1 = {"tables": {"users": {"columns": ["id", "name"]}}}
        schema2 = {"tables": {"users": {"columns": ["id", "name", "email"]}}}
        data_access_module.schema_controller.create_version(schema1)
        data_access_module.schema_controller.create_version(schema2)
        comparison = data_access_module.compare_schema_versions(1, 2)
        assert "added_fields" in comparison
        assert "modified_fields" in comparison


if __name__ == "__main__":
    pytest.main([__file__, "-v"])
