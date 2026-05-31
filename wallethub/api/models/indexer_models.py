from datetime import datetime
from typing import Any, Dict, List, Optional
from pydantic import Field

from .common import BaseModel


class IndexerStatusResponse(BaseModel):
    chain: str
    status: str
    current_block: int
    latest_block: int
    blocks_behind: int
    progress_percent: float


class IndexedBlockResponse(BaseModel):
    chain: str
    block_number: int
    block_hash: str
    parent_hash: str
    timestamp: int
    difficulty: int
    gas_limit: int
    gas_used: int
    base_fee_per_gas: Optional[int]
    miner: str
    transaction_count: int
    indexed_at: datetime


class IndexedTransactionResponse(BaseModel):
    chain: str
    tx_hash: str
    block_number: int
    transaction_index: int
    from_address: str
    to_address: Optional[str]
    value: int
    input: str
    gas: int
    gas_price: int
    nonce: int
    status: Optional[int]
    contract_address: Optional[str]
    decoded_method: Optional[str]
    decoded_params: Optional[Dict[str, Any]]
