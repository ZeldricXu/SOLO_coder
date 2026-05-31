"""
Test Data Builder Module
Centralized management for constructing test data across all test modules.
"""
import json
import random
import string
from abc import ABC, abstractmethod
from dataclasses import dataclass, field, asdict
from datetime import datetime, timedelta
from typing import Any, Dict, List, Optional, TypeVar, Generic, Callable

from faker import Faker

T = TypeVar('T')
fake = Faker()


class BaseBuilder(ABC, Generic[T]):
    """Abstract base class for all test data builders."""

    def __init__(self):
        self._data: Dict[str, Any] = {}
        self._faker = Faker()

    @abstractmethod
    def build(self) -> T:
        """Build and return the constructed object."""
        pass

    def with_field(self, field_name: str, value: Any) -> 'BaseBuilder':
        """Set a specific field value."""
        self._data[field_name] = value
        return self

    def with_overrides(self, **kwargs) -> 'BaseBuilder':
        """Set multiple field values at once."""
        self._data.update(kwargs)
        return self

    def reset(self) -> 'BaseBuilder':
        """Reset the builder to initial state."""
        self._data = {}
        return self


# =============================================================================
# ZKP Module Builders
# =============================================================================

@dataclass
class ZkpProofData:
    """Data class representing ZKP proof data."""
    proof_id: str
    circuit_id: str
    proof_data: str
    public_inputs: List[str]
    verify_result: Optional[str] = None
    verify_time_ms: Optional[int] = None
    error_message: Optional[str] = None
    status: str = "PENDING"


class ZkpProofBuilder(BaseBuilder[ZkpProofData]):
    """Builder for ZKP proof test data."""

    def __init__(self):
        super().__init__()
        self._data = {
            "circuit_id": "circuit_groth16_default",
            "proof_data": self._generate_valid_proof_data(),
            "public_inputs": ["1", "2", "3"],
        }

    def _generate_valid_proof_data(self) -> str:
        """Generate a valid-looking proof data string."""
        proof_obj = {
            "pi_a": [fake.sha256(), fake.sha256(), "1"],
            "pi_b": [[fake.sha256(), fake.sha256()], [fake.sha256(), fake.sha256()], ["1", "0"]],
            "pi_c": [fake.sha256(), fake.sha256(), "1"],
            "protocol": "groth16",
            "curve": "bn254"
        }
        return json.dumps(proof_obj)

    def with_valid_proof(self) -> 'ZkpProofBuilder':
        """Configure with a valid proof that should pass verification."""
        self._data["proof_data"] = self._generate_valid_proof_data()
        return self

    def with_invalid_proof(self) -> 'ZkpProofBuilder':
        """Configure with an invalid proof that should fail verification."""
        self._data["proof_data"] = json.dumps({
            "pi_a": ["invalid", "data", "1"],
            "pi_b": [["invalid", "data"], ["invalid", "data"], ["1", "0"]],
            "pi_c": ["invalid", "data", "1"],
        })
        return self

    def with_empty_proof(self) -> 'ZkpProofBuilder':
        """Configure with empty proof data."""
        self._data["proof_data"] = ""
        return self

    def with_short_proof(self) -> 'ZkpProofBuilder':
        """Configure with a too-short proof data."""
        self._data["proof_data"] = "short"
        return self

    def with_malformed_json(self) -> 'ZkpProofBuilder':
        """Configure with malformed JSON proof data."""
        self._data["proof_data"] = "{invalid json"
        return self

    def with_circuit(self, circuit_id: str) -> 'ZkpProofBuilder':
        """Set a specific circuit ID."""
        self._data["circuit_id"] = circuit_id
        return self

    def with_public_inputs(self, inputs: List[str]) -> 'ZkpProofBuilder':
        """Set specific public inputs."""
        self._data["public_inputs"] = inputs
        return self

    def build(self) -> ZkpProofData:
        proof_id = "proof_" + fake.sha256()[:16]
        return ZkpProofData(
            proof_id=proof_id,
            circuit_id=self._data.get("circuit_id", "circuit_groth16_default"),
            proof_data=self._data.get("proof_data", self._generate_valid_proof_data()),
            public_inputs=self._data.get("public_inputs", ["1", "2", "3"]),
        )

    def build_request_dict(self) -> Dict[str, Any]:
        """Build a request dictionary suitable for API calls."""
        data = self.build()
        return {
            "circuitId": data.circuit_id,
            "proofData": data.proof_data,
            "publicInputs": data.public_inputs,
            "userId": "user_test_001",
            "traceId": "trace_" + fake.sha256()[:16]
        }


