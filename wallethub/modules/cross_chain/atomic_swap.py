from dataclasses import dataclass, field
from typing import Any, Dict, Optional
from enum import Enum
from datetime import datetime, timezone, timedelta
import hashlib
import secrets

from wallethub.core import CrossChainError
from wallethub.utils import generate_id


class SwapStatus(str, Enum):
    INITIATED = "initiated"
    LOCKED = "locked"
    REDEEMED = "redeemed"
    REFUNDED = "refunded"
    EXPIRED = "expired"


@dataclass
class AtomicSwap:
    swap_id: str = field(default_factory=lambda: generate_id("swap"))
    source_chain: str = ""
    target_chain: str = ""
    initiator: str = ""
    participant: str = ""
    source_token: str = ""
    target_token: str = ""
    source_amount: int = 0
    target_amount: int = 0
    secret_hash: str = ""
    secret: Optional[str] = None
    timelock: int = 0
    source_tx_hash: Optional[str] = None
    target_tx_hash: Optional[str] = None
    status: SwapStatus = SwapStatus.INITIATED
    metadata: Dict[str, Any] = field(default_factory=dict)
    created_at: datetime = field(default_factory=lambda: datetime.now(timezone.utc))

    def is_expired(self) -> bool:
        if self.timelock == 0:
            return False
        expire_time = self.created_at + timedelta(seconds=self.timelock)
        return datetime.now(timezone.utc) > expire_time


class AtomicSwapManager:
    def __init__(self, default_timelock: int = 86400):
        self.default_timelock = default_timelock
        self._swaps: Dict[str, AtomicSwap] = {}

    @staticmethod
    def generate_secret() -> tuple[str, str]:
        secret = secrets.token_hex(32)
        secret_hash = "0x" + hashlib.sha256(bytes.fromhex(secret)).hexdigest()
        return secret, secret_hash

    @staticmethod
    def verify_secret(secret: str, secret_hash: str) -> bool:
        computed = "0x" + hashlib.sha256(bytes.fromhex(secret)).hexdigest()
        return computed.lower() == secret_hash.lower()

    def initiate_swap(
        self,
        source_chain: str,
        target_chain: str,
        initiator: str,
        participant: str,
        source_token: str,
        target_token: str,
        source_amount: int,
        target_amount: int,
        secret_hash: Optional[str] = None,
        timelock: Optional[int] = None,
        metadata: Optional[Dict[str, Any]] = None,
    ) -> AtomicSwap:
        if source_amount <= 0 or target_amount <= 0:
            raise CrossChainError("Amounts must be greater than 0")

        secret = None
        if not secret_hash:
            secret, secret_hash = self.generate_secret()

        swap = AtomicSwap(
            source_chain=source_chain,
            target_chain=target_chain,
            initiator=initiator,
            participant=participant,
            source_token=source_token,
            target_token=target_token,
            source_amount=source_amount,
            target_amount=target_amount,
            secret_hash=secret_hash,
            secret=secret,
            timelock=timelock or self.default_timelock,
            metadata=metadata or {},
        )

        self._swaps[swap.swap_id] = swap
        return swap

    def lock_source(
        self,
        swap_id: str,
        source_tx_hash: str,
    ) -> AtomicSwap:
        swap = self._get_swap(swap_id)
        if swap.status != SwapStatus.INITIATED:
            raise CrossChainError(f"Cannot lock swap in status: {swap.status}")

        swap.source_tx_hash = source_tx_hash
        swap.status = SwapStatus.LOCKED
        return swap

    def lock_target(
        self,
        swap_id: str,
        target_tx_hash: str,
    ) -> AtomicSwap:
        swap = self._get_swap(swap_id)
        if swap.status != SwapStatus.LOCKED:
            raise CrossChainError(f"Cannot lock target in status: {swap.status}")

        swap.target_tx_hash = target_tx_hash
        return swap

    def redeem(
        self,
        swap_id: str,
        secret: str,
    ) -> AtomicSwap:
        swap = self._get_swap(swap_id)
        if swap.status != SwapStatus.LOCKED:
            raise CrossChainError(f"Cannot redeem swap in status: {swap.status}")
        if swap.is_expired():
            raise CrossChainError("Swap has expired")
        if not self.verify_secret(secret, swap.secret_hash):
            raise CrossChainError("Invalid secret")

        swap.secret = secret
        swap.status = SwapStatus.REDEEMED
        return swap

    def refund(self, swap_id: str) -> AtomicSwap:
        swap = self._get_swap(swap_id)
        if swap.status not in [SwapStatus.LOCKED, SwapStatus.INITIATED]:
            raise CrossChainError(f"Cannot refund swap in status: {swap.status}")
        if not swap.is_expired():
            raise CrossChainError("Swap has not expired yet")

        swap.status = SwapStatus.REFUNDED
        return swap

    def mark_expired(self, swap_id: str) -> AtomicSwap:
        swap = self._get_swap(swap_id)
        if not swap.is_expired():
            raise CrossChainError("Swap has not expired yet")

        if swap.status == SwapStatus.LOCKED:
            swap.status = SwapStatus.EXPIRED
        return swap

    def get_swap(self, swap_id: str) -> Optional[AtomicSwap]:
        return self._swaps.get(swap_id)

    def _get_swap(self, swap_id: str) -> AtomicSwap:
        swap = self._swaps.get(swap_id)
        if not swap:
            raise CrossChainError(f"Swap {swap_id} not found")
        return swap

    def list_swaps(
        self,
        status: Optional[SwapStatus] = None,
        initiator: Optional[str] = None,
        participant: Optional[str] = None,
    ) -> list[AtomicSwap]:
        swaps = list(self._swaps.values())
        if status:
            swaps = [s for s in swaps if s.status == status]
        if initiator:
            swaps = [s for s in swaps if s.initiator.lower() == initiator.lower()]
        if participant:
            swaps = [s for s in swaps if s.participant.lower() == participant.lower()]
        return swaps

    def cleanup_expired(self) -> int:
        count = 0
        for swap in list(self._swaps.values()):
            if swap.is_expired() and swap.status == SwapStatus.LOCKED:
                swap.status = SwapStatus.EXPIRED
                count += 1
        return count
