from datetime import datetime
from enum import Enum
from typing import Any, Dict, List, Optional

from sqlalchemy import JSON, Integer, String, Text
from sqlalchemy import Enum as SQLEnum
from sqlalchemy.orm import Mapped, mapped_column

from core.database import Base
from core.utils import generate_id
from models.base import BaseModel, TimestampMixin


class PipelineStatus(str, Enum):
    IDLE = "idle"
    RUNNING = "running"
    PAUSED = "paused"
    COMPLETED = "completed"
    FAILED = "failed"


class DocumentStatus(str, Enum):
    PENDING = "pending"
    PARSING = "parsing"
    CHUNKING = "chunking"
    VECTORIZING = "vectorizing"
    COMPLETED = "completed"
    FAILED = "failed"


class DocumentType(str, Enum):
    PDF = "pdf"
    DOCX = "docx"
    TXT = "txt"
    MARKDOWN = "markdown"
    HTML = "html"
    CSV = "csv"


class DocumentPipeline(Base, TimestampMixin):
    __tablename__ = "document_pipelines"

    pipeline_id: Mapped[str] = mapped_column(
        String(64), primary_key=True, default=lambda: generate_id("dpl")
    )
    name: Mapped[str] = mapped_column(String(256), nullable=False)
    description: Mapped[Optional[str]] = mapped_column(String(1024), nullable=True)
    source_type: Mapped[str] = mapped_column(String(64), default="local")
    chunk_size: Mapped[int] = mapped_column(Integer, default=500)
    chunk_overlap: Mapped[int] = mapped_column(Integer, default=50)
    embedding_model: Mapped[str] = mapped_column(String(128), default="text-embedding-ada-002")
    vector_dimension: Mapped[int] = mapped_column(Integer, default=1536)
    status: Mapped[PipelineStatus] = mapped_column(
        SQLEnum(PipelineStatus), default=PipelineStatus.IDLE, index=True
    )
    total_documents: Mapped[int] = mapped_column(Integer, default=0)
    processed_documents: Mapped[int] = mapped_column(Integer, default=0)
    failed_documents: Mapped[int] = mapped_column(Integer, default=0)
    total_chunks: Mapped[int] = mapped_column(Integer, default=0)
    created_by: Mapped[str] = mapped_column(String(64), nullable=False)
    tenant_id: Mapped[Optional[str]] = mapped_column(String(64), index=True)
    config: Mapped[Dict[str, Any]] = mapped_column(JSON, default=dict)
    api_key: Mapped[Optional[str]] = mapped_column(String(256), nullable=True)


class DocumentTask(Base, TimestampMixin):
    __tablename__ = "document_tasks"

    task_id: Mapped[str] = mapped_column(
        String(64), primary_key=True, default=lambda: generate_id("dtk")
    )
    pipeline_id: Mapped[str] = mapped_column(String(64), index=True, nullable=False)
    file_name: Mapped[str] = mapped_column(String(512), nullable=False)
    file_path: Mapped[str] = mapped_column(String(1024), nullable=False)
    file_size: Mapped[int] = mapped_column(Integer, default=0)
    file_type: Mapped[DocumentType] = mapped_column(SQLEnum(DocumentType), default=DocumentType.TXT)
    status: Mapped[DocumentStatus] = mapped_column(
        SQLEnum(DocumentStatus), default=DocumentStatus.PENDING, index=True
    )
    total_chunks: Mapped[int] = mapped_column(Integer, default=0)
    processed_chunks: Mapped[int] = mapped_column(Integer, default=0)
    vector_store: Mapped[str] = mapped_column(String(128), default="pgvector")
    error_message: Mapped[Optional[str]] = mapped_column(String(2048), nullable=True)
    processing_time_ms: Mapped[int] = mapped_column(Integer, default=0)
    created_by: Mapped[str] = mapped_column(String(64), nullable=False)
    tenant_id: Mapped[Optional[str]] = mapped_column(String(64), index=True)
    meta_data: Mapped[Dict[str, Any]] = mapped_column("metadata", JSON, default=dict)
    access_token: Mapped[Optional[str]] = mapped_column(String(512), nullable=True)


