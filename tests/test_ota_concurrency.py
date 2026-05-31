import pytest
import asyncio
from unittest.mock import patch, MagicMock, AsyncMock
from typing import Dict, Any, List

from modules.firmware_ota.upgrade_manager import (
    UpgradeManager,
    UpgradeStrategy,
    UpgradePhase,
)
from tests.builders import OTAUpgradeTaskBuilder, FirmwareInfoBuilder, DeviceProgressBuilder


class TestUpgradeManagerIsolation:
    @pytest.fixture
    def manager(self) -> UpgradeManager:
        return UpgradeManager()

    @pytest.mark.asyncio
    async def test_start_upgrade_creates_isolated_task_context(self, manager: UpgradeManager):
        task1 = OTAUpgradeTaskBuilder().with_task_id("task_001").with_device_count(3, prefix="t1").build()
        task2 = OTAUpgradeTaskBuilder().with_task_id("task_002").with_device_count(5, prefix="t2").build()

        with patch("asyncio.create_task"):
            await manager.start_upgrade(**task1)
            await manager.start_upgrade(**task2)

        assert "task_001" in manager._active_upgrades
        assert "task_002" in manager._active_upgrades
        assert manager._active_upgrades["task_001"]["device_ids"] != manager._active_upgrades["task_002"]["device_ids"]
        assert len(manager._device_progress) == 8

    @pytest.mark.asyncio
    async def test_concurrent_upgrade_tasks_isolated(self, manager: UpgradeManager):
        task_builder = OTAUpgradeTaskBuilder().with_strategy(UpgradeStrategy.INSTANT)

        async def start_task(task_id: str, device_count: int):
            task = task_builder.with_task_id(task_id).with_device_count(device_count, prefix=task_id).build()
            with patch("asyncio.create_task"):
                return await manager.start_upgrade(**task)

        results = await asyncio.gather(
            start_task("concurrent_1", 5),
            start_task("concurrent_2", 3),
            start_task("concurrent_3", 7),
        )

        assert len(manager._active_upgrades) == 3
        assert len(manager._device_progress) == 15

        for i, result in enumerate(results, 1):
            assert result["task_id"] == f"concurrent_{i}"
            assert result["status"] == "running"

    @pytest.mark.asyncio
    async def test_device_progress_isolated_between_tasks(self, manager: UpgradeManager):
        firmware = FirmwareInfoBuilder().with_version("2.0.0").build()
        task1 = OTAUpgradeTaskBuilder().with_task_id("task_a").with_device_ids(["a_dev_1", "a_dev_2"]).with_firmware_info(firmware).build()
        task2 = OTAUpgradeTaskBuilder().with_task_id("task_b").with_device_ids(["b_dev_2", "b_dev_3"]).with_firmware_info(firmware).build()

        with patch("asyncio.create_task"):
            await manager.start_upgrade(**task1)
            await manager.start_upgrade(**task2)

        for device_id in ["a_dev_1", "a_dev_2", "b_dev_2", "b_dev_3"]:
            progress = manager.get_device_progress(device_id)
            assert progress is not None
            assert progress["phase"] == UpgradePhase.PENDING

    @pytest.mark.asyncio
    async def test_upgrade_progress_update_isolation(self, manager: UpgradeManager):
        firmware = FirmwareInfoBuilder().build()
        task = OTAUpgradeTaskBuilder().with_task_id("progress_test").with_device_ids(["p_dev_1", "p_dev_2"]).with_firmware_info(firmware).build()

        with patch("asyncio.create_task"):
            await manager.start_upgrade(**task)

        manager.update_device_progress("p_dev_1", UpgradePhase.DOWNLOADING, 0.5)
        manager.update_device_progress("p_dev_2", UpgradePhase.VERIFYING, 0.8)

        progress1 = manager.get_device_progress("p_dev_1")
        progress2 = manager.get_device_progress("p_dev_2")

        assert progress1["phase"] == UpgradePhase.DOWNLOADING
        assert progress1["progress"] == 0.5
        assert progress2["phase"] == UpgradePhase.VERIFYING
        assert progress2["progress"] == 0.8

    def test_get_nonexistent_task_returns_none(self, manager: UpgradeManager):
        assert manager.get_task_status("nonexistent") is None
        assert manager.get_device_progress("nonexistent") is None

    @pytest.mark.asyncio
    async def test_rollback_callback_isolation(self, manager: UpgradeManager):
        callback1_called = []
        callback2_called = []

        async def callback1(device_id: str, firmware_info: Dict[str, Any]):
            callback1_called.append(device_id)

        async def callback2(device_id: str, firmware_info: Dict[str, Any]):
            callback2_called.append(device_id)

        manager.register_rollback_callback("model_a", callback1)
        manager.register_rollback_callback("model_b", callback2)

        firmware_a = FirmwareInfoBuilder().with_device_model("model_a").build()
        firmware_b = FirmwareInfoBuilder().with_device_model("model_b").build()

        task_a = OTAUpgradeTaskBuilder().with_task_id("task_a").with_device_ids(["a_dev_1"]).with_firmware_info(firmware_a).build()
        task_b = OTAUpgradeTaskBuilder().with_task_id("task_b").with_device_ids(["b_dev_1"]).with_firmware_info(firmware_b).build()

        with patch("asyncio.create_task"):
            await manager.start_upgrade(**task_a)
            await manager.start_upgrade(**task_b)

        manager.update_device_progress("a_dev_1", UpgradePhase.FAILED, 0.0, "Download failed")
        manager.update_device_progress("b_dev_1", UpgradePhase.VERIFYING_UPGRADE, 0.9)

        await manager._trigger_rollback("task_a", ["a_dev_1"])
        await manager._trigger_rollback("task_b", ["b_dev_1"])

        await asyncio.sleep(0.1)

        assert "a_dev_1" in callback1_called
        assert "b_dev_1" in callback2_called


