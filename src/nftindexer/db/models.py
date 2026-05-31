import json
from datetime import datetime, timezone
from typing import Any, Dict, List, Optional

from sqlalchemy import (
    JSON,
    BigInteger,
    Boolean,
    Column,
    DateTime,
    Float,
    ForeignKey,
    Index,
    Integer,
    String,
    Text,
    UniqueConstraint,
    func,
)
from sqlalchemy.orm import Mapped, mapped_column, relationship

from .base import Base


def utc_now() -> datetime:
    return datetime.now(timezone.utc)


class Config(Base):
    __tablename__ = "configs"

    id: Mapped[int] = mapped_column(primary_key=True)
    config_id: Mapped[str] = mapped_column(String(64), unique=True, index=True)
    namespace: Mapped[str] = mapped_column(String(64), index=True)
    version: Mapped[int] = mapped_column(Integer, default=1)
    parameters: Mapped[Dict[str, Any]] = mapped_column(JSON, default=dict)
    enabled: Mapped[bool] = mapped_column(Boolean, default=True)
    applied_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utc_now)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utc_now)
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=utc_now, onupdate=utc_now
    )

    __table_args__ = (
        UniqueConstraint("namespace", "config_id", "version", name="uq_config_namespace_id_version"),
    )


class Entity(Base):
    __tablename__ = "entities"

    id: Mapped[int] = mapped_column(primary_key=True)
    entity_id: Mapped[str] = mapped_column(String(64), unique=True, index=True)
    type: Mapped[str] = mapped_column(String(64), index=True)
    status: Mapped[str] = mapped_column(String(64), index=True, default="active")
    attributes: Mapped[Dict[str, Any]] = mapped_column(JSON, default=dict)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utc_now, index=True)
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=utc_now, onupdate=utc_now, index=True
    )


class RunInstance(Base):
    __tablename__ = "run_instances"

    id: Mapped[int] = mapped_column(primary_key=True)
    run_id: Mapped[str] = mapped_column(String(64), unique=True, index=True)
    entity_id: Mapped[str] = mapped_column(String(64), index=True)
    phase: Mapped[str] = mapped_column(String(64), index=True)
    progress: Mapped[float] = mapped_column(Float, default=0.0)
    started_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utc_now, index=True)
    completed_at: Mapped[Optional[datetime]] = mapped_column(DateTime(timezone=True), nullable=True)
    error_detail: Mapped[Optional[str]] = mapped_column(Text, nullable=True)
    metadata: Mapped[Dict[str, Any]] = mapped_column(JSON, default=dict)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utc_now)
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=utc_now, onupdate=utc_now
    )


class MetricsSnapshot(Base):
    __tablename__ = "metrics_snapshots"

    id: Mapped[int] = mapped_column(primary_key=True)
    snapshot_id: Mapped[str] = mapped_column(String(64), unique=True, index=True)
    timestamp: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utc_now, index=True)
    metrics: Mapped[Dict[str, Any]] = mapped_column(JSON, default=dict)
    dimensions: Mapped[Dict[str, Any]] = mapped_column(JSON, default=dict)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utc_now)


class WalletAddress(Base):
    __tablename__ = "wallet_addresses"

    id: Mapped[int] = mapped_column(primary_key=True)
    address_id: Mapped[str] = mapped_column(String(64), unique=True, index=True)
    address: Mapped[str] = mapped_column(String(42), unique=True, index=True)
    chain_id: Mapped[int] = mapped_column(BigInteger, index=True, default=1)
    derivation_path: Mapped[str] = mapped_column(String(128))
    index: Mapped[int] = mapped_column(Integer)
    public_key: Mapped[Optional[str]] = mapped_column(String(130), nullable=True)
    is_used: Mapped[bool] = mapped_column(Boolean, default=False, index=True)
    balance: Mapped[Optional[str]] = mapped_column(String(64), nullable=True)
    tags: Mapped[List[str]] = mapped_column(JSON, default=list)
    metadata: Mapped[Dict[str, Any]] = mapped_column(JSON, default=dict)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utc_now)
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=utc_now, onupdate=utc_now
    )

    __table_args__ = (
        Index("idx_wallet_addr_chain", "chain_id", "address"),
    )


