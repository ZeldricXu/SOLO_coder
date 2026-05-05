from typing import Dict, List, Optional
from datetime import datetime, date
import threading
import time
import queue
from dataclasses import dataclass, asdict
import json
import hashlib
import os

try:
    import redis
    REDIS_AVAILABLE = True
except ImportError:
    REDIS_AVAILABLE = False

from .models import PerformanceStats, InferenceRequest, generate_id
from ..storage import metadata_store
from ...config import Config


@dataclass
class BufferedInferenceRecord:
    model_id: str
    input_data: str
    result: Optional[Dict]
    inference_time: float
    success: bool
    error_message: str
    timestamp: float = None

    def __post_init__(self):
        if self.timestamp is None:
            self.timestamp = time.time()

    def to_dict(self) -> Dict:
        return {
            "model_id": self.model_id,
            "input_data": self.input_data,
            "result": self.result,
            "inference_time": self.inference_time,
            "success": self.success,
            "error_message": self.error_message,
            "timestamp": self.timestamp
        }

    @classmethod
    def from_dict(cls, data: Dict) -> "BufferedInferenceRecord":
        return cls(
            model_id=data["model_id"],
            input_data=data["input_data"],
            result=data.get("result"),
            inference_time=data["inference_time"],
            success=data["success"],
            error_message=data["error_message"],
            timestamp=data.get("timestamp")
        )


class RedisBufferQueue:
    def __init__(
        self,
        redis_config: Dict,
        queue_key: str = "modelserve:monitoring:queue",
        max_queue_size: int = 10000
    ):
        self._redis_config = redis_config
        self._queue_key = queue_key
        self._max_queue_size = max_queue_size
        self._pending_key_prefix = redis_config.get('pending_key_prefix', 'modelserve:monitoring:pending')
        self._client: Optional[redis.Redis] = None
        self._lock = threading.Lock()
        self._connected = False

    def _get_client(self) -> Optional[redis.Redis]:
        with self._lock:
            if self._client is not None and self._connected:
                try:
                    self._client.ping()
                    return self._client
                except Exception:
                    pass

            if not REDIS_AVAILABLE:
                print("Redis not available, cannot connect to Redis")
                return None

            try:
                client = redis.Redis(
                    host=self._redis_config.get('host', 'localhost'),
                    port=self._redis_config.get('port', 6379),
                    db=self._redis_config.get('db', 0),
                    password=self._redis_config.get('password') or None,
                    socket_timeout=self._redis_config.get('socket_timeout', 5),
                    socket_connect_timeout=self._redis_config.get('socket_connect_timeout', 2),
                    decode_responses=True
                )
                client.ping()
                self._client = client
                self._connected = True
                print("Connected to Redis successfully")
                return client
            except Exception as e:
                print(f"Failed to connect to Redis: {e}")
                self._connected = False
                return None

    def put(self, record: BufferedInferenceRecord) -> bool:
        client = self._get_client()
        if not client:
            return False

        try:
            record_json = json.dumps(record.to_dict(), ensure_ascii=False)
            queue_size = client.llen(self._queue_key)

            if queue_size >= self._max_queue_size:
                print(f"Redis queue full, dropping oldest record")
                client.lpop(self._queue_key)

            client.rpush(self._queue_key, record_json)
            return True
        except Exception as e:
            print(f"Error putting record to Redis queue: {e}")
            self._connected = False
            return False

    def get_batch(self, batch_size: int, timeout: float = 1.0) -> List[BufferedInferenceRecord]:
        client = self._get_client()
        if not client:
            return []

        records: List[BufferedInferenceRecord] = []
        try:
            for _ in range(batch_size):
                result = client.blpop(self._queue_key, timeout=timeout if _ == 0 else 0.1)
                if result is None:
                    break

                _, record_json = result
                try:
                    record_data = json.loads(record_json)
                    record = BufferedInferenceRecord.from_dict(record_data)
                    records.append(record)
                except Exception as e:
                    print(f"Error parsing record from Redis: {e}")

        except Exception as e:
            print(f"Error getting batch from Redis: {e}")
            self._connected = False

        return records

    def qsize(self) -> int:
        client = self._get_client()
        if not client:
            return 0
        try:
            return client.llen(self._queue_key)
        except Exception:
            return 0

    def recover_pending(self, worker_id: str) -> List[BufferedInferenceRecord]:
        client = self._get_client()
        if not client:
            return []

        pending_key = f"{self._pending_key_prefix}:{worker_id}"
        records: List[BufferedInferenceRecord] = []

        try:
            while True:
                result = client.lpop(pending_key)
                if result is None:
                    break

                try:
                    record_data = json.loads(result)
                    record = BufferedInferenceRecord.from_dict(record_data)
                    records.append(record)
                except Exception:
                    pass
        except Exception:
            pass

        return records

    def save_to_pending(self, worker_id: str, records: List[BufferedInferenceRecord]) -> bool:
        client = self._get_client()
        if not client:
            return False

        pending_key = f"{self._pending_key_prefix}:{worker_id}"
        try:
            for record in records:
                record_json = json.dumps(record.to_dict(), ensure_ascii=False)
                client.rpush(pending_key, record_json)
            return True
        except Exception:
            return False

    def clear_pending(self, worker_id: str) -> bool:
        client = self._get_client()
        if not client:
            return False

        pending_key = f"{self._pending_key_prefix}:{worker_id}"
        try:
            client.delete(pending_key)
            return True
        except Exception:
            return False

    def is_connected(self) -> bool:
        return self._connected


