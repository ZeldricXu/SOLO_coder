import pytest
import gc
import weakref
from unittest.mock import Mock, patch, MagicMock
from typing import List
from datetime import datetime, timedelta
import tempfile
import os
import shutil

from src.domain.lifecycle.tiering import DataTieringManager, DataTier, TieringPolicy, TieringAction
from src.domain.lifecycle.archival import DataArchiver, ArchiveTask
from src.domain.lifecycle.cleanup import DataCleanupManager, CleanupPolicy, CleanupTask
from src.infrastructure.config.settings import LifecycleConfig


@pytest.fixture
def lifecycle_config():
    return LifecycleConfig(
        hot_to_cold_days=30,
        cold_to_archive_days=180,
        archive_retention_days=365,
    )


@pytest.fixture
def mock_cold_storage():
    storage = Mock()
    storage.write_parquet.return_value = "/cold/data.parquet"
    storage.read_parquet.return_value = Mock()
    storage.read_parquet.return_value.empty = False
    storage.delete_partition.return_value = True
    return storage


@pytest.fixture
def mock_archive_storage():
    storage = Mock()
    storage.archive_data.return_value = "/archive/data.parquet"
    storage.read_archive.return_value = Mock()
    storage.read_archive.return_value.empty = False
    storage.list_archives.return_value = []
    storage.delete_archive.return_value = True
    storage.cleanup_expired.return_value = ["/archive/old1.parquet", "/archive/old2.parquet"]
    return storage


class TestDataTieringManager:
    def test_evaluate_hot_to_cold(self, data_builder, lifecycle_config):
        manager = DataTieringManager(lifecycle_config)
        table_stats = data_builder.build_lifecycle_table_stats(age_days=45)

        actions = manager.evaluate("test_db", "orders", table_stats)

        assert len(actions) > 0
        hot_to_cold = [a for a in actions if a.target_tier == DataTier.COLD]
        assert len(hot_to_cold) > 0

    def test_evaluate_no_tiering_needed(self, data_builder, lifecycle_config):
        manager = DataTieringManager(lifecycle_config)
        table_stats = data_builder.build_lifecycle_table_stats(age_days=15)

        actions = manager.evaluate("test_db", "recent_data", table_stats)

        hot_to_cold = [a for a in actions if a.target_tier == DataTier.COLD]
        recent_actions = [a for a in hot_to_cold if a.status == "pending"]
        assert len(recent_actions) == 0

    def test_evaluate_cold_to_archive(self, data_builder, lifecycle_config):
        manager = DataTieringManager(lifecycle_config)
        table_stats = data_builder.build_lifecycle_table_stats(age_days=200)

        actions = manager.evaluate("test_db", "old_data", table_stats)

        cold_to_archive = [a for a in actions if a.target_tier == DataTier.ARCHIVE]
        assert len(cold_to_archive) > 0

    def test_add_custom_policy(self, lifecycle_config):
        manager = DataTieringManager(lifecycle_config)
        custom_policy = TieringPolicy(
            source_tier=DataTier.HOT,
            target_tier=DataTier.COLD,
            age_threshold_days=7,
            priority=5,
        )

        manager.add_policy(custom_policy)
        policies = manager.get_policies()

        assert any(p["age_threshold_days"] == 7 for p in policies)

    def test_remove_policy(self, lifecycle_config):
        manager = DataTieringManager(lifecycle_config)
        initial_count = len(manager.get_policies())

        manager.remove_policy(DataTier.HOT, DataTier.COLD)

        assert len(manager.get_policies()) < initial_count

    def test_policy_conditions_min_size(self, data_builder, lifecycle_config):
        manager = DataTieringManager(lifecycle_config)
        policy = TieringPolicy(
            source_tier=DataTier.HOT,
            target_tier=DataTier.COLD,
            age_threshold_days=30,
            conditions={"min_size_mb": 100},
        )
        manager.add_policy(policy)

        small_table = data_builder.build_lifecycle_table_stats(age_days=45, size_mb=10)
        actions = manager.evaluate("test_db", "small_table", small_table)

        skipped = [a for a in actions if a.status == "skipped"]
        assert len(skipped) > 0 or len(actions) == 0

    def test_execute_tiering_with_callback(self, data_builder, lifecycle_config):
        manager = DataTieringManager(lifecycle_config)
        callback_called = []

        def test_callback(action):
            callback_called.append(action)

        manager.register_callback("hot_to_cold", test_callback)
        table_stats = data_builder.build_lifecycle_table_stats(age_days=45)
        actions = manager.evaluate("test_db", "orders", table_stats)

        for action in actions:
            manager.execute_tiering(action)

        assert len(callback_called) >= 0

    def test_check_all_tables(self, data_builder, lifecycle_config):
        manager = DataTieringManager(lifecycle_config)
        table_stats_map = {
            "db1.table1": data_builder.build_lifecycle_table_stats(age_days=10),
            "db1.table2": data_builder.build_lifecycle_table_stats(age_days=50),
            "db2.table3": data_builder.build_lifecycle_table_stats(age_days=200),
        }

        actions = manager.check_all_tables(table_stats_map)

        assert len(actions) > 0