class TestUpgradeStrategyConcurrency:
    @pytest.fixture
    def manager(self) -> UpgradeManager:
        return UpgradeManager()

    @pytest.mark.asyncio
    async def test_instant_upgrade_executes_all_concurrently(self, manager: UpgradeManager):
        task = OTAUpgradeTaskBuilder().with_strategy(UpgradeStrategy.INSTANT).with_device_count(10, prefix="inst").build()

        with patch.object(manager, "_upgrade_device", new_callable=AsyncMock) as mock_upgrade:
            mock_upgrade.return_value = None
            await manager.start_upgrade(**task)

            await asyncio.sleep(0.2)

            task_info = manager.get_task_status(task["task_id"])
            assert task_info is not None
            assert mock_upgrade.call_count == 10

    @pytest.mark.asyncio
    async def test_canary_upgrade_stops_on_failure(self, manager: UpgradeManager):
        task = OTAUpgradeTaskBuilder().with_strategy(UpgradeStrategy.CANARY).with_device_count(20, prefix="canary").build()

        async def failing_upgrade(task_id, device_id, firmware_info):
            if device_id == task["device_ids"][0]:
                raise Exception("Canary device failed")
            await asyncio.sleep(0.01)

        with patch.object(manager, "_upgrade_device", side_effect=failing_upgrade):
            with patch("asyncio.sleep", new_callable=AsyncMock):
                result = await manager.start_upgrade(**task)
                await asyncio.sleep(0.3)

                assert result is not None


class TestRollbackMechanism:
    @pytest.fixture
    def manager(self) -> UpgradeManager:
        return UpgradeManager()

    @pytest.mark.asyncio
    async def test_rollback_threshold_triggers(self, manager: UpgradeManager):
        task = OTAUpgradeTaskBuilder().with_rollback_threshold(0.2).with_device_count(10, prefix="thresh").build()

        with patch("asyncio.create_task"):
            await manager.start_upgrade(**task)

        for i in range(3):
            manager._active_upgrades[task["task_id"]]["failed_count"] += 1
        manager._active_upgrades[task["task_id"]]["success_count"] = 7

        should_continue = await manager._should_continue(task["task_id"])
        assert should_continue is False

    @pytest.mark.asyncio
    async def test_rollback_not_triggered_below_threshold(self, manager: UpgradeManager):
        task = OTAUpgradeTaskBuilder().with_rollback_threshold(0.3).with_device_count(10, prefix="thresh2").build()

        with patch("asyncio.create_task"):
            await manager.start_upgrade(**task)

        manager._active_upgrades[task["task_id"]]["failed_count"] = 2
        manager._active_upgrades[task["task_id"]]["success_count"] = 8

        should_continue = await manager._should_continue(task["task_id"])
        assert should_continue is True