class AddressTag(Base):
    __tablename__ = "address_tags"

    id: Mapped[int] = mapped_column(primary_key=True)
    tag_id: Mapped[str] = mapped_column(String(64), unique=True, index=True)
    address: Mapped[str] = mapped_column(String(42), index=True)
    chain_id: Mapped[int] = mapped_column(BigInteger, index=True, default=1)
    tag: Mapped[str] = mapped_column(String(64), index=True)
    label: Mapped[str] = mapped_column(String(256))
    category: Mapped[str] = mapped_column(String(64), index=True, default="general")
    metadata: Mapped[Dict[str, Any]] = mapped_column(JSON, default=dict)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utc_now)
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=utc_now, onupdate=utc_now
    )

    __table_args__ = (
        UniqueConstraint("address", "chain_id", "tag", name="uq_addr_chain_tag"),
    )


class MultiSigWallet(Base):
    __tablename__ = "multisig_wallets"

    id: Mapped[int] = mapped_column(primary_key=True)
    wallet_id: Mapped[str] = mapped_column(String(64), unique=True, index=True)
    chain_id: Mapped[int] = mapped_column(BigInteger, index=True)
    address: Mapped[str] = mapped_column(String(42), index=True)
    name: Mapped[str] = mapped_column(String(128))
    signers: Mapped[List[str]] = mapped_column(JSON, default=list)
    threshold: Mapped[int] = mapped_column(Integer, default=2)
    nonce: Mapped[int] = mapped_column(BigInteger, default=0)
    version: Mapped[str] = mapped_column(String(32), default="1.0.0")
    metadata: Mapped[Dict[str, Any]] = mapped_column(JSON, default=dict)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utc_now)
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=utc_now, onupdate=utc_now
    )

    proposals: Mapped[List["MultiSigProposal"]] = relationship(back_populates="wallet")


class MultiSigProposal(Base):
    __tablename__ = "multisig_proposals"

    id: Mapped[int] = mapped_column(primary_key=True)
    proposal_id: Mapped[str] = mapped_column(String(64), unique=True, index=True)
    wallet_id: Mapped[str] = mapped_column(String(64), ForeignKey("multisig_wallets.wallet_id"))
    chain_id: Mapped[int] = mapped_column(BigInteger, index=True)
    nonce: Mapped[int] = mapped_column(BigInteger)
    to: Mapped[str] = mapped_column(String(42))
    value: Mapped[str] = mapped_column(String(64), default="0")
    data: Mapped[str] = mapped_column(Text, default="0x")
    operation: Mapped[int] = mapped_column(Integer, default=0)
    safe_tx_gas: Mapped[str] = mapped_column(String(64), default="0")
    base_gas: Mapped[str] = mapped_column(String(64), default="0")
    gas_price: Mapped[str] = mapped_column(String(64), default="0")
    gas_token: Mapped[str] = mapped_column(String(42), default="0x0000000000000000000000000000000000000000")
    refund_receiver: Mapped[str] = mapped_column(String(42), default="0x0000000000000000000000000000000000000000")
    safe_tx_hash: Mapped[str] = mapped_column(String(66), index=True)
    status: Mapped[str] = mapped_column(
        String(32), default="pending", index=True
    )
    execution_tx_hash: Mapped[Optional[str]] = mapped_column(String(66), nullable=True)
    executed_at: Mapped[Optional[datetime]] = mapped_column(DateTime(timezone=True), nullable=True)
    expiry_block: Mapped[Optional[int]] = mapped_column(BigInteger, nullable=True)
    metadata: Mapped[Dict[str, Any]] = mapped_column(JSON, default=dict)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utc_now, index=True)
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=utc_now, onupdate=utc_now
    )

    wallet: Mapped[MultiSigWallet] = relationship(back_populates="proposals")
    signatures: Mapped[List["MultiSigSignature"]] = relationship(back_populates="proposal")


