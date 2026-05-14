import json
import time
import threading
from typing import List, Dict, Any, Optional, Callable
from abc import ABC, abstractmethod
from dataclasses import dataclass, asdict
from qabot.config import settings


@dataclass
class RecommendTask:
    task_id: str
    qa_id: str
    matched_knowledge_id: Optional[str] = None
    status: str = "pending"
    retries: int = 0
    max_retries: int = 3
    created_at: float = None
    started_at: Optional[float] = None
    completed_at: Optional[float] = None
    result: Optional[Dict] = None
    error: Optional[str] = None
    
    def __post_init__(self):
        if self.created_at is None:
            self.created_at = time.time()
    
    def to_dict(self) -> Dict[str, Any]:
        return asdict(self)
    
    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> 'RecommendTask':
        return cls(**data)


class TaskQueue(ABC):
    @abstractmethod
    def enqueue(self, task: RecommendTask) -> bool:
        pass
    
    @abstractmethod
    def dequeue(self) -> Optional[RecommendTask]:
        pass
    
    @abstractmethod
    def mark_in_progress(self, task: RecommendTask) -> bool:
        pass
    
    @abstractmethod
    def mark_completed(self, task: RecommendTask, result: Dict) -> bool:
        pass
    
    @abstractmethod
    def mark_failed(self, task: RecommendTask, error: str) -> bool:
        pass
    
    @abstractmethod
    def retry_task(self, task: RecommendTask) -> bool:
        pass
    
    @abstractmethod
    def get_result(self, qa_id: str) -> Optional[Dict]:
        pass
    
    @abstractmethod
    def queue_size(self) -> int:
        pass


class InMemoryTaskQueue(TaskQueue):
    def __init__(self):
        self.pending_queue: List[RecommendTask] = []
        self.in_progress: Dict[str, RecommendTask] = {}
        self.completed: Dict[str, RecommendTask] = {}
        self.failed: Dict[str, RecommendTask] = {}
        self.results: Dict[str, Dict] = {}
        self._lock = threading.Lock()
    
    def enqueue(self, task: RecommendTask) -> bool:
        with self._lock:
            self.pending_queue.append(task)
            return True
    
    def dequeue(self) -> Optional[RecommendTask]:
        with self._lock:
            if self.pending_queue:
                task = self.pending_queue.pop(0)
                task.status = "in_progress"
                task.started_at = time.time()
                self.in_progress[task.task_id] = task
                return task
        return None
    
    def mark_in_progress(self, task: RecommendTask) -> bool:
        with self._lock:
            if task.task_id in self.in_progress:
                return True
        return False
    
    def mark_completed(self, task: RecommendTask, result: Dict) -> bool:
        with self._lock:
            if task.task_id in self.in_progress:
                del self.in_progress[task.task_id]
            task.status = "completed"
            task.completed_at = time.time()
            task.result = result
            self.completed[task.task_id] = task
            self.results[task.qa_id] = result
            return True
    
    def mark_failed(self, task: RecommendTask, error: str) -> bool:
        with self._lock:
            if task.task_id in self.in_progress:
                del self.in_progress[task.task_id]
            task.status = "failed"
            task.error = error
            self.failed[task.task_id] = task
            return True
    
    def retry_task(self, task: RecommendTask) -> bool:
        with self._lock:
            if task.retries < task.max_retries:
                task.retries += 1
                task.status = "pending"
                task.error = None
                if task.task_id in self.failed:
                    del self.failed[task.task_id]
                self.pending_queue.append(task)
                return True
        return False
    
    def get_result(self, qa_id: str) -> Optional[Dict]:
        with self._lock:
            return self.results.get(qa_id)
    
    def queue_size(self) -> int:
        with self._lock:
            return len(self.pending_queue)
    
    def get_stats(self) -> Dict[str, int]:
        with self._lock:
            return {
                "pending": len(self.pending_queue),
                "in_progress": len(self.in_progress),
                "completed": len(self.completed),
                "failed": len(self.failed)
            }


