import logging
import json
import threading
import time
import uuid
from typing import Dict, Any, List, Optional, Callable
from datetime import datetime
from enum import Enum
from dataclasses import dataclass, field, asdict
from abc import ABC, abstractmethod


class TaskStatus(Enum):
    PENDING = "pending"
    PROCESSING = "processing"
    COMPLETED = "completed"
    FAILED = "failed"
    RETRY = "retry"


@dataclass
class RecommendTask:
    task_id: str
    user_id: Optional[str] = None
    content_id: Optional[str] = None
    recommend_type: str = "related"
    limit: int = 10
    priority: int = 5
    status: TaskStatus = TaskStatus.PENDING
    created_at: datetime = field(default_factory=datetime.utcnow)
    updated_at: datetime = field(default_factory=datetime.utcnow)
    retries: int = 0
    max_retries: int = 3
    error_message: Optional[str] = None
    result: Optional[Dict[str, Any]] = None
    metadata: Dict[str, Any] = field(default_factory=dict)
    
    def to_dict(self) -> Dict[str, Any]:
        data = asdict(self)
        data["status"] = self.status.value
        data["created_at"] = self.created_at.isoformat()
        data["updated_at"] = self.updated_at.isoformat()
        return data
    
    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> 'RecommendTask':
        data = data.copy()
        data["status"] = TaskStatus(data.get("status", "pending"))
        if isinstance(data.get("created_at"), str):
            data["created_at"] = datetime.fromisoformat(data["created_at"])
        if isinstance(data.get("updated_at"), str):
            data["updated_at"] = datetime.fromisoformat(data["updated_at"])
        return cls(**data)


class RecommendTaskQueue(ABC):
    @abstractmethod
    def push(self, task: RecommendTask) -> bool:
        pass
    
    @abstractmethod
    def pop(self) -> Optional[RecommendTask]:
        pass
    
    @abstractmethod
    def peek(self) -> Optional[RecommendTask]:
        pass
    
    @abstractmethod
    def get_status(self, task_id: str) -> Optional[TaskStatus]:
        pass
    
    @abstractmethod
    def update_status(self, task_id: str, status: TaskStatus, result: Dict[str, Any] = None, error: str = None) -> bool:
        pass
    
    @abstractmethod
    def size(self) -> int:
        pass
    
    @abstractmethod
    def clear(self) -> int:
        pass
    
    @abstractmethod
    def get_task(self, task_id: str) -> Optional[RecommendTask]:
        pass


class InMemoryTaskQueue(RecommendTaskQueue):
    def __init__(self):
        self.logger = logging.getLogger(__name__)
        self._lock = threading.RLock()
        self._queue: List[RecommendTask] = []
        self._tasks: Dict[str, RecommendTask] = {}
    
    def push(self, task: RecommendTask) -> bool:
        with self._lock:
            task.status = TaskStatus.PENDING
            task.updated_at = datetime.utcnow()
            self._tasks[task.task_id] = task
            self._queue.append(task)
            self._queue.sort(key=lambda t: (-t.priority, t.created_at))
            self.logger.info(f"Pushed task {task.task_id} to queue")
            return True
    
    def pop(self) -> Optional[RecommendTask]:
        with self._lock:
            if not self._queue:
                return None
            
            pending_tasks = [t for t in self._queue if t.status == TaskStatus.PENDING]
            if not pending_tasks:
                return None
            
            task = pending_tasks[0]
            task.status = TaskStatus.PROCESSING
            task.updated_at = datetime.utcnow()
            
            self._queue.remove(task)
            self.logger.info(f"Popped task {task.task_id} from queue")
            return task
    
    def peek(self) -> Optional[RecommendTask]:
        with self._lock:
            pending_tasks = [t for t in self._queue if t.status == TaskStatus.PENDING]
            return pending_tasks[0] if pending_tasks else None
    
    def get_status(self, task_id: str) -> Optional[TaskStatus]:
        with self._lock:
            task = self._tasks.get(task_id)
            return task.status if task else None
    
    def update_status(self, task_id: str, status: TaskStatus, result: Dict[str, Any] = None, error: str = None) -> bool:
        with self._lock:
            task = self._tasks.get(task_id)
            if not task:
                return False
            
            task.status = status
            task.updated_at = datetime.utcnow()
            if result:
                task.result = result
            if error:
                task.error_message = error
            
            if status == TaskStatus.COMPLETED or status == TaskStatus.FAILED:
                pass
            
            self.logger.info(f"Updated task {task_id} status to {status.value}")
            return True
    
    def size(self) -> int:
        with self._lock:
            return len([t for t in self._queue if t.status == TaskStatus.PENDING])
    
    def clear(self) -> int:
        with self._lock:
            count = len(self._queue)
            self._queue.clear()
            self._tasks.clear()
            return count
    
    def get_task(self, task_id: str) -> Optional[RecommendTask]:
        with self._lock:
            return self._tasks.get(task_id)


