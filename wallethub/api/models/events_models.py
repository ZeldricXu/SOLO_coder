from datetime import datetime
from typing import Any, Dict, Optional
from pydantic import Field

from .common import BaseModel


class EventListenerCreateRequest(BaseModel):
    chain: str
    contract_address: str
    event_name: str
    event_abi: Dict[str, Any]
    start_block: Optional[int] = None
    callback_url: Optional[str] = None
    callback_headers: Dict[str, str] = Field(default_factory=dict)
    filter_params: Dict[str, Any] = Field(default_factory=dict)


class EventListenerResponse(BaseModel):
    listener_id: str
    chain: str
    contract_address: str
    event_name: str
    start_block: int
    current_block: int
    status: str
    callback_url: Optional[str]
    created_at: datetime
    updated_at: datetime


class EventLogResponse(BaseModel):
    log_id: str
    listener_id: str
    chain: str
    block_number: int
    transaction_hash: str
    log_index: int
    contract_address: str
    event_name: str
    args: Dict[str, Any]
    processed: bool
    created_at: datetime
