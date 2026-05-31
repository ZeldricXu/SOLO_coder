from typing import NewType, Union, Literal
from datetime import datetime, timezone
from enum import Enum


ChainId = NewType("ChainId", int)
Address = NewType("Address", str)
Hash = NewType("Hash", str)
HexStr = NewType("HexStr", str)
Wei = NewType("Wei", int)
Gwei = NewType("Gwei", int)
BlockNumber = NewType("BlockNumber", int)
Timestamp = NewType("Timestamp", int)


class ChainNetwork(str, Enum):
    ETHEREUM = "ethereum"
    SEPOLIA = "sepolia"
    POLYGON = "polygon"
    BSC = "bsc"
    ARBITRUM = "arbitrum"
    OPTIMISM = "optimism"
    BASE = "base"


class TransactionStatus(str, Enum):
    PENDING = "pending"
    SIGNED = "signed"
    BROADCAST = "broadcast"
    CONFIRMED = "confirmed"
    FAILED = "failed"
    REJECTED = "rejected"


class MultiSigStatus(str, Enum):
    PENDING = "pending"
    PARTIALLY_SIGNED = "partially_signed"
    FULLY_SIGNED = "fully_signed"
    EXECUTED = "executed"
    REJECTED = "rejected"


class CrossChainStatus(str, Enum):
    INITIATED = "initiated"
    LOCKED = "locked"
    VERIFIED = "verified"
    MINTED = "minted"
    COMPLETED = "completed"
    FAILED = "failed"


class StorageNetwork(str, Enum):
    IPFS = "ipfs"
    ARWEAVE = "arweave"
    FILECOIN = "filecoin"


class EventStatus(str, Enum):
    ACTIVE = "active"
    PAUSED = "paused"
    COMPLETED = "completed"
    FAILED = "failed"


class IndexerStatus(str, Enum):
    RUNNING = "running"
    SYNCING = "syncing"
    PAUSED = "paused"
    ERROR = "error"


def utc_now() -> datetime:
    return datetime.now(timezone.utc)


def utc_timestamp() -> int:
    return int(utc_now().timestamp())