class HybridBufferQueue:
    def __init__(
        self,
        use_redis: bool = False,
        redis_config: Optional[Dict] = None,
        max_queue_size: int = 10000
    ):
        self._use_redis = use_redis and REDIS_AVAILABLE
        self._max_queue_size = max_queue_size
        self._memory_queue: queue.Queue = queue.Queue(maxsize=max_queue_size)

        if self._use_redis and redis_config:
            self._redis_queue = RedisBufferQueue(
                redis_config=redis_config,
                queue_key=redis_config.get('queue_key', 'modelserve:monitoring:queue'),
                max_queue_size=max_queue_size
            )
        else:
            self._redis_queue = None

        self._lock = threading.Lock()
        self._worker_id = hashlib.md5(os.urandom(16)).hexdigest()[:8]

    def put(self, record: BufferedInferenceRecord) -> bool:
        if self._use_redis and self._redis_queue:
            if self._redis_queue.put(record):
                return True

        try:
            self._memory_queue.put_nowait(record)
            return True
        except queue.Full:
            return False

    def get_batch(self, batch_size: int, timeout: float = 1.0) -> List[BufferedInferenceRecord]:
        records: List[BufferedInferenceRecord] = []

        if self._use_redis and self._redis_queue:
            redis_records = self._redis_queue.get_batch(batch_size, timeout)
            if redis_records:
                records.extend(redis_records)
                return records

        try:
            while len(records) < batch_size:
                try:
                    record = self._memory_queue.get(timeout=timeout if len(records) == 0 else 0.1)
                    records.append(record)
                    self._memory_queue.task_done()
                except queue.Empty:
                    break
        except Exception:
            pass

        return records

    def qsize(self) -> int:
        total = 0
        if self._use_redis and self._redis_queue:
            total += self._redis_queue.qsize()
        total += self._memory_queue.qsize()
        return total

    def recover_pending_records(self) -> List[BufferedInferenceRecord]:
        if self._use_redis and self._redis_queue:
            return self._redis_queue.recover_pending(self._worker_id)
        return []

    def save_as_pending(self, records: List[BufferedInferenceRecord]) -> bool:
        if self._use_redis and self._redis_queue:
            return self._redis_queue.save_to_pending(self._worker_id, records)
        return False

    def clear_pending(self) -> bool:
        if self._use_redis and self._redis_queue:
            return self._redis_queue.clear_pending(self._worker_id)
        return False

    def is_redis_connected(self) -> bool:
        if self._redis_queue:
            return self._redis_queue.is_connected()
        return False

    def maxsize(self) -> int:
        return self._max_queue_size


