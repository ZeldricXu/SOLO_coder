import json
import time
import threading
import logging
from typing import Optional, Dict, Any, List, Callable
from datetime import datetime
from dataclasses import dataclass, asdict
from enum import Enum


try:
    import redis
    REDIS_AVAILABLE = True
except ImportError:
    REDIS_AVAILABLE = False


logger = logging.getLogger(__name__)


class TaskStatus(str, Enum):
    PENDING = "pending"
    PROCESSING = "processing"
    COMPLETED = "completed"
    FAILED = "failed"
    CANCELLED = "cancelled"
    RETRYING = "retrying"


class TaskType(str, Enum):
    TRANSCODE = "transcode"
    EDIT = "edit"
    WATERMARK = "watermark"
    THUMBNAIL = "thumbnail"
    QUALITY_CHECK = "quality_check"


@dataclass
class Task:
    task_id: str
    task_type: str
    video_id: str
    params: Dict[str, Any]
    status: str = TaskStatus.PENDING
    priority: int = 0
    retries: int = 0
    max_retries: int = 3
    created_at: Optional[str] = None
    started_at: Optional[str] = None
    completed_at: Optional[str] = None
    error: Optional[str] = None
    result: Optional[Dict[str, Any]] = None

    def to_dict(self) -> Dict[str, Any]:
        return asdict(self)

    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> "Task":
        return cls(
            task_id=data["task_id"],
            task_type=data["task_type"],
            video_id=data["video_id"],
            params=data.get("params", {}),
            status=data.get("status", TaskStatus.PENDING),
            priority=data.get("priority", 0),
            retries=data.get("retries", 0),
            max_retries=data.get("max_retries", 3),
            created_at=data.get("created_at"),
            started_at=data.get("started_at"),
            completed_at=data.get("completed_at"),
            error=data.get("error"),
            result=data.get("result"),
        )


