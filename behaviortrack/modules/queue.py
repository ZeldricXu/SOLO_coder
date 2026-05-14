import json
import logging
import threading
import time
from collections import deque
from dataclasses import dataclass, field
from datetime import datetime, timezone, timedelta
from typing import Any, Callable, Deque, Dict, List, Optional, Protocol, TypeVar, Generic

from ..config import settings
from .redis_client import redis_manager


logger = logging.getLogger(__name__)


T = TypeVar("T")


class QueueProcessor(Protocol):
    def __call__(self, items: List[Any]) -> Dict[str, Any]:
        ...


@dataclass
class QueueStats:
    total_enqueued: int = 0
    total_processed: int = 0
    total_failed: int = 0
    current_queue_size: int = 0
    avg_processing_time_ms: float = 0.0


@dataclass
class AsyncQueueResult:
    task_id: str
    status: str = "queued"
    result: Optional[Dict[str, Any]] = None
    error: Optional[str] = None
    created_at: str = field(default_factory=lambda: datetime.now(timezone.utc).isoformat())
    completed_at: Optional[str] = None
    
    def to_dict(self) -> Dict[str, Any]:
        return {
            "task_id": self.task_id,
            "status": self.status,
            "result": self.result,
            "error": self.error,
            "created_at": self.created_at,
            "completed_at": self.completed_at
        }
    
    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> "AsyncQueueResult":
        return cls(
            task_id=data.get("task_id", ""),
            status=data.get("status", "queued"),
            result=data.get("result"),
            error=data.get("error"),
            created_at=data.get("created_at", datetime.now(timezone.utc).isoformat()),
            completed_at=data.get("completed_at")
        )