class AsyncMonitoringWorker(threading.Thread):
    def __init__(
        self,
        buffer_queue: HybridBufferQueue,
        flush_interval: float = 1.0,
        batch_size: int = 100,
        stats_collection: str = "stats",
        inference_collection: str = "inferences",
        use_redis: bool = False
    ):
        super().__init__(daemon=True, name="MonitoringWorker")
        self._buffer_queue = buffer_queue
        self._flush_interval = flush_interval
        self._batch_size = batch_size
        self._stats_collection = stats_collection
        self._inference_collection = inference_collection
        self._use_redis = use_redis

        self._running = threading.Event()
        self._running.set()
        self._lock = threading.Lock()
        self._cached_stats: Dict[str, PerformanceStats] = {}
        self._shutdown_hook = threading.Event()

    def stop(self):
        self._running.clear()

    def _get_today_date(self) -> str:
        return date.today().isoformat()

    def _get_stat_id(self, model_id: str, stat_date: str) -> str:
        return f"stat_{model_id}_{stat_date}"

    def _get_or_create_stats(self, model_id: str, stat_date: str) -> PerformanceStats:
        stat_id = self._get_stat_id(model_id, stat_date)

        with self._lock:
            if stat_id in self._cached_stats:
                return self._cached_stats[stat_id]

            existing = metadata_store.load(self._stats_collection, stat_id)
            if existing:
                stats = PerformanceStats.from_dict(existing)
            else:
                stats = PerformanceStats(
                    stat_id=stat_id,
                    model_id=model_id,
                    stat_date=stat_date
                )

            self._cached_stats[stat_id] = stats
            return stats

    def _flush_batch(self, records: List[BufferedInferenceRecord]):
        if not records:
            return

        today = self._get_today_date()
        stats_updates: Dict[str, PerformanceStats] = {}

        for record in records:
            try:
                request_id = generate_id("req")
                request = InferenceRequest(
                    request_id=request_id,
                    model_id=record.model_id,
                    input_data=record.input_data,
                    result=record.result,
                    inference_time=record.inference_time,
                    status="success" if record.success else "failed",
                    error_message=record.error_message
                )

                metadata_store.save(self._inference_collection, request_id, request.to_dict())

                stat_id = self._get_stat_id(record.model_id, today)
                if stat_id not in stats_updates:
                    stats_updates[stat_id] = self._get_or_create_stats(record.model_id, today)

                stats_updates[stat_id].add_request(record.inference_time, record.success)

            except Exception as e:
                print(f"Error processing monitoring record: {e}")

        for stat_id, stats in stats_updates.items():
            try:
                metadata_store.save(self._stats_collection, stat_id, stats.to_dict())
            except Exception as e:
                print(f"Error saving stats: {e}")

    def run(self):
        if self._use_redis:
            pending_records = self._buffer_queue.recover_pending_records()
            if pending_records:
                print(f"Recovered {len(pending_records)} pending records from Redis")
                self._flush_batch(pending_records)
                self._buffer_queue.clear_pending()

        while self._running.is_set():
            records: List[BufferedInferenceRecord] = []

            try:
                records = self._buffer_queue.get_batch(self._batch_size, self._flush_interval)

                if records:
                    if self._use_redis and len(records) > 0:
                        self._buffer_queue.save_as_pending(records)

                    self._flush_batch(records)

                    if self._use_redis:
                        self._buffer_queue.clear_pending()

            except Exception as e:
                print(f"Error in monitoring worker: {e}")

    def flush_all(self):
        all_records: List[BufferedInferenceRecord] = []

        while True:
            batch = self._buffer_queue.get_batch(self._batch_size, timeout=0.1)
            if not batch:
                break
            all_records.extend(batch)

        if all_records:
            self._flush_batch(all_records)


