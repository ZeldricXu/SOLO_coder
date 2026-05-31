from __future__ import annotations

import asyncio
import time
from abc import ABC, abstractmethod
from collections import defaultdict, deque
from dataclasses import dataclass, field
from datetime import datetime
from enum import Enum
from typing import Any, Awaitable, Callable, Dict, List, Optional, Set, Tuple
from uuid import uuid4

from top.core.models import AuditLogEntry, CommandRecord
from top.domain.audit.stores import AuditLogStore, CommandStore
from top.domain.audit.bus import CommandBus, CommandHandler, generate_id, utc_now


class BatchPriority(str, Enum):
    LOW = "low"
    NORMAL = "normal"
    HIGH = "high"
    CRITICAL = "critical"


class BatchFlushStrategy(str, Enum):
    SIZE_BASED = "size_based"
    TIME_BASED = "time_based"
    HYBRID = "hybrid"
    MANUAL = "manual"


@dataclass
class BatchItem:
    command_id: str
    command_type: str
    payload: Dict[str, Any]
    issued_by: str
    correlation_id: str
    issued_at: datetime
    priority: BatchPriority = BatchPriority.NORMAL
    handler_result: Optional[Any] = None
    handler_error: Optional[Exception] = None
    stored: bool = False
    audited: bool = False


@dataclass
class BatchResult:
    batch_id: str
    total_items: int
    successful: int
    failed: int
    items: List[BatchItem] = field(default_factory=list)
    started_at: Optional[datetime] = None
    completed_at: Optional[datetime] = None
    duration_ms: float = 0.0
    network_round_trips: int = 0

    @property
    def success_rate(self) -> float:
        return self.successful / self.total_items if self.total_items > 0 else 1.0


@dataclass
class BatchConfig:
    max_batch_size: int = 100
    flush_interval_ms: int = 500
    strategy: BatchFlushStrategy = BatchFlushStrategy.HYBRID
    max_queue_size: int = 10000
    max_concurrent_batches: int = 3
    priority_order: List[BatchPriority] = field(
        default_factory=lambda: [
            BatchPriority.CRITICAL,
            BatchPriority.HIGH,
            BatchPriority.NORMAL,
            BatchPriority.LOW,
        ]
    )
    retry_on_failure: bool = True
    max_retries: int = 3
    retry_delay_ms: int = 100


class BatchCommandStore(ABC):
    @abstractmethod
    async def append_batch(self, commands: List[CommandRecord]) -> List[CommandRecord]:
        pass

    @abstractmethod
    async def get_batch_by_ids(self, command_ids: List[str]) -> Dict[str, Optional[CommandRecord]]:
        pass

    @abstractmethod
    async def count_batch(self, command_ids: List[str]) -> int:
        pass


class BatchAuditLogStore(ABC):
    @abstractmethod
    async def append_batch(self, entries: List[AuditLogEntry]) -> List[AuditLogEntry]:
        pass

    @abstractmethod
    async def query_batch(
        self,
        command_ids: List[str] = None,
        correlation_ids: List[str] = None,
    ) -> List[AuditLogEntry]:
        pass