class InMemoryQueue:
    def __init__(
        self,
        processor: Optional[QueueProcessor] = None,
        max_size: int = 10000,
        batch_size: int = 100,
        flush_interval_ms: int = 100,
        worker_count: int = 2
    ) -> None:
        self._queue: Deque[Any] = deque()
        self._results: Dict[str, AsyncQueueResult] = {}
        self._max_size = max_size
        self._batch_size = batch_size
        self._flush_interval_ms = flush_interval_ms
        self._worker_count = worker_count
        self._processor = processor
        
        self._lock = threading.Lock()
        self._stats = QueueStats()
        self._stop_event = threading.Event()
        self._worker_threads: List[threading.Thread] = []
        self._running = False
        
        self._processing_times: List[float] = []
        
    def enqueue(self, item: Any) -> str:
        task_id = f"task_{int(time.time() * 1000000)}_{threading.get_ident()}"
        
        with self._lock:
            if len(self._queue) >= self._max_size:
                logger.warning(f"Queue is full (size: {len(self._queue)}), dropping oldest items")
                try:
                    self._queue.popleft()
                    self._stats.total_failed += 1
                except IndexError:
                    pass
            
            self._queue.append((task_id, item))
            self._stats.total_enqueued += 1
            self._stats.current_queue_size = len(self._queue)
            
            self._results[task_id] = AsyncQueueResult(task_id=task_id, status="queued")
        
        logger.debug(f"Enqueued task: {task_id}, queue size: {len(self._queue)}")
        return task_id
    
    def enqueue_batch(self, items: List[Any]) -> List[str]:
        task_ids = []
        for item in items:
            task_id = self.enqueue(item)
            task_ids.append(task_id)
        return task_ids
    
    def get_result(self, task_id: str) -> Optional[AsyncQueueResult]:
        return self._results.get(task_id)
    
    def get_all_results(self) -> Dict[str, AsyncQueueResult]:
        return dict(self._results)
    
    def set_processor(self, processor: QueueProcessor) -> None:
        self._processor = processor
    
    def start(self) -> None:
        if self._running:
            logger.warning("Queue workers already running")
            return
        
        logger.info(f"Starting queue workers (count: {self._worker_count})")
        self._stop_event.clear()
        self._running = True
        
        for i in range(self._worker_count):
            thread = threading.Thread(
                target=self._worker_loop,
                name=f"queue-worker-{i}",
                daemon=True
            )
            thread.start()
            self._worker_threads.append(thread)
        
        logger.info("Queue workers started successfully")
    
    def stop(self, wait: bool = True, timeout: float = 5.0) -> None:
        if not self._running:
            return
        
        logger.info("Stopping queue workers...")
        self._stop_event.set()
        
        if wait:
            for thread in self._worker_threads:
                thread.join(timeout=timeout)
        
        self._worker_threads = []
        self._running = False
        logger.info("Queue workers stopped")
    
    def flush(self) -> int:
        if not self._processor:
            logger.warning("No processor configured, cannot flush")
            return 0
        
        processed_count = 0
        
        with self._lock:
            batch_size = min(self._batch_size, len(self._queue))
            if batch_size == 0:
                return 0
            
            items_to_process = []
            for _ in range(batch_size):
                try:
                    items_to_process.append(self._queue.popleft())
                except IndexError:
                    break
            
            self._stats.current_queue_size = len(self._queue)
        
        if items_to_process:
            task_ids = [item[0] for item in items_to_process]
            data_items = [item[1] for item in items_to_process]
            
            try:
                start_time = time.time()
                result = self._processor(data_items)
                elapsed_ms = (time.time() - start_time) * 1000
                
                self._processing_times.append(elapsed_ms)
                if len(self._processing_times) > 1000:
                    self._processing_times = self._processing_times[-1000:]
                
                self._stats.avg_processing_time_ms = sum(self._processing_times) / len(self._processing_times)
                
                for task_id in task_ids:
                    if task_id in self._results:
                        self._results[task_id].status = "completed"
                        self._results[task_id].result = result
                        self._results[task_id].completed_at = datetime.now(timezone.utc).isoformat()
                
                self._stats.total_processed += len(items_to_process)
                processed_count = len(items_to_process)
                
                logger.debug(f"Processed {len(items_to_process)} items in {elapsed_ms:.2f}ms")
                
            except Exception as e:
                logger.exception(f"Error processing batch: {str(e)}")
                self._stats.total_failed += len(items_to_process)
                
                for task_id in task_ids:
                    if task_id in self._results:
                        self._results[task_id].status = "failed"
                        self._results[task_id].error = str(e)
                        self._results[task_id].completed_at = datetime.now(timezone.utc).isoformat()
        
        return processed_count
    
    def _worker_loop(self) -> None:
        logger.debug(f"Worker thread started: {threading.current_thread().name}")
        
        while not self._stop_event.is_set():
            try:
                processed = self.flush()
                if processed == 0:
                    time.sleep(self._flush_interval_ms / 1000.0)
            except Exception as e:
                logger.exception(f"Worker error: {str(e)}")
                time.sleep(self._flush_interval_ms / 1000.0)
        
        logger.debug(f"Worker thread stopped: {threading.current_thread().name}")
    
    def get_stats(self) -> QueueStats:
        with self._lock:
            self._stats.current_queue_size = len(self._queue)
            return QueueStats(
                total_enqueued=self._stats.total_enqueued,
                total_processed=self._stats.total_processed,
                total_failed=self._stats.total_failed,
                current_queue_size=len(self._queue),
                avg_processing_time_ms=self._stats.avg_processing_time_ms
            )
    
    def clear(self) -> None:
        with self._lock:
            self._queue.clear()
            self._results.clear()
            self._stats = QueueStats()
            self._processing_times = []
    
    def is_running(self) -> bool:
        return self._running
    
    def __len__(self) -> int:
        with self._lock:
            return len(self._queue)


