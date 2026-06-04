from datetime import datetime
from typing import Optional, Dict, Any, List
from pydantic import BaseModel


class LogSearchRequest(BaseModel):
    keyword: Optional[str] = None
    service_name: Optional[str] = None
    start_time: Optional[datetime] = None
    end_time: Optional[datetime] = None
    level: Optional[str] = None
    page: int = 1
    page_size: int = 50


class LogTemplateCreate(BaseModel):
    name: str
    query_config: Dict[str, Any]


class PinnedComponentRequest(BaseModel):
    component_type: str
    component_key: str
    position: int = 0


class LayoutConfig(BaseModel):
    layout: Optional[Dict[str, Any]] = None