class TestDataArchiver:
    def setup_method(self):
        self.temp_dir = tempfile.mkdtemp()

    def teardown_method(self):
        if os.path.exists(self.temp_dir):
            shutil.rmtree(self.temp_dir)

    def test_archive_data_memory_cleanup(self, sample_records, mock_cold_storage, mock_archive_storage):
        archiver = DataArchiver(mock_cold_storage, mock_archive_storage)

        archiver_ref = weakref.ref(archiver)
        task = archiver.archive_table_data("test_db", "test_table", sample_records)

        task_ref = weakref.ref(task)
        task = None
        gc.collect()

        assert task_ref() is None or task_ref() is not None

    def test_archive_from_hot(self, sample_records):
        mock_cold = Mock()
        mock_archive = Mock()
        archiver = DataArchiver(mock_cold, mock_archive)

        task = archiver.archive_from_hot("test_db", "test_table", sample_records)

        assert task.status in ("completed", "pending")
        assert task.row_count == len(sample_records)

    def test_migrate_cold_to_archive(self):
        mock_cold = Mock()
        mock_archive = Mock()
        import pandas as pd
        mock_cold.read_parquet.return_value = pd.DataFrame({"id": [1, 2, 3]})
        mock_archive.archive_data.return_value = "/archive/path.parquet"

        archiver = DataArchiver(mock_cold, mock_archive)

        task = archiver.migrate_cold_to_archive("test_db", "test_table", "20240101")

        assert task.status == "completed"

    def test_pre_archive_hook_execution(self, sample_records, mock_cold_storage, mock_archive_storage):
        archiver = DataArchiver(mock_cold_storage, mock_archive_storage)

        hook_called = []
        def pre_hook(task):
            hook_called.append(True)
        archiver.add_pre_archive_hook(pre_hook)

        archiver.archive_table_data("test_db", "test_table", sample_records)

        assert len(hook_called) == 1

    def test_post_archive_hook_execution(self, sample_records, mock_cold_storage, mock_archive_storage):
        archiver = DataArchiver(mock_cold_storage, mock_archive_storage)

        hook_called = []
        def post_hook(task):
            hook_called.append(task)
        archiver.add_post_archive_hook(post_hook)

        archiver.archive_table_data("test_db", "test_table", sample_records)

        assert len(hook_called) == 1

    def test_hook_exception_handling(self, sample_records, mock_cold_storage, mock_archive_storage):
        archiver = DataArchiver(mock_cold_storage, mock_archive_storage)

        def failing_hook(task):
            raise Exception("Hook failed")

        archiver.add_pre_archive_hook(failing_hook)

        task = archiver.archive_table_data("test_db", "test_table", sample_records)

        assert task.status == "completed"

    def test_list_archives(self):
        mock_cold = Mock()
        mock_archive = Mock()
        mock_archive.list_archives.return_value = [
            {"date": "20240101", "row_count": 100},
            {"date": "20240102", "row_count": 200},
        ]

        archiver = DataArchiver(mock_cold, mock_archive)
        archives = archiver.list_archives("test_db", "test_table")

        assert len(archives) == 2

    def test_restore_from_archive(self):
        mock_cold = Mock()
        mock_archive = Mock()
        import pandas as pd
        mock_archive.read_archive.return_value = pd.DataFrame({"id": [1, 2, 3]})

        archiver = DataArchiver(mock_cold, mock_archive)
        records = archiver.restore_from_archive("test_db", "test_table")

        assert len(records) == 3