class MultiSigSignature(Base):
    __tablename__ = "multisig_signatures"

    id: Mapped[int] = mapped_column(primary_key=True)
    signature_id: Mapped[str] = mapped_column(String(64), unique=True, index=True)
    proposal_id: Mapped[str] = mapped_column(String(64), ForeignKey("multisig_proposals.proposal_id"))
    signer: Mapped[str] = mapped_column(String(42), index=True)
    signature: Mapped[str] = mapped_column(String(132))
    signature_type: Mapped[int] = mapped_column(Integer, default=1)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utc_now)

    proposal: Mapped[MultiSigProposal] = relationship(back_populates="signatures")

    __table_args__ = (
        UniqueConstraint("proposal_id", "signer", name="uq_proposal_signer"),
    )


class EventFilter(Base):
    __tablename__ = "event_filters"

    id: Mapped[int] = mapped_column(primary_key=True)
    filter_id: Mapped[str] = mapped_column(String(64), unique=True, index=True)
    chain_id: Mapped[int] = mapped_column(BigInteger, index=True)
    name: Mapped[str] = mapped_column(String(128))
    contract_address: Mapped[str] = mapped_column(String(42), index=True)
    event_signature: Mapped[str] = mapped_column(String(256), index=True)
    topics: Mapped[List[str]] = mapped_column(JSON, default=list)
    from_block: Mapped[int] = mapped_column(BigInteger, default=0)
    to_block: Mapped[Optional[int]] = mapped_column(BigInteger, nullable=True)
    last_processed_block: Mapped[int] = mapped_column(BigInteger, default=0)
    callback_url: Mapped[Optional[str]] = mapped_column(String(512), nullable=True)
    callback_headers: Mapped[Dict[str, str]] = mapped_column(JSON, default=dict)
    is_active: Mapped[bool] = mapped_column(Boolean, default=True, index=True)
    error_count: Mapped[int] = mapped_column(Integer, default=0)
    last_error: Mapped[Optional[str]] = mapped_column(Text, nullable=True)
    metadata: Mapped[Dict[str, Any]] = mapped_column(JSON, default=dict)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utc_now)
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=utc_now, onupdate=utc_now
    )


class EventLog(Base):
    __tablename__ = "event_logs"

    id: Mapped[int] = mapped_column(primary_key=True)
    log_id: Mapped[str] = mapped_column(String(64), unique=True, index=True)
    filter_id: Mapped[str] = mapped_column(String(64), ForeignKey("event_filters.filter_id"), index=True)
    chain_id: Mapped[int] = mapped_column(BigInteger, index=True)
    block_number: Mapped[int] = mapped_column(BigInteger, index=True)
    block_hash: Mapped[str] = mapped_column(String(66))
    transaction_hash: Mapped[str] = mapped_column(String(66), index=True)
    transaction_index: Mapped[int] = mapped_column(Integer)
    log_index: Mapped[int] = mapped_column(Integer)
    address: Mapped[str] = mapped_column(String(42), index=True)
    topics: Mapped[List[str]] = mapped_column(JSON, default=list)
    data: Mapped[str] = mapped_column(Text, default="0x")
    decoded_data: Mapped[Dict[str, Any]] = mapped_column(JSON, default=dict)
    removed: Mapped[bool] = mapped_column(Boolean, default=False)
    processed: Mapped[bool] = mapped_column(Boolean, default=False, index=True)
    processing_error: Mapped[Optional[str]] = mapped_column(Text, nullable=True)
    processed_at: Mapped[Optional[datetime]] = mapped_column(DateTime(timezone=True), nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utc_now, index=True)

    __table_args__ = (
        Index("idx_event_log_tx_hash_log_idx", "transaction_hash", "log_index", unique=True),
        Index("idx_event_log_chain_block", "chain_id", "block_number"),
    )


