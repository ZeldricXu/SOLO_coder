import asyncio
from typing import Optional

from .manager import ConfigManager


class ConfigWatcher:
    def __init__(
        self,
        manager: ConfigManager,
        interval_seconds: float = 30.0,
        logger=None,
    ):
        self._manager = manager
        self._interval = interval_seconds
        self._task: Optional[asyncio.Task] = None
        self._running = False
        self._logger = logger

    async def start(self) -> None:
        if self._running:
            return
        self._running = True
        self._task = asyncio.create_task(self._watch_loop())

    async def stop(self) -> None:
        self._running = False
        if self._task:
            self._task.cancel()
            try:
                await self._task
            except asyncio.CancelledError:
                pass
            self._task = None

    async def _watch_loop(self) -> None:
        if self._logger:
            self._logger.info(f"Config watcher started, interval: {self._interval}s")
        while self._running:
            try:
                await self._check_and_reload()
            except Exception as e:
                if self._logger:
                    self._logger.error(f"Config watcher error: {e}")
            await asyncio.sleep(self._interval)
        if self._logger:
            self._logger.info("Config watcher stopped")

    async def _check_and_reload(self) -> None:
        changed = False
        for source in self._manager._sources:
            try:
                if await source.has_changed():
                    changed = True
                    if self._logger:
                        self._logger.info(f"Config source changed, reloading...")
                    break
            except Exception as e:
                if self._logger:
                    self._logger.warning(f"Error checking config source: {e}")
        if changed:
            await self._manager.reload()

    def is_running(self) -> bool:
        return self._running

    def get_interval(self) -> float:
        return self._interval
