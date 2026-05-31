from typing import List, Dict, Any, Optional, Tuple
from collections import defaultdict
from datetime import datetime
import asyncio
import logging

logger = logging.getLogger(__name__)


class BatchProcessor:
    def __init__(
        self,
        max_batch_size: int = 100,
        max_wait_ms: int = 50,
    ):
        self._max_batch_size = max_batch_size
        self._max_wait_ms = max_wait_ms
        self._pending_items: Dict[str, List[Tuple[Any, asyncio.Future]]] = defaultdict(list)
        self._locks: Dict[str, asyncio.Lock] = defaultdict(asyncio.Lock)
        self._timers: Dict[str, Optional[asyncio.Task]] = {}

    async def add_request(
        self,
        batch_key: str,
        item: Any,
        process_batch_func,
    ) -> Any:
        future = asyncio.Future()

        async with self._locks[batch_key]:
            self._pending_items[batch_key].append((item, future))

            if len(self._pending_items[batch_key]) >= self._max_batch_size:
                items_to_process = self._pending_items.pop(batch_key)
                await self._process_batch(batch_key, items_to_process, process_batch_func)
            elif batch_key not in self._timers or self._timers[batch_key] is None:
                self._timers[batch_key] = asyncio.create_task(
                    self._delayed_process(batch_key, process_batch_func)
                )

        return await future

    async def _delayed_process(self, batch_key: str, process_batch_func) -> None:
        try:
            await asyncio.sleep(self._max_wait_ms / 1000.0)

            async with self._locks[batch_key]:
                if batch_key in self._pending_items and self._pending_items[batch_key]:
                    items_to_process = self._pending_items.pop(batch_key)
                    await self._process_batch(batch_key, items_to_process, process_batch_func)
        except Exception as e:
            logger.error(f"Batch timer error for {batch_key}: {e}")
        finally:
            self._timers[batch_key] = None

    async def _process_batch(
        self,
        batch_key: str,
        items: List[Tuple[Any, asyncio.Future]],
        process_batch_func,
    ) -> None:
        raw_items = [item for item, _ in items]
        futures = [future for _, future in items]

        try:
            results = await process_batch_func(raw_items)
            for future, result in zip(futures, results):
                if not future.done():
                    future.set_result(result)
        except Exception as e:
            for future in futures:
                if not future.done():
                    future.set_exception(e)

    def get_stats(self) -> Dict[str, Any]:
        return {
            "pending_batches": len(self._pending_items),
            "pending_items": {
                key: len(items) for key, items in self._pending_items.items()
            },
            "max_batch_size": self._max_batch_size,
            "max_wait_ms": self._max_wait_ms,
        }