class RedisQueue:
    def __init__(
        self,
        queue_key: str,
        processor: Optional[QueueProcessor] = None,
        batch_size: int = 100,
        flush_interval_ms: int = 100,
        worker_count: int = 2
    ) -> None:
        self._queue_key = queue_key
        self._processor = processor
        self._batch_size = batch_size
        self._flush_interval_ms = flush_interval_ms
        self._worker_count = worker_count
        self._result_prefix = settings.QUEUE_RESULT_PREFIX
        self._result_ttl = settings.QUEUE_RESULT_TTL_SECONDS
        
        self._stats = QueueStats()
        self._stop_event = threading.Event()
        self._worker_threads: List[threading.Thread] = []
        self._running = False
        self._stats_lock = threading.Lock()
        
        self._processing_times: List[float] = []
        
    def enqueue(self, item: Any) -> str:
        task_id = f"task_{int(time.time() * 1000000)}_{threading.get_ident()}"
        
        queue_item = {
            "task_id": task_id,
            "item": item,
            "enqueued_at": datetime.now(timezone.utc).isoformat()
        }
        
        result = redis_manager.enqueue(self._queue_key, queue_item)
        
        if result:
            self._save_task_result(AsyncQueueResult(task_id=task_id, status="queued"))
            
            with self._stats_lock:
                self._stats.total_enqueued += 1
            
            logger.debug(f"Enqueued task to Redis: {task_id}")
        else:
            logger.warning(f"Failed to enqueue task to Redis: {task_id}")
        
        return task_id
    
    def enqueue_batch(self, items: List[Any]) -> List[str]:
        task_ids = []
        queue_items = []
        now = datetime.now(timezone.utc).isoformat()
        
        for item in items:
            task_id = f"task_{int(time.time() * 1000000)}_{threading.get_ident()}_{len(task_ids)}"
            task_ids.append(task_id)
            
            queue_items.append({
                "task_id": task_id,
                "item": item,
                "enqueued_at": now
            })
        
        count = redis_manager.enqueue_batch(self._queue_key, queue_items)
        
        for i, task_id in enumerate(task_ids):
            if i < count:
                self._save_task_result(AsyncQueueResult(task_id=task_id, status="queued"))
            else:
                self._save_task_result(AsyncQueueResult(
                    task_id=task_id,
                    status="failed",
                    error="Failed to enqueue to Redis"
                ))
        
        with self._stats_lock:
            self._stats.total_enqueued += count
        
        logger.debug(f"Enqueued {count}/{len(items)} tasks to Redis")
        return task_ids
    
    def _save_task_result(self, result: AsyncQueueResult) -> None:
        key = f"{self._result_prefix}{result.task_id}"
        redis_manager.set_cache(key, result.to_dict(), self._result_ttl)
    
    def get_result(self, task_id: str) -> Optional[AsyncQueueResult]:
        key = f"{self._result_prefix}{task_id}"
        data = redis_manager.get_cache(key)
        if data:
            return AsyncQueueResult.from_dict(data)
        return None
    
    def get_all_results(self) -> Dict[str, AsyncQueueResult]:
        return {}
    
    def set_processor(self, processor: QueueProcessor) -> None:
        self._processor = processor
    
    def start(self) -> None:
        if self._running:
            logger.warning("Redis queue workers already running")
            return
        
        logger.info(f"Starting Redis queue workers (count: {self._worker_count})")
        self._stop_event.clear()
        self._running = True
        
        for i in range(self._worker_count):
            thread = threading.Thread(
                target=self._worker_loop,
                name=f"redis-queue-worker-{i}",
                daemon=True
            )
            thread.start()
            self._worker_threads.append(thread)
        
        logger.info("Redis queue workers started successfully")
    
    def stop(self, wait: bool = True, timeout: float = 5.0) -> None:
        if not self._running:
            return
        
        logger.info("Stopping Redis queue workers...")
        self._stop_event.set()
        
        if wait:
            for thread in self._worker_threads:
                thread.join(timeout=timeout)
        
        self._worker_threads = []
        self._running = False
        logger.info("Redis queue workers stopped")
    
    def flush(self) -> int:
        if not self._processor:
            logger.warning("No processor configured, cannot flush")
            return 0
        
        items = redis_manager.dequeue_batch(self._queue_key, self._batch_size)
        if not items:
            return 0
        
        task_ids = [item["task_id"] for item in items]
        data_items = [item["item"] for item in items]
        processed_count = 0
        
        try:
            start_time = time.time()
            result = self._processor(data_items)
            elapsed_ms = (time.time() - start_time) * 1000
            
            with self._stats_lock:
                self._processing_times.append(elapsed_ms)
                if len(self._processing_times) > 1000:
                    self._processing_times = self._processing_times[-1000:]
                self._stats.avg_processing_time_ms = sum(self._processing_times) / len(self._processing_times)
            
            for task_id in task_ids:
                completed_result = AsyncQueueResult(
                    task_id=task_id,
                    status="completed",
                    result=result,
                    completed_at=datetime.now(timezone.utc).isoformat()
                )
                self._save_task_result(completed_result)
            
            with self._stats_lock:
                self._stats.total_processed += len(items)
            
            processed_count = len(items)
            logger.debug(f"Processed {len(items)} Redis queue items in {elapsed_ms:.2f}ms")
            
        except Exception as e:
            logger.exception(f"Error processing Redis queue batch: {str(e)}")
            
            with self._stats_lock:
                self._stats.total_failed += len(items)
            
            for task_id in task_ids:
                failed_result = AsyncQueueResult(
                    task_id=task_id,
                    status="failed",
                    error=str(e),
                    completed_at=datetime.now(timezone.utc).isoformat()
                )
                self._save_task_result(failed_result)
        
        return processed_count
    
    def _worker_loop(self) -> None:
        logger.debug(f"Redis worker thread started: {threading.current_thread().name}")
        
        while not self._stop_event.is_set():
            try:
                processed = self.flush()
                if processed == 0:
                    time.sleep(self._flush_interval_ms / 1000.0)
            except Exception as e:
                logger.exception(f"Redis worker error: {str(e)}")
                time.sleep(self._flush_interval_ms / 1000.0)
        
        logger.debug(f"Redis worker thread stopped: {threading.current_thread().name}")
    
    def get_stats(self) -> QueueStats:
        queue_size = redis_manager.queue_size(self._queue_key)
        
        with self._stats_lock:
            return QueueStats(
                total_enqueued=self._stats.total_enqueued,
                total_processed=self._stats.total_processed,
                total_failed=self._stats.total_failed,
                current_queue_size=queue_size,
                avg_processing_time_ms=self._stats.avg_processing_time_ms
            )
    
    def clear(self) -> None:
        redis_manager.clear_queue(self._queue_key)
        
        with self._stats_lock:
            self._stats = QueueStats()
            self._processing_times = []
        
        redis_manager.delete_pattern(f"{self._result_prefix}*")
    
    def is_running(self) -> bool:
        return self._running
    
    def __len__(self) -> int:
        return redis_manager.queue_size(self._queue_key)


