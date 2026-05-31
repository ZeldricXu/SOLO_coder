from datetime import datetime
from typing import Any, Dict, Optional
from sqlalchemy import (
    BigInteger,
    Boolean,
    DateTime,
    Float,
    ForeignKey,
    Index,
    Integer,
    JSON,
    String,
    Text,
    UniqueConstraint,
)
from sqlalchemy.orm import Mapped, mapped_column, relationship
from sqlalchemy.dialects.postgresql import JSONB

from .base import Base


class Entity(Base):
    __tablename__ = "entities"

    id: Mapped[str] = mapped_column(String(64), primary_key=True)
    type: Mapped[str] = mapped_column(String(32), index=True)
    status: Mapped[str] = mapped_column(String(32), index=True, default="active")
    attributes: Mapped[Dict[str, Any]] = mapped_column(
        JSON().with_variant(JSONB, "postgresql"), default_factory=dict
    )
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=datetime.utcnow)
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=datetime.utcnow, onupdate=datetime.utcnow
    )


class Config(Base):
    __tablename__ = "configs"

    config_id: Mapped[str] = mapped_column(String(64), primary_key=True)
    namespace: Mapped[str] = mapped_column(String(64), index=True)
    version: Mapped[int] = mapped_column(Integer, default=1)
    parameters: Mapped[Dict[str, Any]] = mapped_column(
        JSON().with_variant(JSONB, "postgresql"), default_factory=dict
    )
    enabled: Mapped[bool] = mapped_column(Boolean, default=True)
    applied_at: Mapped[Optional[datetime]] = mapped_column(DateTime(timezone=True))
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=datetime.utcnow)

    __table_args__ = (
        UniqueConstraint("namespace", "version", name="uix_namespace_version"),
    )


class RunInstance(Base):
    __tablename__ = "run_instances"

    run_id: Mapped[str] = mapped_column(String(64), primary_key=True)
    entity_id: Mapped[str] = mapped_column(String(64), ForeignKey("entities.id"), index=True)
    phase: Mapped[str] = mapped_column(String(32), index=True)
    progress: Mapped[float] = mapped_column(Float, default=0.0)
    started_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=datetime.utcnow)
    completed_at: Mapped[Optional[datetime]] = mapped_column(DateTime(timezone=True))
    error_detail: Mapped[Optional[Dict[str, Any]]] = mapped_column(
        JSON().with_variant(JSONB, "postgresql")
    )


class Snapshot(Base):
    __tablename__ = "snapshots"

    snapshot_id: Mapped[str] = mapped_column(String(64), primary_key=True)
    timestamp: Mapped[datetime] = mapped_column(DateTime(timezone=True), index=True)
    metrics: Mapped[Dict[str, Any]] = mapped_column(
        JSON().with_variant(JSONB, "postgresql"), default_factory=dict
    )
    dimensions: Mapped[Dict[str, Any]] = mapped_column(
        JSON().with_variant(JSONB, "postgresql"), default_factory=dict
    )


class TransactionRecord(Base):
    __tablename__ = "transactions"

    tx_id: Mapped[str] = mapped_column(String(64), primary_key=True)
    chain: Mapped[str] = mapped_column(String(32), index=True)
    from_address: Mapped[str] = mapped_column(String(42), index=True)
    to_address: Mapped[str] = mapped_column(String(42), index=True)
    value: Mapped[int] = mapped_column(BigInteger, default=0)
    data: Mapped[Optional[str]] = mapped_column(Text)
    nonce: Mapped[int] = mapped_column(Integer, default=0)
    gas_limit: Mapped[int] = mapped_column(BigInteger, default=21000)
    gas_price: Mapped[Optional[int]] = mapped_column(BigInteger)
    max_fee_per_gas: Mapped[Optional[int]] = mapped_column(BigInteger)
    max_priority_fee_per_gas: Mapped[Optional[int]] = mapped_column(BigInteger)
    tx_hash: Mapped[Optional[str]] = mapped_column(String(66), index=True)
    status: Mapped[str] = mapped_column(String(32), index=True, default="pending")
    block_number: Mapped[Optional[int]] = mapped_column(BigInteger, index=True)
    signers: Mapped[Dict[str, Any]] = mapped_column(
        JSON().with_variant(JSONB, "postgresql"), default_factory=list
    )
    multi_sig_threshold: Mapped[Optional[int]] = mapped_column(Integer)
    raw_tx: Mapped[Optional[str]] = mapped_column(Text)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=datetime.utcnow)
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=datetime.utcnow, onupdate=datetime.utcnow
    )

    __table_args__ = (
        Index("ix_chain_from_address", "chain", "from_address"),
        Index("ix_chain_status", "chain", "status"),
    )


