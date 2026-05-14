import time
import threading
import json
from typing import Optional, Dict, Any, List, Callable
from queue import PriorityQueue, Empty
from .config import settings
from .metadata import metadata
from .logger import logger
from .converter import converter
from .models import TaskStatus, now_iso
from .redis_queue import redis_queue


class TaskQueueManager:
    _instance = None

    def __new__(cls):
        if cls._instance is None:
            cls._instance = super().__new__(cls)
            cls._instance._setup()
        return cls._instance

    def _setup(self):
        self.memory_queue = PriorityQueue()
        self.max_retry = settings.task_max_retry
        self.timeout = settings.task_timeout
        self.workers: List[threading.Thread] = []
        self.is_running = False
        self.lock = threading.Lock()
        self.redis_available = redis_queue.is_available()
        self.use_redis = settings.enable_redis_queue and self.redis_available

    def _check_redis_health(self) -> bool:
        if not settings.enable_redis_queue:
            return False
        self.redis_available = redis_queue.is_available()
        self.use_redis = self.redis_available
        return self.redis_available

    def add_task(
        self,
        task_type: str,
        task_id: str,
        priority: int = 5,
        extra_args: Dict[str, Any] = None,
    ) -> bool:
        task_data = {
            "task_type": task_type,
            "task_id": task_id,
            "priority": priority,
            "extra_args": extra_args or {},
            "created_at": time.time(),
        }

        if self._check_redis_health() and task_type == "convert":
            file_id = extra_args.get("file_id") if extra_args else None
            source_format = extra_args.get("source_format") if extra_args else "image"
            target_format = extra_args.get("target_format") if extra_args else "jpg"
            conversion_params = extra_args.get("conversion_params") if extra_args else None

            success = redis_queue.add_convert_task(
                task_id=task_id,
                source_file_id=file_id or "",
                source_format=source_format,
                target_format=target_format,
                conversion_params=conversion_params or {},
                priority=priority,
                extra_args=extra_args or {},
            )
            if success:
                logger.info(
                    f"Task added to Redis queue: {task_id} (type: {task_type}, priority: {priority})",
                    task_id=task_id,
                    task_type=task_type,
                )
                return True

        self.memory_queue.put((priority, task_data))
        logger.info(
            f"Task added to memory queue: {task_id} (type: {task_type}, priority: {priority})",
            task_id=task_id,
            task_type=task_type,
        )
        return True

    def _get_task_from_redis(self) -> Optional[Dict[str, Any]]:
        if not self.use_redis or not self._check_redis_health():
            return None
        return redis_queue.get_convert_task(timeout=2)

    def _get_task_from_memory(self, timeout: float = 5.0) -> Optional[Dict[str, Any]]:
        try:
            priority, task_data = self.memory_queue.get(timeout=timeout)
            return task_data
        except Empty:
            return None

    def _process_task(self, task_data: Dict[str, Any]) -> bool:
        task_type = task_data["task_type"]
        task_id = task_data["task_id"]
        extra_args = task_data.get("extra_args", {})

        logger.info(
            f"Processing task: {task_id} (type: {task_type})",
            task_id=task_id,
            task_type=task_type,
        )

        try:
            if task_type == "convert":
                success, result, message = converter.execute_convert(task_id)
                if self.use_redis and success:
                    redis_queue.mark_task_completed(task_id, result.model_dump() if result else None)
                elif self.use_redis:
                    redis_queue.mark_task_failed(task_id, message)
                return success
            elif task_type == "parse":
                from .parser import parser

                file_id = extra_args.get("file_id")
                parse_type = extra_args.get("parse_type", "text_extract")
                params = extra_args.get("params", {})

                if file_id:
                    success, result, message = parser.parse(file_id, parse_type, params)
                    return success
                return False
            elif task_type == "compress":
                from .compressor import compressor

                file_ids = extra_args.get("file_ids", [])
                compress_format = extra_args.get("compress_format", "zip")
                params = extra_args.get("params", {})
                user_id = extra_args.get("user_id", "anonymous")

                success, task, message = compressor.compress(file_ids, compress_format, params, user_id)
                return success
            elif task_type == "extract":
                from .compressor import compressor

                file_id = extra_args.get("file_id")
                params = extra_args.get("params", {})
                user_id = extra_args.get("user_id", "anonymous")

                if file_id:
                    success, file_ids, message = compressor.extract(file_id, params, user_id)
                    return success
                return False
            else:
                logger.warning(
                    f"Unknown task type: {task_type}",
                    task_id=task_id,
                )
                return False

        except Exception as e:
            logger.error(
                f"Task processing failed: {task_id} - {str(e)}",
                task_id=task_id,
                task_type=task_type,
            )
            if self.use_redis:
                redis_queue.mark_task_failed(task_id, str(e))
            return False

    def _worker_loop(self, worker_id: int):
        logger.info(f"Task worker {worker_id} started")

        while self.is_running:
            try:
                task_data = self._get_task_from_redis()

                if not task_data:
                    task_data = self._get_task_from_memory(timeout=2.0)

                if not task_data:
                    continue

                try:
                    success = self._process_task(task_data)
                    if success:
                        logger.info(
                            f"Task completed successfully: {task_data['task_id']}",
                            task_id=task_data["task_id"],
                            task_type=task_data.get("task_type"),
                        )
                    else:
                        logger.warning(
                            f"Task failed: {task_data['task_id']}",
                            task_id=task_data["task_id"],
                            task_type=task_data.get("task_type"),
                        )

                except Exception as e:
                    logger.error(
                        f"Worker error processing task {task_data.get('task_id')}: {str(e)}",
                        task_id=task_data.get("task_id"),
                    )
                finally:
                    if not task_data.get("from_redis", False):
                        self.memory_queue.task_done()

            except Exception as e:
                logger.error(f"Worker {worker_id} error: {str(e)}")
                time.sleep(1)

        logger.info(f"Task worker {worker_id} stopped")

    def start_workers(self, num_workers: int = 2):
        if self.is_running:
            logger.warning("Task workers already running")
            return

        self.is_running = True
        self.workers = []

        pending_count = 0
        if self._check_redis_health():
            pending_count = redis_queue.restore_pending_tasks_on_startup()

        for i in range(num_workers):
            worker = threading.Thread(target=self._worker_loop, args=(i,), daemon=True)
            worker.start()
            self.workers.append(worker)

        logger.info(f"Started {num_workers} task workers. Pending tasks in Redis: {pending_count}")

    def stop_workers(self):
        self.is_running = False
        for worker in self.workers:
            worker.join(timeout=10)
        self.workers = []
        logger.info("Task workers stopped")

    def get_queue_size(self) -> int:
        redis_size = 0
        if self._check_redis_health():
            redis_size = redis_queue.get_queue_size()
        memory_size = self.memory_queue.qsize()
        return redis_size + memory_size

    def clear_queue(self):
        while not self.memory_queue.empty():
            try:
                self.memory_queue.get_nowait()
                self.memory_queue.task_done()
            except Empty:
                break

        if self._check_redis_health():
            redis_queue.clear_all_queues()

        logger.info("Task queues cleared")

    def process_task_sync(self, task_type: str, task_id: str, extra_args: Dict[str, Any] = None):
        task_data = {
            "task_type": task_type,
            "task_id": task_id,
            "priority": 5,
            "extra_args": extra_args or {},
        }
        return self._process_task(task_data)

    def is_redis_available(self) -> bool:
        return self._check_redis_health()

    def get_redis_pending_tasks(self, limit: int = 100) -> List[Dict[str, Any]]:
        if not self._check_redis_health():
            return []
        return redis_queue.get_pending_tasks(
            queue_key=settings.redis_convert_queue_key,
            limit=limit,
        )


task_queue = TaskQueueManager()
