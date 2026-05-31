from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from typing import Any, Callable, Dict, List, Optional
from enum import Enum
import asyncio
import hashlib
import time

from ..utils import get_logger

logger = get_logger(__name__)


class ProcessingStrategyType(str, Enum):
    DEFAULT = "default"
    BATCH = "batch"
    DEDUP = "dedup"
    RETRY = "retry"
    PRIORITY = "priority"
    RATE_LIMITED = "rate_limited"


@dataclass
class ProcessingContext:
    filter_id: str
    chain_id: int
    event_signature: str
    log_data: Dict[str, Any]
    received_at: float = field(default_factory=time.time)
    attempt_count: int = 0
    priority: int = 0
    metadata: Dict[str, Any] = field(default_factory=dict)


@dataclass
class ProcessingResult:
    success: bool
    processed: bool = False
    error: Optional[str] = None
    retryable: bool = False
    next_retry_at: Optional[float] = None
    metadata: Dict[str, Any] = field(default_factory=dict)


class IEventProcessingStrategy(ABC):
    @abstractmethod
    def get_strategy_type(self) -> ProcessingStrategyType:
        ...

    @abstractmethod
    async def process_event(
        self,
        context: ProcessingContext,
        handler: Callable[[ProcessingContext], Any],
    ) -> ProcessingResult:
        ...

    def get_name(self) -> str:
        return self.get_strategy_type().value


class DefaultProcessingStrategy(IEventProcessingStrategy):
    def get_strategy_type(self) -> ProcessingStrategyType:
        return ProcessingStrategyType.DEFAULT

    async def process_event(
        self,
        context: ProcessingContext,
        handler: Callable[[ProcessingContext], Any],
    ) -> ProcessingResult:
        try:
            result = handler(context)
            if asyncio.iscoroutine(result):
                await result
            return ProcessingResult(success=True, processed=True)
        except Exception as e:
            logger.error(f"Error processing event {context.filter_id}: {e}")
            return ProcessingResult(success=False, error=str(e), retryable=True)


class BatchProcessingStrategy(IEventProcessingStrategy):
    def __init__(self, batch_size: int = 10, flush_interval: float = 1.0):
        self._batch_size = batch_size
        self._flush_interval = flush_interval
        self._batches: Dict[str, List[ProcessingContext]] = {}
        self._handlers: Dict[str, Callable[[List[ProcessingContext]], Any]] = {}
        self._flush_tasks: Dict[str, asyncio.Task] = {}
        self._lock = asyncio.Lock()

    def get_strategy_type(self) -> ProcessingStrategyType:
        return ProcessingStrategyType.BATCH

    def set_batch_handler(self, filter_id: str, handler: Callable[[List[ProcessingContext]], Any]) -> None:
        self._handlers[filter_id] = handler

    async def process_event(
        self,
        context: ProcessingContext,
        handler: Callable[[ProcessingContext], Any],
    ) -> ProcessingResult:
        filter_id = context.filter_id

        async with self._lock:
            if filter_id not in self._batches:
                self._batches[filter_id] = []

            self._batches[filter_id].append(context)
            batch_size = len(self._batches[filter_id])

            if batch_size >= self._batch_size:
                await self._flush_batch(filter_id, handler)
                return ProcessingResult(success=True, processed=True)

            if filter_id not in self._flush_tasks or self._flush_tasks[filter_id].done():
                self._flush_tasks[filter_id] = asyncio.create_task(
                    self._delayed_flush(filter_id, handler)
                )

        return ProcessingResult(success=True, processed=False)

    async def _delayed_flush(self, filter_id: str, handler: Callable[[ProcessingContext], Any]) -> None:
        await asyncio.sleep(self._flush_interval)
        async with self._lock:
            if filter_id in self._batches and self._batches[filter_id]:
                await self._flush_batch(filter_id, handler)

    async def _flush_batch(self, filter_id: str, handler: Callable[[ProcessingContext], Any]) -> None:
        batch = self._batches.get(filter_id, [])
        if not batch:
            return

        self._batches[filter_id] = []

        if filter_id in self._handlers:
            try:
                result = self._handlers[filter_id](batch)
                if asyncio.iscoroutine(result):
                    await result
            except Exception as e:
                logger.error(f"Error processing batch for {filter_id}: {e}")
        else:
            for ctx in batch:
                try:
                    result = handler(ctx)
                    if asyncio.iscoroutine(result):
                        await result
                except Exception as e:
                    logger.error(f"Error processing event in batch {filter_id}: {e}")