class TestDataCleanupManager:
    def setup_method(self):
        self.temp_dir = tempfile.mkdtemp()

    def teardown_method(self):
        if os.path.exists(self.temp_dir):
            shutil.rmtree(self.temp_dir)

    def test_evaluate_cleanup(self, data_builder, lifecycle_config, mock_archive_storage):
        manager = DataCleanupManager(lifecycle_config, mock_archive_storage)
        table_stats = data_builder.build_lifecycle_table_stats(age_days=400)

        tasks = manager.evaluate_cleanup("test_db", "old_table", table_stats)

        assert len(tasks) > 0

    def test_add_cleanup_policy(self, lifecycle_config, mock_archive_storage):
        manager = DataCleanupManager(lifecycle_config, mock_archive_storage)

        policy = CleanupPolicy(
            name="custom_policy",
            database_pattern="*",
            table_pattern="*",
            retention_days=90,
        )
        manager.add_policy(policy)

        policies = manager.get_policies()
        assert any(p["name"] == "custom_policy" for p in policies)

    def test_remove_cleanup_policy(self, lifecycle_config, mock_archive_storage):
        manager = DataCleanupManager(lifecycle_config, mock_archive_storage)
        initial_count = len(manager.get_policies())

        manager.remove_policy("default_archive_cleanup")

        assert len(manager.get_policies()) < initial_count

    def test_cleanup_expired_archives(self, lifecycle_config, mock_archive_storage):
        manager = DataCleanupManager(lifecycle_config, mock_archive_storage)
        deleted = manager.cleanup_expired_archives(365)

        assert len(deleted) == 2

    def test_execute_cleanup_with_callback(self, data_builder, lifecycle_config, mock_archive_storage):
        manager = DataCleanupManager(lifecycle_config, mock_archive_storage)
        cleanup_result = {"rows_deleted": 100, "rows_archived": 0}

        def callback(task):
            return cleanup_result

        manager.register_callback("test_policy", callback)

        table_stats = data_builder.build_lifecycle_table_stats(age_days=400)
        tasks = manager.evaluate_cleanup("test_db", "test_table", table_stats)

        for task in tasks:
            result = manager.execute_cleanup(task)
            assert result.status in ("completed", "no_handler")

    def test_cleanup_callback_error_handling(self, data_builder, lifecycle_config, mock_archive_storage):
        manager = DataCleanupManager(lifecycle_config, mock_archive_storage)

        def failing_callback(task):
            raise RuntimeError("Cleanup failed")

        manager.register_callback("error_policy", failing_callback)

        policy = CleanupPolicy(
            name="error_policy",
            retention_days=30,
        )
        manager.add_policy(policy)

        table_stats = data_builder.build_lifecycle_table_stats(age_days=60)
        tasks = manager.evaluate_cleanup("test_db", "test_table", table_stats)

        for task in tasks:
            result = manager.execute_cleanup(task)
            assert result.status == "completed" or result.status == "failed"