# =============================================================================
# HD Wallet Module Builders
# =============================================================================

@dataclass
class HdWalletData:
    """Data class representing HD wallet data."""
    wallet_id: str
    chain_type: str
    derivation_path: str
    address: str
    public_key: str
    private_key_encrypted: str
    label: Optional[str] = None
    tags: List[str] = field(default_factory=list)
    user_id: Optional[str] = None
    status: str = "ACTIVE"


@dataclass
class AddressBookData:
    """Data class representing address book entry."""
    id: str
    address: str
    chain_type: str
    name: str
    label: Optional[str] = None
    tags: List[str] = field(default_factory=list)
    user_id: Optional[str] = None
    is_whitelist: bool = False
    is_blacklist: bool = False


class HdWalletBuilder(BaseBuilder[HdWalletData]):
    """Builder for HD wallet test data."""

    def __init__(self):
        super().__init__()
        self._data = {
            "chain_type": "ETH",
            "mnemonic": None,
            "derivation_path": None,
            "index": 0,
            "label": "Default Wallet",
            "tags": ["default"],
        }

    def for_eth(self) -> 'HdWalletBuilder':
        """Configure for Ethereum chain."""
        self._data["chain_type"] = "ETH"
        self._data["derivation_path"] = "m/44'/60'/0'/0/0"
        return self

    def for_btc(self) -> 'HdWalletBuilder':
        """Configure for Bitcoin chain."""
        self._data["chain_type"] = "BTC"
        self._data["derivation_path"] = "m/44'/0'/0'/0/0"
        return self

    def for_polygon(self) -> 'HdWalletBuilder':
        """Configure for Polygon chain."""
        self._data["chain_type"] = "POLYGON"
        self._data["derivation_path"] = "m/44'/60'/0'/0/0"
        return self

    def with_index(self, index: int) -> 'HdWalletBuilder':
        """Set the derivation index."""
        self._data["index"] = index
        chain = self._data.get("chain_type", "ETH")
        base_path = "m/44'/60'/0'/0/" if chain != "BTC" else "m/44'/0'/0'/0/"
        self._data["derivation_path"] = f"{base_path}{index}"
        return self

    def with_label(self, label: str) -> 'HdWalletBuilder':
        """Set wallet label."""
        self._data["label"] = label
        return self

    def with_tags(self, tags: List[str]) -> 'HdWalletBuilder':
        """Set wallet tags."""
        self._data["tags"] = tags
        return self

    def _generate_eth_address(self) -> str:
        """Generate a valid-looking Ethereum address."""
        return "0x" + fake.sha256()[:40]

    def _generate_btc_address(self) -> str:
        """Generate a valid-looking Bitcoin address."""
        return "bc1" + ''.join(random.choices(string.ascii_lowercase + string.digits, k=38))

    def _generate_public_key(self) -> str:
        """Generate a valid-looking public key."""
        return "04" + fake.sha256() + fake.sha256()

    def build(self) -> HdWalletData:
        wallet_id = "wallet_" + fake.uuid4().replace("-", "")[:16]
        chain_type = self._data.get("chain_type", "ETH")

        if chain_type == "BTC":
            address = self._generate_btc_address()
        else:
            address = self._generate_eth_address()

        return HdWalletData(
            wallet_id=wallet_id,
            chain_type=chain_type,
            derivation_path=self._data.get("derivation_path", "m/44'/60'/0'/0/0"),
            address=address,
            public_key=self._generate_public_key(),
            private_key_encrypted="enc_" + fake.sha256(),
            label=self._data.get("label"),
            tags=self._data.get("tags", []),
        )

    def build_request_dict(self, user_id: str = None) -> Dict[str, Any]:
        """Build a request dictionary suitable for API calls."""
        return {
            "chainType": self._data.get("chain_type", "ETH"),
            "derivationPath": self._data.get("derivation_path"),
            "index": self._data.get("index", 0),
            "label": self._data.get("label"),
            "tags": self._data.get("tags", []),
            "userId": user_id or "user_test_001"
        }


