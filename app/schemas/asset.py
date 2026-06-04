from datetime import datetime
from typing import Optional, Dict, Any
from pydantic import BaseModel


class AssetCreate(BaseModel):
    name: str
    category: str
    ip: Optional[str] = None
    port: Optional[int] = None
    version: Optional[str] = None
    owner: Optional[str] = None
    status: str = "normal"


class AssetUpdate(BaseModel):
    name: Optional[str] = None
    category: Optional[str] = None
    ip: Optional[str] = None
    port: Optional[int] = None
    version: Optional[str] = None
    owner: Optional[str] = None
    status: Optional[str] = None


class ChangeLogEntry(BaseModel):
    asset_id: int
    field_name: str
    old_value: Optional[str] = None
    new_value: Optional[str] = None
    operator_id: int