class DedupProcessingStrategy(IEventProcessingStrategy):
    def __init__(self, cache_ttl: int = 3600, max_cache_size: int = 10000):
        self._cache_ttl = cache_ttl
        self._max_cache_size = max_cache_size
        self._seen_events: Dict[str, float] = {}
        self._lock = asyncio.Lock()

    def get_strategy_type(self) -> ProcessingStrategyType:
        return ProcessingStrategyType.DEDUP

    async def process_event(
        self,
        context: ProcessingContext,
        handler: Callable[[ProcessingContext], Any],
    ) -> ProcessingResult:
        event_key = self._compute_event_key(context)

        async with self._lock:
            now = time.time()
            if event_key in self._seen_events:
                if now - self._seen_events[event_key] < self._cache_ttl:
                    logger.debug(f"Duplicate event detected: {event_key}")
                    return ProcessingResult(success=True, processed=False)
                else:
                    del self._seen_events[event_key]

            if len(self._seen_events) >= self._max_cache_size:
                self._evict_old_entries()

            self._seen_events[event_key] = now

        try:
            result = handler(context)
            if asyncio.iscoroutine(result):
                await result
            return ProcessingResult(success=True, processed=True)
        except Exception as e:
            async with self._lock:
                if event_key in self._seen_events:
                    del self._seen_events[event_key]
            return ProcessingResult(success=False, error=str(e), retryable=True)

    def _compute_event_key(self, context: ProcessingContext) -> str:
        log_data = context.log_data
        components = [
            context.filter_id,
            str(log_data.get("blockHash", "")),
            str(log_data.get("logIndex", "")),
            str(log_data.get("transactionHash", "")),
        ]
        key = "|".join(components)
        return hashlib.md5(key.encode()).hexdigest()

    def _evict_old_entries(self) -> None:
        now = time.time()
        keys_to_evict = []
        for key, timestamp in self._seen_events.items():
            if now - timestamp >= self._cache_ttl:
                keys_to_evict.append(key)
        for key in keys_to_evict:
            del self._seen_events[key]

        if len(self._seen_events) >= self._max_cache_size:
            sorted_keys = sorted(self._seen_events.keys(), key=lambda k: self._seen_events[k])
            keys_to_remove = sorted_keys[: len(sorted_keys) // 2]
            for key in keys_to_remove:
                del self._seen_events[key]


class RetryProcessingStrategy(IEventProcessingStrategy):
    def __init__(self, max_retries: int = 3, base_delay: float = 1.0, max_delay: float = 60.0):
        self._max_retries = max_retries
        self._base_delay = base_delay
        self._max_delay = max_delay

    def get_strategy_type(self) -> ProcessingStrategyType:
        return ProcessingStrategyType.RETRY

    async def process_event(
        self,
        context: ProcessingContext,
        handler: Callable[[ProcessingContext], Any],
    ) -> ProcessingResult:
        last_error = None
        for attempt in range(self._max_retries):
            context.attempt_count = attempt + 1
            try:
                result = handler(context)
                if asyncio.iscoroutine(result):
                    await result
                return ProcessingResult(success=True, processed=True)
            except Exception as e:
                last_error = str(e)
                logger.warning(
                    f"Attempt {attempt + 1}/{self._max_retries} failed for event {context.filter_id}: {e}"
                )
                if attempt < self._max_retries - 1:
                    delay = min(self._base_delay * (2 ** attempt), self._max_delay)
                    await asyncio.sleep(delay)

        return ProcessingResult(
            success=False,
            error=last_error or "Max retries exceeded",
            retryable=False,
        )


class PriorityProcessingStrategy(IEventProcessingStrategy):
    def __init__(self):
        self._high_priority_events = {"Transfer", "Deposit", "Withdraw", "Approval"}
        self._lock = asyncio.Lock()
        self._queue: asyncio.PriorityQueue = asyncio.PriorityQueue()

    def get_strategy_type(self) -> ProcessingStrategyType:
        return ProcessingStrategyType.PRIORITY

    async def process_event(
        self,
        context: ProcessingContext,
        handler: Callable[[ProcessingContext], Any],
    ) -> ProcessingResult:
        priority = self._compute_priority(context)
        context.priority = priority

        if priority <= 1:
            try:
                result = handler(context)
                if asyncio.iscoroutine(result):
                    await result
                return ProcessingResult(success=True, processed=True)
            except Exception as e:
                return ProcessingResult(success=False, error=str(e), retryable=True)
        else:
            asyncio.create_task(self._process_low_priority(context, handler))
            return ProcessingResult(success=True, processed=False)

    def _compute_priority(self, context: ProcessingContext) -> int:
        event_sig = context.event_signature
        for high_priority in self._high_priority_events:
            if high_priority in event_sig:
                return 0
        return 2

    async def _process_low_priority(
        self, context: ProcessingContext, handler: Callable[[ProcessingContext], Any]
    ) -> None:
        try:
            result = handler(context)
            if asyncio.iscoroutine(result):
                await result
        except Exception as e:
            logger.error(f"Error processing low priority event {context.filter_id}: {e}")


class RateLimitedProcessingStrategy(IEventProcessingStrategy):
    def __init__(self, max_events_per_second: int = 100):
        self._max_events_per_second = max_events_per_second
        self._min_interval = 1.0 / max_events_per_second
        self._last_processed: Dict[str, float] = {}
        self._lock = asyncio.Lock()

    def get_strategy_type(self) -> ProcessingStrategyType:
        return ProcessingStrategyType.RATE_LIMITED

    async def process_event(
        self,
        context: ProcessingContext,
        handler: Callable[[ProcessingContext], Any],
    ) -> ProcessingResult:
        filter_id = context.filter_id

        async with self._lock:
            now = time.time()
            last_time = self._last_processed.get(filter_id, 0)
            elapsed = now - last_time

            if elapsed < self._min_interval:
                await asyncio.sleep(self._min_interval - elapsed)

            self._last_processed[filter_id] = time.time()

        try:
            result = handler(context)
            if asyncio.iscoroutine(result):
                await result
            return ProcessingResult(success=True, processed=True)
        except Exception as e:
            return ProcessingResult(success=False, error=str(e), retryable=True)


class EventStrategyRegistry:
    def __init__(self):
        self._strategies: Dict[ProcessingStrategyType, IEventProcessingStrategy] = {}
        self._filter_strategies: Dict[str, ProcessingStrategyType] = {}
        self._custom_strategies: Dict[str, IEventProcessingStrategy] = {}
        self._default_strategy: ProcessingStrategyType = ProcessingStrategyType.DEFAULT
        self._initialized = False

    async def initialize(self) -> None:
        if self._initialized:
            return

        self._strategies[ProcessingStrategyType.DEFAULT] = DefaultProcessingStrategy()
        self._strategies[ProcessingStrategyType.BATCH] = BatchProcessingStrategy()
        self._strategies[ProcessingStrategyType.DEDUP] = DedupProcessingStrategy()
        self._strategies[ProcessingStrategyType.RETRY] = RetryProcessingStrategy()
        self._strategies[ProcessingStrategyType.PRIORITY] = PriorityProcessingStrategy()
        self._strategies[ProcessingStrategyType.RATE_LIMITED] = RateLimitedProcessingStrategy()

        self._initialized = True
        logger.info("EventStrategyRegistry initialized with default strategies")

    async def shutdown(self) -> None:
        self._filter_strategies.clear()
        self._custom_strategies.clear()
        for strategy in self._strategies.values():
            if hasattr(strategy, "shutdown"):
                try:
                    await strategy.shutdown()
                except Exception as e:
                    logger.error(f"Error shutting down strategy: {e}")
        self._strategies.clear()
        self._initialized = False
        logger.info("EventStrategyRegistry shutdown")

    def get_strategy(self, filter_id: Optional[str] = None) -> IEventProcessingStrategy:
        if filter_id and filter_id in self._filter_strategies:
            strategy_type = self._filter_strategies[filter_id]
            return self._strategies.get(
                strategy_type, self._strategies[ProcessingStrategyType.DEFAULT]
            )
        return self._strategies[self._default_strategy]

    def set_filter_strategy(self, filter_id: str, strategy_type: ProcessingStrategyType) -> None:
        if strategy_type not in self._strategies and strategy_type.value not in self._custom_strategies:
            raise ValueError(f"Strategy {strategy_type} not registered")

        self._filter_strategies[filter_id] = strategy_type
        logger.info(f"Filter {filter_id} strategy set to {strategy_type}")

    def set_default_strategy(self, strategy_type: ProcessingStrategyType) -> None:
        if strategy_type not in self._strategies:
            raise ValueError(f"Strategy {strategy_type} not registered")

        old_strategy = self._default_strategy
        self._default_strategy = strategy_type
        logger.info(f"Default event strategy changed from {old_strategy} to {strategy_type}")

    def register_custom_strategy(self, name: str, strategy: IEventProcessingStrategy) -> None:
        self._custom_strategies[name] = strategy
        logger.info(f"Custom event processing strategy registered: {name}")

    def unregister_custom_strategy(self, name: str) -> None:
        if name in self._custom_strategies:
            del self._custom_strategies[name]
            logger.info(f"Custom event processing strategy unregistered: {name}")

    def get_available_strategies(self) -> List[Dict[str, Any]]:
        result = []
        for strategy_type, strategy in self._strategies.items():
            result.append({
                "type": strategy_type.value,
                "name": strategy.get_name(),
                "description": self._get_strategy_description(strategy_type),
            })
        for name, strategy in self._custom_strategies.items():
            result.append({
                "type": f"custom:{name}",
                "name": strategy.get_name(),
                "description": "Custom user-defined strategy",
            })
        return result

    def _get_strategy_description(self, strategy_type: ProcessingStrategyType) -> str:
        descriptions = {
            ProcessingStrategyType.DEFAULT: "Default immediate processing strategy",
            ProcessingStrategyType.BATCH: "Batch processing with configurable size and flush interval",
            ProcessingStrategyType.DEDUP: "Duplicate event detection and filtering",
            ProcessingStrategyType.RETRY: "Automatic retry with exponential backoff",
            ProcessingStrategyType.PRIORITY: "Priority-based processing for high-value events",
            ProcessingStrategyType.RATE_LIMITED: "Rate-limited processing to prevent overload",
        }
        return descriptions.get(strategy_type, "")

    def get_filter_strategy_mapping(self) -> Dict[str, str]:
        return {k: v.value for k, v in self._filter_strategies.items()}


_strategy_registry: Optional[EventStrategyRegistry] = None


def get_strategy_registry() -> EventStrategyRegistry:
    global _strategy_registry
    if _strategy_registry is None:
        _strategy_registry = EventStrategyRegistry()
    return _strategy_registry
