from pydantic import BaseModel
from typing import Optional, List
from datetime import datetime


class DocumentResponse(BaseModel):
    id: str
    file_name: str
    file_type: str
    file_size: int
    upload_time: datetime
    chunk_count: int
    collection_name: str
    
    class Config:
        from_attributes = True


class QueryRequest(BaseModel):
    query: str
    knowledge_base_id: str = "default"
    top_k: int = 5
    stream: bool = True


class SourceNode(BaseModel):
    document_id: str
    file_name: str
    content: str
    score: float
    chunk_index: Optional[int] = None


class QueryResponse(BaseModel):
    answer: str
    sources: List[SourceNode]


class UploadResponse(BaseModel):
    document_id: str
    file_name: str
    file_type: str
    chunk_count: int
    status: str = "success"


class CollectionInfo(BaseModel):
    name: str
    document_count: int
    chunk_count: int


class APIConfigResponse(BaseModel):
    id: str
    name: str
    base_url: str
    model: str
    embedding_model: str
    is_active: bool
    created_at: datetime
    updated_at: datetime
    
    class Config:
        from_attributes = True


class APIConfigCreate(BaseModel):
    name: str
    api_key: str
    base_url: str = "https://api.openai.com/v1"
    model: str = "gpt-3.5-turbo"
    embedding_model: str = "text-embedding-3-small"


class APIConfigUpdate(BaseModel):
    name: Optional[str] = None
    api_key: Optional[str] = None
    base_url: Optional[str] = None
    model: Optional[str] = None
    embedding_model: Optional[str] = None
