from datetime import datetime
from typing import Any, Dict, Optional
from pydantic import Field

from .common import BaseModel


class ChainInfoResponse(BaseModel):
    chain: str
    chain_id: int
    name: str
    rpc_url: str
    symbol: str
    block_number: int
    gas_price_wei: int
    is_connected: bool


class BlockResponse(BaseModel):
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
    transactions: list[Dict[str, Any]] = Field(default_factory=list)


class TransactionResponse(BaseModel):
    chain: str
    tx_hash: str
    block_number: Optional[int]
    from_address: str
    to_address: Optional[str]
    value: int
    gas: int
    gas_price: int
    max_fee_per_gas: Optional[int]
    max_priority_fee_per_gas: Optional[int]
    nonce: int
    input: str
    status: Optional[int]
    contract_address: Optional[str]
