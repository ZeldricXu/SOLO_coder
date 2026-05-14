import json
import time
from typing import Optional, Dict, Any, List
from pathlib import Path

from .config import settings
from .logger import logger


try:
    import redis

    REDIS_AVAILABLE = True
except ImportError:
    REDIS_AVAILABLE = False


class RedisQueueManager:
    _instance = None

    def __new__(cls):
        if cls._instance is None:
            cls._instance = super().__new__(cls)
            cls._instance._setup()
        return cls._instance

    def _setup(self):
        self.enabled = settings.enable_redis_queue and REDIS_AVAILABLE
        self.redis_client = None
        self.convert_queue_key = settings.redis_convert_queue_key
        self.upload_queue_key = settings.redis_upload_queue_key
        self.task_prefix = settings.redis_task_prefix
        self._lock = None

        if self.enabled:
            self._connect()

    def _connect(self):
        try:
            if settings.redis_password:
                self.redis_client = redis.Redis(
                    host=settings.redis_host,
                    port=settings.redis_port,
                    db=settings.redis_db,
                    password=settings.redis_password,
                    ssl=settings.redis_use_ssl,
                    decode_responses=True,
                    socket_timeout=5,
                    socket_connect_timeout=5,
                )
            else:
                self.redis_client = redis.Redis(
                    host=settings.redis_host,
                    port=settings.redis_port,
                    db=settings.redis_db,
                    ssl=settings.redis_use_ssl,
                    decode_responses=True,
                    socket_timeout=5,
                    socket_connect_timeout=5,
                )

            self.redis_client.ping()
            logger.info(f"Redis queue connected: {settings.redis_host}:{settings.redis_port}")
        except Exception as e:
            logger.error(f"Redis connection failed, falling back to memory queue: {e}")
            self.enabled = False
            self.redis_client = None

    def is_available(self) -> bool:
        if not self.enabled or not self.redis_client:
            return False
        try:
            self.redis_client.ping()
            return True
        except Exception as e:
            logger.warning(f"Redis health check failed: {e}")
            return False

    def _get_task_key(self, task_id: str) -> str:
        return f"{self.task_prefix}{task_id}"

    def add_convert_task(
        self,
        task_id: str,
        source_file_id: str,
        source_format: str,
        target_format: str,
        conversion_params: Dict[str, Any] = None,
        priority: int = 5,
        extra_args: Dict[str, Any] = None,
    ) -> bool:
        if not self.is_available():
            return False

        task_data = {
            "task_id": task_id,
            "task_type": "convert",
            "source_file_id": source_file_id,
            "source_format": source_format,
            "target_format": target_format,
            "conversion_params": conversion_params or {},
            "priority": priority,
            "created_at": time.time(),
            "extra_args": extra_args or {},
        }

        try:
            task_key = self._get_task_key(task_id)
            self.redis_client.set(task_key, json.dumps(task_data))
            self.redis_client.zadd(self.convert_queue_key, {task_id: priority})
            logger.info(f"Convert task added to Redis queue: {task_id}")
            return True
        except Exception as e:
            logger.error(f"Failed to add convert task to Redis: {e}")
            return False

    def get_convert_task(self, timeout: int = 5) -> Optional[Dict[str, Any]]:
        if not self.is_available():
            return None

        try:
            result = self.redis_client.zpopmin(self.convert_queue_key, count=1)
            if not result:
                return None

            task_id = result[0][0]
            task_key = self._get_task_key(task_id)
            task_json = self.redis_client.get(task_key)

            if task_json:
                return json.loads(task_json)
            return None
        except Exception as e:
            logger.error(f"Failed to get convert task from Redis: {e}")
            return None

    def add_upload_task(
        self,
        session_id: str,
        file_name: str,
        total_size: int,
        total_chunks: int,
        user_id: str = "anonymous",
        priority: int = 5,
    ) -> bool:
        if not self.is_available():
            return False

        task_data = {
            "task_id": session_id,
            "task_type": "upload",
            "session_id": session_id,
            "file_name": file_name,
            "total_size": total_size,
            "total_chunks": total_chunks,
            "user_id": user_id,
            "priority": priority,
            "created_at": time.time(),
        }

        try:
            task_key = self._get_task_key(session_id)
            self.redis_client.set(task_key, json.dumps(task_data))
            self.redis_client.zadd(self.upload_queue_key, {session_id: priority})
            logger.info(f"Upload task added to Redis queue: {session_id}")
            return True
        except Exception as e:
            logger.error(f"Failed to add upload task to Redis: {e}")
            return False

    def get_upload_task(self, timeout: int = 5) -> Optional[Dict[str, Any]]:
        if not self.is_available():
            return None

        try:
            result = self.redis_client.zpopmin(self.upload_queue_key, count=1)
            if not result:
                return None

            task_id = result[0][0]
            task_key = self._get_task_key(task_id)
            task_json = self.redis_client.get(task_key)

            if task_json:
                return json.loads(task_json)
            return None
        except Exception as e:
            logger.error(f"Failed to get upload task from Redis: {e}")
            return None

    def update_task_status(
        self,
        task_id: str,
        status: str,
        error_message: Optional[str] = None,
    ) -> bool:
        if not self.is_available():
            return False

        try:
            task_key = self._get_task_key(task_id)
            task_json = self.redis_client.get(task_key)
            if task_json:
                task_data = json.loads(task_json)
                task_data["status"] = status
                task_data["updated_at"] = time.time()
                if error_message:
                    task_data["error_message"] = error_message
                self.redis_client.set(task_key, json.dumps(task_data))
            return True
        except Exception as e:
            logger.error(f"Failed to update task status: {e}")
            return False

    def get_task_status(self, task_id: str) -> Optional[Dict[str, Any]]:
        if not self.is_available():
            return None

        try:
            task_key = self._get_task_key(task_id)
            task_json = self.redis_client.get(task_key)
            if task_json:
                return json.loads(task_json)
            return None
        except Exception as e:
            logger.error(f"Failed to get task status: {e}")
            return None

    def mark_task_completed(self, task_id: str, result_data: Dict[str, Any] = None) -> bool:
        if not self.is_available():
            return False

        try:
            task_key = self._get_task_key(task_id)
            task_json = self.redis_client.get(task_key)
            if task_json:
                task_data = json.loads(task_json)
                task_data["status"] = "completed"
                task_data["completed_at"] = time.time()
                if result_data:
                    task_data["result"] = result_data
                self.redis_client.set(task_key, json.dumps(task_data))
                self.redis_client.expire(task_key, 86400)
            return True
        except Exception as e:
            logger.error(f"Failed to mark task completed: {e}")
            return False

    def mark_task_failed(self, task_id: str, error_message: str) -> bool:
        if not self.is_available():
            return False

        try:
            task_key = self._get_task_key(task_id)
            task_json = self.redis_client.get(task_key)
            if task_json:
                task_data = json.loads(task_json)
                task_data["status"] = "failed"
                task_data["failed_at"] = time.time()
                task_data["error_message"] = error_message
                self.redis_client.set(task_key, json.dumps(task_data))
                self.redis_client.expire(task_key, 86400)
            return True
        except Exception as e:
            logger.error(f"Failed to mark task failed: {e}")
            return False

    def cleanup_old_tasks(self, max_age_seconds: int = 86400) -> int:
        if not self.is_available():
            return 0

        try:
            cleaned = 0
            cursor = 0
            pattern = f"{self.task_prefix}*"

            while True:
                cursor, keys = self.redis_client.scan(cursor, match=pattern)
                for key in keys:
                    task_json = self.redis_client.get(key)
                    if task_json:
                        task_data = json.loads(task_json)
                        created_at = task_data.get("created_at", 0)
                        if time.time() - created_at > max_age_seconds:
                            self.redis_client.delete(key)
                            cleaned += 1

                if cursor == 0:
                    break

            if cleaned > 0:
                logger.info(f"Cleaned up {cleaned} old tasks from Redis")
            return cleaned
        except Exception as e:
            logger.error(f"Failed to cleanup old tasks: {e}")
            return 0

    def get_queue_size(self, queue_key: str = None) -> int:
        if not self.is_available():
            return 0

        try:
            if queue_key:
                return self.redis_client.zcard(queue_key)
            return (
                self.redis_client.zcard(self.convert_queue_key)
                + self.redis_client.zcard(self.upload_queue_key)
            )
        except Exception as e:
            logger.error(f"Failed to get queue size: {e}")
            return 0

    def clear_queue(self, queue_key: str) -> bool:
        if not self.is_available():
            return False

        try:
            self.redis_client.delete(queue_key)
            return True
        except Exception as e:
            logger.error(f"Failed to clear queue: {e}")
            return False

    def clear_all_queues(self) -> bool:
        if not self.is_available():
            return False

        try:
            self.redis_client.delete(self.convert_queue_key)
            self.redis_client.delete(self.upload_queue_key)
            return True
        except Exception as e:
            logger.error(f"Failed to clear all queues: {e}")
            return False

    def get_pending_tasks(self, queue_key: str, limit: int = 100) -> List[Dict[str, Any]]:
        if not self.is_available():
            return []

        try:
            tasks = []
            task_ids = self.redis_client.zrange(queue_key, 0, limit - 1)
            for task_id in task_ids:
                task_key = self._get_task_key(task_id)
                task_json = self.redis_client.get(task_key)
                if task_json:
                    tasks.append(json.loads(task_json))
            return tasks
        except Exception as e:
            logger.error(f"Failed to get pending tasks: {e}")
            return []

    def restore_pending_tasks_on_startup(self) -> int:
        if not self.is_available():
            return 0

        try:
            count = 0
            for queue_key in [self.convert_queue_key, self.upload_queue_key]:
                pending = self.redis_client.zcard(queue_key)
                count += pending
                if pending > 0:
                    logger.info(f"Found {pending} pending tasks in {queue_key}")
            return count
        except Exception as e:
            logger.error(f"Failed to restore pending tasks: {e}")
            return 0


redis_queue = RedisQueueManager()
