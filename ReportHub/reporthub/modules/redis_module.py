import json
import logging
from typing import Optional, Any, Dict, List
from datetime import datetime, timedelta
from contextlib import contextmanager

try:
    import redis
    from redis import Redis as RedisClient
    from redis.exceptions import RedisError, ConnectionError
except ImportError:
    redis = None
    RedisClient = None
    RedisError = Exception
    ConnectionError = Exception

from reporthub.config.settings import settings, RedisConfig

logger = logging.getLogger(__name__)


class RedisConnectionManager:
    _instance: Optional["RedisConnectionManager"] = None
    _pool: Optional["redis.ConnectionPool"] = None

    def __new__(cls, config: Optional[RedisConfig] = None):
        if cls._instance is None:
            cls._instance = super().__new__(cls)
            cls._instance._initialized = False
        return cls._instance

    def __init__(self, config: Optional[RedisConfig] = None):
        if self._initialized:
            return
        self.config = config or settings.REDIS_CONFIG
        self._initialized = False
        self._pool = None
        self._initialized = True

    def get_connection_pool(self) -> "redis.ConnectionPool":
        if self._pool is None and redis is not None:
            self._pool = redis.ConnectionPool(
                host=self.config.host,
                port=self.config.port,
                db=self.config.db,
                password=self.config.password,
                max_connections=self.config.max_connections,
                socket_timeout=self.config.socket_timeout,
                socket_connect_timeout=self.config.socket_connect_timeout,
                retry_on_timeout=self.config.retry_on_timeout,
                decode_responses=False
            )
        return self._pool

    @contextmanager
    def get_client(self):
        if redis is None:
            raise ImportError("Redis library is not installed. Install it with: pip install redis")
        pool = self.get_connection_pool()
        client = redis.Redis(connection_pool=pool)
        try:
            yield client
        except RedisError as e:
            logger.error(f"Redis error: {e}")
            raise
        finally:
            pass

    def is_available(self) -> bool:
        if redis is None:
            return False
        try:
            with self.get_client() as client:
                return client.ping()
        except (RedisError, ImportError):
            return False

    def close(self):
        if self._pool:
            self._pool.disconnect()
            self._pool = None


redis_manager = RedisConnectionManager()


class RedisQueue:
    def __init__(self, queue_key: str, connection_manager: Optional[RedisConnectionManager] = None):
        self.queue_key = queue_key
        self.connection_manager = connection_manager or redis_manager

    def push(self, item: Dict[str, Any], priority: bool = False) -> bool:
        if not self.connection_manager.is_available():
            return False
        try:
            with self.connection_manager.get_client() as client:
                item_json = json.dumps(item, default=str)
                if priority:
                    client.lpush(self.queue_key, item_json)
                else:
                    client.rpush(self.queue_key, item_json)
            return True
        except RedisError as e:
            logger.error(f"Failed to push to queue {self.queue_key}: {e}")
            return False

    def pop(self, timeout: int = 0) -> Optional[Dict[str, Any]]:
        if not self.connection_manager.is_available():
            return None
        try:
            with self.connection_manager.get_client() as client:
                if timeout > 0:
                    result = client.blpop(self.queue_key, timeout=timeout)
                    if result:
                        _, data = result
                        return json.loads(data)
                else:
                    result = client.lpop(self.queue_key)
                    if result:
                        return json.loads(result)
            return None
        except RedisError as e:
            logger.error(f"Failed to pop from queue {self.queue_key}: {e}")
            return None

    def size(self) -> int:
        if not self.connection_manager.is_available():
            return 0
        try:
            with self.connection_manager.get_client() as client:
                return client.llen(self.queue_key)
        except RedisError as e:
            logger.error(f"Failed to get queue size for {self.queue_key}: {e}")
            return 0

    def clear(self) -> bool:
        if not self.connection_manager.is_available():
            return False
        try:
            with self.connection_manager.get_client() as client:
                client.delete(self.queue_key)
            return True
        except RedisError as e:
            logger.error(f"Failed to clear queue {self.queue_key}: {e}")
            return False

    def peek_all(self, limit: int = 100) -> List[Dict[str, Any]]:
        if not self.connection_manager.is_available():
            return []
        try:
            with self.connection_manager.get_client() as client:
                items = client.lrange(self.queue_key, 0, limit - 1)
                return [json.loads(item) for item in items]
        except RedisError as e:
            logger.error(f"Failed to peek queue {self.queue_key}: {e}")
            return []