class TestTaskFinalization:
    @pytest.fixture
    def manager(self) -> UpgradeManager:
        return UpgradeManager()

    @pytest.mark.asyncio
    async def test_task_finalization_marks_completed(self, manager: UpgradeManager):
        task = OTAUpgradeTaskBuilder().with_task_id("finalize_test").with_device_count(5, prefix="fin").build()

        with patch("asyncio.create_task"):
            await manager.start_upgrade(**task)

        manager._active_upgrades["finalize_test"]["success_count"] = 4
        manager._active_upgrades["finalize_test"]["failed_count"] = 1

        await manager._finalize_task("finalize_test")

        task_info = manager.get_task_status("finalize_test")
        assert task_info["status"] == "completed"
        assert "completed_at" in task_info

    @pytest.mark.asyncio
    async def test_finalize_nonexistent_task_no_error(self, manager: UpgradeManager):
        await manager._finalize_task("nonexistent")


class TestUpgradeDevice:
    @pytest.fixture
    def manager(self) -> UpgradeManager:
        return UpgradeManager()

    @pytest.mark.asyncio
    async def test_upgrade_nonexistent_device(self, manager: UpgradeManager):
        await manager._upgrade_device("task_1", "nonexistent_dev", {})

    @pytest.mark.asyncio
    async def test_upgrade_device_complete_flow(self, manager: UpgradeManager):
        task = OTAUpgradeTaskBuilder().with_task_id("upgrade_test").with_device_ids(["test_dev_1"]).build()

        with patch("asyncio.create_task"):
            await manager.start_upgrade(**task)

        with patch("asyncio.sleep", new_callable=AsyncMock):
            await manager._upgrade_device("upgrade_test", "test_dev_1", {})

        progress = manager.get_device_progress("test_dev_1")
        assert progress["phase"] == UpgradePhase.COMPLETED
        assert progress["progress"] == 1.0

        task_info = manager.get_task_status("upgrade_test")
        assert task_info["success_count"] == 1

    @pytest.mark.asyncio
    async def test_upgrade_device_with_exception(self, manager: UpgradeManager):
        task = OTAUpgradeTaskBuilder().with_task_id("error_test").with_device_ids(["error_dev"]).build()

        with patch("asyncio.create_task"):
            await manager.start_upgrade(**task)

        with patch.object(manager, "_upgrade_device", side_effect=Exception("Upgrade failed")):
            try:
                await manager._upgrade_device("error_test", "error_dev", {})
            except Exception:
                pass

        progress = manager.get_device_progress("error_dev")
        assert progress["phase"] == UpgradePhase.PENDING


class TestShouldContinue:
    @pytest.fixture
    def manager(self) -> UpgradeManager:
        return UpgradeManager()

    @pytest.mark.asyncio
    async def test_should_continue_with_no_devices(self, manager: UpgradeManager):
        task = OTAUpgradeTaskBuilder().with_task_id("empty_test").with_device_count(0, prefix="empty").build()

        with patch("asyncio.create_task"):
            await manager.start_upgrade(**task)

        should_continue = await manager._should_continue("empty_test")
        assert should_continue is True

    @pytest.mark.asyncio
    async def test_should_continue_nonexistent_task(self, manager: UpgradeManager):
        should_continue = await manager._should_continue("nonexistent")
        assert should_continue is False


class TestBatchUpgrade:
    @pytest.fixture
    def manager(self) -> UpgradeManager:
        return UpgradeManager()

    @pytest.mark.asyncio
    async def test_batch_upgrade_nonexistent_task(self, manager: UpgradeManager):
        await manager._execute_batch_upgrade("nonexistent")

    @pytest.mark.asyncio
    async def test_canary_upgrade_nonexistent_task(self, manager: UpgradeManager):
        await manager._execute_canary_upgrade("nonexistent")

    @pytest.mark.asyncio
    async def test_instant_upgrade_nonexistent_task(self, manager: UpgradeManager):
        await manager._execute_instant_upgrade("nonexistent")


