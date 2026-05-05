from typing import List, Optional
from pydantic import BaseModel
from enum import Enum


class Role(str, Enum):
    USER = "user"
    ASSISTANT = "assistant"
    SYSTEM = "system"


class Message(BaseModel):
    role: Role
    content: str


class UploadResponse(BaseModel):
    task_id: str
    status: str
    message: str


class TaskStatusResponse(BaseModel):
    task_id: str
    status: str
    file_name: str
    collection_name: str
    total_chunks: Optional[int] = None
    processed_chunks: int = 0
    error_message: Optional[str] = None


class ChatRequest(BaseModel):
    model: str
    messages: List[Message]
    collection_name: Optional[str] = None
    stream: bool = True
    temperature: Optional[float] = 0.7
    max_tokens: Optional[int] = 2000


class ChatResponse(BaseModel):
    content: str
    model: str
    created_at: str


class ErrorResponse(BaseModel):
    error: str
    detail: Optional[str] = None


class HealthResponse(BaseModel):
    status: str
    version: str
    timestamp: str


class CollectionInfo(BaseModel):
    name: str
    document_count: int
