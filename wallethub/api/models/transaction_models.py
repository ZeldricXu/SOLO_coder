from datetime import datetime
from typing import Any, Dict, List, Optional
from pydantic import Field, field_validator

from .common import BaseModel


class TransactionCreateRequest(BaseModel):
    chain: str = "ethereum"
    from_address: Optional[str] = None
    to_address: str = Field(..., description="Recipient address")
    value: int = Field(0, description="Amount in wei")
    data: Optional[str] = Field(None, description="Transaction data (hex)")
    gas_limit: Optional[int] = Field(None, description="Gas limit")
    gas_price: Optional[int] = Field(None, description="Gas price in wei (legacy)")
    max_fee_per_gas: Optional[int] = Field(None, description="Max fee per gas (EIP-1559)")
    max_priority_fee_per_gas: Optional[int] = Field(None, description="Max priority fee per gas (EIP-1559)")
    nonce: Optional[int] = Field(None, description="Transaction nonce")
    eip1559: bool = Field(True, description="Use EIP-1559 transaction type")


class TransactionResponse(BaseModel):
    tx_id: str
    chain: str
    from_address: Optional[str]
    to_address: str
    value: int
    data: Optional[str]
    nonce: Optional[int]
    gas_limit: int
    gas_price: Optional[int]
    max_fee_per_gas: Optional[int]
    max_priority_fee_per_gas: Optional[int]
    tx_hash: Optional[str]
    status: str
    block_number: Optional[int]
    created_at: datetime
    updated_at: datetime


class MultiSigProposalRequest(BaseModel):
    wallet_id: str
    to_address: str
    value: int = 0
    data: Optional[str] = None


class MultiSigProposalResponse(BaseModel):
    proposal_id: str
    wallet_id: str
    to_address: str
    value: int
    data: Optional[str]
    nonce: int
    signers: List[str]
    threshold: int
    status: str
    created_at: datetime


class SignRequest(BaseModel):
    message: Optional[str] = None
    typed_data: Optional[Dict[str, Any]] = None
    key_id: Optional[str] = None
    private_key: Optional[str] = None

    @field_validator("message", "typed_data")
    @classmethod
    def check_at_least_one(cls, v, values):
        if v is None and values.data.get("message") is None and values.data.get("typed_data") is None:
            raise ValueError("Either message or typed_data must be provided")
        return v


class SignResponse(BaseModel):
    signature: str
    signer_address: str
    message_hash: Optional[str] = None


class GasEstimateRequest(BaseModel):
    chain: str = "ethereum"
    to_address: Optional[str] = None
    value: int = 0
    data: Optional[str] = None
    from_address: Optional[str] = None


class GasFeeEstimate(BaseModel):
    gas_price: Optional[int] = None
    max_fee_per_gas: Optional[int] = None
    max_priority_fee_per_gas: Optional[int] = None
    estimated_cost: int


class GasEstimateResponse(BaseModel):
    chain: str
    block_number: int
    base_fee: Optional[int]
    gas_limit: int
    slow: GasFeeEstimate
    standard: GasFeeEstimate
    fast: GasFeeEstimate
    urgent: GasFeeEstimate