class RedisTaskStore:
    def __init__(self, connection_manager: Optional[RedisConnectionManager] = None,
                 status_prefix: str = None, lock_prefix: str = None):
        self.connection_manager = connection_manager or redis_manager
        self.status_prefix = status_prefix or settings.TASK_QUEUE_KEYS["TASK_STATUS_PREFIX"]
        self.lock_prefix = lock_prefix or settings.TASK_QUEUE_KEYS["TASK_LOCK_PREFIX"]

    def _task_key(self, task_id: str) -> str:
        return f"{self.status_prefix}{task_id}"

    def _lock_key(self, task_id: str) -> str:
        return f"{self.lock_prefix}{task_id}"

    def save_task(self, task_id: str, task_data: Dict[str, Any], expiry_seconds: int = 86400) -> bool:
        if not self.connection_manager.is_available():
            return False
        try:
            with self.connection_manager.get_client() as client:
                task_json = json.dumps(task_data, default=str)
                client.setex(self._task_key(task_id), expiry_seconds, task_json)
            return True
        except RedisError as e:
            logger.error(f"Failed to save task {task_id}: {e}")
            return False

    def get_task(self, task_id: str) -> Optional[Dict[str, Any]]:
        if not self.connection_manager.is_available():
            return None
        try:
            with self.connection_manager.get_client() as client:
                data = client.get(self._task_key(task_id))
                if data:
                    return json.loads(data)
            return None
        except RedisError as e:
            logger.error(f"Failed to get task {task_id}: {e}")
            return None

    def update_task_status(self, task_id: str, status: str, **updates) -> bool:
        existing = self.get_task(task_id) or {}
        existing.update({
            "task_id": task_id,
            "status": status,
            "updated_at": datetime.utcnow().isoformat()
        })
        existing.update(updates)
        return self.save_task(task_id, existing)

    def delete_task(self, task_id: str) -> bool:
        if not self.connection_manager.is_available():
            return False
        try:
            with self.connection_manager.get_client() as client:
                client.delete(self._task_key(task_id))
            return True
        except RedisError as e:
            logger.error(f"Failed to delete task {task_id}: {e}")
            return False

    def acquire_lock(self, task_id: str, timeout_seconds: int = 300) -> bool:
        if not self.connection_manager.is_available():
            return True
        try:
            with self.connection_manager.get_client() as client:
                return client.set(
                    self._lock_key(task_id),
                    "1",
                    nx=True,
                    ex=timeout_seconds
                )
        except RedisError as e:
            logger.error(f"Failed to acquire lock for task {task_id}: {e}")
            return False

    def release_lock(self, task_id: str) -> bool:
        if not self.connection_manager.is_available():
            return True
        try:
            with self.connection_manager.get_client() as client:
                client.delete(self._lock_key(task_id))
            return True
        except RedisError as e:
            logger.error(f"Failed to release lock for task {task_id}: {e}")
            return False