class EventQueue:
    def __init__(
        self,
        processor: Optional[QueueProcessor] = None,
        max_size: int = 10000,
        batch_size: int = 100,
        flush_interval_ms: int = 100,
        worker_count: int = 2,
        queue_key: Optional[str] = None,
        use_redis: Optional[bool] = None
    ) -> None:
        self._use_redis = use_redis if use_redis is not None else settings.USE_REDIS_QUEUE
        self._queue_key = queue_key or settings.BEHAVIOR_QUEUE_KEY
        
        if self._use_redis and redis_manager.is_connected():
            logger.info(f"Using Redis queue: {self._queue_key}")
            self._impl = RedisQueue(
                queue_key=self._queue_key,
                processor=processor,
                batch_size=batch_size,
                flush_interval_ms=flush_interval_ms,
                worker_count=worker_count
            )
        else:
            if self._use_redis:
                logger.warning("Redis not available, falling back to in-memory queue")
            else:
                logger.info("Using in-memory queue")
            self._impl = InMemoryQueue(
                processor=processor,
                max_size=max_size,
                batch_size=batch_size,
                flush_interval_ms=flush_interval_ms,
                worker_count=worker_count
            )
    
    def enqueue(self, item: Any) -> str:
        return self._impl.enqueue(item)
    
    def enqueue_batch(self, items: List[Any]) -> List[str]:
        return self._impl.enqueue_batch(items)
    
    def get_result(self, task_id: str) -> Optional[AsyncQueueResult]:
        return self._impl.get_result(task_id)
    
    def get_all_results(self) -> Dict[str, AsyncQueueResult]:
        return self._impl.get_all_results()
    
    def set_processor(self, processor: QueueProcessor) -> None:
        self._impl.set_processor(processor)
    
    def start(self) -> None:
        self._impl.start()
    
    def stop(self, wait: bool = True, timeout: float = 5.0) -> None:
        self._impl.stop(wait, timeout)
    
    def flush(self) -> int:
        return self._impl.flush()
    
    def get_stats(self) -> QueueStats:
        return self._impl.get_stats()
    
    def clear(self) -> None:
        self._impl.clear()
    
    def is_running(self) -> bool:
        return self._impl.is_running()
    
    def __len__(self) -> int:
        return len(self._impl)
    
    @property
    def use_redis(self) -> bool:
        return isinstance(self._impl, RedisQueue)


class CacheValue(Generic[T]):
    def __init__(self, value: T, ttl_seconds: int = 300) -> None:
        self.value = value
        self.created_at = time.time()
        self.ttl_seconds = ttl_seconds
    
    def is_expired(self) -> bool:
        return (time.time() - self.created_at) > self.ttl_seconds


