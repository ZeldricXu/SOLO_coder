from datetime import datetime
from typing import Any, Dict, Optional
from pydantic import Field

from .common import BaseModel


class CrossChainTransferRequest(BaseModel):
    source_chain: str
    target_chain: str
    source_address: str
    target_address: str
    token_address: str
    amount: int
    bridge_type: str = "lock_mint"
    metadata: Dict[str, Any] = Field(default_factory=dict)


class CrossChainTransferResponse(BaseModel):
    transfer_id: str
    source_chain: str
    target_chain: str
    source_address: str
    target_address: str
    token_address: str
    amount: int
    source_tx_hash: Optional[str]
    target_tx_hash: Optional[str]
    message_hash: Optional[str]
    status: str
    created_at: datetime
    updated_at: datetime


class AtomicSwapRequest(BaseModel):
    source_chain: str
    target_chain: str
    initiator: str
    participant: str
    source_token: str
    target_token: str
    source_amount: int
    target_amount: int
    secret_hash: Optional[str] = None
    timelock: int = 86400


class AtomicSwapResponse(BaseModel):
    swap_id: str
    source_chain: str
    target_chain: str
    initiator: str
    participant: str
    source_token: str
    target_token: str
    source_amount: int
    target_amount: int
    secret_hash: str
    secret: Optional[str] = None
    timelock: int
    status: str
    created_at: datetime
