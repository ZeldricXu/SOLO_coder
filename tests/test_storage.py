import pytest
import asyncio
import json
from datetime import datetime
from pathlib import Path
import tempfile
import shutil
import os

import sys
sys.path.insert(0, str(Path(__file__).parent.parent))

from app import storage_module
from app.modules.storage import (
    ResourceState, BackupStatus, RecoveryStatus, StorageTier,
    ResourceLease, PooledResource, PoolStats, BackupRecord, RecoveryTask,
    AsyncResourcePool, MultiTierStorageManager, ResourcePoolManager,
    BackupManager, RecoveryManager, StorageManagementModule
)


@pytest.fixture
async def setup_test_env():
    await storage_module.initialize()
    yield
    await storage_module.shutdown()


class TestResourceLease:
    def test_is_expired(self):
        lease = ResourceLease(
            resource_id="test1",
            resource_type="test",
            acquired_at=datetime.utcnow(),
            lease_duration_seconds=0.1  # 0.1 seconds for testing
        )
        assert not lease.is_expired()
        import time
        time.sleep(0.2)  # Wait for lease to expire
        assert lease.is_expired()

    def test_heartbeat(self):
        lease = ResourceLease(
            resource_id="test1",
            resource_type="test",
            acquired_at=datetime.utcnow()
        )
        assert lease.state == ResourceState.ACQUIRED
        lease.heartbeat()
        assert lease.state == ResourceState.IN_USE
        assert lease.last_heartbeat_at is not None


class TestPooledResource:
    def test_can_reuse(self):
        resource = PooledResource(
            resource={"data": "test"},
            resource_id="test1",
            resource_type="test"
        )
        assert resource.can_reuse()

    def test_cannot_reuse_after_max_use(self):
        resource = PooledResource(
            resource={"data": "test"},
            resource_id="test1",
            resource_type="test",
            max_use_count=1
        )
        resource.use_count = 1
        assert not resource.can_reuse()

    def test_cannot_reuse_failed(self):
        resource = PooledResource(
            resource={"data": "test"},
            resource_id="test1",
            resource_type="test"
        )
        resource.mark_failed()
        assert not resource.can_reuse()

    def test_mark_used(self):
        resource = PooledResource(
            resource={"data": "test"},
            resource_id="test1",
            resource_type="test"
        )
        lease = ResourceLease(
            resource_id="test1",
            resource_type="test",
            acquired_at=datetime.utcnow()
        )
        resource.mark_used(lease)
        assert resource.state == ResourceState.IN_USE
        assert resource.lease == lease
        assert resource.use_count == 1

    def test_mark_idle(self):
        resource = PooledResource(
            resource={"data": "test"},
            resource_id="test1",
            resource_type="test"
        )
        lease = ResourceLease(
            resource_id="test1",
            resource_type="test",
            acquired_at=datetime.utcnow()
        )
        resource.mark_used(lease)
        resource.mark_idle()
        assert resource.state == ResourceState.IDLE
        assert resource.lease is None


