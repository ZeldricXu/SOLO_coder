from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional


@dataclass
class CreateWalletRequest:
    chain_id: int
    name: str
    signers: List[str]
    threshold: int
    version: str = "1.0.0"


@dataclass
class CreateProposalRequest:
    wallet_id: str
    to: str
    value: int
    data: str
    operation: int = 0
    safe_tx_gas: int = 0
    base_gas: int = 0
    gas_price: int = 0
    gas_token: str = "0x0000000000000000000000000000000000000000"
    refund_receiver: str = "0x0000000000000000000000000000000000000000"


@dataclass
class AddSignatureRequest:
    proposal_id: str
    signer: str
    signature: str
    signature_type: int = 1


@dataclass
class FilterConfig:
    chain_id: int
    contract_address: str
    event_signature: str
    topics: List[str] = field(default_factory=list)
    from_block: int = 0
    to_block: Optional[int] = None
    callback_url: Optional[str] = None
    callback_headers: Dict[str, str] = field(default_factory=dict)
    name: Optional[str] = None
    strategy: Optional[str] = None


@dataclass
class DecodedEvent:
    name: str
    signature: str
    data: Dict[str, Any]
    topics: List[str]
    raw_data: str


@dataclass
class BridgeRequest:
    source_chain_id: int
    target_chain_id: int
    sender: str
    recipient: str
    amount: int
    token_address: str
    message_payload: Dict[str, Any] = field(default_factory=dict)
    relayer_fee: int = 0


@dataclass
class MessageProof:
    message_hash: str
    proof_data: Dict[str, Any]
    signatures: List[str]
    merkle_proof: Optional[List[str]] = None