class CrossChainTransaction(Base):
    __tablename__ = "cross_chain_transactions"

    id: Mapped[int] = mapped_column(primary_key=True)
    tx_id: Mapped[str] = mapped_column(String(64), unique=True, index=True)
    source_chain_id: Mapped[int] = mapped_column(BigInteger, index=True)
    target_chain_id: Mapped[int] = mapped_column(BigInteger, index=True)
    source_tx_hash: Mapped[str] = mapped_column(String(66), index=True)
    target_tx_hash: Mapped[Optional[str]] = mapped_column(String(66), nullable=True, index=True)
    source_block_number: Mapped[int] = mapped_column(BigInteger)
    target_block_number: Mapped[Optional[int]] = mapped_column(BigInteger, nullable=True)
    sender: Mapped[str] = mapped_column(String(42), index=True)
    recipient: Mapped[str] = mapped_column(String(42), index=True)
    amount: Mapped[str] = mapped_column(String(64))
    token_address: Mapped[str] = mapped_column(String(42))
    message_hash: Mapped[str] = mapped_column(String(66), unique=True, index=True)
    message_payload: Mapped[Dict[str, Any]] = mapped_column(JSON, default=dict)
    status: Mapped[str] = mapped_column(String(32), default="pending", index=True)
    confirmations_source: Mapped[int] = mapped_column(Integer, default=0)
    confirmations_target: Mapped[int] = mapped_column(Integer, default=0)
    proof_data: Mapped[Dict[str, Any]] = mapped_column(JSON, default=dict)
    error_details: Mapped[Optional[str]] = mapped_column(Text, nullable=True)
    completed_at: Mapped[Optional[datetime]] = mapped_column(DateTime(timezone=True), nullable=True)
    metadata: Mapped[Dict[str, Any]] = mapped_column(JSON, default=dict)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utc_now, index=True)
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=utc_now, onupdate=utc_now
    )

    messages: Mapped[List["CrossChainMessage"]] = relationship(back_populates="transaction")


class CrossChainMessage(Base):
    __tablename__ = "cross_chain_messages"

    id: Mapped[int] = mapped_column(primary_key=True)
    message_id: Mapped[str] = mapped_column(String(64), unique=True, index=True)
    tx_id: Mapped[str] = mapped_column(String(64), ForeignKey("cross_chain_transactions.tx_id"))
    channel_id: Mapped[str] = mapped_column(String(64), index=True)
    message_type: Mapped[str] = mapped_column(String(64), index=True)
    payload: Mapped[Dict[str, Any]] = mapped_column(JSON, default=dict)
    signatures: Mapped[List[str]] = mapped_column(JSON, default=list)
    status: Mapped[str] = mapped_column(String(32), default="pending", index=True)
    relayer_address: Mapped[Optional[str]] = mapped_column(String(42), nullable=True)
    gas_used: Mapped[Optional[str]] = mapped_column(String(64), nullable=True)
    proof: Mapped[Dict[str, Any]] = mapped_column(JSON, default=dict)
    verified_at: Mapped[Optional[datetime]] = mapped_column(DateTime(timezone=True), nullable=True)
    metadata: Mapped[Dict[str, Any]] = mapped_column(JSON, default=dict)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utc_now)
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=utc_now, onupdate=utc_now
    )

    transaction: Mapped[CrossChainTransaction] = relationship(back_populates="messages")