class TestAsyncResourcePool:
    async def test_acquire_release(self):
        def create_resource():
            return {"id": f"resource_{os.urandom(4).hex()}"}

        pool = AsyncResourcePool(
            name="test_pool",
            max_size=5,
            min_idle=2,
            resource_factory=create_resource
        )

        try:
            await pool.start()
            
            # Acquire resource
            async with pool.acquire() as (resource_id, resource):
                assert resource_id is not None
                assert "id" in resource
            
            # Check stats
            stats = pool.get_stats()
            assert stats["stats"]["total_acquisitions"] == 1
            assert stats["stats"]["total_releases"] == 1
            
        finally:
            await pool.stop()

    async def test_concurrent_acquisition(self):
        def create_resource():
            return {"id": f"resource_{os.urandom(4).hex()}"}

        pool = AsyncResourcePool(
            name="test_pool",
            max_size=3,
            min_idle=1,
            resource_factory=create_resource
        )

        try:
            await pool.start()
            
            async def acquire_and_release():
                async with pool.acquire() as (resource_id, resource):
                    await asyncio.sleep(0.01)
                    return resource_id
            
            # Test 5 concurrent acquisitions
            tasks = [acquire_and_release() for _ in range(5)]
            results = await asyncio.gather(*tasks)
            assert len(results) == 5
            assert len(set(results)) <= 3  # Should reuse resources
            
        finally:
            await pool.stop()

    async def test_timeout(self):
        def create_resource():
            import time
            time.sleep(0.1)  # Slow resource creation
            return {"id": "test"}

        pool = AsyncResourcePool(
            name="test_pool",
            max_size=1,
            min_idle=0,
            max_wait_time=0.05,  # Short timeout
            resource_factory=create_resource
        )

        try:
            await pool.start()
            
            # Acquire resource and keep it
            async with pool.acquire() as (_, _):
                # Try to acquire another resource - should timeout
                with pytest.raises(TimeoutError):
                    async with pool.acquire(timeout=0.05):
                        pass
            
        finally:
            await pool.stop()

    async def test_resource_validation(self):
        def create_resource():
            return {"valid": True}

        def validate_resource(resource):
            return resource.get("valid", False)

        pool = AsyncResourcePool(
            name="test_pool",
            max_size=2,
            min_idle=1,
            resource_factory=create_resource,
            resource_validator=validate_resource
        )

        try:
            await pool.start()
            
            async with pool.acquire() as (resource_id, resource):
                assert resource["valid"]
            
        finally:
            await pool.stop()

    async def test_resource_cleanup(self):
        cleanup_called = False

        def create_resource():
            return {"data": "test"}

        def cleanup_resource(resource):
            nonlocal cleanup_called
            cleanup_called = True

        pool = AsyncResourcePool(
            name="test_pool",
            max_size=1,
            min_idle=0,
            resource_factory=create_resource,
            resource_cleanup=cleanup_resource
        )

        try:
            await pool.start()
            await pool.stop()
            assert cleanup_called
        finally:
            await pool.stop()


class TestMultiTierStorageManager:
    def test_init_tiers(self):
        manager = MultiTierStorageManager()
        for tier in StorageTier:
            tier_path = manager.get_tier_path(tier)
            assert tier_path.exists()

    def test_assign_to_tier(self):
        manager = MultiTierStorageManager()
        backup_id = "test_backup"
        tier = StorageTier.WARM
        path = manager.assign_to_tier(backup_id, tier)
        assert manager.get_backup_tier(backup_id) == tier
        assert str(path).endswith(tier.value)

    def test_can_store(self):
        manager = MultiTierStorageManager()
        # Test with small size
        assert manager.can_store(StorageTier.HOT, 1024)  # 1KB


class TestResourcePoolManager:
    async def test_initialize(self):
        manager = ResourcePoolManager()
        await manager.initialize()
        assert "file_buffer" in manager._pools
        assert "network_connection" in manager._pools
        assert "compression" in manager._pools
        assert "hash_compute" in manager._pools
        await manager.shutdown()

    async def test_use_resource(self):
        manager = ResourcePoolManager()
        await manager.initialize()
        
        try:
            async with manager.use_resource("file_buffer") as (resource_id, resource):
                assert resource_id is not None
                assert "buffer" in resource
                assert "position" in resource
        finally:
            await manager.shutdown()

    def test_get_all_stats(self):
        manager = ResourcePoolManager()
        stats = manager.get_all_stats()
        assert "pools" in stats
        assert "summary" in stats