class RedisGenerationQueue:
    def __init__(self, connection_manager: Optional[RedisConnectionManager] = None):
        queue_key = settings.TASK_QUEUE_KEYS["GENERATION_QUEUE"]
        self.queue = RedisQueue(queue_key, connection_manager)
        self.task_store = RedisTaskStore(connection_manager)
        self.dlq = RedisQueue(settings.TASK_QUEUE_KEYS["DEAD_LETTER_QUEUE"], connection_manager)

    def submit_task(self, template_id: str, report_params: Dict[str, Any] = None,
                    generator: str = None, max_retries: int = 3, priority: bool = False) -> str:
        import uuid
        task_id = f"gen_{uuid.uuid4().hex[:12]}"
        task_data = {
            "task_id": task_id,
            "task_type": "generate_report",
            "template_id": template_id,
            "report_params": report_params or {},
            "generator": generator,
            "max_retries": max_retries,
            "retry_count": 0,
            "status": "pending",
            "created_at": datetime.utcnow().isoformat(),
            "priority": priority
        }
        self.task_store.save_task(task_id, task_data)
        self.queue.push(task_data, priority)
        return task_id

    def get_next_task(self, timeout: int = 5) -> Optional[Dict[str, Any]]:
        item = self.queue.pop(timeout)
        if not item:
            return None
        task_id = item.get("task_id")
        if task_id and self.task_store.acquire_lock(task_id):
            stored_task = self.task_store.get_task(task_id)
            if stored_task:
                stored_task["_queue_item"] = item
                return stored_task
        return None

    def complete_task(self, task_id: str, result: Dict[str, Any]) -> bool:
        success = self.task_store.update_task_status(
            task_id,
            "completed",
            result=result,
            completed_at=datetime.utcnow().isoformat()
        )
        self.task_store.release_lock(task_id)
        return success

    def fail_task(self, task_id: str, error: str, retry: bool = True) -> bool:
        task = self.task_store.get_task(task_id)
        if not task:
            self.task_store.release_lock(task_id)
            return False
        retry_count = task.get("retry_count", 0) + 1
        max_retries = task.get("max_retries", 3)
        if retry and retry_count <= max_retries:
            task["retry_count"] = retry_count
            task["status"] = "retrying"
            task["error"] = error
            task["last_error_at"] = datetime.utcnow().isoformat()
            self.task_store.save_task(task_id, task)
            delay_seconds = min(retry_count * 10, 60)
            from reporthub.config.settings import settings
            from reporthub.models.reports import Report
            temp_report = Report()
            temp_report.report_data = {"rows": range(100)}
            row_count = task.get("report_params", {}).get("row_count", 0)
            if row_count < 100:
                complexity = 1
            elif row_count < 1000:
                complexity = 2
            elif row_count < 10000:
                complexity = 3
            elif row_count < 100000:
                complexity = 4
            else:
                complexity = 5
            retry_config = settings.get_retry_config(complexity)
            base_delay = retry_config.base_delay * (retry_config.backoff_multiplier ** (retry_count - 1))
            task["_requeue_delay"] = base_delay
            self.queue.push(task, priority=True)
        else:
            task["status"] = "failed"
            task["error"] = error
            task["failed_at"] = datetime.utcnow().isoformat()
            task["total_retries"] = retry_count
            self.task_store.save_task(task_id, task)
            self.dlq.push(task)
        self.task_store.release_lock(task_id)
        return True

    def get_task_status(self, task_id: str) -> Optional[Dict[str, Any]]:
        return self.task_store.get_task(task_id)

    def get_queue_size(self) -> int:
        return self.queue.size()

    def recover_pending_tasks(self) -> int:
        tasks = self.queue.peek_all(1000)
        return len(tasks)

    def clear_queue(self) -> bool:
        return self.queue.clear()


class RedisExportQueue:
    def __init__(self, connection_manager: Optional[RedisConnectionManager] = None):
        queue_key = settings.TASK_QUEUE_KEYS["EXPORT_QUEUE"]
        self.queue = RedisQueue(queue_key, connection_manager)
        self.task_store = RedisTaskStore(connection_manager)
        self.dlq = RedisQueue(settings.TASK_QUEUE_KEYS["DEAD_LETTER_QUEUE"], connection_manager)

    def submit_task(self, report_id: str, export_format: str,
                    export_options: Dict[str, Any] = None,
                    max_retries: int = None,
                    report_complexity: int = 1,
                    priority: bool = False) -> str:
        import uuid
        task_id = f"exp_{uuid.uuid4().hex[:12]}"
        from reporthub.config.settings import settings
        retry_config = settings.get_retry_config(report_complexity)
        task_data = {
            "task_id": task_id,
            "task_type": "export_report",
            "report_id": report_id,
            "export_format": export_format,
            "export_options": export_options or {},
            "report_complexity": report_complexity,
            "max_retries": max_retries or retry_config.max_retries,
            "base_retry_delay": retry_config.base_delay,
            "backoff_multiplier": retry_config.backoff_multiplier,
            "retry_count": 0,
            "status": "pending",
            "created_at": datetime.utcnow().isoformat(),
            "priority": priority
        }
        self.task_store.save_task(task_id, task_data)
        self.queue.push(task_data, priority)
        return task_id

    def get_next_task(self, timeout: int = 5) -> Optional[Dict[str, Any]]:
        item = self.queue.pop(timeout)
        if not item:
            return None
        task_id = item.get("task_id")
        if task_id and self.task_store.acquire_lock(task_id):
            stored_task = self.task_store.get_task(task_id)
            if stored_task:
                stored_task["_queue_item"] = item
                return stored_task
        return None

    def complete_task(self, task_id: str, export_file: str) -> bool:
        success = self.task_store.update_task_status(
            task_id,
            "completed",
            export_file=export_file,
            completed_at=datetime.utcnow().isoformat()
        )
        self.task_store.release_lock(task_id)
        return success

    def fail_task(self, task_id: str, error: str, retry: bool = True) -> bool:
        task = self.task_store.get_task(task_id)
        if not task:
            self.task_store.release_lock(task_id)
            return False
        retry_count = task.get("retry_count", 0) + 1
        max_retries = task.get("max_retries", 3)
        if retry and retry_count <= max_retries:
            task["retry_count"] = retry_count
            task["status"] = "retrying"
            task["error"] = error
            task["last_error_at"] = datetime.utcnow().isoformat()
            self.task_store.save_task(task_id, task)
            base_delay = task.get("base_retry_delay", 1.0)
            backoff = task.get("backoff_multiplier", 2.0)
            complexity = task.get("report_complexity", 1)
            delay_seconds = base_delay * (backoff ** (retry_count - 1)) * complexity
            task["_requeue_delay"] = delay_seconds
            self.queue.push(task, priority=True)
        else:
            task["status"] = "failed"
            task["error"] = error
            task["failed_at"] = datetime.utcnow().isoformat()
            task["total_retries"] = retry_count
            self.task_store.save_task(task_id, task)
            self.dlq.push(task)
        self.task_store.release_lock(task_id)
        return True

    def get_task_status(self, task_id: str) -> Optional[Dict[str, Any]]:
        return self.task_store.get_task(task_id)

    def get_queue_size(self) -> int:
        return self.queue.size()

    def clear_queue(self) -> bool:
        return self.queue.clear()