class RedisTaskQueue(TaskQueue):
    def __init__(self, redis_client=None):
        self.redis = redis_client
        self.queue_key = settings.redis.RECOMMEND_QUEUE_KEY
        self.result_prefix = settings.redis.RECOMMEND_RESULT_KEY_PREFIX
        self._has_redis = False
        
        if self.redis is None:
            try:
                import redis
                self.redis = redis.Redis(
                    host=settings.redis.HOST,
                    port=settings.redis.PORT,
                    db=settings.redis.DB,
                    password=settings.redis.PASSWORD if settings.redis.PASSWORD else None,
                    decode_responses=True
                )
                self.redis.ping()
                self._has_redis = True
            except Exception:
                self._has_redis = False
                self._fallback = InMemoryTaskQueue()
    
    def _use_fallback(self) -> bool:
        return not self._has_redis or settings.redis.ENABLE_PERSISTENCE is False
    
    def enqueue(self, task: RecommendTask) -> bool:
        if self._use_fallback():
            return self._fallback.enqueue(task)
        
        try:
            task_data = json.dumps(task.to_dict())
            self.redis.rpush(self.queue_key, task_data)
            return True
        except Exception:
            return self._fallback.enqueue(task)
    
    def dequeue(self) -> Optional[RecommendTask]:
        if self._use_fallback():
            return self._fallback.dequeue()
        
        try:
            result = self.redis.blpop(self.queue_key, timeout=1)
            if result:
                _, task_data = result
                task_dict = json.loads(task_data)
                task = RecommendTask.from_dict(task_dict)
                task.status = "in_progress"
                task.started_at = time.time()
                return task
        except Exception:
            pass
        return self._fallback.dequeue()
    
    def mark_in_progress(self, task: RecommendTask) -> bool:
        if self._use_fallback():
            return self._fallback.mark_in_progress(task)
        return True
    
    def mark_completed(self, task: RecommendTask, result: Dict) -> bool:
        if self._use_fallback():
            return self._fallback.mark_completed(task, result)
        
        try:
            task.status = "completed"
            task.completed_at = time.time()
            task.result = result
            
            result_key = f"{self.result_prefix}{task.qa_id}"
            self.redis.setex(
                result_key,
                3600,
                json.dumps(result)
            )
            return True
        except Exception:
            return self._fallback.mark_completed(task, result)
    
    def mark_failed(self, task: RecommendTask, error: str) -> bool:
        if self._use_fallback():
            return self._fallback.mark_failed(task, error)
        
        try:
            task.status = "failed"
            task.error = error
            return True
        except Exception:
            return self._fallback.mark_failed(task, error)
    
    def retry_task(self, task: RecommendTask) -> bool:
        if self._use_fallback():
            return self._fallback.retry_task(task)
        
        try:
            if task.retries < task.max_retries:
                task.retries += 1
                task.status = "pending"
                task.error = None
                task_data = json.dumps(task.to_dict())
                self.redis.rpush(self.queue_key, task_data)
                return True
        except Exception:
            pass
        return self._fallback.retry_task(task)
    
    def get_result(self, qa_id: str) -> Optional[Dict]:
        if self._use_fallback():
            return self._fallback.get_result(qa_id)
        
        try:
            result_key = f"{self.result_prefix}{qa_id}"
            result_data = self.redis.get(result_key)
            if result_data:
                return json.loads(result_data)
        except Exception:
            pass
        return self._fallback.get_result(qa_id)
    
    def queue_size(self) -> int:
        if self._use_fallback():
            return self._fallback.queue_size()
        
        try:
            return self.redis.llen(self.queue_key)
        except Exception:
            return self._fallback.queue_size()


class RecommendationWorker:
    def __init__(
        self,
        task_queue: TaskQueue,
        recommend_func: Callable[[Optional[str], Optional[str]], List[Dict]]
    ):
        self.task_queue = task_queue
        self.recommend_func = recommend_func
        self._running = False
        self._thread: Optional[threading.Thread] = None
    
    def start(self):
        if self._running:
            return
        
        self._running = True
        self._thread = threading.Thread(target=self._worker_loop, daemon=True)
        self._thread.start()
    
    def stop(self):
        self._running = False
        if self._thread:
            self._thread.join(timeout=5)
    
    def _worker_loop(self):
        while self._running:
            try:
                task = self.task_queue.dequeue()
                if task:
                    self._process_task(task)
                else:
                    time.sleep(0.1)
            except Exception:
                time.sleep(0.1)
    
    def _process_task(self, task: RecommendTask):
        try:
            recommendations = self.recommend_func(
                task.matched_knowledge_id,
                task.qa_id
            )
            
            result = {
                "qa_id": task.qa_id,
                "recommendations": [r if isinstance(r, dict) else r.model_dump() for r in recommendations],
                "generated_at": time.time()
            }
            
            self.task_queue.mark_completed(task, result)
            
        except Exception as e:
            error_msg = str(e)
            if task.retries < task.max_retries:
                time.sleep(settings.redis.RETRY_DELAY_SECONDS)
                self.task_queue.retry_task(task)
            else:
                self.task_queue.mark_failed(task, error_msg)


class RecommendationQueueManager:
    _instance = None
    
    def __new__(cls):
        if cls._instance is None:
            cls._instance = super().__new__(cls)
            cls._instance._initialized = False
        return cls._instance
    
    def __init__(self):
        if self._initialized:
            return
        self._initialized = True
        
        if settings.redis.ENABLE_PERSISTENCE:
            self.task_queue: TaskQueue = RedisTaskQueue()
        else:
            self.task_queue: TaskQueue = InMemoryTaskQueue()
        
        self.worker: Optional[RecommendationWorker] = None
        self._recommend_func = None
    
    def set_recommend_func(self, func):
        self._recommend_func = func
    
    def start_worker(self):
        if self._recommend_func is None:
            return
        
        if self.worker is None:
            self.worker = RecommendationWorker(self.task_queue, self._recommend_func)
        
        self.worker.start()
    
    def stop_worker(self):
        if self.worker:
            self.worker.stop()
    
    def submit_task(
        self,
        qa_id: str,
        matched_knowledge_id: Optional[str] = None
    ) -> str:
        import uuid
        task_id = f"task_{uuid.uuid4().hex[:12]}"
        task = RecommendTask(
            task_id=task_id,
            qa_id=qa_id,
            matched_knowledge_id=matched_knowledge_id,
            max_retries=settings.redis.MAX_RETRIES
        )
        self.task_queue.enqueue(task)
        return task_id
    
    def get_result(self, qa_id: str) -> Optional[Dict]:
        return self.task_queue.get_result(qa_id)
    
    def get_queue_stats(self) -> Dict[str, int]:
        if hasattr(self.task_queue, 'get_stats'):
            return self.task_queue.get_stats()
        return {"queue_size": self.task_queue.queue_size()}


queue_manager = RecommendationQueueManager()

__all__ = [
    "RecommendTask",
    "TaskQueue",
    "InMemoryTaskQueue",
    "RedisTaskQueue",
    "RecommendationWorker",
    "RecommendationQueueManager",
    "queue_manager"
]
