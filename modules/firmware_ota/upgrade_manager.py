import asyncio
from datetime import datetime
from typing import Any, Dict, List, Optional
from core import emit_event, EventTypes


class UpgradeStrategy:
    INSTANT = "instant"
    BATCH = "batch"
    CANARY = "canary"
    SCHEDULED = "scheduled"


class UpgradePhase:
    PENDING = "pending"
    DOWNLOADING = "downloading"
    VERIFYING = "verifying"
    INSTALLING = "installing"
    REBOOTING = "rebooting"
    VERIFYING_UPGRADE = "verifying_upgrade"
    COMPLETED = "completed"
    FAILED = "failed"
    ROLLBACK = "rollback"


class UpgradeManager:
    def __init__(self):
        self._active_upgrades: Dict[str, Dict[str, Any]] = {}
        self._device_progress: Dict[str, Dict[str, Any]] = {}
        self._rollback_callbacks: Dict[str, callable] = {}
        self._completed_tasks: Dict[str, Dict[str, Any]] = {}
        self._max_completed_tasks = 100

    def register_rollback_callback(self, device_model: str, callback: callable) -> None:
        self._rollback_callbacks[device_model] = callback

    async def start_upgrade(
        self,
        task_id: str,
        device_ids: List[str],
        firmware_info: Dict[str, Any],
        strategy: str = UpgradeStrategy.INSTANT,
        batch_size: int = 10,
        auto_rollback: bool = True,
        rollback_threshold: float = 0.2,
    ) -> Dict[str, Any]:
        task_info = {
            "task_id": task_id,
            "device_ids": device_ids,
            "firmware_info": firmware_info,
            "strategy": strategy,
            "batch_size": batch_size,
            "auto_rollback": auto_rollback,
            "rollback_threshold": rollback_threshold,
            "status": "running",
            "success_count": 0,
            "failed_count": 0,
            "current_batch": 0,
            "started_at": datetime.utcnow(),
        }

        self._active_upgrades[task_id] = task_info

        for device_id in device_ids:
            self._device_progress[device_id] = {
                "task_id": task_id,
                "device_id": device_id,
                "phase": UpgradePhase.PENDING,
                "progress": 0.0,
                "error": None,
                "rollback_triggered": False,
            }

        emit_event(
            EventTypes.OTA_UPDATE_STARTED,
            "upgrade_manager",
            {
                "task_id": task_id,
                "device_count": len(device_ids),
                "strategy": strategy,
            },
        )

        if strategy == UpgradeStrategy.INSTANT:
            asyncio.create_task(self._execute_instant_upgrade(task_id))
        elif strategy == UpgradeStrategy.BATCH:
            asyncio.create_task(self._execute_batch_upgrade(task_id))
        elif strategy == UpgradeStrategy.CANARY:
            asyncio.create_task(self._execute_canary_upgrade(task_id))

        return task_info

    async def _execute_instant_upgrade(self, task_id: str) -> None:
        task_info = self._active_upgrades.get(task_id)
        if not task_info:
            return

        device_ids = task_info["device_ids"]
        tasks = [
            self._upgrade_device(task_id, device_id, task_info["firmware_info"])
            for device_id in device_ids
        ]
        await asyncio.gather(*tasks, return_exceptions=True)

        await self._finalize_task(task_id)

    async def _execute_batch_upgrade(self, task_id: str) -> None:
        task_info = self._active_upgrades.get(task_id)
        if not task_info:
            return

        device_ids = task_info["device_ids"]
        batch_size = task_info["batch_size"]

        for i in range(0, len(device_ids), batch_size):
            task_info["current_batch"] = i // batch_size + 1
            batch = device_ids[i:i + batch_size]

            if not await self._should_continue(task_id):
                await self._trigger_rollback(task_id, batch)
                break

            tasks = [
                self._upgrade_device(task_id, device_id, task_info["firmware_info"])
                for device_id in batch
            ]
            await asyncio.gather(*tasks, return_exceptions=True)

            await asyncio.sleep(30)

        await self._finalize_task(task_id)

    async def _execute_canary_upgrade(self, task_id: str) -> None:
        task_info = self._active_upgrades.get(task_id)
        if not task_info:
            return

        device_ids = task_info["device_ids"]
        canary_size = max(1, min(5, len(device_ids) // 10))
        canary_devices = device_ids[:canary_size]
        remaining_devices = device_ids[canary_size:]

        tasks = [
            self._upgrade_device(task_id, device_id, task_info["firmware_info"])
            for device_id in canary_devices
        ]
        await asyncio.gather(*tasks, return_exceptions=True)

        if await self._should_continue(task_id):
            await asyncio.sleep(60)
            tasks = [
                self._upgrade_device(task_id, device_id, task_info["firmware_info"])
                for device_id in remaining_devices
            ]
            await asyncio.gather(*tasks, return_exceptions=True)
        else:
            await self._trigger_rollback(task_id, canary_devices)

        await self._finalize_task(task_id)

    async def _upgrade_device(
        self,
        task_id: str,
        device_id: str,
        firmware_info: Dict[str, Any],
    ) -> None:
        progress = self._device_progress.get(device_id)
        if not progress:
            return

        try:
            for phase in [
                UpgradePhase.DOWNLOADING,
                UpgradePhase.VERIFYING,
                UpgradePhase.INSTALLING,
                UpgradePhase.REBOOTING,
                UpgradePhase.VERIFYING_UPGRADE,
            ]:
                progress["phase"] = phase
                progress["progress"] = [
                    0.1, 0.3, 0.5, 0.7, 0.9
                ][[
                    UpgradePhase.DOWNLOADING,
                    UpgradePhase.VERIFYING,
                    UpgradePhase.INSTALLING,
                    UpgradePhase.REBOOTING,
                    UpgradePhase.VERIFYING_UPGRADE,
                ].index(phase)]
                await asyncio.sleep(0.1)

            progress["phase"] = UpgradePhase.COMPLETED
            progress["progress"] = 1.0

            task_info = self._active_upgrades.get(task_id)
            if task_info:
                task_info["success_count"] += 1

        except Exception as e:
            progress["phase"] = UpgradePhase.FAILED
            progress["error"] = str(e)

            task_info = self._active_upgrades.get(task_id)
            if task_info:
                task_info["failed_count"] += 1

    async def _should_continue(self, task_id: str) -> bool:
        task_info = self._active_upgrades.get(task_id)
        if not task_info:
            return False

        total = task_info["success_count"] + task_info["failed_count"]
        if total == 0:
            return True

        failure_rate = task_info["failed_count"] / total
        return failure_rate < task_info["rollback_threshold"]

    async def _trigger_rollback(
        self,
        task_id: str,
        device_ids: Optional[List[str]] = None,
    ) -> None:
        task_info = self._active_upgrades.get(task_id)
        if not task_info:
            return

        if device_ids is None:
            device_ids = task_info["device_ids"]

        for device_id in device_ids:
            progress = self._device_progress.get(device_id)
            if progress and progress["phase"] in [
                UpgradePhase.FAILED,
                UpgradePhase.VERIFYING_UPGRADE,
            ]:
                progress["rollback_triggered"] = True
                progress["phase"] = UpgradePhase.ROLLBACK

                callback = self._rollback_callbacks.get(
                    task_info["firmware_info"].get("device_model", "default")
                )
                if callback:
                    try:
                        await callback(device_id, task_info["firmware_info"])
                    except Exception as e:
                        progress["error"] = f"Rollback failed: {e}"

        emit_event(
            EventTypes.OTA_UPDATE_FAILED,
            "upgrade_manager",
            {
                "task_id": task_id,
                "device_ids": device_ids,
                "rollback_triggered": True,
            },
        )

    async def cancel_upgrade(self, task_id: str) -> bool:
        task_info = self._active_upgrades.get(task_id)
        if not task_info:
            return False

        task_info["status"] = "cancelled"
        task_info["cancelled_at"] = datetime.utcnow()

        emit_event(
            EventTypes.OTA_UPDATE_FAILED,
            "upgrade_manager",
            {
                "task_id": task_id,
                "cancelled": True,
            },
        )

        self._cleanup_task(task_id)
        return True

    def get_completed_tasks(self) -> List[Dict[str, Any]]:
        return list(self._completed_tasks.values())

    def clear_completed_tasks(self) -> int:
        count = len(self._completed_tasks)
        self._completed_tasks.clear()
        return count

    def get_memory_usage(self) -> Dict[str, int]:
        return {
            "active_upgrades": len(self._active_upgrades),
            "device_progress": len(self._device_progress),
            "completed_tasks": len(self._completed_tasks),
        }

    async def _finalize_task(self, task_id: str) -> None:
        task_info = self._active_upgrades.get(task_id)
        if not task_info:
            return

        task_info["status"] = "completed"
        task_info["completed_at"] = datetime.utcnow()

        emit_event(
            EventTypes.OTA_UPDATE_COMPLETED,
            "upgrade_manager",
            {
                "task_id": task_id,
                "success_count": task_info["success_count"],
                "failed_count": task_info["failed_count"],
            },
        )

        self._cleanup_task(task_id)

    def _cleanup_task(self, task_id: str) -> None:
        task_info = self._active_upgrades.pop(task_id, None)
        if task_info:
            device_ids = task_info.get("device_ids", [])
            for device_id in device_ids:
                self._device_progress.pop(device_id, None)

            task_info["completed_at"] = datetime.utcnow()
            self._completed_tasks[task_id] = task_info

            if len(self._completed_tasks) > self._max_completed_tasks:
                oldest_task_id = next(iter(self._completed_tasks))
                self._completed_tasks.pop(oldest_task_id, None)

    def update_device_progress(
        self,
        device_id: str,
        phase: str,
        progress: float,
        error: Optional[str] = None,
    ) -> None:
        if device_id in self._device_progress:
            self._device_progress[device_id]["phase"] = phase
            self._device_progress[device_id]["progress"] = progress
            if error:
                self._device_progress[device_id]["error"] = error

    def get_device_progress(self, device_id: str) -> Optional[Dict[str, Any]]:
        return self._device_progress.get(device_id)

    def get_task_status(self, task_id: str) -> Optional[Dict[str, Any]]:
        if task_id in self._active_upgrades:
            return self._active_upgrades.get(task_id)
        return self._completed_tasks.get(task_id)

    def get_all_tasks(self) -> List[Dict[str, Any]]:
        return list(self._active_upgrades.values())


upgrade_manager = UpgradeManager()