class RedisQueueManager:
    def __init__(
        self,
        redis_url: str = "redis://localhost:6379/0",
        queue_prefix: str = "videoprocess",
        task_type: str = "transcode",
    ):
        self.redis_url = redis_url
        self.queue_prefix = queue_prefix
        self.task_type = task_type
        self.redis = None
        self._connected = False

        if REDIS_AVAILABLE:
            self._connect()
        else:
            logger.warning("Redis not available, using in-memory queue")
            self._in_memory_queue: List[Task] = []
            self._in_memory_tasks: Dict[str, Task] = {}

    def _connect(self) -> bool:
        try:
            self.redis = redis.from_url(self.redis_url, decode_responses=True)
            self.redis.ping()
            self._connected = True
            logger.info(f"Connected to Redis: {self.redis_url}")
            return True
        except Exception as e:
            logger.warning(f"Failed to connect to Redis: {e}, using in-memory queue")
            self._connected = False
            self._in_memory_queue: List[Task] = []
            self._in_memory_tasks: Dict[str, Task] = {}
            return False

    def _get_queue_key(self) -> str:
        return f"{self.queue_prefix}:queue:{self.task_type}"

    def _get_task_key(self, task_id: str) -> str:
        return f"{self.queue_prefix}:task:{task_id}"

    def _get_processing_key(self) -> str:
        return f"{self.queue_prefix}:processing:{self.task_type}"

    def _get_retry_key(self) -> str:
        return f"{self.queue_prefix}:retry:{self.task_type}"

    def push(self, task: Task) -> bool:
        if not task.created_at:
            task.created_at = datetime.now().isoformat()

        if self._connected and self.redis:
            try:
                task_json = json.dumps(task.to_dict())
                self.redis.zadd(self._get_queue_key(), {task_json: task.priority})
                self.redis.set(self._get_task_key(task.task_id), task_json)
                logger.info(f"Task pushed to queue: {task.task_id}")
                return True
            except Exception as e:
                logger.error(f"Failed to push task to Redis: {e}")
                return False
        else:
            self._in_memory_queue.append(task)
            self._in_memory_tasks[task.task_id] = task
            logger.info(f"Task pushed to in-memory queue: {task.task_id}")
            return True

    def pop(self, timeout: int = 0) -> Optional[Task]:
        if self._connected and self.redis:
            try:
                result = self.redis.bzpopmin(
                    [self._get_queue_key()],
                    timeout=timeout,
                )
                if result:
                    task_json = result[1]
                    task = Task.from_dict(json.loads(task_json))
                    task.status = TaskStatus.PROCESSING
                    task.started_at = datetime.now().isoformat()
                    self._update_task(task)
                    logger.info(f"Task popped from queue: {task.task_id}")
                    return task
            except Exception as e:
                logger.error(f"Failed to pop task from Redis: {e}")
                return None
        else:
            if self._in_memory_queue:
                self._in_memory_queue.sort(key=lambda t: t.priority, reverse=True)
                task = self._in_memory_queue.pop(0)
                task.status = TaskStatus.PROCESSING
                task.started_at = datetime.now().isoformat()
                return task
            return None

    def _update_task(self, task: Task) -> bool:
        if self._connected and self.redis:
            try:
                self.redis.set(self._get_task_key(task.task_id), json.dumps(task.to_dict()))
                return True
            except Exception as e:
                logger.error(f"Failed to update task in Redis: {e}")
                return False
        else:
            if task.task_id in self._in_memory_tasks:
                self._in_memory_tasks[task.task_id] = task
                return True
            return False

    def complete_task(self, task: Task, result: Optional[Dict[str, Any]] = None) -> bool:
        task.status = TaskStatus.COMPLETED
        task.completed_at = datetime.now().isoformat()
        task.result = result
        success = self._update_task(task)
        logger.info(f"Task completed: {task.task_id}")
        return success

    def fail_task(self, task: Task, error: str) -> bool:
        task.retries += 1

        if task.retries < task.max_retries:
            task.status = TaskStatus.RETRYING
            task.error = error
            logger.warning(f"Task failed, retrying: {task.task_id} (attempt {task.retries}/{task.max_retries})")

            if self._connected and self.redis:
                try:
                    self._update_task(task)
                    self.redis.zadd(
                        self._get_retry_key(),
                        {json.dumps(task.to_dict()): time.time() + 5},
                    )
                    return True
                except Exception:
                    pass
            return self._update_task(task)
        else:
            task.status = TaskStatus.FAILED
            task.error = error
            task.completed_at = datetime.now().isoformat()
            logger.error(f"Task failed permanently: {task.task_id} - {error}")
            return self._update_task(task)

    def get_task(self, task_id: str) -> Optional[Task]:
        if self._connected and self.redis:
            try:
                task_json = self.redis.get(self._get_task_key(task_id))
                if task_json:
                    return Task.from_dict(json.loads(task_json))
            except Exception as e:
                logger.error(f"Failed to get task from Redis: {e}")
        else:
            return self._in_memory_tasks.get(task_id)
        return None

    def get_queue_size(self) -> int:
        if self._connected and self.redis:
            try:
                return self.redis.zcard(self._get_queue_key())
            except Exception:
                return 0
        else:
            return len(self._in_memory_queue)

    def get_active_tasks_count(self) -> int:
        if self._connected and self.redis:
            try:
                return self.redis.zcard(self._get_processing_key())
            except Exception:
                return 0
        else:
            return len([t for t in self._in_memory_tasks.values() if t.status == TaskStatus.PROCESSING])

    def clear_queue(self) -> bool:
        if self._connected and self.redis:
            try:
                self.redis.delete(self._get_queue_key())
                return True
            except Exception:
                return False
        else:
            self._in_memory_queue.clear()
            return True

    def is_connected(self) -> bool:
        return self._connected

    def queue_stats(self) -> Dict[str, Any]:
        stats = {
            "queue_size": self.get_queue_size(),
            "active_tasks": self.get_active_tasks_count(),
            "redis_connected": self._connected,
            "task_type": self.task_type,
        }
        return stats


class TaskWorker:
    def __init__(
        self,
        queue_manager: RedisQueueManager,
        processor: Callable[[Task], Optional[Dict[str, Any]]],
        worker_id: str = "worker_001",
        max_concurrent: int = 1,
    ):
        self.queue_manager = queue_manager
        self.processor = processor
        self.worker_id = worker_id
        self.max_concurrent = max_concurrent
        self.running = False
        self._threads: List[threading.Thread] = []

    def _worker_loop(self):
        logger.info(f"Worker {self.worker_id} started")
        while self.running:
            try:
                task = self.queue_manager.pop(timeout=5)
                if task:
                    logger.info(f"Worker {self.worker_id} processing task: {task.task_id}")
                    try:
                        result = self.processor(task)
                        self.queue_manager.complete_task(task, result)
                    except Exception as e:
                        logger.error(f"Task processing error: {e}")
                        self.queue_manager.fail_task(task, str(e))
            except Exception as e:
                logger.error(f"Worker loop error: {e}")
                time.sleep(1)
        logger.info(f"Worker {self.worker_id} stopped")

    def start(self):
        if self.running:
            return
        self.running = True
        for i in range(self.max_concurrent):
            t = threading.Thread(target=self._worker_loop, daemon=True)
            t.start()
            self._threads.append(t)
        logger.info(f"Started {self.max_concurrent} worker thread(s)")

    def stop(self):
        self.running = False
        for t in self._threads:
            t.join(timeout=10)
        self._threads.clear()
        logger.info(f"All workers stopped")


