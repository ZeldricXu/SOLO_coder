import asyncio
import logging
from dataclasses import dataclass, field
from typing import Any, Awaitable, Callable, Dict, List, Optional
from datetime import datetime, timezone

import httpx

from wallethub.core import EventListenerError
from wallethub.utils import async_retry, generate_id

logger = logging.getLogger(__name__)


@dataclass
class CallbackResult:
    callback_id: str
    success: bool
    status_code: Optional[int] = None
    response: Optional[Dict[str, Any]] = None
    error: Optional[str] = None
    execution_time_ms: int = 0
    timestamp: datetime = field(default_factory=lambda: datetime.now(timezone.utc))


class CallbackHandler:
    def __init__(self, timeout: int = 30, max_retries: int = 3):
        self.timeout = timeout
        self.max_retries = max_retries
        self._callbacks: Dict[str, Callable[[Dict[str, Any]], Awaitable[None]]] = {}
        self._results: Dict[str, List[CallbackResult]] = {}
        self._http_client: Optional[httpx.AsyncClient] = None

    async def __aenter__(self) -> "CallbackHandler":
        self._http_client = httpx.AsyncClient(timeout=self.timeout)
        return self

    async def __aexit__(self, exc_type, exc, tb) -> None:
        if self._http_client:
            await self._http_client.aclose()

    def register_callback(
        self,
        name: str,
        callback: Callable[[Dict[str, Any]], Awaitable[None]],
    ) -> None:
        self._callbacks[name] = callback
        self._results[name] = []

    def unregister_callback(self, name: str) -> None:
        self._callbacks.pop(name, None)
        self._results.pop(name, None)

    async def execute_callback(
        self,
        name: str,
        event_data: Dict[str, Any],
    ) -> CallbackResult:
        start_time = asyncio.get_event_loop().time()
        callback_id = generate_id("cb")

        if name not in self._callbacks:
            return CallbackResult(
                callback_id=callback_id,
                success=False,
                error=f"Callback {name} not registered",
            )

        try:
            callback = self._callbacks[name]
            await callback(event_data)
            execution_time = int((asyncio.get_event_loop().time() - start_time) * 1000)

            result = CallbackResult(
                callback_id=callback_id,
                success=True,
                execution_time_ms=execution_time,
            )
        except Exception as e:
            execution_time = int((asyncio.get_event_loop().time() - start_time) * 1000)
            result = CallbackResult(
                callback_id=callback_id,
                success=False,
                error=str(e),
                execution_time_ms=execution_time,
            )
            logger.error(f"Callback {name} failed: {str(e)}")

        self._results[name].append(result)
        if len(self._results[name]) > 1000:
            self._results[name] = self._results[name][-1000:]

        return result

    @async_retry(max_attempts=3, delay=1.0, backoff=2.0)
    async def webhook_callback(
        self,
        url: str,
        event_data: Dict[str, Any],
        headers: Optional[Dict[str, str]] = None,
    ) -> CallbackResult:
        start_time = asyncio.get_event_loop().time()
        callback_id = generate_id("webhook")

        if self._http_client is None:
            self._http_client = httpx.AsyncClient(timeout=self.timeout)

        try:
            response = await self._http_client.post(
                url,
                json=event_data,
                headers=headers or {},
            )
            execution_time = int((asyncio.get_event_loop().time() - start_time) * 1000)

            result = CallbackResult(
                callback_id=callback_id,
                success=response.is_success,
                status_code=response.status_code,
                response=response.json() if response.content else None,
                execution_time_ms=execution_time,
            )
        except Exception as e:
            execution_time = int((asyncio.get_event_loop().time() - start_time) * 1000)
            result = CallbackResult(
                callback_id=callback_id,
                success=False,
                error=str(e),
                execution_time_ms=execution_time,
            )
            logger.error(f"Webhook to {url} failed: {str(e)}")
            raise

        return result

    async def execute_callbacks(
        self,
        event_data: Dict[str, Any],
        callback_names: Optional[List[str]] = None,
    ) -> List[CallbackResult]:
        names = callback_names or list(self._callbacks.keys())
        tasks = [self.execute_callback(name, event_data) for name in names]
        return await asyncio.gather(*tasks, return_exceptions=True)

    def get_callback_results(self, name: str) -> List[CallbackResult]:
        return self._results.get(name, [])

    def get_all_callbacks(self) -> List[str]:
        return list(self._callbacks.keys())

    def get_stats(self) -> Dict[str, Any]:
        stats = {}
        for name, results in self._results.items():
            if not results:
                continue

            successful = sum(1 for r in results if r.success)
            total = len(results)
            avg_time = sum(r.execution_time_ms for r in results) / total

            stats[name] = {
                "total_executions": total,
                "successful": successful,
                "failed": total - successful,
                "success_rate": successful / total if total > 0 else 0,
                "avg_execution_time_ms": avg_time,
            }
        return stats
