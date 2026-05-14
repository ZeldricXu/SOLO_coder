import json
import logging
from datetime import datetime
from pathlib import Path
from typing import Optional
from .config import settings
from .models import ProcessLog, LogLevel, generate_id, now_iso


class FileEngineLogger:
    _instance = None
    _memory_logs: list = []

    def __new__(cls):
        if cls._instance is None:
            cls._instance = super().__new__(cls)
            cls._instance._setup()
        return cls._instance

    def _setup(self):
        self.logs_dir = settings.logs_dir
        self.memory_logs = []
        self.max_memory_logs = 1000

        log_file = self.logs_dir / "fileengine.log"
        logging.basicConfig(
            level=logging.INFO,
            format="%(asctime)s - %(levelname)s - %(message)s",
            handlers=[
                logging.FileHandler(log_file, encoding="utf-8"),
                logging.StreamHandler(),
            ],
        )
        self.logger = logging.getLogger("fileengine")

    def _save_log(self, process_log: ProcessLog):
        self.memory_logs.append(process_log)
        if len(self.memory_logs) > self.max_memory_logs:
            self.memory_logs = self.memory_logs[-self.max_memory_logs // 2 :]

        log_file = self.logs_dir / "process_logs.jsonl"
        try:
            with open(log_file, "a", encoding="utf-8") as f:
                f.write(process_log.model_dump_json() + "\n")
        except Exception as e:
            self.logger.error(f"Failed to save log: {e}")

    def log(
        self,
        content: str,
        level: LogLevel = LogLevel.INFO,
        task_id: Optional[str] = None,
        file_id: Optional[str] = None,
        task_type: Optional[str] = None,
    ):
        process_log = ProcessLog(
            log_content=content,
            log_level=level,
            task_id=task_id,
            file_id=file_id,
            task_type=task_type,
        )

        log_msg = f"[task={task_id}] [file={file_id}] [type={task_type}] {content}"
        if level == LogLevel.DEBUG:
            self.logger.debug(log_msg)
        elif level == LogLevel.INFO:
            self.logger.info(log_msg)
        elif level == LogLevel.WARNING:
            self.logger.warning(log_msg)
        elif level == LogLevel.ERROR:
            self.logger.error(log_msg)

        self._save_log(process_log)
        return process_log

    def debug(
        self,
        content: str,
        task_id: Optional[str] = None,
        file_id: Optional[str] = None,
        task_type: Optional[str] = None,
    ):
        return self.log(content, LogLevel.DEBUG, task_id, file_id, task_type)

    def info(
        self,
        content: str,
        task_id: Optional[str] = None,
        file_id: Optional[str] = None,
        task_type: Optional[str] = None,
    ):
        return self.log(content, LogLevel.INFO, task_id, file_id, task_type)

    def warning(
        self,
        content: str,
        task_id: Optional[str] = None,
        file_id: Optional[str] = None,
        task_type: Optional[str] = None,
    ):
        return self.log(content, LogLevel.WARNING, task_id, file_id, task_type)

    def error(
        self,
        content: str,
        task_id: Optional[str] = None,
        file_id: Optional[str] = None,
        task_type: Optional[str] = None,
    ):
        return self.log(content, LogLevel.ERROR, task_id, file_id, task_type)

    def get_logs(self, task_id: Optional[str] = None, file_id: Optional[str] = None, limit: int = 100):
        result = [
            log.model_dump()
            for log in self.memory_logs
            if (task_id is None or log.task_id == task_id)
            and (file_id is None or log.file_id == file_id)
        ]
        return result[-limit:]


logger = FileEngineLogger()