class AddressBookBuilder(BaseBuilder[AddressBookData]):
    """Builder for address book test data."""

    def __init__(self):
        super().__init__()
        self._data = {
            "chain_type": "ETH",
            "name": "Test Contact",
            "label": "friend",
            "tags": ["personal"],
            "is_whitelist": False,
            "is_blacklist": False,
        }

    def for_eth(self) -> 'AddressBookBuilder':
        """Configure for Ethereum address."""
        self._data["chain_type"] = "ETH"
        return self

    def for_btc(self) -> 'AddressBookBuilder':
        """Configure for Bitcoin address."""
        self._data["chain_type"] = "BTC"
        return self

    def with_whitelist(self, enabled: bool = True) -> 'AddressBookBuilder':
        """Set whitelist status."""
        self._data["is_whitelist"] = enabled
        return self

    def with_blacklist(self, enabled: bool = True) -> 'AddressBookBuilder':
        """Set blacklist status."""
        self._data["is_blacklist"] = enabled
        return self

    def build(self) -> AddressBookData:
        chain_type = self._data.get("chain_type", "ETH")
        if chain_type == "BTC":
            address = "bc1" + ''.join(random.choices(string.ascii_lowercase + string.digits, k=38))
        else:
            address = "0x" + fake.sha256()[:40]

        return AddressBookData(
            id="addr_" + fake.uuid4().replace("-", "")[:16],
            address=address,
            chain_type=chain_type,
            name=self._data.get("name", "Test Contact"),
            label=self._data.get("label"),
            tags=self._data.get("tags", []),
            is_whitelist=self._data.get("is_whitelist", False),
            is_blacklist=self._data.get("is_blacklist", False),
        )

    def build_request_dict(self, user_id: str = None) -> Dict[str, Any]:
        """Build a request dictionary suitable for API calls."""
        data = self.build()
        return {
            "address": data.address,
            "chainType": data.chain_type,
            "name": data.name,
            "label": data.label,
            "tags": data.tags,
            "isWhitelist": data.is_whitelist,
            "isBlacklist": data.is_blacklist,
            "userId": user_id or "user_test_001"
        }


# =============================================================================
# Block Indexer Module Builders
# =============================================================================

@dataclass
class BlockData:
    """Data class representing block data."""
    chain_type: str
    block_number: int
    block_hash: str
    parent_hash: str
    miner: str
    timestamp: int
    transaction_count: int
    gas_limit: str
    gas_used: str
    extra_data: Optional[str] = None
    transactions: List[Dict[str, Any]] = field(default_factory=list)


@dataclass
class TransactionData:
    """Data class representing transaction data."""
    tx_hash: str
    tx_index: int
    from_address: str
    to_address: str
    value: str
    gas_price: str
    gas_limit: str
    gas_used: str
    input_data: str
    status: str
    contract_address: Optional[str] = None