class ZKPProof(Base):
    __tablename__ = "zkp_proofs"

    id: Mapped[int] = mapped_column(primary_key=True)
    proof_id: Mapped[str] = mapped_column(String(64), unique=True, index=True)
    circuit_id: Mapped[str] = mapped_column(String(64), index=True)
    proof_system: Mapped[str] = mapped_column(String(32), index=True)
    proof_data: Mapped[Dict[str, Any]] = mapped_column(JSON, default=dict)
    public_inputs: Mapped[List[str]] = mapped_column(JSON, default=list)
    verification_key_hash: Mapped[str] = mapped_column(String(66))
    is_valid: Mapped[Optional[bool]] = mapped_column(Boolean, nullable=True, index=True)
    verification_time_ms: Mapped[Optional[int]] = mapped_column(Integer, nullable=True)
    error_details: Mapped[Optional[str]] = mapped_column(Text, nullable=True)
    caller_address: Mapped[Optional[str]] = mapped_column(String(42), nullable=True)
    verified_at: Mapped[Optional[datetime]] = mapped_column(DateTime(timezone=True), nullable=True)
    metadata: Mapped[Dict[str, Any]] = mapped_column(JSON, default=dict)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utc_now, index=True)


class GasPriceHistory(Base):
    __tablename__ = "gas_price_history"

    id: Mapped[int] = mapped_column(primary_key=True)
    chain_id: Mapped[int] = mapped_column(BigInteger, index=True)
    block_number: Mapped[int] = mapped_column(BigInteger, index=True)
    timestamp: Mapped[datetime] = mapped_column(DateTime(timezone=True), index=True)
    base_fee_per_gas: Mapped[Optional[str]] = mapped_column(String(64), nullable=True)
    priority_fee_low: Mapped[str] = mapped_column(String(64))
    priority_fee_medium: Mapped[str] = mapped_column(String(64))
    priority_fee_high: Mapped[str] = mapped_column(String(64))
    gas_used_ratio: Mapped[float] = mapped_column(Float, default=0.0)
    pending_transactions: Mapped[int] = mapped_column(Integer, default=0)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utc_now)

    __table_args__ = (
        Index("idx_gas_chain_block", "chain_id", "block_number", unique=True),
    )


class IndexedBlock(Base):
    __tablename__ = "indexed_blocks"

    id: Mapped[int] = mapped_column(primary_key=True)
    chain_id: Mapped[int] = mapped_column(BigInteger, index=True)
    block_number: Mapped[int] = mapped_column(BigInteger, index=True)
    block_hash: Mapped[str] = mapped_column(String(66), unique=True, index=True)
    parent_hash: Mapped[str] = mapped_column(String(66))
    timestamp: Mapped[datetime] = mapped_column(DateTime(timezone=True), index=True)
    difficulty: Mapped[str] = mapped_column(String(64))
    total_difficulty: Mapped[str] = mapped_column(String(64))
    size: Mapped[int] = mapped_column(Integer)
    gas_limit: Mapped[str] = mapped_column(String(64))
    gas_used: Mapped[str] = mapped_column(String(64))
    base_fee_per_gas: Mapped[Optional[str]] = mapped_column(String(64), nullable=True)
    miner: Mapped[str] = mapped_column(String(42))
    extra_data: Mapped[str] = mapped_column(Text, default="0x")
    transaction_count: Mapped[int] = mapped_column(Integer, default=0)
    log_count: Mapped[int] = mapped_column(Integer, default=0)
    status: Mapped[str] = mapped_column(String(32), default="indexed", index=True)
    metadata: Mapped[Dict[str, Any]] = mapped_column(JSON, default=dict)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utc_now)

    __table_args__ = (
        Index("idx_block_chain_number", "chain_id", "block_number", unique=True),
    )