class InMemoryStatisticsCache:
    def __init__(self, default_ttl_seconds: int = 300) -> None:
        self._cache: Dict[str, CacheValue] = {}
        self._default_ttl = default_ttl_seconds
        self._lock = threading.Lock()
        self._hit_count = 0
        self._miss_count = 0
    
    def get(self, key: str) -> Optional[Any]:
        with self._lock:
            cache_entry = self._cache.get(key)
            
            if cache_entry is None:
                self._miss_count += 1
                return None
            
            if cache_entry.is_expired():
                del self._cache[key]
                self._miss_count += 1
                return None
            
            self._hit_count += 1
            return cache_entry.value
    
    def set(self, key: str, value: Any, ttl_seconds: Optional[int] = None) -> None:
        ttl = ttl_seconds if ttl_seconds is not None else self._default_ttl
        
        with self._lock:
            self._cache[key] = CacheValue(value, ttl)
    
    def delete(self, key: str) -> bool:
        with self._lock:
            if key in self._cache:
                del self._cache[key]
                return True
            return False
    
    def clear(self) -> None:
        with self._lock:
            self._cache.clear()
            self._hit_count = 0
            self._miss_count = 0
    
    def evict_expired(self) -> int:
        with self._lock:
            expired_keys = [
                key for key, value in self._cache.items()
                if value.is_expired()
            ]
            for key in expired_keys:
                del self._cache[key]
            return len(expired_keys)
    
    def get_hit_rate(self) -> float:
        total = self._hit_count + self._miss_count
        if total == 0:
            return 0.0
        return self._hit_count / total
    
    def get_stats(self) -> Dict[str, Any]:
        with self._lock:
            return {
                "cache_size": len(self._cache),
                "hit_count": self._hit_count,
                "miss_count": self._miss_count,
                "hit_rate": self.get_hit_rate()
            }


class RedisStatisticsCache:
    def __init__(self, default_ttl_seconds: int = 300, prefix: Optional[str] = None) -> None:
        self._default_ttl = default_ttl_seconds
        self._prefix = prefix or settings.STATISTICS_CACHE_PREFIX
        self._hit_count = 0
        self._miss_count = 0
        self._lock = threading.Lock()
    
    def _get_full_key(self, key: str) -> str:
        return f"{self._prefix}{key}"
    
    def get(self, key: str) -> Optional[Any]:
        full_key = self._get_full_key(key)
        value = redis_manager.get_cache(full_key)
        
        with self._lock:
            if value is not None:
                self._hit_count += 1
            else:
                self._miss_count += 1
        
        return value
    
    def set(self, key: str, value: Any, ttl_seconds: Optional[int] = None) -> None:
        ttl = ttl_seconds if ttl_seconds is not None else self._default_ttl
        full_key = self._get_full_key(key)
        redis_manager.set_cache(full_key, value, ttl)
    
    def delete(self, key: str) -> bool:
        full_key = self._get_full_key(key)
        return redis_manager.delete_cache(full_key)
    
    def clear(self) -> None:
        redis_manager.delete_pattern(f"{self._prefix}*")
        with self._lock:
            self._hit_count = 0
            self._miss_count = 0
    
    def evict_expired(self) -> int:
        return 0
    
    def get_hit_rate(self) -> float:
        with self._lock:
            total = self._hit_count + self._miss_count
            if total == 0:
                return 0.0
            return self._hit_count / total
    
    def get_stats(self) -> Dict[str, Any]:
        redis_client = redis_manager.get_client()
        cache_size = 0
        if redis_client:
            try:
                keys = redis_client.keys(f"{self._prefix}*")
                cache_size = len(keys)
            except:
                pass
        
        with self._lock:
            return {
                "cache_size": cache_size,
                "hit_count": self._hit_count,
                "miss_count": self._miss_count,
                "hit_rate": self.get_hit_rate()
            }