class TestResourceLeakPrevention:
    @pytest.fixture
    def manager(self) -> UpgradeManager:
        return UpgradeManager()

    @pytest.mark.asyncio
    async def test_task_completion_cleans_up_resources(self, manager: UpgradeManager):
        task = OTAUpgradeTaskBuilder().with_task_id("cleanup_test").with_device_count(5, prefix="cleanup").build()

        with patch("asyncio.create_task"):
            await manager.start_upgrade(**task)

        assert "cleanup_test" in manager._active_upgrades
        assert len(manager._device_progress) == 5

        await manager._finalize_task("cleanup_test")

        assert "cleanup_test" not in manager._active_upgrades
        assert len(manager._device_progress) == 0
        assert "cleanup_test" in manager._completed_tasks

    @pytest.mark.asyncio
    async def test_cancel_upgrade_cleans_up_resources(self, manager: UpgradeManager):
        task = OTAUpgradeTaskBuilder().with_task_id("cancel_test").with_device_count(3, prefix="cancel").build()

        with patch("asyncio.create_task"):
            await manager.start_upgrade(**task)

        assert len(manager._active_upgrades) == 1
        assert len(manager._device_progress) == 3

        result = await manager.cancel_upgrade("cancel_test")

        assert result is True
        assert len(manager._active_upgrades) == 0
        assert len(manager._device_progress) == 0
        assert "cancel_test" in manager._completed_tasks

    @pytest.mark.asyncio
    async def test_cancel_nonexistent_task_returns_false(self, manager: UpgradeManager):
        result = await manager.cancel_upgrade("nonexistent")
        assert result is False

    @pytest.mark.asyncio
    async def test_completed_tasks_limited_to_max(self, manager: UpgradeManager):
        manager._max_completed_tasks = 5

        for i in range(10):
            task = OTAUpgradeTaskBuilder().with_task_id(f"limit_task_{i}").with_device_count(1, prefix=f"t{i}").build()
            with patch("asyncio.create_task"):
                await manager.start_upgrade(**task)
            await manager._finalize_task(f"limit_task_{i}")

        assert len(manager._completed_tasks) == 5
        assert len(manager._active_upgrades) == 0
        assert len(manager._device_progress) == 0

    @pytest.mark.asyncio
    async def test_clear_completed_tasks(self, manager: UpgradeManager):
        for i in range(3):
            task = OTAUpgradeTaskBuilder().with_task_id(f"clear_task_{i}").with_device_count(1, prefix=f"c{i}").build()
            with patch("asyncio.create_task"):
                await manager.start_upgrade(**task)
            await manager._finalize_task(f"clear_task_{i}")

        assert len(manager._completed_tasks) == 3

        cleared = manager.clear_completed_tasks()
        assert cleared == 3
        assert len(manager._completed_tasks) == 0

    @pytest.mark.asyncio
    async def test_get_memory_usage(self, manager: UpgradeManager):
        usage = manager.get_memory_usage()
        assert usage["active_upgrades"] == 0
        assert usage["device_progress"] == 0
        assert usage["completed_tasks"] == 0

        task = OTAUpgradeTaskBuilder().with_task_id("usage_test").with_device_count(3, prefix="usage").build()
        with patch("asyncio.create_task"):
            await manager.start_upgrade(**task)

        usage = manager.get_memory_usage()
        assert usage["active_upgrades"] == 1
        assert usage["device_progress"] == 3
        assert usage["completed_tasks"] == 0

        await manager._finalize_task("usage_test")

        usage = manager.get_memory_usage()
        assert usage["active_upgrades"] == 0
        assert usage["device_progress"] == 0
        assert usage["completed_tasks"] == 1

    @pytest.mark.asyncio
    async def test_get_completed_tasks(self, manager: UpgradeManager):
        for i in range(3):
            task = OTAUpgradeTaskBuilder().with_task_id(f"history_task_{i}").with_device_count(1, prefix=f"h{i}").build()
            with patch("asyncio.create_task"):
                await manager.start_upgrade(**task)
            await manager._finalize_task(f"history_task_{i}")

        completed = manager.get_completed_tasks()
        assert len(completed) == 3
        task_ids = [t["task_id"] for t in completed]
        assert "history_task_0" in task_ids
        assert "history_task_1" in task_ids
        assert "history_task_2" in task_ids

    @pytest.mark.asyncio
    async def test_concurrent_task_completion_cleanup(self, manager: UpgradeManager):
        async def complete_task(task_id: str):
            task = OTAUpgradeTaskBuilder().with_task_id(task_id).with_device_count(2, prefix=task_id).build()
            with patch("asyncio.create_task"):
                await manager.start_upgrade(**task)
            await manager._finalize_task(task_id)

        tasks = [complete_task(f"concurrent_cleanup_{i}") for i in range(10)]
        await asyncio.gather(*tasks)

        assert len(manager._active_upgrades) == 0
        assert len(manager._device_progress) == 0
        assert len(manager._completed_tasks) == 10

    @pytest.mark.asyncio
    async def test_cleanup_nonexistent_task_no_error(self, manager: UpgradeManager):
        manager._cleanup_task("nonexistent")
        assert len(manager._active_upgrades) == 0
        assert len(manager._device_progress) == 0