class IndexedTransaction(Base):
    __tablename__ = "indexed_transactions"

    id: Mapped[int] = mapped_column(primary_key=True)
    chain_id: Mapped[int] = mapped_column(BigInteger, index=True)
    block_number: Mapped[int] = mapped_column(BigInteger, index=True)
    tx_hash: Mapped[str] = mapped_column(String(66), unique=True, index=True)
    transaction_index: Mapped[int] = mapped_column(Integer)
    from_address: Mapped[str] = mapped_column(String(42), index=True)
    to_address: Mapped[Optional[str]] = mapped_column(String(42), nullable=True, index=True)
    value: Mapped[str] = mapped_column(String(64))
    gas: Mapped[str] = mapped_column(String(64))
    gas_price: Mapped[Optional[str]] = mapped_column(String(64), nullable=True)
    max_fee_per_gas: Mapped[Optional[str]] = mapped_column(String(64), nullable=True)
    max_priority_fee_per_gas: Mapped[Optional[str]] = mapped_column(String(64), nullable=True)
    input: Mapped[str] = mapped_column(Text, default="0x")
    nonce: Mapped[int] = mapped_column(BigInteger)
    transaction_type: Mapped[int] = mapped_column(Integer, default=0)
    status: Mapped[int] = mapped_column(Integer, nullable=True)
    gas_used: Mapped[Optional[str]] = mapped_column(String(64), nullable=True)
    cumulative_gas_used: Mapped[Optional[str]] = mapped_column(String(64), nullable=True)
    contract_address: Mapped[Optional[str]] = mapped_column(String(42), nullable=True, index=True)
    logs_count: Mapped[int] = mapped_column(Integer, default=0)
    decoded_data: Mapped[Dict[str, Any]] = mapped_column(JSON, default=dict)
    metadata: Mapped[Dict[str, Any]] = mapped_column(JSON, default=dict)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utc_now)

    __table_args__ = (
        Index("idx_tx_chain_block", "chain_id", "block_number"),
        Index("idx_tx_from", "from_address"),
        Index("idx_tx_to", "to_address"),
    )


class IndexedLog(Base):
    __tablename__ = "indexed_logs"

    id: Mapped[int] = mapped_column(primary_key=True)
    chain_id: Mapped[int] = mapped_column(BigInteger, index=True)
    block_number: Mapped[int] = mapped_column(BigInteger, index=True)
    tx_hash: Mapped[str] = mapped_column(String(66), index=True)
    log_index: Mapped[int] = mapped_column(Integer)
    address: Mapped[str] = mapped_column(String(42), index=True)
    topics: Mapped[List[str]] = mapped_column(JSON, default=list)
    data: Mapped[str] = mapped_column(Text, default="0x")
    removed: Mapped[bool] = mapped_column(Boolean, default=False)
    event_signature: Mapped[Optional[str]] = mapped_column(String(256), nullable=True, index=True)
    decoded_data: Mapped[Dict[str, Any]] = mapped_column(JSON, default=dict)
    metadata: Mapped[Dict[str, Any]] = mapped_column(JSON, default=dict)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utc_now)

    __table_args__ = (
        Index("idx_log_tx_hash_log_idx", "tx_hash", "log_index", unique=True),
        Index("idx_log_chain_block", "chain_id", "block_number"),
        Index("idx_log_address", "address"),
    )


class StoredContent(Base):
    __tablename__ = "stored_contents"

    id: Mapped[int] = mapped_column(primary_key=True)
    content_id: Mapped[str] = mapped_column(String(64), unique=True, index=True)
    cid: Mapped[str] = mapped_column(String(256), index=True)
    storage_network: Mapped[str] = mapped_column(String(32), index=True, default="ipfs")
    content_hash: Mapped[str] = mapped_column(String(66), index=True)
    content_type: Mapped[str] = mapped_column(String(128), default="application/octet-stream")
    size_bytes: Mapped[int] = mapped_column(BigInteger)
    name: Mapped[Optional[str]] = mapped_column(String(256), nullable=True)
    description: Mapped[Optional[str]] = mapped_column(Text, nullable=True)
    is_pinned: Mapped[bool] = mapped_column(Boolean, default=True)
    pin_providers: Mapped[List[str]] = mapped_column(JSON, default=list)
    access_url: Mapped[Optional[str]] = mapped_column(String(512), nullable=True)
    metadata: Mapped[Dict[str, Any]] = mapped_column(JSON, default=dict)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utc_now)
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=utc_now, onupdate=utc_now
    )

    __table_args__ = (
        UniqueConstraint("cid", "storage_network", name="uq_cid_network"),
    )