class StatisticsCache:
    def __init__(self, default_ttl_seconds: int = 300, use_redis: Optional[bool] = None) -> None:
        self._use_redis = use_redis if use_redis is not None else settings.USE_REDIS_QUEUE
        
        if self._use_redis and redis_manager.is_connected():
            logger.info("Using Redis statistics cache")
            self._impl = RedisStatisticsCache(default_ttl_seconds)
        else:
            if self._use_redis:
                logger.warning("Redis not available, falling back to in-memory statistics cache")
            logger.info("Using in-memory statistics cache")
            self._impl = InMemoryStatisticsCache(default_ttl_seconds)
    
    def get(self, key: str) -> Optional[Any]:
        return self._impl.get(key)
    
    def set(self, key: str, value: Any, ttl_seconds: Optional[int] = None) -> None:
        self._impl.set(key, value, ttl_seconds)
    
    def delete(self, key: str) -> bool:
        return self._impl.delete(key)
    
    def clear(self) -> None:
        self._impl.clear()
    
    def evict_expired(self) -> int:
        return self._impl.evict_expired()
    
    def get_hit_rate(self) -> float:
        return self._impl.get_hit_rate()
    
    def get_stats(self) -> Dict[str, Any]:
        return self._impl.get_stats()
    
    @property
    def use_redis(self) -> bool:
        return isinstance(self._impl, RedisStatisticsCache)


class TimeWindowManager:
    def __init__(self, window_seconds: int = 300):
        self._window_seconds = window_seconds
        self._current_window_start: Optional[datetime] = None
        self._last_check_time: Optional[datetime] = None
        self._lock = threading.Lock()
    
    def _get_current_window_start(self) -> datetime:
        now = datetime.now(timezone.utc)
        timestamp = int(now.timestamp())
        window_start_timestamp = (timestamp // self._window_seconds) * self._window_seconds
        return datetime.fromtimestamp(window_start_timestamp, tz=timezone.utc)
    
    def check_window_rollover(self) -> bool:
        with self._lock:
            new_window_start = self._get_current_window_start()
            
            if self._current_window_start is None:
                self._current_window_start = new_window_start
                self._last_check_time = datetime.now(timezone.utc)
                return False
            
            if new_window_start > self._current_window_start:
                self._current_window_start = new_window_start
                self._last_check_time = datetime.now(timezone.utc)
                return True
            
            self._last_check_time = datetime.now(timezone.utc)
            return False
    
    def get_current_window_start(self) -> datetime:
        return self._get_current_window_start()
    
    def get_current_window_end(self) -> datetime:
        return self._get_current_window_start() + timedelta(seconds=self._window_seconds)
    
    def get_window_info(self) -> Dict[str, Any]:
        with self._lock:
            start = self._get_current_window_start()
            end = start + timedelta(seconds=self._window_seconds)
            now = datetime.now(timezone.utc)
            remaining = (end - now).total_seconds()
            
            return {
                "window_seconds": self._window_seconds,
                "current_window_start": start.isoformat(),
                "current_window_end": end.isoformat(),
                "remaining_seconds": max(0, int(remaining)),
                "is_rolled_over": self._current_window_start is not None
            }


class AnalysisTaskQueue:
    def __init__(
        self,
        processor: Optional[QueueProcessor] = None,
        batch_size: int = 50,
        flush_interval_ms: int = 500,
        worker_count: int = 1
    ) -> None:
        self._queue = EventQueue(
            processor=processor,
            batch_size=batch_size,
            flush_interval_ms=flush_interval_ms,
            worker_count=worker_count,
            queue_key=settings.ANALYSIS_QUEUE_KEY
        )
    
    def submit_analysis_task(self, task_type: str, params: Dict[str, Any]) -> str:
        task = {
            "task_type": task_type,
            "params": params,
            "created_at": datetime.now(timezone.utc).isoformat()
        }
        return self._queue.enqueue(task)
    
    def submit_daily_stats_task(self, start_date: str, end_date: str) -> str:
        return self.submit_analysis_task("daily_stats", {
            "start_date": start_date,
            "end_date": end_date
        })
    
    def submit_user_profile_task(self, user_id: str) -> str:
        return self.submit_analysis_task("user_profile", {
            "user_id": user_id
        })
    
    def submit_event_analysis_task(self, event_type: str, start_time: str, end_time: str) -> str:
        return self.submit_analysis_task("event_analysis", {
            "event_type": event_type,
            "start_time": start_time,
            "end_time": end_time
        })
    
    def get_task_result(self, task_id: str) -> Optional[AsyncQueueResult]:
        return self._queue.get_result(task_id)
    
    def get_stats(self) -> QueueStats:
        return self._queue.get_stats()
    
    def start(self) -> None:
        self._queue.start()
    
    def stop(self, wait: bool = True, timeout: float = 5.0) -> None:
        self._queue.stop(wait, timeout)
    
    def is_running(self) -> bool:
        return self._queue.is_running()