class CrossChainTransfer(Base):
    __tablename__ = "cross_chain_transfers"

    transfer_id: Mapped[str] = mapped_column(String(64), primary_key=True)
    source_chain: Mapped[str] = mapped_column(String(32), index=True)
    target_chain: Mapped[str] = mapped_column(String(32), index=True)
    source_address: Mapped[str] = mapped_column(String(42))
    target_address: Mapped[str] = mapped_column(String(42))
    token_address: Mapped[str] = mapped_column(String(42))
    amount: Mapped[int] = mapped_column(BigInteger)
    source_tx_hash: Mapped[Optional[str]] = mapped_column(String(66), index=True)
    target_tx_hash: Mapped[Optional[str]] = mapped_column(String(66), index=True)
    message_hash: Mapped[Optional[str]] = mapped_column(String(66), index=True)
    status: Mapped[str] = mapped_column(String(32), index=True, default="initiated")
    proof_data: Mapped[Optional[Dict[str, Any]]] = mapped_column(
        JSON().with_variant(JSONB, "postgresql")
    )
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=datetime.utcnow)
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=datetime.utcnow, onupdate=datetime.utcnow
    )


class StoredContent(Base):
    __tablename__ = "stored_content"

    content_id: Mapped[str] = mapped_column(String(128), primary_key=True)
    network: Mapped[str] = mapped_column(String(32), index=True)
    cid: Mapped[str] = mapped_column(String(128), index=True)
    content_hash: Mapped[str] = mapped_column(String(64), index=True)
    content_type: Mapped[str] = mapped_column(String(64))
    size: Mapped[int] = mapped_column(BigInteger, default=0)
    pinned: Mapped[bool] = mapped_column(Boolean, default=True)
    pin_service: Mapped[Optional[str]] = mapped_column(String(64))
    metadata: Mapped[Dict[str, Any]] = mapped_column(
        JSON().with_variant(JSONB, "postgresql"), default_factory=dict
    )
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=datetime.utcnow)

    __table_args__ = (
        Index("ix_network_cid", "network", "cid"),
    )


class EventListener(Base):
    __tablename__ = "event_listeners"

    listener_id: Mapped[str] = mapped_column(String(64), primary_key=True)
    chain: Mapped[str] = mapped_column(String(32), index=True)
    contract_address: Mapped[str] = mapped_column(String(42), index=True)
    event_name: Mapped[str] = mapped_column(String(64))
    event_abi: Mapped[Dict[str, Any]] = mapped_column(
        JSON().with_variant(JSONB, "postgresql")
    )
    start_block: Mapped[int] = mapped_column(BigInteger, default=0)
    current_block: Mapped[int] = mapped_column(BigInteger, default=0)
    status: Mapped[str] = mapped_column(String(32), index=True, default="active")
    callback_url: Mapped[Optional[str]] = mapped_column(String(255))
    callback_headers: Mapped[Optional[Dict[str, Any]]] = mapped_column(
        JSON().with_variant(JSONB, "postgresql"), default_factory=dict
    )
    filter_params: Mapped[Dict[str, Any]] = mapped_column(
        JSON().with_variant(JSONB, "postgresql"), default_factory=dict
    )
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=datetime.utcnow)
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=datetime.utcnow, onupdate=datetime.utcnow
    )


class EventLog(Base):
    __tablename__ = "event_logs"

    log_id: Mapped[str] = mapped_column(String(64), primary_key=True)
    listener_id: Mapped[str] = mapped_column(String(64), ForeignKey("event_listeners.listener_id"), index=True)
    chain: Mapped[str] = mapped_column(String(32), index=True)
    block_number: Mapped[int] = mapped_column(BigInteger, index=True)
    block_hash: Mapped[str] = mapped_column(String(66))
    transaction_hash: Mapped[str] = mapped_column(String(66), index=True)
    log_index: Mapped[int] = mapped_column(Integer)
    contract_address: Mapped[str] = mapped_column(String(42))
    event_name: Mapped[str] = mapped_column(String(64))
    args: Mapped[Dict[str, Any]] = mapped_column(
        JSON().with_variant(JSONB, "postgresql"), default_factory=dict
    )
    processed: Mapped[bool] = mapped_column(Boolean, default=False)
    processed_at: Mapped[Optional[datetime]] = mapped_column(DateTime(timezone=True))
    callback_status: Mapped[Optional[str]] = mapped_column(String(32))
    callback_response: Mapped[Optional[Dict[str, Any]]] = mapped_column(
        JSON().with_variant(JSONB, "postgresql")
    )
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=datetime.utcnow)

    __table_args__ = (
        UniqueConstraint(
            "chain", "transaction_hash", "log_index",
            name="uix_chain_tx_log"
        ),
        Index("ix_block_processed", "block_number", "processed"),
    )


class HDWallet(Base):
    __tablename__ = "hd_wallets"

    wallet_id: Mapped[str] = mapped_column(String(64), primary_key=True)
    name: Mapped[str] = mapped_column(String(128))
    mnemonic_encrypted: Mapped[Optional[str]] = mapped_column(Text)
    master_xpub: Mapped[str] = mapped_column(String(256), index=True)
    derivation_path: Mapped[str] = mapped_column(String(64), default="m/44'/60'/0'/0")
    chain_code: Mapped[str] = mapped_column(String(64))
    parent_fingerprint: Mapped[str] = mapped_column(String(8))
    depth: Mapped[int] = mapped_column(Integer, default=0)
    network: Mapped[str] = mapped_column(String(32), default="ethereum")
    is_encrypted: Mapped[bool] = mapped_column(Boolean, default=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=datetime.utcnow)


