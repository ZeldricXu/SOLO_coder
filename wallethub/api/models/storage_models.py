from datetime import datetime
from typing import Any, Dict, Optional, Union
from pydantic import Field

from .common import BaseModel


class StorageUploadRequest(BaseModel):
    network: str = "ipfs"
    data: Union[str, Dict[str, Any]]
    pin: bool = True
    content_type: Optional[str] = None
    metadata: Dict[str, Any] = Field(default_factory=dict)


class StorageResponse(BaseModel):
    content_id: str
    network: str
    cid: str
    content_hash: str
    content_type: str
    size: int
    pinned: bool
    url: str
    created_at: datetime


class PinRequest(BaseModel):
    network: str = "ipfs"
    cid: str
    service: Optional[str] = None