class MonitoringManager:
    def __init__(
        self,
        flush_interval: float = 1.0,
        batch_size: int = 100,
        max_queue_size: int = 10000,
        use_redis: bool = False,
        redis_config: Optional[Dict] = None
    ):
        self.stats_collection = "stats"
        self.inference_collection = "inferences"
        self._lock = threading.Lock()
        self._realtime_stats: Dict[str, PerformanceStats] = {}

        self._use_redis = use_redis and REDIS_AVAILABLE
        self._redis_config = redis_config or Config.REDIS_CONFIG if hasattr(Config, 'REDIS_CONFIG') else {}

        self._buffer_queue = HybridBufferQueue(
            use_redis=self._use_redis,
            redis_config=self._redis_config,
            max_queue_size=max_queue_size
        )

        self._flush_interval = flush_interval
        self._batch_size = batch_size

        self._worker: Optional[AsyncMonitoringWorker] = None
        self._worker_lock = threading.Lock()
        self._started = False

    def _ensure_worker_started(self):
        with self._worker_lock:
            if self._started and self._worker and self._worker.is_alive():
                return

            if self._worker and self._worker.is_alive():
                return

            self._worker = AsyncMonitoringWorker(
                buffer_queue=self._buffer_queue,
                flush_interval=self._flush_interval,
                batch_size=self._batch_size,
                stats_collection=self.stats_collection,
                inference_collection=self.inference_collection,
                use_redis=self._use_redis
            )
            self._worker.start()
            self._started = True
            print(f"Async monitoring worker started (Redis: {self._use_redis})")

    def _get_today_date(self) -> str:
        return date.today().isoformat()

    def _get_stat_id(self, model_id: str, stat_date: str) -> str:
        return f"stat_{model_id}_{stat_date}"

    def _get_or_create_stats(self, model_id: str, stat_date: Optional[str] = None) -> PerformanceStats:
        if stat_date is None:
            stat_date = self._get_today_date()

        stat_id = self._get_stat_id(model_id, stat_date)

        with self._lock:
            if stat_id in self._realtime_stats:
                return self._realtime_stats[stat_id]

            existing = metadata_store.load(self.stats_collection, stat_id)
            if existing:
                stats = PerformanceStats.from_dict(existing)
            else:
                stats = PerformanceStats(
                    stat_id=stat_id,
                    model_id=model_id,
                    stat_date=stat_date
                )

            self._realtime_stats[stat_id] = stats
            return stats

    def record_inference(
        self,
        model_id: str,
        input_data: str,
        result: Optional[Dict],
        inference_time: float,
        success: bool = True,
        error_message: str = ""
    ) -> InferenceRequest:
        self._ensure_worker_started()

        record = BufferedInferenceRecord(
            model_id=model_id,
            input_data=input_data[:1000],
            result=result,
            inference_time=inference_time,
            success=success,
            error_message=error_message
        )

        if not self._buffer_queue.put(record):
            print(f"Warning: Monitoring buffer is full, dropping record for model {model_id}")

        request_id = generate_id("req")
        return InferenceRequest(
            request_id=request_id,
            model_id=model_id,
            input_data=input_data[:1000],
            result=result,
            inference_time=inference_time,
            status="success" if success else "failed",
            error_message=error_message
        )

    def record_inference_sync(
        self,
        model_id: str,
        input_data: str,
        result: Optional[Dict],
        inference_time: float,
        success: bool = True,
        error_message: str = ""
    ) -> InferenceRequest:
        request_id = generate_id("req")

        request = InferenceRequest(
            request_id=request_id,
            model_id=model_id,
            input_data=input_data[:1000],
            result=result,
            inference_time=inference_time,
            status="success" if success else "failed",
            error_message=error_message
        )

        metadata_store.save(self.inference_collection, request_id, request.to_dict())

        stats = self._get_or_create_stats(model_id)
        stats.add_request(inference_time, success)
        metadata_store.save(self.stats_collection, stats.stat_id, stats.to_dict())

        return request

    def get_stats(self, model_id: str, stat_date: Optional[str] = None) -> Optional[PerformanceStats]:
        if stat_date is None:
            stat_date = self._get_today_date()

        stat_id = self._get_stat_id(model_id, stat_date)

        if stat_id in self._realtime_stats:
            return self._realtime_stats[stat_id]

        data = metadata_store.load(self.stats_collection, stat_id)
        if data:
            return PerformanceStats.from_dict(data)
        return None

    def get_model_stats_range(
        self,
        model_id: str,
        start_date: str,
        end_date: str
    ) -> List[PerformanceStats]:
        self.flush()

        all_stats = metadata_store.list_by_field(self.stats_collection, "model_id", model_id)
        filtered_stats = []

        for stat_data in all_stats:
            stat = PerformanceStats.from_dict(stat_data)
            if start_date <= stat.stat_date <= end_date:
                filtered_stats.append(stat)

        return sorted(filtered_stats, key=lambda s: s.stat_date)

    def get_aggregated_stats(self, model_id: str, start_date: str, end_date: str) -> Dict:
        self.flush()

        stats_list = self.get_model_stats_range(model_id, start_date, end_date)

        if not stats_list:
            return {
                "model_id": model_id,
                "start_date": start_date,
                "end_date": end_date,
                "total_requests": 0,
                "total_errors": 0,
                "avg_latency": 0.0,
                "max_latency": 0.0,
                "min_latency": 0.0,
                "throughput": 0.0
            }

        total_requests = sum(s.request_count for s in stats_list)
        total_errors = sum(s.error_count for s in stats_list)
        total_latency = sum(s.total_latency for s in stats_list)

        all_max_latencies = [s.max_latency for s in stats_list if s.max_latency > 0]
        all_min_latencies = [s.min_latency for s in stats_list if s.min_latency > 0]

        return {
            "model_id": model_id,
            "start_date": start_date,
            "end_date": end_date,
            "total_requests": total_requests,
            "total_errors": total_errors,
            "avg_latency": total_latency / total_requests if total_requests > 0 else 0.0,
            "max_latency": max(all_max_latencies) if all_max_latencies else 0.0,
            "min_latency": min(all_min_latencies) if all_min_latencies else 0.0,
            "throughput": total_requests / max(1, len(stats_list))
        }

    def get_recent_inferences(self, model_id: str, limit: int = 100) -> List[InferenceRequest]:
        self.flush()

        all_inferences = metadata_store.list_by_field(self.inference_collection, "model_id", model_id)
        inferences = [InferenceRequest.from_dict(i) for i in all_inferences]
        sorted_inferences = sorted(inferences, key=lambda i: i.request_time, reverse=True)
        return sorted_inferences[:limit]

    def get_inference(self, request_id: str) -> Optional[InferenceRequest]:
        data = metadata_store.load(self.inference_collection, request_id)
        if data:
            return InferenceRequest.from_dict(data)
        return None

    def get_error_rate(self, model_id: str, stat_date: Optional[str] = None) -> float:
        stats = self.get_stats(model_id, stat_date)
        if not stats or stats.request_count == 0:
            return 0.0
        return stats.error_count / stats.request_count

    def flush(self, model_id: Optional[str] = None):
        with self._worker_lock:
            if self._worker and self._worker.is_alive():
                self._worker.flush_all()

        with self._lock:
            if model_id:
                for stat_id, stats in list(self._realtime_stats.items()):
                    if stats.model_id == model_id:
                        metadata_store.save(self.stats_collection, stat_id, stats.to_dict())
                        del self._realtime_stats[stat_id]
            else:
                for stat_id, stats in self._realtime_stats.items():
                    metadata_store.save(self.stats_collection, stat_id, stats.to_dict())
                self._realtime_stats.clear()

    def get_all_models_stats(self, stat_date: Optional[str] = None) -> Dict[str, PerformanceStats]:
        if stat_date is None:
            stat_date = self._get_today_date()

        self.flush()

        all_stats = metadata_store.list_all(self.stats_collection)
        result = {}

        for stat_data in all_stats:
            stat = PerformanceStats.from_dict(stat_data)
            if stat.stat_date == stat_date:
                result[stat.model_id] = stat

        return result

    def shutdown(self, timeout: float = 5.0):
        with self._worker_lock:
            if self._worker and self._worker.is_alive():
                self._worker.flush_all()
                self._worker.stop()
                self._worker.join(timeout=timeout)
                self._worker = None
                self._started = False
                print("Async monitoring worker stopped")

        self.flush()

    def get_queue_status(self) -> Dict:
        return {
            "queue_size": self._buffer_queue.qsize(),
            "max_size": self._buffer_queue.maxsize(),
            "worker_running": self._worker.is_alive() if self._worker else False,
            "realtime_stats_count": len(self._realtime_stats),
            "use_redis": self._use_redis,
            "redis_connected": self._buffer_queue.is_redis_connected()
        }

    def check_redis_connection(self) -> bool:
        return self._buffer_queue.is_redis_connected()


monitoring_config = Config.MONITORING_CONFIG if hasattr(Config, 'MONITORING_CONFIG') else {}
redis_config = Config.REDIS_CONFIG if hasattr(Config, 'REDIS_CONFIG') else {}

monitoring_manager = MonitoringManager(
    flush_interval=monitoring_config.get('flush_interval_ms', 1000) / 1000.0,
    batch_size=monitoring_config.get('batch_size', 100),
    max_queue_size=monitoring_config.get('max_queue_size', 10000),
    use_redis=monitoring_config.get('use_redis', False),
    redis_config=redis_config
)