class TranscodeQueue:
    def __init__(self, redis_url: str = "redis://localhost:6379/0"):
        self.queue_manager = RedisQueueManager(
            redis_url=redis_url,
            queue_prefix="videoprocess",
            task_type="transcode",
        )
        self.worker: Optional[TaskWorker] = None

    def submit_task(
        self,
        video_id: str,
        target_format: str,
        target_codec: Optional[str] = None,
        profile: Optional[str] = None,
        priority: int = 0,
    ) -> Task:
        from videoprocess.models import generate_id

        task_id = generate_id("task")
        task = Task(
            task_id=task_id,
            task_type=TaskType.TRANSCODE,
            video_id=video_id,
            params={
                "target_format": target_format,
                "target_codec": target_codec,
                "profile": profile,
            },
            priority=priority,
        )
        self.queue_manager.push(task)
        return task

    def get_task(self, task_id: str) -> Optional[Task]:
        return self.queue_manager.get_task(task_id)

    def get_queue_size(self) -> int:
        return self.queue_manager.get_queue_size()

    def queue_stats(self) -> Dict[str, Any]:
        return self.queue_manager.queue_stats()

    def start_worker(self, processor: Callable[[Task], Optional[Dict[str, Any]]]):
        if self.worker is None:
            self.worker = TaskWorker(
                queue_manager=self.queue_manager,
                processor=processor,
                worker_id="transcode_worker",
            )
            self.worker.start()

    def stop_worker(self):
        if self.worker:
            self.worker.stop()
            self.worker = None


class EditQueue:
    def __init__(self, redis_url: str = "redis://localhost:6379/0"):
        self.queue_manager = RedisQueueManager(
            redis_url=redis_url,
            queue_prefix="videoprocess",
            task_type="edit",
        )
        self.worker: Optional[TaskWorker] = None

    def submit_task(
        self,
        video_id: str,
        edit_type: str,
        edit_params: Dict[str, Any],
        priority: int = 0,
    ) -> Task:
        from videoprocess.models import generate_id

        task_id = generate_id("task")
        task = Task(
            task_id=task_id,
            task_type=TaskType.EDIT,
            video_id=video_id,
            params={
                "edit_type": edit_type,
                "edit_params": edit_params,
            },
            priority=priority,
        )
        self.queue_manager.push(task)
        return task

    def get_task(self, task_id: str) -> Optional[Task]:
        return self.queue_manager.get_task(task_id)

    def get_queue_size(self) -> int:
        return self.queue_manager.get_queue_size()

    def queue_stats(self) -> Dict[str, Any]:
        return self.queue_manager.queue_stats()

    def start_worker(self, processor: Callable[[Task], Optional[Dict[str, Any]]]):
        if self.worker is None:
            self.worker = TaskWorker(
                queue_manager=self.queue_manager,
                processor=processor,
                worker_id="edit_worker",
            )
            self.worker.start()

    def stop_worker(self):
        if self.worker:
            self.worker.stop()
            self.worker = None


class ReferenceChecker:
    def __init__(self, db_session):
        self.db = db_session

    def check_video_references(self, video_id: str) -> Dict[str, Any]:
        from videoprocess.models import TranscodeRecordORM, EditRecordORM, HistoryRecordORM, ThumbnailORM

        references = {
            "video_id": video_id,
            "has_references": False,
            "references": [],
        }

        transcodes = self.db.query(TranscodeRecordORM).filter(
            TranscodeRecordORM.video_id == video_id
        ).filter(
            TranscodeRecordORM.transcode_status.in_(["pending", "processing"])
        ).all()

        for t in transcodes:
            references["references"].append({
                "type": "transcode",
                "id": t.transcode_id,
                "status": t.transcode_status,
            })

        edits = self.db.query(EditRecordORM).filter(
            EditRecordORM.video_id == video_id
        ).filter(
            EditRecordORM.edit_status.in_(["pending", "processing"])
        ).all()

        for e in edits:
            references["references"].append({
                "type": "edit",
                "id": e.edit_id,
                "status": e.edit_status,
            })

        merge_edits = self.db.query(EditRecordORM).filter(
            EditRecordORM.edit_type == "merge"
        ).filter(
            EditRecordORM.edit_status.in_(["pending", "processing"])
        ).all()

        for e in merge_edits:
            params = e.edit_params or {}
            video_ids = params.get("video_ids", [])
            if video_id in video_ids:
                references["references"].append({
                    "type": "merge_edit",
                    "id": e.edit_id,
                    "status": e.edit_status,
                })

        references["has_references"] = len(references["references"]) > 0
        return references

    def can_delete_video(self, video_id: str) -> tuple[bool, Optional[str]]:
        refs = self.check_video_references(video_id)
        if refs["has_references"]:
            ref_types = [r["type"] for r in refs["references"]]
            return False, f"视频被引用: {', '.join(set(ref_types))}"
        return True, None
