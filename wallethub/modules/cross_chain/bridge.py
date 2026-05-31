from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional
from enum import Enum
from datetime import datetime, timezone
import hashlib
import threading

from wallethub.core import CrossChainStatus, CrossChainError
from wallethub.utils import generate_id


class BridgeType(str, Enum):
    LOCK_MINT = "lock_mint"
    BURN_MINT = "burn_mint"
    CUSTODIAL = "custodial"
    ATOMIC_SWAP = "atomic_swap"


_VALID_TRANSITIONS: Dict[CrossChainStatus, List[CrossChainStatus]] = {
    CrossChainStatus.INITIATED: [CrossChainStatus.LOCKED, CrossChainStatus.FAILED],
    CrossChainStatus.LOCKED: [CrossChainStatus.VERIFIED, CrossChainStatus.FAILED],
    CrossChainStatus.VERIFIED: [CrossChainStatus.MINTED, CrossChainStatus.FAILED],
    CrossChainStatus.MINTED: [CrossChainStatus.COMPLETED, CrossChainStatus.FAILED],
    CrossChainStatus.COMPLETED: [],
    CrossChainStatus.FAILED: [],
}


@dataclass
class BridgeTransfer:
    transfer_id: str = field(default_factory=lambda: generate_id("xchain"))
    source_chain: str = ""
    target_chain: str = ""
    source_address: str = ""
    target_address: str = ""
    token_address: str = ""
    amount: int = 0
    bridge_type: BridgeType = BridgeType.LOCK_MINT
    source_tx_hash: Optional[str] = None
    target_tx_hash: Optional[str] = None
    message_hash: Optional[str] = None
    status: CrossChainStatus = CrossChainStatus.INITIATED
    proof_data: Dict[str, Any] = field(default_factory=dict)
    metadata: Dict[str, Any] = field(default_factory=dict)
    created_at: datetime = field(default_factory=lambda: datetime.now(timezone.utc))
    updated_at: datetime = field(default_factory=lambda: datetime.now(timezone.utc))

    def generate_message_hash(self) -> str:
        message = (
            f"{self.source_chain}:{self.target_chain}:{self.source_address}:"
            f"{self.target_address}:{self.token_address}:{self.amount}:{self.transfer_id}"
        )
        return "0x" + hashlib.sha256(message.encode()).hexdigest()

    def can_transition_to(self, target_status: CrossChainStatus) -> bool:
        return target_status in _VALID_TRANSITIONS.get(self.status, [])