class RedisTaskQueue(RecommendTaskQueue):
    def __init__(self, redis_client=None, queue_name: str = "recommend_tasks"):
        self.logger = logging.getLogger(__name__)
        self._redis = redis_client
        self._queue_name = queue_name
        self._task_prefix = "task:"
        self._lock = threading.RLock()
    
    def _get_redis_client(self):
        if self._redis is None:
            try:
                import redis
                from searchengine.config.settings import settings
                self._redis = redis.Redis(
                    host=settings.REDIS_HOST,
                    port=settings.REDIS_PORT,
                    db=settings.REDIS_DB,
                    password=settings.REDIS_PASSWORD,
                    decode_responses=True
                )
            except Exception as e:
                self.logger.warning(f"Redis not available, using in-memory queue: {e}")
                return None
        return self._redis
    
    def push(self, task: RecommendTask) -> bool:
        redis = self._get_redis_client()
        if redis is None:
            return False
        
        try:
            task.status = TaskStatus.PENDING
            task.updated_at = datetime.utcnow()
            
            task_key = f"{self._task_prefix}{task.task_id}"
            redis.set(task_key, json.dumps(task.to_dict()))
            
            redis.zadd(
                self._queue_name,
                {task.task_id: -task.priority}
            )
            
            self.logger.info(f"Pushed task {task.task_id} to Redis queue")
            return True
        except Exception as e:
            self.logger.error(f"Failed to push task to Redis: {e}")
            return False
    
    def pop(self) -> Optional[RecommendTask]:
        redis = self._get_redis_client()
        if redis is None:
            return None
        
        try:
            while True:
                result = redis.zpopmin(self._queue_name)
                if not result:
                    return None
                
                task_id = result[0][0]
                task_key = f"{self._task_prefix}{task_id}"
                
                task_data = redis.get(task_key)
                if not task_data:
                    continue
                
                task = RecommendTask.from_dict(json.loads(task_data))
                task.status = TaskStatus.PROCESSING
                task.updated_at = datetime.utcnow()
                
                redis.set(task_key, json.dumps(task.to_dict()))
                
                self.logger.info(f"Popped task {task_id} from Redis queue")
                return task
                
        except Exception as e:
            self.logger.error(f"Failed to pop task from Redis: {e}")
            return None
    
    def peek(self) -> Optional[RecommendTask]:
        redis = self._get_redis_client()
        if redis is None:
            return None
        
        try:
            results = redis.zrange(self._queue_name, 0, 0)
            if not results:
                return None
            
            task_id = results[0]
            task_key = f"{self._task_prefix}{task_id}"
            task_data = redis.get(task_key)
            
            if task_data:
                return RecommendTask.from_dict(json.loads(task_data))
            return None
        except Exception as e:
            self.logger.error(f"Failed to peek task from Redis: {e}")
            return None
    
    def get_status(self, task_id: str) -> Optional[TaskStatus]:
        redis = self._get_redis_client()
        if redis is None:
            return None
        
        try:
            task_key = f"{self._task_prefix}{task_id}"
            task_data = redis.get(task_key)
            if task_data:
                task = RecommendTask.from_dict(json.loads(task_data))
                return task.status
            return None
        except Exception as e:
            self.logger.error(f"Failed to get task status: {e}")
            return None
    
    def update_status(self, task_id: str, status: TaskStatus, result: Dict[str, Any] = None, error: str = None) -> bool:
        redis = self._get_redis_client()
        if redis is None:
            return False
        
        try:
            task_key = f"{self._task_prefix}{task_id}"
            task_data = redis.get(task_key)
            
            if not task_data:
                return False
            
            task = RecommendTask.from_dict(json.loads(task_data))
            task.status = status
            task.updated_at = datetime.utcnow()
            if result:
                task.result = result
            if error:
                task.error_message = error
            
            redis.set(task_key, json.dumps(task.to_dict()))
            self.logger.info(f"Updated task {task_id} status to {status.value}")
            return True
        except Exception as e:
            self.logger.error(f"Failed to update task status: {e}")
            return False
    
    def size(self) -> int:
        redis = self._get_redis_client()
        if redis is None:
            return 0
        
        try:
            return redis.zcard(self._queue_name)
        except Exception as e:
            self.logger.error(f"Failed to get queue size: {e}")
            return 0
    
    def clear(self) -> int:
        redis = self._get_redis_client()
        if redis is None:
            return 0
        
        try:
            size = redis.zcard(self._queue_name)
            redis.delete(self._queue_name)
            
            keys = redis.keys(f"{self._task_prefix}*")
            if keys:
                redis.delete(*keys)
            
            return size
        except Exception as e:
            self.logger.error(f"Failed to clear queue: {e}")
            return 0
    
    def get_task(self, task_id: str) -> Optional[RecommendTask]:
        redis = self._get_redis_client()
        if redis is None:
            return None
        
        try:
            task_key = f"{self._task_prefix}{task_id}"
            task_data = redis.get(task_key)
            if task_data:
                return RecommendTask.from_dict(json.loads(task_data))
            return None
        except Exception as e:
            self.logger.error(f"Failed to get task: {e}")
            return None


