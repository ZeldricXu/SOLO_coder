import hashlib
import uuid
import time
from datetime import datetime, timedelta
from typing import Optional, Dict, Any, List
from enum import Enum
from pydantic import BaseModel, Field


def generate_id(prefix: str) -> str:
    timestamp = int(time.time() * 1000)
    unique = uuid.uuid4().hex[:8]
    return f"{prefix}_{timestamp}_{unique}"


def now_iso() -> str:
    return datetime.utcnow().isoformat() + "Z"


def expire_at_days(days: int) -> str:
    expire = datetime.utcnow() + timedelta(days=days)
    return expire.isoformat() + "Z"


class FileStatus(str, Enum):
    UPLOADING = "uploading"
    STORED = "stored"
    PROCESSING = "processing"
    ERROR = "error"
    EXPIRED = "expired"


class TaskStatus(str, Enum):
    PENDING = "pending"
    PROCESSING = "processing"
    COMPLETED = "completed"
    FAILED = "failed"
    RETRYING = "retrying"


class LogLevel(str, Enum):
    DEBUG = "debug"
    INFO = "info"
    WARNING = "warning"
    ERROR = "error"


class FileInfo(BaseModel):
    file_id: str = Field(default_factory=lambda: generate_id("file"))
    file_name: str
    file_type: str
    file_size: int
    storage_path: str
    upload_user: str = "anonymous"
    upload_time: str = Field(default_factory=now_iso)
    status: FileStatus = FileStatus.STORED
    expire_at: str
    sha256: Optional[str] = None
    mime_type: Optional[str] = None
    chunks: int = 0
    chunks_received: int = 0
    chunk_session_id: Optional[str] = None

    class Config:
        use_enum_values = True


class ConvertTask(BaseModel):
    task_id: str = Field(default_factory=lambda: generate_id("task_convert"))
    source_file_id: str
    source_format: str
    target_format: str
    target_file_id: Optional[str] = None
    conversion_params: Dict[str, Any] = Field(default_factory=dict)
    task_status: TaskStatus = TaskStatus.PENDING
    created_at: str = Field(default_factory=now_iso)
    started_at: Optional[str] = None
    completed_at: Optional[str] = None
    error_message: Optional[str] = None
    retry_count: int = 0

    class Config:
        use_enum_values = True


class ConvertResult(BaseModel):
    result_file_id: str
    source_file_id: str
    result_format: str
    result_size: int
    result_path: str
    conversion_time: float


class ParseResult(BaseModel):
    parse_id: str = Field(default_factory=lambda: generate_id("parse"))
    file_id: str
    parse_type: str
    parse_result: Any
    parse_status: TaskStatus = TaskStatus.COMPLETED
    parse_time: str = Field(default_factory=now_iso)
    error_message: Optional[str] = None

    class Config:
        use_enum_values = True


class CompressTask(BaseModel):
    compress_id: str = Field(default_factory=lambda: generate_id("compress"))
    source_files: List[str]
    compress_format: str
    result_file_id: Optional[str] = None
    compress_status: TaskStatus = TaskStatus.PENDING
    compress_time: Optional[str] = None
    compression_params: Dict[str, Any] = Field(default_factory=dict)
    error_message: Optional[str] = None

    class Config:
        use_enum_values = True


class ProcessLog(BaseModel):
    log_id: str = Field(default_factory=lambda: generate_id("log"))
    task_id: Optional[str] = None
    file_id: Optional[str] = None
    task_type: Optional[str] = None
    log_content: str
    log_time: str = Field(default_factory=now_iso)
    log_level: LogLevel = LogLevel.INFO

    class Config:
        use_enum_values = True


class UploadSession(BaseModel):
    session_id: str = Field(default_factory=lambda: generate_id("sess"))
    file_name: str
    total_size: int
    total_chunks: int
    chunks_received: List[int] = Field(default_factory=list)
    file_id: Optional[str] = None
    upload_user: str = "anonymous"
    created_at: str = Field(default_factory=now_iso)


class ConvertRequest(BaseModel):
    file_id: str
    target_format: str
    conversion_params: Dict[str, Any] = Field(default_factory=dict)
    user_id: str = "anonymous"


class ParseRequest(BaseModel):
    file_id: str
    parse_type: str
    parse_params: Dict[str, Any] = Field(default_factory=dict)


class CompressRequest(BaseModel):
    file_ids: List[str]
    compress_format: str = "zip"
    compression_params: Dict[str, Any] = Field(default_factory=dict)


class ApiResponse(BaseModel):
    code: int = 200
    message: str = "success"
    data: Optional[Dict[str, Any]] = None
