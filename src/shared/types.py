from __future__ import annotations

from datetime import datetime
from enum import Enum
from typing import Any, Dict, Generic, List, Optional, TypeVar, Union
from uuid import UUID, uuid4

from pydantic import BaseModel, Field, field_validator

ChainId = int
Address = str
Hash = str
HexString = str
WeiAmount = int
GasAmount = int
BlockNumber = int
Timestamp = int

T = TypeVar("T")


class Chain(str, Enum):
    ETHEREUM = "ethereum"
    GOERLI = "goerli"
    POLYGON = "polygon"
    BSC = "bsc"
    ARBITRUM = "arbitrum"
    OPTIMISM = "optimism"


class EntityStatus(str, Enum):
    PENDING = "pending"
    PROCESSING = "processing"
    COMPLETED = "completed"
    FAILED = "failed"
    CANCELLED = "cancelled"


class RunPhase(str, Enum):
    INITIALIZING = "initializing"
    VALIDATING = "validating"
    PROCESSING = "processing"
    PERSISTING = "persisting"
    COMPLETED = "completed"
    FAILED = "failed"


class EntityType(str, Enum):
    EVENT = "event"
    TRANSACTION = "transaction"
    BLOCK = "block"
    ADDRESS = "address"
    PROOF = "proof"
    BRIDGE = "bridge"


class BaseEntity(BaseModel):
    id: str = Field(default_factory=lambda: f"ent_{uuid4().hex[:12]}")
    type: EntityType
    status: EntityStatus = EntityStatus.PENDING
    attributes: Dict[str, Any] = Field(default_factory=dict)
    created_at: datetime = Field(default_factory=datetime.utcnow)
    updated_at: datetime = Field(default_factory=datetime.utcnow)


class BaseConfig(BaseModel):
    config_id: str = Field(default_factory=lambda: f"cfg_{uuid4().hex[:8]}")
    namespace: str = "default"
    version: int = 1
    parameters: Dict[str, Any] = Field(default_factory=dict)
    enabled: bool = True
    applied_at: Optional[datetime] = None


class RunInstance(BaseModel):
    run_id: str = Field(default_factory=lambda: f"run_{uuid4().hex[:12]}")
    entity_id: str
    phase: RunPhase = RunPhase.INITIALIZING
    progress: float = 0.0
    started_at: datetime = Field(default_factory=datetime.utcnow)
    completed_at: Optional[datetime] = None
    error_detail: Optional[str] = None


class MetricsSnapshot(BaseModel):
    snapshot_id: str = Field(default_factory=lambda: f"snap_{uuid4().hex[:8]}")
    timestamp: datetime = Field(default_factory=datetime.utcnow)
    metrics: Dict[str, float] = Field(default_factory=dict)
    dimensions: Dict[str, str] = Field(default_factory=dict)


class APIResponse(BaseModel, Generic[T]):
    code: int = 200
    data: Optional[T] = None
    message: Optional[str] = None

    @classmethod
    def success(cls, data: T = None, message: str = "Success") -> "APIResponse[T]":
        return cls(code=200, data=data, message=message)

    @classmethod
    def created(cls, data: T = None, message: str = "Created") -> "APIResponse[T]":
        return cls(code=201, data=data, message=message)

    @classmethod
    def error(cls, code: int, message: str) -> "APIResponse[T]":
        return cls(code=code, data=None, message=message)


class ResourceCreateRequest(BaseModel):
    type: str
    config: Dict[str, Any] = Field(default_factory=dict)
    labels: Dict[str, str] = Field(default_factory=dict)


class ResourceStatusResponse(BaseModel):
    id: str
    status: str
    progress: float = 0.0
    started_at: Optional[datetime] = None
    completed_at: Optional[datetime] = None


class BatchOperation(BaseModel):
    action: str
    id: str
    params: Dict[str, Any] = Field(default_factory=dict)


class BatchRequest(BaseModel):
    operations: List[BatchOperation]


class BatchResult(BaseModel):
    id: str
    success: bool
    result: Optional[Dict[str, Any]] = None
    error: Optional[str] = None