class RecommendWorker:
    def __init__(
        self,
        task_queue: RecommendTaskQueue,
        recommend_module=None,
        cache_module=None
    ):
        self.logger = logging.getLogger(__name__)
        self._queue = task_queue
        self._recommend = recommend_module
        self._cache = cache_module
        self._running = False
        self._thread: Optional[threading.Thread] = None
        self._poll_interval = 1.0
        self._max_workers = 4
        self._executor = None
    
    def start(self):
        if self._running:
            self.logger.warning("Worker already running")
            return
        
        self._running = True
        self._thread = threading.Thread(target=self._worker_loop, daemon=True)
        self._thread.start()
        self.logger.info("Recommend worker started")
    
    def stop(self):
        self._running = False
        if self._thread:
            self._thread.join(timeout=5)
        self.logger.info("Recommend worker stopped")
    
    def is_running(self) -> bool:
        return self._running
    
    def _worker_loop(self):
        while self._running:
            try:
                task = self._queue.pop()
                if task:
                    self._process_task(task)
                else:
                    time.sleep(self._poll_interval)
            except Exception as e:
                self.logger.error(f"Worker loop error: {e}")
                time.sleep(self._poll_interval)
    
    def _process_task(self, task: RecommendTask):
        try:
            self.logger.info(f"Processing task {task.task_id}")
            
            from searchengine.models.base import RecommendRequest
            
            if self._recommend:
                recommend_request = RecommendRequest(
                    user_id=task.user_id,
                    content_id=task.content_id,
                    recommend_type=task.recommend_type,
                    limit=task.limit
                )
                
                result = self._recommend.generate_recommendations(recommend_request)
                result_dict = result.model_dump()
                
                if self._cache:
                    cache_key = f"recommend:{task.recommend_type}:{task.user_id or task.content_id}"
                    self._cache.set(cache_key, result_dict, ttl=300)
                
                self._queue.update_status(
                    task.task_id,
                    TaskStatus.COMPLETED,
                    result=result_dict
                )
                
                self.logger.info(f"Task {task.task_id} completed successfully")
            else:
                self._queue.update_status(
                    task.task_id,
                    TaskStatus.FAILED,
                    error="Recommend module not available"
                )
                
        except Exception as e:
            self.logger.error(f"Task {task.task_id} failed: {e}")
            
            if task.retries < task.max_retries:
                task.retries += 1
                task.status = TaskStatus.RETRY
                self._queue.push(task)
                self.logger.info(f"Task {task.task_id} scheduled for retry {task.retries}/{task.max_retries}")
            else:
                self._queue.update_status(
                    task.task_id,
                    TaskStatus.FAILED,
                    error=str(e)
                )


class RecommendTaskManager:
    def __init__(self, use_redis: bool = False):
        self.logger = logging.getLogger(__name__)
        
        if use_redis:
            try:
                self._queue = RedisTaskQueue()
                self.logger.info("Using Redis task queue")
            except Exception as e:
                self.logger.warning(f"Failed to initialize Redis queue, using in-memory: {e}")
                self._queue = InMemoryTaskQueue()
        else:
            self._queue = InMemoryTaskQueue()
            self.logger.info("Using in-memory task queue")
        
        self._worker: Optional[RecommendWorker] = None
    
    def create_task(
        self,
        user_id: Optional[str] = None,
        content_id: Optional[str] = None,
        recommend_type: str = "related",
        limit: int = 10,
        priority: int = 5
    ) -> str:
        task_id = str(uuid.uuid4())[:12]
        task = RecommendTask(
            task_id=task_id,
            user_id=user_id,
            content_id=content_id,
            recommend_type=recommend_type,
            limit=limit,
            priority=priority
        )
        
        self._queue.push(task)
        return task_id
    
    def get_task_status(self, task_id: str) -> Optional[Dict[str, Any]]:
        task = self._queue.get_task(task_id)
        if task:
            return {
                "task_id": task.task_id,
                "status": task.status.value,
                "created_at": task.created_at.isoformat(),
                "updated_at": task.updated_at.isoformat(),
                "retries": task.retries,
                "result": task.result,
                "error": task.error_message
            }
        return None
    
    def get_queue_size(self) -> int:
        return self._queue.size()
    
    def start_worker(self, recommend_module=None, cache_module=None):
        if self._worker and self._worker.is_running():
            self.logger.warning("Worker already running")
            return
        
        self._worker = RecommendWorker(
            task_queue=self._queue,
            recommend_module=recommend_module,
            cache_module=cache_module
        )
        self._worker.start()
    
    def stop_worker(self):
        if self._worker:
            self._worker.stop()
    
    def clear_queue(self) -> int:
        return self._queue.clear()


task_manager = RecommendTaskManager(use_redis=False)
