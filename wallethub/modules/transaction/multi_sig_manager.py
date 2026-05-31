from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional, Tuple
from eth_account import Account
from eth_account.messages import encode_defunct
from eth_utils import to_checksum_address
import hashlib

from wallethub.core import MultiSigStatus, SigningError, TransactionError
from wallethub.utils import generate_id


@dataclass
class MultiSigWallet:
    wallet_id: str
    name: str
    chain: str
    owners: List[str]
    threshold: int
    safe_address: Optional[str] = None
    metadata: Dict[str, Any] = field(default_factory=dict)

    def __post_init__(self) -> None:
        self.owners = [to_checksum_address(addr) for addr in self.owners]
        if self.threshold <= 0:
            raise SigningError("Threshold must be greater than 0")
        if self.threshold > len(self.owners):
            raise SigningError("Threshold cannot exceed number of owners")


@dataclass
class MultiSigProposal:
    proposal_id: str = field(default_factory=lambda: generate_id("prop"))
    wallet_id: str = ""
    to_address: str = ""
    value: int = 0
    data: Optional[str] = None
    nonce: int = 0
    signatures: Dict[str, str] = field(default_factory=dict)
    status: MultiSigStatus = MultiSigStatus.PENDING
    created_at: int = field(default_factory=lambda: int(__import__("time").time()))

    def message_hash(self) -> str:
        message = f"{self.wallet_id}:{self.to_address}:{self.value}:{self.data or ''}:{self.nonce}"
        return "0x" + hashlib.keccak256(message.encode()).hex()

    def sign(self, owner_address: str, signature: str) -> None:
        owner_address = to_checksum_address(owner_address)
        if owner_address in self.signatures:
            raise SigningError("Owner has already signed this proposal")
        self.signatures[owner_address] = signature
        self._update_status()

    def revoke_signature(self, owner_address: str) -> None:
        owner_address = to_checksum_address(owner_address)
        if owner_address not in self.signatures:
            raise SigningError("Owner has not signed this proposal")
        del self.signatures[owner_address]
        self._update_status()

    def _update_status(self) -> None:
        if len(self.signatures) >= self._get_threshold():
            self.status = MultiSigStatus.FULLY_SIGNED
        elif len(self.signatures) > 0:
            self.status = MultiSigStatus.PARTIALLY_SIGNED
        else:
            self.status = MultiSigStatus.PENDING

    def _get_threshold(self) -> int:
        return 1


class MultiSigManager:
    def __init__(self):
        self._wallets: Dict[str, MultiSigWallet] = {}
        self._proposals: Dict[str, MultiSigProposal] = {}
        self._wallet_proposals: Dict[str, List[str]] = {}

    def create_wallet(
        self,
        name: str,
        chain: str,
        owners: List[str],
        threshold: int,
        safe_address: Optional[str] = None,
    ) -> MultiSigWallet:
        wallet_id = generate_id("msig")
        wallet = MultiSigWallet(
            wallet_id=wallet_id,
            name=name,
            chain=chain,
            owners=owners,
            threshold=threshold,
            safe_address=safe_address,
        )
        self._wallets[wallet_id] = wallet
        self._wallet_proposals[wallet_id] = []
        return wallet

    def get_wallet(self, wallet_id: str) -> Optional[MultiSigWallet]:
        return self._wallets.get(wallet_id)

    def list_wallets(self) -> List[MultiSigWallet]:
        return list(self._wallets.values())

    def create_proposal(
        self,
        wallet_id: str,
        to_address: str,
        value: int = 0,
        data: Optional[str] = None,
    ) -> MultiSigProposal:
        wallet = self.get_wallet(wallet_id)
        if not wallet:
            raise SigningError(f"Wallet {wallet_id} not found")

        nonce = len(self._wallet_proposals[wallet_id])
        proposal = MultiSigProposal(
            wallet_id=wallet_id,
            to_address=to_checksum_address(to_address),
            value=value,
            data=data,
            nonce=nonce,
        )
        proposal._get_threshold = lambda: wallet.threshold
        self._proposals[proposal.proposal_id] = proposal
        self._wallet_proposals[wallet_id].append(proposal.proposal_id)
        return proposal

    def get_proposal(self, proposal_id: str) -> Optional[MultiSigProposal]:
        return self._proposals.get(proposal_id)

    def list_proposals(self, wallet_id: Optional[str] = None) -> List[MultiSigProposal]:
        if wallet_id:
            return [
                self._proposals[pid]
                for pid in self._wallet_proposals.get(wallet_id, [])
            ]
        return list(self._proposals.values())

    def sign_proposal(
        self,
        proposal_id: str,
        owner_address: str,
        private_key: str,
    ) -> MultiSigProposal:
        proposal = self.get_proposal(proposal_id)
        if not proposal:
            raise SigningError(f"Proposal {proposal_id} not found")

        wallet = self.get_wallet(proposal.wallet_id)
        if not wallet:
            raise SigningError(f"Wallet {proposal.wallet_id} not found")

        owner_address = to_checksum_address(owner_address)
        if owner_address not in wallet.owners:
            raise SigningError(f"Address {owner_address} is not an owner")

        message = encode_defunct(text=proposal.message_hash())
        signed_message = Account.sign_message(message, private_key)

        proposal.sign(owner_address, signed_message.signature.hex())
        return proposal

    def verify_signature(
        self,
        message: str,
        signature: str,
        expected_address: str,
    ) -> bool:
        try:
            encoded_message = encode_defunct(text=message)
            recovered_address = Account.recover_message(
                encoded_message, signature=signature
            )
            return recovered_address.lower() == expected_address.lower()
        except Exception:
            return False

    def get_executable_proposals(self, wallet_id: str) -> List[MultiSigProposal]:
        return [
            p
            for p in self.list_proposals(wallet_id)
            if p.status == MultiSigStatus.FULLY_SIGNED
        ]

    def mark_proposal_executed(self, proposal_id: str) -> None:
        proposal = self.get_proposal(proposal_id)
        if proposal:
            proposal.status = MultiSigStatus.EXECUTED

    def mark_proposal_rejected(self, proposal_id: str) -> None:
        proposal = self.get_proposal(proposal_id)
        if proposal:
            proposal.status = MultiSigStatus.REJECTED