class BatchingCommandBus(CommandBus):
    def __init__(
        self,
        command_store: Optional[CommandStore] = None,
        audit_store: Optional[AuditLogStore] = None,
        batch_config: Optional[BatchConfig] = None,
    ):
        super().__init__(command_store, audit_store)
        self._batch_config = batch_config or BatchConfig()
        self._queues: Dict[BatchPriority, deque[BatchItem]] = {
            priority: deque()
            for priority in BatchPriority
        }
        self._processing = False
        self._processor_task: Optional[asyncio.Task] = None
        self._batch_lock = asyncio.Lock()
        self._batches_processed = 0
        self._items_processed = 0
        self._total_network_round_trips_saved = 0
        self._pending_operations: Dict[str, List[Tuple[int, asyncio.Future]]] = {}

    @property
    def config(self) -> BatchConfig:
        return self._batch_config

    @property
    def total_batches_processed(self) -> int:
        return self._batches_processed

    @property
    def total_items_processed(self) -> int:
        return self._items_processed

    @property
    def network_round_trips_saved(self) -> int:
        return self._total_network_round_trips_saved

    def queue_size(self) -> Dict[str, int]:
        return {
            priority.value: len(queue)
            for priority, queue in self._queues.items()
        }

    async def start_batcher(self) -> None:
        if self._processor_task is None or self._processor_task.done():
            self._processor_task = asyncio.create_task(self._batch_processor())

    async def stop_batcher(self) -> None:
        await self.flush_all()
        if self._processor_task and not self._processor_task.done():
            self._processor_task.cancel()
            try:
                await self._processor_task
            except asyncio.CancelledError:
                pass
        self._processor_task = None

    async def send_batch(
        self,
        commands: List[Tuple[str, Dict[str, Any]]],
        issued_by: str = "system",
        correlation_id: Optional[str] = None,
        priority: BatchPriority = BatchPriority.NORMAL,
    ) -> BatchResult:
        batch_id = generate_id("batch")
        base_correlation = correlation_id or generate_id("corr")

        items: List[BatchItem] = []
        for idx, (cmd_type, payload) in enumerate(commands):
            item_correlation = f"{base_correlation}:{idx}" if len(commands) > 1 else base_correlation
            items.append(BatchItem(
                command_id=generate_id("cmd"),
                command_type=cmd_type,
                payload=payload,
                issued_by=issued_by,
                correlation_id=item_correlation,
                issued_at=utc_now(),
                priority=priority,
            ))

        result = BatchResult(
            batch_id=batch_id,
            total_items=len(items),
            successful=0,
            failed=0,
            items=items,
            started_at=utc_now(),
        )

        await self._process_batch_items(result, items)
        result.completed_at = utc_now()
        result.duration_ms = (result.completed_at - result.started_at).total_seconds() * 1000

        self._batches_processed += 1
        self._items_processed += len(items)
        self._total_network_round_trips_saved += max(0, len(items) - result.network_round_trips)

        return result

    async def send_queued(
        self,
        command_type: str,
        payload: Dict[str, Any],
        issued_by: str = "system",
        correlation_id: Optional[str] = None,
        priority: BatchPriority = BatchPriority.NORMAL,
    ) -> str:
        async with self._batch_lock:
            queue = self._queues[priority]
            if len(queue) >= self._batch_config.max_queue_size:
                queue.popleft()

            item = BatchItem(
                command_id=generate_id("cmd"),
                command_type=command_type,
                payload=payload,
                issued_by=issued_by,
                correlation_id=correlation_id or generate_id("corr"),
                issued_at=utc_now(),
                priority=priority,
            )
            queue.append(item)

            await self.start_batcher()
            return item.command_id

    async def flush(self, priority: Optional[BatchPriority] = None) -> int:
        async with self._batch_lock:
            items_to_process: List[BatchItem] = []
            if priority:
                queue = self._queues[priority]
                while queue:
                    items_to_process.append(queue.popleft())
            else:
                for p in self._batch_config.priority_order:
                    queue = self._queues[p]
                    while queue:
                        items_to_process.append(queue.popleft())

            if items_to_process:
                batch_result = BatchResult(
                    batch_id=generate_id("batch"),
                    total_items=len(items_to_process),
                    successful=0,
                    failed=0,
                    items=items_to_process,
                    started_at=utc_now(),
                )
                await self._process_batch_items(batch_result, items_to_process)
                batch_result.completed_at = utc_now()
                self._batches_processed += 1
                self._items_processed += len(items_to_process)
                self._total_network_round_trips_saved += max(
                    0, len(items_to_process) - batch_result.network_round_trips
                )

            return len(items_to_process)

    async def flush_all(self) -> int:
        return await self.flush()

    async def _process_batch_items(
        self,
        result: BatchResult,
        items: List[BatchItem],
    ) -> None:
        if not items:
            return

        commands: List[CommandRecord] = []
        for item in items:
            cmd = CommandRecord(
                command_id=item.command_id,
                command_type=item.command_type,
                payload=item.payload,
                issued_by=item.issued_by,
                issued_at=item.issued_at,
                correlation_id=item.correlation_id,
            )
            commands.append(cmd)

        await self._store_batch(result, commands, items)
        await self._audit_batch(result, items)
        await self._execute_handlers_batch(result, items)

        for item in items:
            if item.handler_error:
                result.failed += 1
            else:
                result.successful += 1

    async def _store_batch(
        self,
        result: BatchResult,
        commands: List[CommandRecord],
        items: List[BatchItem],
    ) -> None:
        if hasattr(self._command_store, 'append_batch'):
            try:
                await self._command_store.append_batch(commands)
                for item in items:
                    item.stored = True
                result.network_round_trips += 1
            except Exception:
                for cmd, item in zip(commands, items):
                    try:
                        await self._command_store.append(cmd)
                        item.stored = True
                        result.network_round_trips += 1
                    except Exception:
                        pass
        else:
            for cmd, item in zip(commands, items):
                try:
                    await self._command_store.append(cmd)
                    item.stored = True
                    result.network_round_trips += 1
                except Exception:
                    pass

    async def _audit_batch(
        self,
        result: BatchResult,
        items: List[BatchItem],
    ) -> None:
        entries: List[AuditLogEntry] = []
        for item in items:
            if item.stored:
                entries.append(AuditLogEntry(
                    log_id=generate_id("audit"),
                    action="command.issued",
                    actor=item.issued_by,
                    resource=f"command:{item.command_type}",
                    details={
                        "command_id": item.command_id,
                        "payload": item.payload,
                    },
                    command_id=item.command_id,
                    correlation_id=item.correlation_id,
                ))

        if hasattr(self._audit_store, 'append_batch') and entries:
            try:
                await self._audit_store.append_batch(entries)
                for item in items:
                    if item.stored:
                        item.audited = True
                result.network_round_trips += 1
            except Exception:
                for entry, item in zip(entries, [i for i in items if i.stored]):
                    try:
                        await self._audit_store.append(entry)
                        item.audited = True
                        result.network_round_trips += 1
                    except Exception:
                        pass
        else:
            for entry, item in zip(entries, [i for i in items if i.stored]):
                try:
                    await self._audit_store.append(entry)
                    item.audited = True
                    result.network_round_trips += 1
                except Exception:
                    pass

    async def _execute_handlers_batch(
        self,
        result: BatchResult,
        items: List[BatchItem],
    ) -> None:
        for item in items:
            if not item.stored:
                continue

            handler = self._get_handler(item.command_type)
            if not handler:
                continue

            command = CommandRecord(
                command_id=item.command_id,
                command_type=item.command_type,
                payload=item.payload,
                issued_by=item.issued_by,
                issued_at=item.issued_at,
                correlation_id=item.correlation_id,
            )

            retry_count = 0
            while retry_count <= self._batch_config.max_retries:
                try:
                    if isinstance(handler, CommandHandler):
                        result_value = await handler.handle(command)
                    elif asyncio.iscoroutinefunction(handler):
                        result_value = await handler(command)
                    else:
                        result_value = handler(command)

                    item.handler_result = result_value

                    if item.audited:
                        await self._audit_store.append(AuditLogEntry(
                            log_id=generate_id("audit"),
                            action="command.executed",
                            actor=item.issued_by,
                            resource=f"command:{item.command_type}",
                            details={
                                "command_id": item.command_id,
                                "success": True,
                                "result": str(result_value)[:100] if result_value else None,
                            },
                            command_id=item.command_id,
                            correlation_id=item.correlation_id,
                        ))
                        result.network_round_trips += 1
                    break

                except Exception as e:
                    retry_count += 1
                    if retry_count <= self._batch_config.max_retries and self._batch_config.retry_on_failure:
                        await asyncio.sleep(self._batch_config.retry_delay_ms / 1000)
                        continue
                    else:
                        item.handler_error = e
                        if item.audited:
                            await self._audit_store.append(AuditLogEntry(
                                log_id=generate_id("audit"),
                                action="command.failed",
                                actor=item.issued_by,
                                resource=f"command:{item.command_type}",
                                details={
                                    "command_id": item.command_id,
                                    "error": str(e),
                                    "retry_count": retry_count,
                                },
                                command_id=item.command_id,
                                correlation_id=item.correlation_id,
                            ))
                            result.network_round_trips += 1
                        break

    async def _batch_processor(self) -> None:
        while True:
            try:
                await asyncio.sleep(self._batch_config.flush_interval_ms / 1000)

                total_pending = sum(len(q) for q in self._queues.values())
                if total_pending >= self._batch_config.max_batch_size:
                    await self.flush()
                elif (
                    self._batch_config.strategy == BatchFlushStrategy.TIME_BASED and
                    total_pending > 0
                ):
                    await self.flush()
            except asyncio.CancelledError:
                break
            except Exception:
                continue

    def get_stats(self) -> Dict[str, Any]:
        queue_sizes = self.queue_size()
        total_pending = sum(queue_sizes.values())

        return {
            "config": {
                "max_batch_size": self._batch_config.max_batch_size,
                "flush_interval_ms": self._batch_config.flush_interval_ms,
                "strategy": self._batch_config.strategy.value,
                "max_queue_size": self._batch_config.max_queue_size,
            },
            "queues": queue_sizes,
            "total_pending": total_pending,
            "total_batches_processed": self._batches_processed,
            "total_items_processed": self._items_processed,
            "network_round_trips_saved": self._total_network_round_trips_saved,
            "processor_running": self._processor_task is not None and not self._processor_task.done(),
        }


_batching_bus_instance: Optional[BatchingCommandBus] = None


def get_batching_bus(
    command_store: Optional[CommandStore] = None,
    audit_store: Optional[AuditLogStore] = None,
    batch_config: Optional[BatchConfig] = None,
) -> BatchingCommandBus:
    global _batching_bus_instance
    if _batching_bus_instance is None:
        _batching_bus_instance = BatchingCommandBus(
            command_store=command_store,
            audit_store=audit_store,
            batch_config=batch_config,
        )
    return _batching_bus_instance


def set_batching_bus_instance(bus: BatchingCommandBus) -> None:
    global _batching_bus_instance
    _batching_bus_instance = bus