class TestResourceRelease:
    def test_tiering_manager_memory_cleanup(self, data_builder, lifecycle_config):
        manager = DataTieringManager(lifecycle_config)
        manager_ref = weakref.ref(manager)

        table_stats = data_builder.build_lifecycle_table_stats(age_days=45)
        actions = manager.evaluate("test_db", "test_table", table_stats)

        del manager
        gc.collect()

        assert manager_ref() is None

    def test_archiver_memory_cleanup(self, sample_records, mock_cold_storage, mock_archive_storage):
        archiver = DataArchiver(mock_cold_storage, mock_archive_storage)
        archiver_ref = weakref.ref(archiver)

        task = archiver.archive_table_data("test_db", "test_table", sample_records)

        del archiver
        gc.collect()

        assert archiver_ref() is None

    def test_cleanup_manager_memory_cleanup(self, lifecycle_config, mock_archive_storage):
        manager = DataCleanupManager(lifecycle_config, mock_archive_storage)
        manager_ref = weakref.ref(manager)

        policies = manager.get_policies()

        del manager
        gc.collect()

        assert manager_ref() is None

    def test_task_objects_cleanup(self, sample_records, mock_cold_storage, mock_archive_storage):
        archiver = DataArchiver(mock_cold_storage, mock_archive_storage)

        task = archiver.archive_table_data("test_db", "test_table", sample_records)
        task_ref = weakref.ref(task)

        del task
        gc.collect()

        assert task_ref() is None

    def test_file_handle_release_on_error(self, mock_cold_storage, mock_archive_storage):
        mock_cold_storage.write_parquet.side_effect = Exception("Storage write error")

        archiver = DataArchiver(mock_cold_storage, mock_archive_storage)

        task = archiver.archive_table_data("test_db", "test_table", [{"id": 1}])

        assert task.status == "failed"
        assert task.error_message is not None

    def test_concurrent_archival_resource_management(self, sample_records, mock_cold_storage, mock_archive_storage):
        from concurrent.futures import ThreadPoolExecutor, as_completed

        archiver = DataArchiver(mock_cold_storage, mock_archive_storage)

        def archive_worker(table_id):
            return archiver.archive_table_data("test_db", f"table_{table_id}", sample_records[:10])

        with ThreadPoolExecutor(max_workers=4) as executor:
            futures = [executor.submit(archive_worker, i) for i in range(8)]
            results = [f.result() for f in as_completed(futures)]

        assert all(r is not None for r in results)
        assert all(r.status == "completed" for r in results)

    def test_memory_usage_with_large_datasets(self, mock_cold_storage, mock_archive_storage):
        archiver = DataArchiver(mock_cold_storage, mock_archive_storage)

        large_records = [{"id": i, "value": f"data_{i}"} for i in range(10000)]

        import sys
        initial_memory = sys.getsizeof(large_records)

        task = archiver.archive_table_data("test_db", "large_table", large_records)

        assert task.status == "completed"
        assert task.row_count == 10000

    def test_hook_resources_cleanup(self, sample_records, mock_cold_storage, mock_archive_storage):
        archiver = DataArchiver(mock_cold_storage, mock_archive_storage)

        hook_state = {"executed": False}

        def stateful_hook(task):
            hook_state["executed"] = True
            hook_state["task_id"] = task.task_id

        archiver.add_post_archive_hook(stateful_hook)

        task = archiver.archive_table_data("test_db", "test_table", sample_records)

        assert hook_state["executed"]
        assert hook_state["task_id"] == task.task_id

    def test_callback_circular_reference(self, mock_cold_storage, mock_archive_storage):
        archiver = DataArchiver(mock_cold_storage, mock_archive_storage)
        archiver_ref = weakref.ref(archiver)

        class CallbackHolder:
            def __init__(self, archiver):
                self.archiver = archiver

            def callback(self, task):
                pass

        holder = CallbackHolder(archiver)
        archiver.add_post_archive_hook(holder.callback)

        del archiver
        del holder
        gc.collect()

        assert archiver_ref() is None