class CrossChainBridge:
    def __init__(self):
        self._transfers: Dict[str, BridgeTransfer] = {}
        self._chain_bridges: Dict[str, Any] = {}
        self._validators: List[str] = []
        self._lock = threading.RLock()

    def register_chain_bridge(self, chain: str, bridge_contract: Any) -> None:
        with self._lock:
            self._chain_bridges[chain] = bridge_contract

    def register_validator(self, validator_address: str) -> None:
        if not validator_address:
            raise CrossChainError(
                message="Validator address cannot be empty",
                details={"operation": "register_validator", "validation_failed": "empty_address"}
            )
        with self._lock:
            if validator_address not in self._validators:
                self._validators.append(validator_address)

    def initiate_transfer(
        self,
        source_chain: str,
        target_chain: str,
        source_address: str,
        target_address: str,
        token_address: str,
        amount: int,
        bridge_type: BridgeType = BridgeType.LOCK_MINT,
        metadata: Optional[Dict[str, Any]] = None,
    ) -> BridgeTransfer:
        if not source_chain or not target_chain:
            raise CrossChainError(
                message="Source and target chains cannot be empty",
                details={
                    "operation": "initiate_transfer",
                    "source_chain": source_chain,
                    "target_chain": target_chain,
                    "validation_failed": "empty_chain"
                }
            )
        if not source_address or not target_address:
            raise CrossChainError(
                message="Source and target addresses cannot be empty",
                details={
                    "operation": "initiate_transfer",
                    "source_chain": source_chain,
                    "target_chain": target_chain,
                    "validation_failed": "empty_address"
                }
            )
        if not token_address:
            raise CrossChainError(
                message="Token address cannot be empty",
                details={
                    "operation": "initiate_transfer",
                    "source_chain": source_chain,
                    "target_chain": target_chain,
                    "validation_failed": "empty_token_address"
                }
            )
        if amount <= 0:
            raise CrossChainError(
                message="Amount must be greater than 0",
                details={
                    "operation": "initiate_transfer",
                    "source_chain": source_chain,
                    "target_chain": target_chain,
                    "amount": amount,
                    "validation_failed": "invalid_amount"
                }
            )
        if source_chain not in self._chain_bridges:
            raise CrossChainError(
                message=f"Bridge not configured for source chain: {source_chain}",
                details={
                    "operation": "initiate_transfer",
                    "source_chain": source_chain,
                    "target_chain": target_chain,
                    "configured_chains": list(self._chain_bridges.keys()),
                    "error": "source_chain_not_configured"
                }
            )
        if target_chain not in self._chain_bridges:
            raise CrossChainError(
                message=f"Bridge not configured for target chain: {target_chain}",
                details={
                    "operation": "initiate_transfer",
                    "source_chain": source_chain,
                    "target_chain": target_chain,
                    "configured_chains": list(self._chain_bridges.keys()),
                    "error": "target_chain_not_configured"
                }
            )

        transfer = BridgeTransfer(
            source_chain=source_chain,
            target_chain=target_chain,
            source_address=source_address,
            target_address=target_address,
            token_address=token_address,
            amount=amount,
            bridge_type=bridge_type,
            metadata=metadata or {},
        )
        transfer.message_hash = transfer.generate_message_hash()

        with self._lock:
            self._transfers[transfer.transfer_id] = transfer

        return transfer

    def confirm_source_lock(
        self,
        transfer_id: str,
        source_tx_hash: str,
        proof_data: Optional[Dict[str, Any]] = None,
    ) -> BridgeTransfer:
        if not transfer_id:
            raise CrossChainError(
                message="Transfer ID cannot be empty",
                details={"operation": "confirm_source_lock", "validation_failed": "empty_transfer_id"}
            )
        if not source_tx_hash:
            raise CrossChainError(
                message="Source transaction hash cannot be empty",
                details={"operation": "confirm_source_lock", "transfer_id": transfer_id, "validation_failed": "empty_tx_hash"}
            )

        with self._lock:
            transfer = self._get_transfer(transfer_id)

            if not transfer.can_transition_to(CrossChainStatus.LOCKED):
                raise CrossChainError(
                    message=f"Invalid status transition from {transfer.status} to locked",
                    details={
                        "operation": "confirm_source_lock",
                        "transfer_id": transfer_id,
                        "current_status": transfer.status,
                        "target_status": CrossChainStatus.LOCKED,
                        "valid_transitions": _VALID_TRANSITIONS.get(transfer.status, []),
                        "source_chain": transfer.source_chain,
                        "target_chain": transfer.target_chain,
                        "amount": transfer.amount,
                        "error": "invalid_state_transition"
                    }
                )

            transfer.source_tx_hash = source_tx_hash
            transfer.status = CrossChainStatus.LOCKED
            if proof_data:
                transfer.proof_data.update(proof_data)
            transfer.updated_at = datetime.now(timezone.utc)
            return transfer

    def verify_message(
        self,
        transfer_id: str,
        validator_signatures: List[str],
    ) -> BridgeTransfer:
        if not transfer_id:
            raise CrossChainError(
                message="Transfer ID cannot be empty",
                details={"operation": "verify_message", "validation_failed": "empty_transfer_id"}
            )
        if not validator_signatures:
            raise CrossChainError(
                message="Validator signatures cannot be empty",
                details={"operation": "verify_message", "transfer_id": transfer_id, "validation_failed": "empty_signatures"}
            )

        with self._lock:
            transfer = self._get_transfer(transfer_id)

            if not transfer.can_transition_to(CrossChainStatus.VERIFIED):
                raise CrossChainError(
                    message=f"Cannot verify message in status: {transfer.status}",
                    details={
                        "operation": "verify_message",
                        "transfer_id": transfer_id,
                        "current_status": transfer.status,
                        "target_status": CrossChainStatus.VERIFIED,
                        "valid_transitions": _VALID_TRANSITIONS.get(transfer.status, []),
                        "source_chain": transfer.source_chain,
                        "target_chain": transfer.target_chain,
                        "error": "invalid_state_transition"
                    }
                )

            required_signatures = max(1, len(self._validators) * 2 // 3 + 1)
            if len(validator_signatures) < required_signatures:
                raise CrossChainError(
                    message=f"Insufficient signatures: required {required_signatures}, got {len(validator_signatures)}",
                    details={
                        "operation": "verify_message",
                        "transfer_id": transfer_id,
                        "required_signatures": required_signatures,
                        "provided_signatures": len(validator_signatures),
                        "total_validators": len(self._validators),
                        "source_chain": transfer.source_chain,
                        "target_chain": transfer.target_chain,
                        "error": "insufficient_signatures"
                    }
                )

            transfer.status = CrossChainStatus.VERIFIED
            transfer.proof_data["signatures"] = validator_signatures
            transfer.updated_at = datetime.now(timezone.utc)
            return transfer

    def mint_target(
        self,
        transfer_id: str,
        target_tx_hash: str,
    ) -> BridgeTransfer:
        if not transfer_id:
            raise CrossChainError(
                message="Transfer ID cannot be empty",
                details={"operation": "mint_target", "validation_failed": "empty_transfer_id"}
            )
        if not target_tx_hash:
            raise CrossChainError(
                message="Target transaction hash cannot be empty",
                details={"operation": "mint_target", "transfer_id": transfer_id, "validation_failed": "empty_tx_hash"}
            )

        with self._lock:
            transfer = self._get_transfer(transfer_id)

            if not transfer.can_transition_to(CrossChainStatus.MINTED):
                raise CrossChainError(
                    message=f"Cannot mint in status: {transfer.status}",
                    details={
                        "operation": "mint_target",
                        "transfer_id": transfer_id,
                        "current_status": transfer.status,
                        "target_status": CrossChainStatus.MINTED,
                        "valid_transitions": _VALID_TRANSITIONS.get(transfer.status, []),
                        "source_chain": transfer.source_chain,
                        "target_chain": transfer.target_chain,
                        "error": "invalid_state_transition"
                    }
                )

            transfer.target_tx_hash = target_tx_hash
            transfer.status = CrossChainStatus.MINTED
            transfer.updated_at = datetime.now(timezone.utc)
            return transfer

    def complete_transfer(self, transfer_id: str) -> BridgeTransfer:
        if not transfer_id:
            raise CrossChainError(
                message="Transfer ID cannot be empty",
                details={"operation": "complete_transfer", "validation_failed": "empty_transfer_id"}
            )

        with self._lock:
            transfer = self._get_transfer(transfer_id)

            if not transfer.can_transition_to(CrossChainStatus.COMPLETED):
                raise CrossChainError(
                    message=f"Cannot complete transfer in status: {transfer.status}",
                    details={
                        "operation": "complete_transfer",
                        "transfer_id": transfer_id,
                        "current_status": transfer.status,
                        "target_status": CrossChainStatus.COMPLETED,
                        "valid_transitions": _VALID_TRANSITIONS.get(transfer.status, []),
                        "source_chain": transfer.source_chain,
                        "target_chain": transfer.target_chain,
                        "error": "invalid_state_transition"
                    }
                )

            transfer.status = CrossChainStatus.COMPLETED
            transfer.updated_at = datetime.now(timezone.utc)
            return transfer

    def fail_transfer(
        self,
        transfer_id: str,
        error_message: str,
    ) -> BridgeTransfer:
        if not transfer_id:
            raise CrossChainError(
                message="Transfer ID cannot be empty",
                details={"operation": "fail_transfer", "validation_failed": "empty_transfer_id"}
            )
        if not error_message:
            raise CrossChainError(
                message="Error message cannot be empty",
                details={"operation": "fail_transfer", "transfer_id": transfer_id, "validation_failed": "empty_error_message"}
            )

        with self._lock:
            transfer = self._get_transfer(transfer_id)

            if not transfer.can_transition_to(CrossChainStatus.FAILED):
                raise CrossChainError(
                    message=f"Cannot fail transfer in terminal status: {transfer.status}",
                    details={
                        "operation": "fail_transfer",
                        "transfer_id": transfer_id,
                        "current_status": transfer.status,
                        "source_chain": transfer.source_chain,
                        "target_chain": transfer.target_chain,
                        "error": "transfer_already_terminal"
                    }
                )

            transfer.status = CrossChainStatus.FAILED
            transfer.proof_data["error"] = error_message
            transfer.proof_data["failed_at"] = datetime.now(timezone.utc).isoformat()
            transfer.updated_at = datetime.now(timezone.utc)
            return transfer

    def get_transfer(self, transfer_id: str) -> Optional[BridgeTransfer]:
        return self._transfers.get(transfer_id)

    def _get_transfer(self, transfer_id: str) -> BridgeTransfer:
        transfer = self._transfers.get(transfer_id)
        if not transfer:
            raise CrossChainError(
                message=f"Transfer {transfer_id} not found",
                details={
                    "operation": "get_transfer",
                    "transfer_id": transfer_id,
                    "error": "transfer_not_found"
                }
            )
        return transfer

    def list_transfers(
        self,
        source_chain: Optional[str] = None,
        target_chain: Optional[str] = None,
        status: Optional[CrossChainStatus] = None,
    ) -> List[BridgeTransfer]:
        with self._lock:
            transfers = list(self._transfers.values())
            if source_chain:
                transfers = [t for t in transfers if t.source_chain == source_chain]
            if target_chain:
                transfers = [t for t in transfers if t.target_chain == target_chain]
            if status:
                transfers = [t for t in transfers if t.status == status]
            return transfers

    def get_pending_verifications(self) -> List[BridgeTransfer]:
        return [
            t for t in self._transfers.values()
            if t.status == CrossChainStatus.LOCKED
        ]
