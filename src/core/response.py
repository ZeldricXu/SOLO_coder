from pydantic import BaseModel, Field, field_serializer
from typing import Any, Optional, Dict, List
from datetime import datetime


class ApiResponse(BaseModel):
    code: int
    message: Optional[str] = None
    data: Optional[Any] = None
    timestamp: datetime = Field(default_factory=datetime.utcnow)

    @field_serializer("timestamp")
    @classmethod
    def serialize_timestamp(cls, v: datetime) -> str:
        return v.isoformat()

    @classmethod
    def success(cls, data: Any = None, message: str = "success") -> "ApiResponse":
        return cls(code=200, message=message, data=data)

    @classmethod
    def created(cls, data: Any = None, message: str = "created") -> "ApiResponse":
        return cls(code=201, message=message, data=data)

    @classmethod
    def error(cls, code: int, message: str, data: Any = None) -> "ApiResponse":
        return cls(code=code, message=message, data=data)


class BatchOperation(BaseModel):
    action: str
    id: str


class BatchRequest(BaseModel):
    operations: List[BatchOperation]


class BatchResult(BaseModel):
    batch_id: str
    results: List[Dict[str, Any]]


class ResourceCreateRequest(BaseModel):
    type: str
    config: Dict[str, Any] = Field(default_factory=dict)
    labels: Dict[str, str] = Field(default_factory=dict)


class ResourceStatusResponse(BaseModel):
    id: str
    status: str
    progress: Optional[float] = None