class AddressBookEntry(Base):
    __tablename__ = "address_book"

    entry_id: Mapped[str] = mapped_column(String(64), primary_key=True)
    wallet_id: Mapped[Optional[str]] = mapped_column(String(64), ForeignKey("hd_wallets.wallet_id"), index=True)
    chain: Mapped[str] = mapped_column(String(32), index=True)
    address: Mapped[str] = mapped_column(String(42), index=True)
    path: Mapped[str] = mapped_column(String(64))
    label: Mapped[Optional[str]] = mapped_column(String(128), index=True)
    tags: Mapped[Dict[str, Any]] = mapped_column(
        JSON().with_variant(JSONB, "postgresql"), default_factory=list
    )
    metadata: Mapped[Dict[str, Any]] = mapped_column(
        JSON().with_variant(JSONB, "postgresql"), default_factory=dict
    )
    is_own: Mapped[bool] = mapped_column(Boolean, default=False)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=datetime.utcnow)
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=datetime.utcnow, onupdate=datetime.utcnow
    )

    __table_args__ = (
        UniqueConstraint("wallet_id", "chain", "path", name="uix_wallet_chain_path"),
    )


class GasPriceRecord(Base):
    __tablename__ = "gas_price_records"

    record_id: Mapped[str] = mapped_column(String(64), primary_key=True)
    chain: Mapped[str] = mapped_column(String(32), index=True)
    block_number: Mapped[int] = mapped_column(BigInteger, index=True)
    timestamp: Mapped[int] = mapped_column(BigInteger, index=True)
    slow: Mapped[int] = mapped_column(BigInteger)
    standard: Mapped[int] = mapped_column(BigInteger)
    fast: Mapped[int] = mapped_column(BigInteger)
    base_fee: Mapped[Optional[int]] = mapped_column(BigInteger)
    priority_fee_low: Mapped[Optional[int]] = mapped_column(BigInteger)
    priority_fee_med: Mapped[Optional[int]] = mapped_column(BigInteger)
    priority_fee_high: Mapped[Optional[int]] = mapped_column(BigInteger)


class IndexedBlock(Base):
    __tablename__ = "indexed_blocks"

    chain: Mapped[str] = mapped_column(String(32), primary_key=True)
    block_number: Mapped[int] = mapped_column(BigInteger, primary_key=True)
    block_hash: Mapped[str] = mapped_column(String(66), index=True)
    parent_hash: Mapped[str] = mapped_column(String(66))
    timestamp: Mapped[int] = mapped_column(BigInteger, index=True)
    difficulty: Mapped[int] = mapped_column(BigInteger)
    total_difficulty: Mapped[Optional[str]] = mapped_column(String(64))
    gas_limit: Mapped[int] = mapped_column(BigInteger)
    gas_used: Mapped[int] = mapped_column(BigInteger)
    base_fee_per_gas: Mapped[Optional[int]] = mapped_column(BigInteger)
    miner: Mapped[str] = mapped_column(String(42))
    extra_data: Mapped[Optional[str]] = mapped_column(Text)
    transaction_count: Mapped[int] = mapped_column(Integer, default=0)
    indexed_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=datetime.utcnow)

    __table_args__ = (
        Index("ix_chain_timestamp", "chain", "timestamp"),
    )


class IndexedTransaction(Base):
    __tablename__ = "indexed_transactions"

    chain: Mapped[str] = mapped_column(String(32), primary_key=True)
    tx_hash: Mapped[str] = mapped_column(String(66), primary_key=True)
    block_number: Mapped[int] = mapped_column(BigInteger, index=True)
    transaction_index: Mapped[int] = mapped_column(Integer)
    from_address: Mapped[str] = mapped_column(String(42), index=True)
    to_address: Mapped[Optional[str]] = mapped_column(String(42), index=True)
    value: Mapped[int] = mapped_column(BigInteger)
    input: Mapped[Optional[str]] = mapped_column(Text)
    gas: Mapped[int] = mapped_column(BigInteger)
    gas_price: Mapped[int] = mapped_column(BigInteger)
    max_fee_per_gas: Mapped[Optional[int]] = mapped_column(BigInteger)
    max_priority_fee_per_gas: Mapped[Optional[int]] = mapped_column(BigInteger)
    nonce: Mapped[int] = mapped_column(Integer)
    status: Mapped[Optional[int]] = mapped_column(Integer)
    contract_address: Mapped[Optional[str]] = mapped_column(String(42), index=True)
    logs_bloom: Mapped[Optional[str]] = mapped_column(Text)
    decoded_method: Mapped[Optional[str]] = mapped_column(String(64))
    decoded_params: Mapped[Optional[Dict[str, Any]]] = mapped_column(
        JSON().with_variant(JSONB, "postgresql")
    )
    indexed_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=datetime.utcnow)

    __table_args__ = (
        Index("ix_block_number_index", "block_number", "transaction_index"),
        Index("ix_from_to", "from_address", "to_address"),
    )
