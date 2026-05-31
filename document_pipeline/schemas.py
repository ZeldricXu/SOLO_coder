from datetime import datetime
from typing import List, Optional, Dict, Any
from enum import Enum
from pydantic import BaseModel, Field, ConfigDict


class DocumentFormat(str, Enum):
    PDF = "pdf"
    DOCX = "docx"
    TXT = "txt"
    MD = "md"
    HTML = "html"
    CSV = "csv"
    XLSX = "xlsx"
    PPTX = "pptx"
    JSON = "json"
    EML = "eml"
    MSG = "msg"


class ChunkingStrategy(str, Enum):
    FIXED_SIZE = "fixed_size"
    SEMANTIC = "semantic"
    RECURSIVE = "recursive"
    PARAGRAPH = "paragraph"
    SENTENCE = "sentence"
    MARKDOWN = "markdown"


class DocumentInfo(BaseModel):
    document_id: str
    name: str
    format: DocumentFormat
    size_bytes: int
    page_count: Optional[int] = None
    language: Optional[str] = None
    encoding: Optional[str] = None
    metadata: Dict[str, Any] = Field(default_factory=dict)
    created_at: datetime

    model_config = ConfigDict(from_attributes=True)


class DocumentParseRequest(BaseModel):
    file_path: Optional[str] = None
    file_content: Optional[bytes] = None
    file_name: str
    format: Optional[DocumentFormat] = None
    extract_images: bool = False
    extract_tables: bool = True
    ocr_enabled: bool = False
    ocr_language: str = "chi_sim+eng"


class DocumentParseResponse(BaseModel):
    document: DocumentInfo
    text_content: str
    raw_elements: Optional[List[Dict[str, Any]]] = None
    extracted_tables: Optional[List[Dict[str, Any]]] = None
    extracted_images: Optional[List[Dict[str, Any]]] = None
    parse_duration_ms: float


class Chunk(BaseModel):
    chunk_id: str
    content: str
    index: int
    start_pos: int
    end_pos: int
    word_count: int
    token_count: Optional[int] = None
    metadata: Dict[str, Any] = Field(default_factory=dict)


class ChunkingRequest(BaseModel):
    text: str
    strategy: ChunkingStrategy = Field(default=ChunkingStrategy.RECURSIVE)
    chunk_size: int = Field(default=512, ge=64, le=8192)
    chunk_overlap: int = Field(default=50, ge=0, le=1024)
    separators: Optional[List[str]] = None
    model_name: Optional[str] = "gpt-3.5-turbo"
    document_id: Optional[str] = None


class ChunkingResponse(BaseModel):
    chunks: List[Chunk]
    total_chunks: int
    strategy: ChunkingStrategy
    chunk_size: int
    chunk_overlap: int


class EmbeddingRequest(BaseModel):
    texts: List[str]
    model_name: str = Field(default="text-embedding-ada-002")
    batch_size: int = Field(default=32, ge=1, le=1024)
    normalize_embeddings: bool = True


class EmbeddingResponse(BaseModel):
    embeddings: List[List[float]]
    model_name: str
    embedding_dim: int
    total_tokens: int
    duration_ms: float


class DocumentPipelineRequest(BaseModel):
    file_path: Optional[str] = None
    file_content: Optional[bytes] = None
    file_name: str
    format: Optional[DocumentFormat] = None
    chunking_strategy: ChunkingStrategy = ChunkingStrategy.RECURSIVE
    chunk_size: int = 512
    chunk_overlap: int = 50
    embedding_model: str = "text-embedding-ada-002"
    extract_images: bool = False
    extract_tables: bool = True
    metadata: Optional[Dict[str, Any]] = None


class DocumentPipelineResponse(BaseModel):
    pipeline_id: str
    document: DocumentInfo
    chunks: List[Chunk]
    embeddings: Optional[List[List[float]]] = None
    total_duration_ms: float
    stages: Dict[str, float]