class DocumentChunk(Base, TimestampMixin):
    __tablename__ = "document_chunks"

    chunk_id: Mapped[str] = mapped_column(
        String(64), primary_key=True, default=lambda: generate_id("dck")
    )
    task_id: Mapped[str] = mapped_column(String(64), index=True, nullable=False)
    pipeline_id: Mapped[str] = mapped_column(String(64), index=True, nullable=False)
    chunk_index: Mapped[int] = mapped_column(Integer, nullable=False)
    content: Mapped[str] = mapped_column(Text, nullable=False)
    word_count: Mapped[int] = mapped_column(Integer, default=0)
    token_count: Mapped[int] = mapped_column(Integer, default=0)
    embedding: Mapped[List[float]] = mapped_column(JSON, default=list)
    meta_data: Mapped[Dict[str, Any]] = mapped_column("metadata", JSON, default=dict)
    vector_id: Mapped[Optional[str]] = mapped_column(String(64), nullable=True, index=True)


class DocumentPipelineCreate(BaseModel):
    name: str
    description: Optional[str] = None
    source_type: str = "local"
    chunk_size: int = 500
    chunk_overlap: int = 50
    embedding_model: str = "text-embedding-ada-002"
    vector_dimension: int = 1536
    created_by: str
    tenant_id: Optional[str] = None
    config: Dict[str, Any] = {}
    api_key: Optional[str] = None


class DocumentPipelineResponse(BaseModel):
    pipeline_id: str
    name: str
    description: Optional[str]
    source_type: str
    chunk_size: int
    chunk_overlap: int
    embedding_model: str
    vector_dimension: int
    status: PipelineStatus
    total_documents: int
    processed_documents: int
    failed_documents: int
    total_chunks: int
    processing_progress: float = 0.0
    success_rate: float = 0.0
    created_by: str
    tenant_id: Optional[str]
    created_at: datetime
    updated_at: datetime
    config: Dict[str, Any]
    api_key: Optional[str] = None
    mobile_layout: Dict[str, Any] = {}


class DocumentTaskCreate(BaseModel):
    pipeline_id: str
    file_name: str
    file_path: str
    file_size: int = 0
    file_type: DocumentType = DocumentType.TXT
    vector_store: str = "pgvector"
    created_by: str
    tenant_id: Optional[str] = None
    metadata: Dict[str, Any] = {}
    access_token: Optional[str] = None


class DocumentTaskResponse(BaseModel):
    task_id: str
    pipeline_id: str
    file_name: str
    file_path: str
    file_size: int
    file_type: DocumentType
    status: DocumentStatus
    total_chunks: int
    processed_chunks: int
    processing_progress: float = 0.0
    vector_store: str
    error_message: Optional[str]
    processing_time_ms: int
    created_by: str
    tenant_id: Optional[str]
    created_at: datetime
    updated_at: datetime
    metadata: Dict[str, Any]
    access_token: Optional[str] = None


class DocumentChunkCreate(BaseModel):
    task_id: str
    pipeline_id: str
    chunk_index: int
    content: str
    word_count: int = 0
    token_count: int = 0
    embedding: List[float] = []
    tenant_id: Optional[str] = None
    metadata: Dict[str, Any] = {}


class DocumentChunkResponse(BaseModel):
    chunk_id: str
    task_id: str
    pipeline_id: str
    chunk_index: int
    content: str
    word_count: int
    token_count: int
    has_embedding: bool
    tenant_id: Optional[str]
    created_at: datetime


class PipelineStatsResponse(BaseModel):
    pipeline_id: str
    name: str
    status: PipelineStatus
    total_documents: int
    processed_documents: int
    failed_documents: int
    total_chunks: int
    processing_progress: float
    success_rate: float
    avg_chunk_size: float
    avg_processing_time_per_document: float
    throughput_per_hour: float
    mobile_compatible: bool = False
