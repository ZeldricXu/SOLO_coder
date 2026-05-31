from pydantic import BaseModel, Field, field_validator
from typing import List, Dict, Any, Optional, Union
from enum import Enum
from datetime import datetime


class DocumentFormat(str, Enum):
    PDF = "pdf"
    WORD = "word"
    EXCEL = "excel"
    PPT = "ppt"
    TXT = "txt"
    MD = "markdown"
    HTML = "html"
    JSON = "json"
    CSV = "csv"


class ChunkingStrategy(str, Enum):
    FIXED_SIZE = "fixed_size"
    SEMANTIC = "semantic"
    RECURSIVE = "recursive"
    PARAGRAPH = "paragraph"


class Document(BaseModel):
    document_id: str
    format: DocumentFormat
    content: bytes
    metadata: Dict[str, Any] = Field(default_factory=dict)


class DocumentChunk(BaseModel):
    chunk_id: str
    document_id: str
    content: str
    start_index: int
    end_index: int
    metadata: Dict[str, Any] = Field(default_factory=dict)


class VectorEmbedding(BaseModel):
    chunk_id: str
    vector: List[float]
    dimension: int
    model_name: str


class ParseRequest(BaseModel):
    document_id: Optional[str] = None
    format: DocumentFormat
    content: Union[str, bytes]
    metadata: Dict[str, Any] = Field(default_factory=dict)

    @field_validator("content", mode="before")
    @classmethod
    def content_to_bytes(cls, v):
        if isinstance(v, str):
            return v.encode("utf-8")
        return v


class ChunkRequest(BaseModel):
    document_id: str
    text: str
    strategy: ChunkingStrategy = ChunkingStrategy.RECURSIVE
    chunk_size: int = 1000
    chunk_overlap: int = 200
    metadata: Dict[str, Any] = Field(default_factory=dict)


class VectorizeRequest(BaseModel):
    chunks: List[DocumentChunk]
    model_name: str = "default-embedding"


class PipelineRequest(BaseModel):
    document_id: Optional[str] = None
    format: DocumentFormat
    content: Union[str, bytes]
    chunking_strategy: ChunkingStrategy = ChunkingStrategy.RECURSIVE
    chunk_size: int = 1000
    chunk_overlap: int = 200
    embedding_model: str = "default-embedding"
    metadata: Dict[str, Any] = Field(default_factory=dict)

    @field_validator("content", mode="before")
    @classmethod
    def content_to_bytes(cls, v):
        if isinstance(v, str):
            return v.encode("utf-8")
        return v


class PipelineResult(BaseModel):
    document_id: str
    chunks: List[DocumentChunk]
    embeddings: List[VectorEmbedding]
    processing_time: float
    started_at: datetime
    completed_at: datetime
