from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional
import hashlib
from eth_account import Account
from eth_account.messages import encode_defunct

from wallethub.core import CrossChainError
from wallethub.utils import generate_id


@dataclass
class CrossChainMessage:
    message_id: str = field(default_factory=lambda: generate_id("msg"))
    source_chain: str = ""
    target_chain: str = ""
    source_tx_hash: str = ""
    target_address: str = ""
    payload: Dict[str, Any] = field(default_factory=dict)
    block_number: int = 0
    timestamp: int = 0
    signatures: List[Dict[str, str]] = field(default_factory=list)
    merkle_proof: Optional[Dict[str, Any]] = None

    def hash(self) -> str:
        message = (
            f"{self.source_chain}:{self.target_chain}:{self.source_tx_hash}:"
            f"{self.target_address}:{self.payload}:{self.block_number}:{self.timestamp}"
        )
        return "0x" + hashlib.sha256(str(message).encode()).hexdigest()


class MessageVerifier:
    def __init__(self, required_signatures: int = 2):
        self.required_signatures = required_signatures
        self._trusted_validators: List[str] = []
        self._verified_messages: Dict[str, CrossChainMessage] = {}

    def add_trusted_validator(self, address: str) -> None:
        address = address.lower()
        if address not in self._trusted_validators:
            self._trusted_validators.append(address)

    def remove_trusted_validator(self, address: str) -> None:
        address = address.lower()
        if address in self._trusted_validators:
            self._trusted_validators.remove(address)

    def get_trusted_validators(self) -> List[str]:
        return self._trusted_validators.copy()

    def sign_message(self, message: CrossChainMessage, private_key: str) -> str:
        msg_hash = message.hash()
        encoded = encode_defunct(hexstr=msg_hash)
        signed = Account.sign_message(encoded, private_key)
        return signed.signature.hex()

    def verify_signature(
        self,
        message: CrossChainMessage,
        signature: str,
        expected_signer: str,
    ) -> bool:
        try:
            msg_hash = message.hash()
            encoded = encode_defunct(hexstr=msg_hash)
            recovered = Account.recover_message(encoded, signature=signature)
            return recovered.lower() == expected_signer.lower()
        except Exception:
            return False

    def add_signature(
        self,
        message: CrossChainMessage,
        signature: str,
        signer_address: str,
    ) -> CrossChainMessage:
        if signer_address.lower() not in self._trusted_validators:
            raise CrossChainError(f"Signer {signer_address} is not a trusted validator")

        existing = next(
            (s for s in message.signatures if s["signer"].lower() == signer_address.lower()),
            None
        )
        if existing:
            raise CrossChainError(f"Signer {signer_address} has already signed this message")

        if not self.verify_signature(message, signature, signer_address):
            raise CrossChainError("Invalid signature")

        message.signatures.append({
            "signer": signer_address,
            "signature": signature,
        })

        return message

    def is_verified(self, message: CrossChainMessage) -> bool:
        valid_signatures = 0
        for sig in message.signatures:
            if sig["signer"].lower() in self._trusted_validators:
                if self.verify_signature(message, sig["signature"], sig["signer"]):
                    valid_signatures += 1

        return valid_signatures >= self.required_signatures

    def verify_merkle_proof(
        self,
        leaf: str,
        proof: List[str],
        root: str,
    ) -> bool:
        current = leaf
        for sibling in proof:
            if current < sibling:
                combined = current + sibling
            else:
                combined = sibling + current
            current = "0x" + hashlib.sha256(bytes.fromhex(combined[2:])).hexdigest()

        return current.lower() == root.lower()

    def verify_and_store(
        self,
        message: CrossChainMessage,
    ) -> bool:
        if self.is_verified(message):
            self._verified_messages[message.message_id] = message
            return True
        return False

    def get_verified_message(self, message_id: str) -> Optional[CrossChainMessage]:
        return self._verified_messages.get(message_id)

    def list_verified_messages(
        self,
        source_chain: Optional[str] = None,
        target_chain: Optional[str] = None,
    ) -> List[CrossChainMessage]:
        messages = list(self._verified_messages.values())
        if source_chain:
            messages = [m for m in messages if m.source_chain == source_chain]
        if target_chain:
            messages = [m for m in messages if m.target_chain == target_chain]
        return messages