class BlockDataBuilder(BaseBuilder[BlockData]):
    """Builder for block indexer test data."""

    def __init__(self):
        super().__init__()
        self._data = {
            "chain_type": "ETH",
            "block_number": 1000000,
            "transaction_count": 10,
            "include_transactions": True,
        }

    def for_eth(self) -> 'BlockDataBuilder':
        """Configure for Ethereum chain."""
        self._data["chain_type"] = "ETH"
        return self

    def for_polygon(self) -> 'BlockDataBuilder':
        """Configure for Polygon chain."""
        self._data["chain_type"] = "POLYGON"
        return self

    def with_block_number(self, block_number: int) -> 'BlockDataBuilder':
        """Set specific block number."""
        self._data["block_number"] = block_number
        return self

    def with_transaction_count(self, count: int) -> 'BlockDataBuilder':
        """Set number of transactions in block."""
        self._data["transaction_count"] = count
        return self

    def with_empty_transactions(self) -> 'BlockDataBuilder':
        """Configure block with no transactions."""
        self._data["transaction_count"] = 0
        self._data["include_transactions"] = False
        return self

    def with_large_block(self, tx_count: int = 200) -> 'BlockDataBuilder':
        """Configure a large block with many transactions."""
        self._data["transaction_count"] = tx_count
        self._data["include_transactions"] = True
        return self

    def _generate_tx_hash(self) -> str:
        """Generate a valid-looking transaction hash."""
        return "0x" + fake.sha256()

    def _generate_address(self) -> str:
        """Generate a valid-looking address."""
        return "0x" + fake.sha256()[:40]

    def build(self) -> BlockData:
        block_number = self._data.get("block_number", 1000000)
        tx_count = self._data.get("transaction_count", 10)
        include_txs = self._data.get("include_transactions", True)

        transactions = []
        if include_txs:
            for i in range(tx_count):
                transactions.append({
                    "txHash": self._generate_tx_hash(),
                    "txIndex": i,
                    "fromAddress": self._generate_address(),
                    "toAddress": self._generate_address(),
                    "value": "0x" + hex(random.randint(0, 10**18))[2:],
                    "gasPrice": "0x" + hex(random.randint(10**9, 10**11))[2:],
                    "gasLimit": "0x5208",
                    "gasUsed": "0x5208",
                    "inputData": "0x",
                    "status": "success",
                    "contractAddress": None,
                })

        return BlockData(
            chain_type=self._data.get("chain_type", "ETH"),
            block_number=block_number,
            block_hash="0x" + fake.sha256(),
            parent_hash="0x" + fake.sha256(),
            miner=self._generate_address(),
            timestamp=int(datetime.utcnow().timestamp()),
            transaction_count=tx_count,
            gas_limit="0x1c9c380",
            gas_used="0x" + hex(random.randint(10**6, 10**7))[2:],
            extra_data="0x" + fake.sha256()[:16],
            transactions=transactions,
        )

    def build_request_dict(self) -> Dict[str, Any]:
        """Build a request dictionary suitable for API calls."""
        data = self.build()
        return {
            "chainType": data.chain_type,
            "blockNumber": data.block_number,
            "blockHash": data.block_hash,
            "parentHash": data.parent_hash,
            "miner": data.miner,
            "timestamp": data.timestamp,
            "gasLimit": data.gas_limit,
            "gasUsed": data.gas_used,
            "extraData": data.extra_data,
            "transactions": data.transactions,
        }


# =============================================================================
# Builder Factory
# =============================================================================

class BuilderFactory:
    """Factory for creating builder instances."""

    @staticmethod
    def zkp_proof() -> ZkpProofBuilder:
        """Create a ZKP proof builder."""
        return ZkpProofBuilder()

    @staticmethod
    def hd_wallet() -> HdWalletBuilder:
        """Create an HD wallet builder."""
        return HdWalletBuilder()

    @staticmethod
    def address_book() -> AddressBookBuilder:
        """Create an address book builder."""
        return AddressBookBuilder()

    @staticmethod
    def block_data() -> BlockDataBuilder:
        """Create a block data builder."""
        return BlockDataBuilder()

    @staticmethod
    def bulk_build(builder: BaseBuilder, count: int,
                   modifier: Optional[Callable[[int, BaseBuilder], None]] = None) -> List[Any]:
        """Build multiple instances with optional modification per instance."""
        results = []
        for i in range(count):
            builder.reset()
            if modifier:
                modifier(i, builder)
            results.append(builder.build())
        return results
