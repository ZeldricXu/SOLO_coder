from __future__ import annotations

import asyncio
from typing import Any, Callable, Dict, Optional


class TaskScheduler:
    def __init__(self):
        self._scheduled: Dict[str, Dict[str, Any]] = {}
        self._running: Dict[str, asyncio.Task] = {}
        self._lock = asyncio.Lock()

    async def schedule_once(
        self,
        task_id: str,
        coro,
        delay_seconds: float = 0,
    ) -> str:
        async with self._lock:
            self._scheduled[task_id] = {
                "type": "once",
                "coro": coro,
                "delay": delay_seconds,
            }

        if delay_seconds <= 0:
            await self._run_task(task_id, coro)
        else:
            await asyncio.sleep(delay_seconds)
            await self._run_task(task_id, coro)

        return task_id

    async def schedule_periodic(
        self,
        task_id: str,
        coro_func: Callable[..., Any],
        interval_seconds: float,
        run_immediately: bool = False,
    ) -> str:
        async with self._lock:
            self._scheduled[task_id] = {
                "type": "periodic",
                "func": coro_func,
                "interval": interval_seconds,
                "running": True,
            }

        if run_immediately:
            await self._run_task(task_id, coro_func())

        async def periodic_loop():
            while True:
                await asyncio.sleep(interval_seconds)
                info = self._scheduled.get(task_id)
                if not info or not info.get("running"):
                    break
                await self._run_task(task_id, coro_func())

        loop_task = asyncio.create_task(periodic_loop())
        async with self._lock:
            self._running[task_id] = loop_task

        return task_id

    async def cancel(self, task_id: str) -> bool:
        async with self._lock:
            if task_id in self._scheduled:
                self._scheduled[task_id]["running"] = False
                if task_id in self._running:
                    self._running[task_id].cancel()
                    del self._running[task_id]
                del self._scheduled[task_id]
                return True
        return False

    async def _run_task(self, task_id: str, coro) -> Any:
        try:
            return await coro
        except Exception:
            pass
