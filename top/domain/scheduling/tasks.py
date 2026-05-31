from __future__ import annotations

import asyncio
import time
from abc import ABC, abstractmethod
from typing import Any, Callable, Dict, Optional

from top.domain.scheduling.models import (
    ExecutionContext,
    ExecutionPhase,
    ExecutionStatus,
    RetryPolicy,
    TaskResult,
    utc_now,
)


class TaskHandler(ABC):
    @abstractmethod
    async def execute(self, context: ExecutionContext) -> Any:
        pass

    @property
    def name(self) -> str:
        return self.__class__.__name__


class TaskHandlerRegistry:
    def __init__(self):
        self._handlers: Dict[str, Callable[..., Any]] = {}
        self._instance_handlers: Dict[str, TaskHandler] = {}

    def register(self, name: str, handler: Callable[..., Any]) -> None:
        if isinstance(handler, TaskHandler):
            self._instance_handlers[name] = handler
        else:
            self._handlers[name] = handler

    def unregister(self, name: str) -> None:
        self._handlers.pop(name, None)
        self._instance_handlers.pop(name, None)

    def get(self, name: str) -> Optional[Callable[..., Any]]:
        if name in self._instance_handlers:
            return self._instance_handlers[name]
        return self._handlers.get(name)

    def has(self, name: str) -> bool:
        return name in self._handlers or name in self._instance_handlers


class TaskExecutor:
    def __init__(self, registry: Optional[TaskHandlerRegistry] = None):
        self._registry = registry or TaskHandlerRegistry()
        self._default_policy = RetryPolicy.default()

    @property
    def registry(self) -> TaskHandlerRegistry:
        return self._registry

    def register_handler(self, name: str, handler: Callable[..., Any]) -> None:
        self._registry.register(name, handler)

    def unregister_handler(self, name: str) -> None:
        self._registry.unregister(name)

    async def execute(
        self,
        task_def: Any,
        context: ExecutionContext,
        retry_policy: Optional[RetryPolicy] = None,
    ) -> TaskResult:
        policy = retry_policy or self._default_policy

        handler_name = getattr(task_def, 'handler', None)
        task_id = getattr(task_def, 'task_id', 'unknown')

        handler = self._registry.get(handler_name) if handler_name else None

        if not handler:
            return TaskResult(
                task_id=task_id,
                status=ExecutionStatus.FAILED,
                error=f"Handler '{handler_name}' not registered",
            )

        last_error: Optional[Exception] = None
        result = TaskResult(task_id=task_id, status=ExecutionStatus.PENDING)
        start_time_total = time.time()

        for attempt in range(policy.max_retries + 1):
            context.attempt = attempt + 1
            context.update(status=ExecutionStatus.RUNNING, phase=ExecutionPhase.EXECUTING)
            result.started_at = utc_now()
            result.attempt = attempt + 1

            try:
                start_time = time.time()

                if isinstance(handler, TaskHandler):
                    actual_result = await handler.execute(context)
                elif asyncio.iscoroutinefunction(handler):
                    actual_result = await handler(task_def, context)
                else:
                    actual_result = handler(task_def, context)

                duration = (time.time() - start_time) * 1000
                result.status = ExecutionStatus.SUCCESS
                result.result = actual_result
                result.duration_ms = duration
                result.completed_at = utc_now()

                context.update(
                    status=ExecutionStatus.SUCCESS,
                    phase=ExecutionPhase.FINALIZING,
                )

                return result

            except asyncio.TimeoutError as e:
                last_error = e
                if attempt >= policy.max_retries:
                    break
                await self._apply_backoff(policy, attempt)

            except Exception as e:
                last_error = e
                if not policy.is_retryable(e):
                    break
                if attempt >= policy.max_retries:
                    break
                await self._apply_backoff(policy, attempt)

        duration = (time.time() - start_time_total) * 1000
        result.status = ExecutionStatus.FAILED
        result.error = str(last_error) if last_error else "Unknown error"
        result.duration_ms = duration
        result.completed_at = utc_now()
        context.update(
            status=ExecutionStatus.FAILED,
            phase=ExecutionPhase.ROLLBACK,
            error=str(last_error) if last_error else None,
        )

        return result

    async def _apply_backoff(self, policy: RetryPolicy, attempt: int) -> None:
        delay = policy.initial_delay_ms * (policy.backoff_factor ** attempt)
        delay = min(delay, policy.max_delay_ms)
        await asyncio.sleep(delay / 1000.0)