class RedisScheduleQueue:
    def __init__(self, connection_manager: Optional[RedisConnectionManager] = None):
        queue_key = settings.TASK_QUEUE_KEYS["SCHEDULE_QUEUE"]
        self.queue = RedisQueue(queue_key, connection_manager)
        self.task_store = RedisTaskStore(connection_manager)
        self.dlq = RedisQueue(settings.TASK_QUEUE_KEYS["DEAD_LETTER_QUEUE"], connection_manager)

    def submit_task(self, schedule_id: str, template_id: str,
                    export_format: str = "xlsx",
                    notify_users: List[str] = None,
                    priority: bool = False) -> str:
        import uuid
        task_id = f"sched_{uuid.uuid4().hex[:12]}"
        task_data = {
            "task_id": task_id,
            "task_type": "schedule_run",
            "schedule_id": schedule_id,
            "template_id": template_id,
            "export_format": export_format,
            "notify_users": notify_users or [],
            "max_retries": 2,
            "retry_count": 0,
            "status": "pending",
            "created_at": datetime.utcnow().isoformat(),
            "priority": priority
        }
        self.task_store.save_task(task_id, task_data)
        self.queue.push(task_data, priority)
        return task_id

    def get_next_task(self, timeout: int = 5) -> Optional[Dict[str, Any]]:
        item = self.queue.pop(timeout)
        if not item:
            return None
        task_id = item.get("task_id")
        if task_id and self.task_store.acquire_lock(task_id):
            stored_task = self.task_store.get_task(task_id)
            if stored_task:
                stored_task["_queue_item"] = item
                return stored_task
        return None

    def complete_task(self, task_id: str, report_id: str) -> bool:
        success = self.task_store.update_task_status(
            task_id,
            "completed",
            generated_report_id=report_id,
            completed_at=datetime.utcnow().isoformat()
        )
        self.task_store.release_lock(task_id)
        return success

    def fail_task(self, task_id: str, error: str, retry: bool = True) -> bool:
        task = self.task_store.get_task(task_id)
        if not task:
            self.task_store.release_lock(task_id)
            return False
        retry_count = task.get("retry_count", 0) + 1
        max_retries = task.get("max_retries", 2)
        if retry and retry_count <= max_retries:
            task["retry_count"] = retry_count
            task["status"] = "retrying"
            task["error"] = error
            task["last_error_at"] = datetime.utcnow().isoformat()
            self.task_store.save_task(task_id, task)
            delay_seconds = min(retry_count * 30, 300)
            task["_requeue_delay"] = delay_seconds
            self.queue.push(task, priority=True)
        else:
            task["status"] = "failed"
            task["error"] = error
            task["failed_at"] = datetime.utcnow().isoformat()
            self.task_store.save_task(task_id, task)
            self.dlq.push(task)
        self.task_store.release_lock(task_id)
        return True

    def get_task_status(self, task_id: str) -> Optional[Dict[str, Any]]:
        return self.task_store.get_task(task_id)

    def get_queue_size(self) -> int:
        return self.queue.size()

    def clear_queue(self) -> bool:
        return self.queue.clear()


def is_redis_available() -> bool:
    return redis_manager.is_available()