class BatchResponse(BaseModel):
    batch_id: str = Field(default_factory=lambda: f"batch_{uuid4().hex[:8]}")
    results: List[BatchResult] = Field(default_factory=list)


class BlockHeader(BaseModel):
    number: BlockNumber
    hash: Hash
    parent_hash: Hash
    timestamp: Timestamp
    difficulty: int
    total_difficulty: int
    gas_limit: GasAmount
    gas_used: GasAmount
    miner: Address
    extra_data: HexString
    base_fee_per_gas: Optional[WeiAmount] = None


class Transaction(BaseModel):
    hash: Hash
    block_hash: Optional[Hash]
    block_number: Optional[BlockNumber]
    from_address: Address
    to_address: Optional[Address]
    value: WeiAmount
    gas: GasAmount
    gas_price: Optional[WeiAmount]
    max_fee_per_gas: Optional[WeiAmount] = None
    max_priority_fee_per_gas: Optional[WeiAmount] = None
    input: HexString
    nonce: int
    transaction_index: Optional[int]
    chain_id: Optional[ChainId]
    type: int = 0


class TransactionReceipt(BaseModel):
    transaction_hash: Hash
    transaction_index: int
    block_hash: Hash
    block_number: BlockNumber
    from_address: Address
    to_address: Optional[Address]
    cumulative_gas_used: GasAmount
    gas_used: GasAmount
    contract_address: Optional[Address]
    logs: List["EventLog"]
    status: int
    effective_gas_price: WeiAmount


class EventLog(BaseModel):
    log_index: int
    transaction_hash: Hash
    transaction_index: int
    block_hash: Hash
    block_number: BlockNumber
    address: Address
    data: HexString
    topics: List[HexString]
    removed: bool = False


class GasEstimate(BaseModel):
    gas_limit: GasAmount
    gas_price: WeiAmount
    max_fee_per_gas: Optional[WeiAmount] = None
    max_priority_fee_per_gas: Optional[WeiAmount] = None
    estimated_cost: WeiAmount
    confidence: float
    recommendation: str = "average"


class ZKPProof(BaseModel):
    proof_type: str
    circuit_id: str
    proof_data: HexString
    public_inputs: List[HexString]
    verifier_address: Optional[Address] = None


class ZKPVerificationResult(BaseModel):
    verified: bool
    circuit_id: str
    verification_time_ms: float
    error: Optional[str] = None
    public_outputs: Optional[List[HexString]] = None


class HDWalletAccount(BaseModel):
    address: Address
    path: str
    index: int
    public_key: HexString
    chain_code: Optional[HexString] = None
    label: Optional[str] = None
    tags: List[str] = Field(default_factory=list)


class AddressBookEntry(BaseModel):
    address: Address
    name: str
    chain: Chain
    labels: List[str] = Field(default_factory=list)
    notes: Optional[str] = None
    created_at: datetime = Field(default_factory=datetime.utcnow)


class CrossChainMessage(BaseModel):
    message_id: str = Field(default_factory=lambda: f"msg_{uuid4().hex[:12]}")
    source_chain: Chain
    target_chain: Chain
    source_address: Address
    target_address: Address
    amount: WeiAmount
    token_address: Optional[Address] = None
    data: HexString = "0x"
    source_transaction_hash: Optional[Hash] = None
    target_transaction_hash: Optional[Hash] = None
    status: str = "pending"
    created_at: datetime = Field(default_factory=datetime.utcnow)
    updated_at: datetime = Field(default_factory=datetime.utcnow)


class StoredContent(BaseModel):
    cid: str
    storage_network: str
    size: int
    pin_status: str = "pinned"
    created_at: datetime = Field(default_factory=datetime.utcnow)
    metadata: Dict[str, Any] = Field(default_factory=dict)


class SignedTransaction(BaseModel):
    raw_transaction: HexString
    hash: Hash
    from_address: Address
    to_address: Optional[Address]
    value: WeiAmount
    gas: GasAmount
    gas_price: WeiAmount
    nonce: int
    chain_id: ChainId
    signers: List[Address] = Field(default_factory=list)
