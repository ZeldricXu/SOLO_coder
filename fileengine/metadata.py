import json
from pathlib import Path
from typing import Optional, Dict, List
from .config import settings
from .models import (
    FileInfo,
    ConvertTask,
    ParseResult,
    CompressTask,
    UploadSession,
    FileStatus,
    TaskStatus,
)
from .logger import logger


class MetadataManager:
    _instance = None

    def __new__(cls):
        if cls._instance is None:
            cls._instance = super().__new__(cls)
            cls._instance._setup()
        return cls._instance

    def _setup(self):
        self.storage_dir = settings.storage_dir
        self.metadata_file = self.storage_dir / "metadata.json"
        self._load()

    def _load(self):
        if self.metadata_file.exists():
            try:
                with open(self.metadata_file, "r", encoding="utf-8") as f:
                    data = json.load(f)
                    self.files: Dict[str, dict] = data.get("files", {})
                    self.convert_tasks: Dict[str, dict] = data.get("convert_tasks", {})
                    self.parse_results: Dict[str, dict] = data.get("parse_results", {})
                    self.compress_tasks: Dict[str, dict] = data.get("compress_tasks", {})
                    self.upload_sessions: Dict[str, dict] = data.get("upload_sessions", {})
            except Exception as e:
                logger.error(f"Failed to load metadata: {e}")
                self.files = {}
                self.convert_tasks = {}
                self.parse_results = {}
                self.compress_tasks = {}
                self.upload_sessions = {}
        else:
            self.files = {}
            self.convert_tasks = {}
            self.parse_results = {}
            self.compress_tasks = {}
            self.upload_sessions = {}

    def _save(self):
        try:
            data = {
                "files": self.files,
                "convert_tasks": self.convert_tasks,
                "parse_results": self.parse_results,
                "compress_tasks": self.compress_tasks,
                "upload_sessions": self.upload_sessions,
            }
            with open(self.metadata_file, "w", encoding="utf-8") as f:
                json.dump(data, f, ensure_ascii=False, indent=2)
        except Exception as e:
            logger.error(f"Failed to save metadata: {e}")

    def save_file(self, file_info: FileInfo):
        self.files[file_info.file_id] = file_info.model_dump()
        self._save()
        logger.info(f"File metadata saved: {file_info.file_id}", file_id=file_info.file_id)
        return file_info

    def get_file(self, file_id: str) -> Optional[FileInfo]:
        data = self.files.get(file_id)
        if data:
            return FileInfo(**data)
        return None

    def update_file(self, file_id: str, updates: dict):
        if file_id in self.files:
            self.files[file_id].update(updates)
            self._save()
            return FileInfo(**self.files[file_id])
        return None

    def list_files(self, user_id: Optional[str] = None) -> List[FileInfo]:
        result = []
        for data in self.files.values():
            if user_id is None or data.get("upload_user") == user_id:
                result.append(FileInfo(**data))
        return result

    def delete_file(self, file_id: str) -> bool:
        if file_id in self.files:
            del self.files[file_id]
            self._save()
            logger.info(f"File metadata deleted: {file_id}")
            return True
        return False

    def save_convert_task(self, task: ConvertTask):
        self.convert_tasks[task.task_id] = task.model_dump()
        self._save()
        logger.info(f"Convert task saved: {task.task_id}", task_id=task.task_id, task_type="convert")
        return task

    def get_convert_task(self, task_id: str) -> Optional[ConvertTask]:
        data = self.convert_tasks.get(task_id)
        if data:
            return ConvertTask(**data)
        return None

    def list_convert_tasks(self, file_id: Optional[str] = None) -> List[ConvertTask]:
        result = []
        for data in self.convert_tasks.values():
            if file_id is None or data.get("source_file_id") == file_id:
                result.append(ConvertTask(**data))
        return result

    def save_parse_result(self, result: ParseResult):
        self.parse_results[result.parse_id] = result.model_dump()
        self._save()
        logger.info(f"Parse result saved: {result.parse_id}", file_id=result.file_id)
        return result

    def get_parse_result(self, parse_id: str) -> Optional[ParseResult]:
        data = self.parse_results.get(parse_id)
        if data:
            return ParseResult(**data)
        return None

    def save_compress_task(self, task: CompressTask):
        self.compress_tasks[task.compress_id] = task.model_dump()
        self._save()
        logger.info(f"Compress task saved: {task.compress_id}", task_id=task.compress_id, task_type="compress")
        return task

    def get_compress_task(self, compress_id: str) -> Optional[CompressTask]:
        data = self.compress_tasks.get(compress_id)
        if data:
            return CompressTask(**data)
        return None

    def save_upload_session(self, session: UploadSession):
        self.upload_sessions[session.session_id] = session.model_dump()
        self._save()
        logger.info(f"Upload session saved: {session.session_id}")
        return session

    def get_upload_session(self, session_id: str) -> Optional[UploadSession]:
        data = self.upload_sessions.get(session_id)
        if data:
            return UploadSession(**data)
        return None

    def delete_upload_session(self, session_id: str) -> bool:
        if session_id in self.upload_sessions:
            del self.upload_sessions[session_id]
            self._save()
            return True
        return False


metadata = MetadataManager()
