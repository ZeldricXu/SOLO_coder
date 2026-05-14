import threading
import time
import queue
from pathlib import Path
from typing import Optional, Dict, Any, List
from datetime import datetime

from .config import settings
from .storage import storage
from .metadata import metadata
from .logger import logger
from .models import (
    FileInfo,
    FileStatus,
    UploadSession,
    TaskStatus,
    now_iso,
    generate_id,
)
from .redis_queue import redis_queue


class AsyncUploadManager:
    _instance = None

    def __new__(cls):
        if cls._instance is None:
            cls._instance = super().__new__(cls)
            cls._instance._setup()
        return cls._instance

    def _setup(self):
        self.enabled = settings.enable_async_upload
        self.chunk_size = settings.chunk_size
        self.max_file_size = settings.max_file_size
        self.worker_count = settings.async_upload_worker_count

        self.upload_tasks: Dict[str, Dict[str, Any]] = {}
        self.chunk_data_store: Dict[str, Dict[int, bytes]] = {}
        self._lock = threading.RLock()

        self.workers: List[threading.Thread] = []
        self.is_running = False

        self.redis_available = redis_queue.is_available()

    def start_workers(self):
        if self.is_running:
            logger.warning("Async upload workers already running")
            return

        self.is_running = True
        self.workers = []

        for i in range(self.worker_count):
            worker = threading.Thread(
                target=self._worker_loop,
                args=(i,),
                daemon=True,
            )
            worker.start()
            self.workers.append(worker)

        logger.info(f"Started {self.worker_count} async upload workers")

    def stop_workers(self):
        self.is_running = False
        for worker in self.workers:
            worker.join(timeout=10)
        self.workers = []
        logger.info("Async upload workers stopped")

    def init_async_upload(
        self,
        file_name: str,
        total_size: int,
        user_id: str = "anonymous",
    ) -> Dict[str, Any]:
        if total_size > self.max_file_size:
            return {
                "success": False,
                "message": f"File size exceeds maximum limit of {self.max_file_size} bytes",
            }

        total_chunks = (total_size + self.chunk_size - 1) // self.chunk_size
        session_id = generate_id("upload")

        with self._lock:
            self.upload_tasks[session_id] = {
                "session_id": session_id,
                "file_name": file_name,
                "total_size": total_size,
                "total_chunks": total_chunks,
                "user_id": user_id,
                "status": TaskStatus.PENDING,
                "chunks_received": [],
                "chunks_data": {},
                "created_at": time.time(),
                "file_id": None,
                "error_message": None,
                "progress": 0.0,
            }

        session = UploadSession(
            session_id=session_id,
            file_name=file_name,
            total_size=total_size,
            total_chunks=total_chunks,
            upload_user=user_id,
        )
        metadata.save_upload_session(session)

        if self.redis_available:
            redis_queue.add_upload_task(
                session_id=session_id,
                file_name=file_name,
                total_size=total_size,
                total_chunks=total_chunks,
                user_id=user_id,
                priority=5,
            )

        logger.info(f"Async upload initialized: {session_id} ({file_name}, {total_chunks} chunks)")

        return {
            "success": True,
            "upload_task_id": session_id,
            "session_id": session_id,
            "file_name": file_name,
            "total_chunks": total_chunks,
            "chunk_size": self.chunk_size,
            "total_size": total_size,
            "status": TaskStatus.PENDING,
        }

    def upload_chunk_async(
        self,
        upload_task_id: str,
        chunk_index: int,
        chunk_data: bytes,
    ) -> Dict[str, Any]:
        with self._lock:
            task = self.upload_tasks.get(upload_task_id)
            if not task:
                return {
                    "success": False,
                    "message": f"Upload task not found: {upload_task_id}",
                }

            if task["status"] == TaskStatus.COMPLETED:
                return {
                    "success": False,
                    "message": "Upload already completed",
                }

            if chunk_index < 0 or chunk_index >= task["total_chunks"]:
                return {
                    "success": False,
                    "message": f"Invalid chunk index: {chunk_index}",
                }

            if chunk_index not in task["chunks_received"]:
                task["chunks_data"][chunk_index] = chunk_data
                task["chunks_received"].append(chunk_index)
                task["chunks_received"].sort()

            task["progress"] = len(task["chunks_received"]) / task["total_chunks"] * 100
            is_complete = len(task["chunks_received"]) == task["total_chunks"]

            if is_complete:
                task["status"] = TaskStatus.PROCESSING
                logger.info(
                    f"All chunks received for {upload_task_id}, preparing to merge"
                )

        session = metadata.get_upload_session(upload_task_id)
        if session:
            session.chunks_received = task["chunks_received"]
            metadata.save_upload_session(session)

        if self.redis_available:
            redis_queue.update_task_status(
                upload_task_id,
                task["status"],
            )

        return {
            "success": True,
            "message": f"Chunk {chunk_index} received",
            "progress": task["progress"],
            "chunks_received": len(task["chunks_received"]),
            "total_chunks": task["total_chunks"],
            "is_complete": is_complete,
        }

    def _worker_loop(self, worker_id: int):
        logger.info(f"Async upload worker {worker_id} started")

        while self.is_running:
            try:
                task_to_process = None

                with self._lock:
                    for task_id, task in self.upload_tasks.items():
                        if task["status"] == TaskStatus.PROCESSING:
                            task_to_process = (task_id, task)
                            break

                if task_to_process:
                    task_id, task = task_to_process
                    self._process_upload_task(task_id, task)
                else:
                    if self.redis_available:
                        redis_task = redis_queue.get_upload_task(timeout=2)
                        if redis_task:
                            self._process_redis_upload_task(redis_task)
                        else:
                            time.sleep(0.1)
                    else:
                        time.sleep(0.1)

            except Exception as e:
                logger.error(f"Async upload worker {worker_id} error: {e}")
                time.sleep(1)

        logger.info(f"Async upload worker {worker_id} stopped")

    def _process_upload_task(self, task_id: str, task: Dict[str, Any]):
        logger.info(f"Processing upload task: {task_id}")

        try:
            with self._lock:
                if task["status"] != TaskStatus.PROCESSING:
                    return

            session_dir = settings.chunks_dir / task_id
            session_dir.mkdir(parents=True, exist_ok=True)

            for chunk_index, chunk_data in task["chunks_data"].items():
                chunk_file = session_dir / f"chunk_{chunk_index}"
                with open(chunk_file, "wb") as f:
                    f.write(chunk_data)

            total_size = task["total_size"]
            file_ext = Path(task["file_name"]).suffix.lower().lstrip(".") or "bin"
            file_id = generate_id("file")
            storage_filename = f"{file_id}.{file_ext}"
            storage_path = settings.upload_dir / storage_filename

            total_written = 0
            with open(storage_path, "wb") as out_file:
                for i in range(task["total_chunks"]):
                    chunk_file = session_dir / f"chunk_{i}"
                    if chunk_file.exists():
                        with open(chunk_file, "rb") as cf:
                            data = cf.read()
                            total_written += len(data)
                            out_file.write(data)

            import hashlib
            sha256_hash = hashlib.sha256()
            with open(storage_path, "rb") as f:
                for chunk in iter(lambda: f.read(4096), b""):
                    sha256_hash.update(chunk)

            from .models import expire_at_days
            file_info = FileInfo(
                file_id=file_id,
                file_name=task["file_name"],
                file_type=file_ext,
                file_size=total_written,
                storage_path=str(storage_path),
                upload_user=task["user_id"],
                status=FileStatus.STORED,
                expire_at=expire_at_days(settings.file_expire_days),
                sha256=sha256_hash.hexdigest(),
                chunks=task["total_chunks"],
                chunks_received=task["total_chunks"],
                chunk_session_id=task_id,
            )

            metadata.save_file(file_info)

            with self._lock:
                task["status"] = TaskStatus.COMPLETED
                task["file_id"] = file_id
                task["progress"] = 100.0

            metadata.delete_upload_session(task_id)

            try:
                import shutil
                shutil.rmtree(session_dir)
            except Exception as e:
                logger.warning(f"Failed to clean up chunk directory: {e}")

            if self.redis_available:
                redis_queue.mark_task_completed(
                    task_id,
                    {"file_id": file_id, "file_size": total_written},
                )

            logger.info(
                f"Async upload completed: {task_id} -> {file_id} ({total_written} bytes)",
                file_id=file_id,
            )

        except Exception as e:
            error_msg = f"Async upload failed: {str(e)}"
            logger.error(error_msg)

            with self._lock:
                task["status"] = TaskStatus.FAILED
                task["error_message"] = error_msg

            if self.redis_available:
                redis_queue.mark_task_failed(task_id, error_msg)

    def _process_redis_upload_task(self, redis_task: Dict[str, Any]):
        session_id = redis_task.get("session_id")
        if not session_id:
            return

        logger.info(f"Processing Redis upload task: {session_id}")

        with self._lock:
            if session_id not in self.upload_tasks:
                session = metadata.get_upload_session(session_id)
                if session:
                    self.upload_tasks[session_id] = {
                        "session_id": session_id,
                        "file_name": session.file_name,
                        "total_size": session.total_size,
                        "total_chunks": session.total_chunks,
                        "user_id": session.upload_user,
                        "status": TaskStatus.PENDING,
                        "chunks_received": session.chunks_received,
                        "chunks_data": {},
                        "created_at": time.time(),
                        "file_id": None,
                        "error_message": None,
                        "progress": len(session.chunks_received) / session.total_chunks * 100,
                    }

    def get_upload_status(self, upload_task_id: str) -> Optional[Dict[str, Any]]:
        with self._lock:
            task = self.upload_tasks.get(upload_task_id)
            if task:
                return {
                    "upload_task_id": upload_task_id,
                    "file_name": task["file_name"],
                    "status": task["status"],
                    "progress": task["progress"],
                    "chunks_received": len(task["chunks_received"]),
                    "total_chunks": task["total_chunks"],
                    "file_id": task.get("file_id"),
                    "error_message": task.get("error_message"),
                }

        if self.redis_available:
            redis_status = redis_queue.get_task_status(upload_task_id)
            if redis_status:
                return redis_status

        return None

    def list_async_uploads(self, status: Optional[str] = None) -> List[Dict[str, Any]]:
        with self._lock:
            result = []
            for task_id, task in self.upload_tasks.items():
                if status is None or task["status"] == status:
                    result.append({
                        "upload_task_id": task_id,
                        "file_name": task["file_name"],
                        "status": task["status"],
                        "progress": task["progress"],
                        "total_chunks": task["total_chunks"],
                        "created_at": task["created_at"],
                    })
            return result

    def cancel_async_upload(self, upload_task_id: str) -> bool:
        with self._lock:
            if upload_task_id in self.upload_tasks:
                task = self.upload_tasks[upload_task_id]
                if task["status"] in [TaskStatus.PENDING, TaskStatus.PROCESSING]:
                    task["status"] = "cancelled"
                    logger.info(f"Async upload cancelled: {upload_task_id}")

                    try:
                        session_dir = settings.chunks_dir / upload_task_id
                        if session_dir.exists():
                            import shutil
                            shutil.rmtree(session_dir)
                    except Exception as e:
                        logger.warning(f"Failed to clean up cancelled upload: {e}")

                    return True

        return False

    def cleanup_completed_uploads(self, max_age_seconds: int = 3600) -> int:
        with self._lock:
            current_time = time.time()
            to_remove = []

            for task_id, task in self.upload_tasks.items():
                age = current_time - task["created_at"]
                if task["status"] in [TaskStatus.COMPLETED, TaskStatus.FAILED, "cancelled"]:
                    if age > max_age_seconds:
                        to_remove.append(task_id)

            for task_id in to_remove:
                del self.upload_tasks[task_id]

            if to_remove:
                logger.info(f"Cleaned up {len(to_remove)} completed upload tasks")

            return len(to_remove)


async_upload = AsyncUploadManager()