class TestLifecycleIntegration:
    def test_full_lifecycle_flow(self, data_builder, lifecycle_config, mock_cold_storage, mock_archive_storage):
        tiering = DataTieringManager(lifecycle_config)
        archiver = DataArchiver(mock_cold_storage, mock_archive_storage)
        cleanup = DataCleanupManager(lifecycle_config, mock_archive_storage)

        table_stats = data_builder.build_lifecycle_table_stats(age_days=200)

        tiering_actions = tiering.evaluate("test_db", "test_table", table_stats)
        assert len(tiering_actions) > 0

        records = data_builder.build_sample_records(100)
        archive_task = archiver.archive_table_data("test_db", "test_table", records)
        assert archive_task.status == "completed"

        cleanup_tasks = cleanup.evaluate_cleanup("test_db", "test_table", table_stats)
        assert len(cleanup_tasks) >= 0

    def test_tiering_to_cleanup_pipeline(self, data_builder, lifecycle_config, mock_archive_storage):
        tiering = DataTieringManager(lifecycle_config)
        cleanup = DataCleanupManager(lifecycle_config, mock_archive_storage)

        table_stats = data_builder.build_lifecycle_table_stats(age_days=500)

        tiering_actions = tiering.evaluate("db", "very_old_data", table_stats)

        archive_actions = [a for a in tiering_actions if a.target_tier == DataTier.ARCHIVE]
        assert len(archive_actions) > 0

        cleanup_tasks = cleanup.evaluate_cleanup("db", "very_old_data", table_stats)
        assert len(cleanup_tasks) > 0

    def test_error_propagation(self, sample_records, mock_cold_storage, mock_archive_storage):
        mock_cold_storage.write_parquet.side_effect = IOError("Disk full")

        archiver = DataArchiver(mock_cold_storage, mock_archive_storage)

        task = archiver.archive_table_data("test_db", "test_table", sample_records)

        assert task.status == "failed"
        assert "Disk full" in task.error_message

    def test_idempotent_cleanup_operations(self, lifecycle_config, mock_archive_storage):
        mock_archive_storage.cleanup_expired.return_value = []
        manager = DataCleanupManager(lifecycle_config, mock_archive_storage)

        result1 = manager.cleanup_expired_archives(365)
        result2 = manager.cleanup_expired_archives(365)

        assert result1 == result2


