import pytest
import time
from datetime import datetime, timedelta
from streamsql.modules.lifecycle_manager.tiered_storage import StorageTier, TieredStorage
from streamsql.modules.lifecycle_manager.archive_manager import ArchiveManager, ArchiveFormat
from streamsql.modules.lifecycle_manager.cleanup import CleanupManager
from streamsql.modules.lifecycle_manager.lifecycle import LifecycleManager


def test_storage_tier_enum():
    assert hasattr(StorageTier, "HOT")
    assert hasattr(StorageTier, "COLD")
    assert hasattr(StorageTier, "ARCHIVE")
    assert hasattr(StorageTier, "PURGE")


def test_tiered_storage_get_tier():
    storage = TieredStorage(
        hot_threshold_days=7,
        cold_threshold_days=30,
        archive_threshold_days=90,
    )
    now = time.time()
    hot_time = now - (5 * 24 * 3600)
    cold_time = now - (15 * 24 * 3600)
    archive_time = now - (100 * 24 * 3600)

    assert storage.get_tier(hot_time) == StorageTier.HOT
    assert storage.get_tier(cold_time) == StorageTier.COLD
    assert storage.get_tier(archive_time) == StorageTier.ARCHIVE


def test_tiered_storage_should_migrate():
    storage = TieredStorage(
        hot_threshold_days=7,
        cold_threshold_days=30,
        archive_threshold_days=90,
    )
    now = time.time()
    cold_time = now - (15 * 24 * 3600)

    assert storage.should_migrate(cold_time, StorageTier.HOT) is True
    assert storage.should_migrate(cold_time, StorageTier.COLD) is False


def test_archive_manager_json(tmp_path):
    manager = ArchiveManager(
        archive_dir=str(tmp_path),
        format=ArchiveFormat.JSON,
    )
    data = [{"id": 1, "name": "Test"}, {"id": 2, "name": "Test2"}]
    archive_path = manager.archive(data, "test_table", "2024-01")
    assert archive_path.exists()

    loaded = manager.load(archive_path)
    assert len(loaded) == 2
    assert loaded[0]["id"] == 1


def test_archive_manager_csv(tmp_path):
    manager = ArchiveManager(
        archive_dir=str(tmp_path),
        format=ArchiveFormat.CSV,
    )
    data = [
        {"id": 1, "name": "Test", "value": 10.5},
        {"id": 2, "name": "Test2", "value": 20.5},
    ]
    archive_path = manager.archive(data, "test_table", "2024-01")
    assert archive_path.exists()

    loaded = manager.load(archive_path)
    assert len(loaded) == 2


def test_cleanup_manager_clean_old_files(tmp_path):
    manager = CleanupManager()

    old_file = tmp_path / "old.txt"
    old_file.write_text("old data")
    old_time = time.time() - (100 * 24 * 3600)
    import os
    os.utime(old_file, (old_time, old_time))

    new_file = tmp_path / "new.txt"
    new_file.write_text("new data")

    removed = manager.clean_old_files(str(tmp_path), days=30)
    assert removed >= 1
    assert not old_file.exists()
    assert new_file.exists()


def test_cleanup_manager_clean_by_size(tmp_path):
    manager = CleanupManager()

    for i in range(10):
        f = tmp_path / f"file_{i}.txt"
        f.write_text("x" * 1024)

    removed = manager.clean_by_size(str(tmp_path), max_size_kb=5)
    assert removed >= 5


def test_lifecycle_manager_process_data():
    manager = LifecycleManager(
        hot_threshold_days=7,
        cold_threshold_days=30,
        archive_threshold_days=90,
    )
    now = time.time()
    data_items = [
        {"id": 1, "timestamp": now - (2 * 24 * 3600), "data": "hot"},
        {"id": 2, "timestamp": now - (15 * 24 * 3600), "data": "cold"},
        {"id": 3, "timestamp": now - (100 * 24 * 3600), "data": "archive"},
    ]

    result = manager.process_data(data_items)
    assert len(result["hot"]) == 1
    assert len(result["cold"]) == 1
    assert len(result["archive"]) == 1
    assert result["hot"][0]["id"] == 1
    assert result["cold"][0]["id"] == 2
    assert result["archive"][0]["id"] == 3


def test_lifecycle_manager_run_cycle(tmp_path):
    manager = LifecycleManager(
        hot_threshold_days=7,
        cold_threshold_days=30,
        archive_threshold_days=90,
        archive_dir=str(tmp_path),
    )
    now = time.time()
    data_items = [
        {"id": 1, "timestamp": now - (100 * 24 * 3600), "data": "test"},
    ]

    result = manager.run_cycle(data_items, table_name="test_table")
    assert "archived" in result
    assert result["total_processed"] == 1