class TestBackupManager:
    async def test_create_backup(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            # Create test file
            test_file = Path(temp_dir) / "test.txt"
            test_file.write_text("test content")
            
            manager = BackupManager(backup_dir=temp_dir)
            record = manager.create_backup(str(temp_dir), "test_backup")
            assert record.backup_id.startswith("bak_")
            assert record.name == "test_backup"
            assert record.source_path == str(temp_dir)

    async def test_execute_backup(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            # Create test file
            test_file = Path(temp_dir) / "test.txt"
            test_file.write_text("test content")
            
            manager = BackupManager(backup_dir=temp_dir)
            record = manager.create_backup(str(temp_dir), "test_backup")
            result = await manager.execute_backup(record.backup_id)
            assert result.status == BackupStatus.COMPLETED
            assert result.file_count > 0
            assert result.size_bytes > 0
            assert result.checksum

    async def test_execute_backup_failure(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            manager = BackupManager(backup_dir=temp_dir)
            # Create backup with non-existent source
            record = manager.create_backup("/nonexistent/path", "test_backup")
            with pytest.raises(Exception):
                await manager.execute_backup(record.backup_id)

    def test_list_backups(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            manager = BackupManager(backup_dir=temp_dir)
            # Create test file
            test_file = Path(temp_dir) / "test.txt"
            test_file.write_text("test content")
            
            record = manager.create_backup(str(temp_dir), "test_backup")
            backups = manager.list_backups()
            assert len(backups) >= 1

    def test_delete_backup(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            manager = BackupManager(backup_dir=temp_dir)
            # Create test file
            test_file = Path(temp_dir) / "test.txt"
            test_file.write_text("test content")
            
            record = manager.create_backup(str(temp_dir), "test_backup")
            assert manager.delete_backup(record.backup_id)
            assert manager.get_backup(record.backup_id).status == BackupStatus.DELETED

    async def test_verify_backup_integrity(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            # Create test file
            test_file = Path(temp_dir) / "test.txt"
            test_file.write_text("test content")
            
            manager = BackupManager(backup_dir=temp_dir)
            record = manager.create_backup(str(temp_dir), "test_backup")
            await manager.execute_backup(record.backup_id)
            assert await manager.verify_backup_integrity(record.backup_id)

    def test_get_storage_stats(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            manager = BackupManager(backup_dir=temp_dir)
            stats = manager.get_storage_stats()
            assert isinstance(stats, dict)
            for tier in StorageTier:
                assert tier.value in stats


class TestRecoveryManager:
    async def test_create_recovery_task(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            # Create test file
            test_file = Path(temp_dir) / "test.txt"
            test_file.write_text("test content")
            
            backup_manager = BackupManager(backup_dir=temp_dir)
            record = backup_manager.create_backup(str(temp_dir), "test_backup")
            await backup_manager.execute_backup(record.backup_id)
            
            recovery_manager = RecoveryManager(backup_manager)
            task = recovery_manager.create_recovery_task(record.backup_id, str(Path(temp_dir) / "restore"))
            assert task.recovery_id.startswith("rec_")
            assert task.backup_id == record.backup_id

    async def test_execute_recovery(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            # Create test file
            test_file = Path(temp_dir) / "test.txt"
            test_file.write_text("test content")
            
            backup_manager = BackupManager(backup_dir=temp_dir)
            record = backup_manager.create_backup(str(temp_dir), "test_backup")
            await backup_manager.execute_backup(record.backup_id)
            
            recovery_manager = RecoveryManager(backup_manager)
            restore_dir = Path(temp_dir) / "restore"
            task = recovery_manager.create_recovery_task(record.backup_id, str(restore_dir))
            result = await recovery_manager.execute_recovery(task.recovery_id)
            assert result.status == RecoveryStatus.COMPLETED
            assert result.restored_files > 0
            # Check if file was restored
            restored_file = restore_dir / "test.txt"
            assert restored_file.exists()

    async def test_execute_recovery_failure(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            backup_manager = BackupManager(backup_dir=temp_dir)
            recovery_manager = RecoveryManager(backup_manager)
            # Try to recover non-existent backup
            task = recovery_manager.create_recovery_task("nonexistent", str(Path(temp_dir) / "restore"))
            result = await recovery_manager.execute_recovery(task.recovery_id)
            assert result.status == RecoveryStatus.FAILED


class TestStorageManagementModule:
    async def test_initialize_shutdown(self):
        module = StorageManagementModule()
        await module.initialize()
        assert module._initialized
        await module.shutdown()
        assert not module._initialized

    async def test_create_and_execute_backup(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            # Create test file
            test_file = Path(temp_dir) / "test.txt"
            test_file.write_text("test content")
            
            module = StorageManagementModule()
            await module.initialize()
            
            try:
                record = await module.create_and_execute_backup(
                    str(temp_dir), "test_backup"
                )
                assert record.status == BackupStatus.COMPLETED
                assert record.file_count > 0
            finally:
                await module.shutdown()

    async def test_create_and_execute_recovery(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            # Create test file
            test_file = Path(temp_dir) / "test.txt"
            test_file.write_text("test content")
            
            module = StorageManagementModule()
            await module.initialize()
            
            try:
                # Create backup
                backup_record = await module.create_and_execute_backup(
                    str(temp_dir), "test_backup"
                )
                
                # Create recovery
                restore_dir = Path(temp_dir) / "restore"
                recovery_task = await module.create_and_execute_recovery(
                    backup_record.backup_id, str(restore_dir)
                )
                assert recovery_task.status == RecoveryStatus.COMPLETED
                assert recovery_task.restored_files > 0
            finally:
                await module.shutdown()

    def test_get_observability_metrics(self):
        module = StorageManagementModule()
        metrics = module.get_observability_metrics()
        assert "storage_tiers" in metrics
        assert "resource_pools" in metrics
        assert "backups" in metrics


if __name__ == "__main__":
    pytest.main([__file__, "-v"])