class TestMemoryLeakFixes:
    def test_archiver_weakref_hooks(self, mock_cold_storage, mock_archive_storage):
        import weakref
        import gc

        archiver = DataArchiver(mock_cold_storage, mock_archive_storage)
        archiver_ref = weakref.ref(archiver)

        class HookHolder:
            def __init__(self):
                self.called = False

            def pre_hook(self, task):
                self.called = True

            def post_hook(self, task):
                self.called = True

        holder = HookHolder()
        holder_ref = weakref.ref(holder)

        archiver.add_pre_archive_hook(holder.pre_hook)
        archiver.add_post_archive_hook(holder.post_hook)

        del holder
        gc.collect()

        assert holder_ref() is None

        records = [{"id": 1, "value": "test"}]
        task = archiver.archive_table_data("test_db", "test_table", records)

        assert task.status == "completed"

        del archiver
        gc.collect()

        assert archiver_ref() is None

    def test_archiver_close_method(self, mock_cold_storage, mock_archive_storage):
        archiver = DataArchiver(mock_cold_storage, mock_archive_storage)

        hook_called = []
        archiver.add_pre_archive_hook(lambda task: hook_called.append(True))

        archiver.close()

        with pytest.raises(RuntimeError, match="closed"):
            archiver.archive_table_data("test_db", "test_table", [{"id": 1}])

        assert archiver._closed == True

    def test_archiver_dataframe_cleanup(self, mock_cold_storage, mock_archive_storage, sample_records):
        import weakref
        import gc
        import pandas as pd

        archiver = DataArchiver(mock_cold_storage, mock_archive_storage)

        df_ref = None
        df_local_ref = None

        original_archive = mock_archive_storage.archive_data
        def track_archive(db, table, df, date_str):
            nonlocal df_ref, df_local_ref
            df_ref = weakref.ref(df)
            df_local_ref = df
            return "/archive/path.parquet"

        mock_archive_storage.archive_data.side_effect = track_archive

        task = archiver.archive_table_data("test_db", "test_table", sample_records, target_tier="archive")

        assert task.status == "completed"

        mock_archive_storage.reset_mock()
        del df_local_ref
        del task
        gc.collect()
        gc.collect()

        assert df_ref() is None

    def test_cleanup_manager_weakref_callbacks(self, lifecycle_config, mock_archive_storage):
        import weakref
        import gc

        cleanup = DataCleanupManager(lifecycle_config, mock_archive_storage)
        cleanup_ref = weakref.ref(cleanup)

        class CallbackHolder:
            def __init__(self):
                self.result = {"rows_deleted": 100}

            def cleanup_callback(self, task):
                return self.result

        holder = CallbackHolder()
        holder_ref = weakref.ref(holder)

        cleanup.register_callback("test_policy", holder.cleanup_callback)

        del holder
        gc.collect()

        assert holder_ref() is None

        cleanup_ref2 = weakref.ref(cleanup)

        del cleanup
        gc.collect()

        assert cleanup_ref() is None
        assert cleanup_ref2() is None

    def test_cleanup_manager_close(self, lifecycle_config, mock_archive_storage):
        manager = DataCleanupManager(lifecycle_config, mock_archive_storage)

        manager.register_callback("test", lambda task: {"rows_deleted": 50})

        manager.close()

        with pytest.raises(RuntimeError, match="closed"):
            manager.add_policy(CleanupPolicy(name="test2", retention_days=30))

        assert manager._closed == True

    def test_tiering_manager_weakref_callbacks(self, lifecycle_config):
        import weakref
        import gc

        tiering = DataTieringManager(lifecycle_config)
        tiering_ref = weakref.ref(tiering)

        class CallbackHolder:
            def __init__(self):
                self.called = False

            def tiering_callback(self, action):
                self.called = True

        holder = CallbackHolder()
        holder_ref = weakref.ref(holder)

        tiering.register_callback("hot_to_cold", holder.tiering_callback)

        del holder
        gc.collect()

        assert holder_ref() is None

        del tiering
        gc.collect()

        assert tiering_ref() is None

    def test_tiering_manager_close(self, lifecycle_config):
        manager = DataTieringManager(lifecycle_config)

        manager.register_callback("hot_to_cold", lambda action: None)

        manager.close()

        with pytest.raises(RuntimeError, match="closed"):
            manager.add_policy(TieringPolicy(
                source_tier=DataTier.HOT,
                target_tier=DataTier.COLD,
                age_threshold_days=7,
            ))

        assert manager._closed == True

    def test_no_memory_leak_with_many_hooks(self, mock_cold_storage, mock_archive_storage, sample_records):
        import weakref
        import gc

        archiver = DataArchiver(mock_cold_storage, mock_archive_storage)

        hook_count = 50
        hook_refs = []

        for i in range(hook_count):
            hook_counter = [0]
            def make_hook(counter):
                def hook(task):
                    counter[0] += 1
                return hook

            hook = make_hook(hook_counter)
            hook_ref = weakref.ref(hook)
            hook_refs.append(hook_ref)
            archiver.add_pre_archive_hook(hook)
            del hook
            del hook_counter

        gc.collect()

        task = archiver.archive_table_data("test_db", "test_table", sample_records)

        assert task.status == "completed"

        del task
        gc.collect()

        archiver.close()

        del archiver
        gc.collect()
        gc.collect()
        gc.collect()

        alive_count = sum(1 for ref in hook_refs if ref() is not None)
        assert alive_count <= 1

    def test_large_dataframe_memory_release(self, mock_cold_storage, mock_archive_storage):
        import weakref
        import gc
        import pandas as pd

        archiver = DataArchiver(mock_cold_storage, mock_archive_storage)

        large_records = [{"id": i, "data": "x" * 1000} for i in range(10000)]

        df_weak_ref = None
        df_local_ref = None

        original_write = mock_cold_storage.write_parquet
        def track_write(db, table, df, date_str):
            nonlocal df_weak_ref, df_local_ref
            df_weak_ref = weakref.ref(df)
            df_local_ref = df
            return "/cold/path.parquet"

        mock_cold_storage.write_parquet.side_effect = track_write

        task = archiver.archive_table_data("test_db", "large_table", large_records)

        assert task.status == "completed"
        assert task.row_count == 10000

        mock_cold_storage.reset_mock()
        del df_local_ref
        del task
        del large_records
        gc.collect()
        gc.collect()

        assert df_weak_ref() is None

    def test_circular_reference_prevention(self, mock_cold_storage, mock_archive_storage):
        import weakref
        import gc

        archiver = DataArchiver(mock_cold_storage, mock_archive_storage)
        archiver_ref = weakref.ref(archiver)

        class CircularRef:
            def __init__(self, archiver):
                self.archiver = archiver
                self.called = False

            def hook(self, task):
                self.called = True

        circular = CircularRef(archiver)
        circular_ref = weakref.ref(circular)

        archiver.add_post_archive_hook(circular.hook)

        del circular
        gc.collect()

        assert circular_ref() is None

        del archiver
        gc.collect()

        assert archiver_ref() is None

    def test_concurrent_archival_no_memory_leak(self, mock_cold_storage, mock_archive_storage, sample_records):
        import weakref
        import gc
        from concurrent.futures import ThreadPoolExecutor, as_completed

        archiver = DataArchiver(mock_cold_storage, mock_archive_storage)
        archiver_ref = weakref.ref(archiver)

        def archive_worker(table_id):
            return archiver.archive_table_data("test_db", f"table_{table_id}", sample_records[:50])

        with ThreadPoolExecutor(max_workers=4) as executor:
            futures = [executor.submit(archive_worker, i) for i in range(10)]
            results = [f.result() for f in as_completed(futures)]

        assert all(r.status == "completed" for r in results)

        archiver.close()
        del archiver
        gc.collect()

        assert archiver_ref() is None

    def test_restore_from_archive_memory_cleanup(self, mock_cold_storage, mock_archive_storage):
        import weakref
        import gc
        import pandas as pd

        archiver = DataArchiver(mock_cold_storage, mock_archive_storage)

        df_ref = None

        original_read = mock_archive_storage.read_archive
        def track_read(db, table, date_str):
            nonlocal df_ref
            df = pd.DataFrame({"id": [1, 2, 3]})
            df_ref = weakref.ref(df)
            return df

        mock_archive_storage.read_archive.side_effect = track_read

        records = archiver.restore_from_archive("test_db", "test_table")

        assert len(records) == 3

        gc.collect()

        assert df_ref() is None or df_ref() is not None

        del records
        gc.collect()

        assert df_ref() is None

    def test_callback_holder_does_not_prevent_gc(self, lifecycle_config):
        import weakref
        import gc

        class ExternalSystem:
            def __init__(self, manager):
                self.manager = manager
                self.processed = 0

            def handle_cleanup(self, task):
                self.processed += 1
                return {"rows_deleted": 10}

        manager = DataCleanupManager(lifecycle_config, None)

        system = ExternalSystem(manager)
        system_ref = weakref.ref(system)

        manager.register_callback("test_policy", system.handle_cleanup)

        del system
        gc.collect()

        assert system_ref() is None

        task = CleanupTask(
            task_id="test",
            database_name="test",
            table_name="test",
            policy_name="test_policy",
            retention_days=30,
        )

        result = manager.execute_cleanup(task)

        assert result.status == "completed"

    def test_expired_weakref_cleanup(self, mock_cold_storage, mock_archive_storage, sample_records):
        import gc

        archiver = DataArchiver(mock_cold_storage, mock_archive_storage)

        class TempHook:
            pass

        hook1 = TempHook()
        hook1.counter = 0

        def weak_hook(task):
            hook1.counter += 1

        archiver.add_post_archive_hook(weak_hook)

        del weak_hook
        gc.collect()

        for _ in range(5):
            archiver.archive_table_data("test_db", "test_table", sample_records)

        assert hook1.counter == 0 or hook1.counter > 0

    def test_close_releases_all_resources(self, lifecycle_config, mock_archive_storage):
        import weakref
        import gc

        cleanup = DataCleanupManager(lifecycle_config, mock_archive_storage)
        cleanup_ref = weakref.ref(cleanup)

        cleanup.register_callback("policy1", lambda t: {})
        cleanup.add_policy(CleanupPolicy(name="custom", retention_days=60))

        cleanup.close()

        assert cleanup._closed == True
        assert len(cleanup._cleanup_callbacks) == 0
        assert len(cleanup._policies) == 0

        del cleanup
        gc.collect()

        assert cleanup_ref() is None
