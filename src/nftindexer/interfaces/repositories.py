from abc import ABC, abstractmethod
from typing import Any, Dict, List, Optional, Tuple
from sqlalchemy.ext.asyncio import AsyncSession

from ..db.models import (
    MultiSigWallet,
    MultiSigProposal,
    MultiSigSignature,
    EventFilter,
    EventLog,
    CrossChainTransaction,
    CrossChainMessage,
)


class IMultiSigRepository(ABC):
    @abstractmethod
    async def create_wallet(self, wallet: MultiSigWallet) -> MultiSigWallet: ...

    @abstractmethod
    async def get_wallet(self, wallet_id: str) -> Optional[MultiSigWallet]: ...

    @abstractmethod
    async def list_wallets(
        self, chain_id: Optional[int] = None, offset: int = 0, limit: int = 50
    ) -> Tuple[List[MultiSigWallet], int]: ...

    @abstractmethod
    async def create_proposal(self, proposal: MultiSigProposal) -> MultiSigProposal: ...

    @abstractmethod
    async def get_proposal(self, proposal_id: str) -> Optional[MultiSigProposal]: ...

    @abstractmethod
    async def get_proposal_with_relations(self, proposal_id: str) -> Optional[MultiSigProposal]: ...

    @abstractmethod
    async def list_proposals(
        self,
        wallet_id: Optional[str] = None,
        status: Optional[str] = None,
        offset: int = 0,
        limit: int = 50,
    ) -> Tuple[List[MultiSigProposal], int]: ...

    @abstractmethod
    async def add_signature(self, signature: MultiSigSignature) -> MultiSigSignature: ...

    @abstractmethod
    async def get_signature_count(self, proposal_id: str) -> int: ...

    @abstractmethod
    async def has_signature(self, proposal_id: str, signer: str) -> bool: ...

    @abstractmethod
    async def update_proposal_status(self, proposal_id: str, status: str, **kwargs) -> None: ...

    @abstractmethod
    async def increment_wallet_nonce(self, wallet_id: str) -> None: ...


class IEventListenerRepository(ABC):
    @abstractmethod
    async def create_filter(self, filter_obj: EventFilter) -> EventFilter: ...

    @abstractmethod
    async def get_filter(self, filter_id: str) -> Optional[EventFilter]: ...

    @abstractmethod
    async def list_filters(
        self,
        chain_id: Optional[int] = None,
        is_active: Optional[bool] = None,
        offset: int = 0,
        limit: int = 50,
    ) -> Tuple[List[EventFilter], int]: ...

    @abstractmethod
    async def list_active_filters(self) -> List[EventFilter]: ...

    @abstractmethod
    async def update_filter_status(self, filter_id: str, is_active: bool) -> None: ...

    @abstractmethod
    async def update_last_processed_block(self, filter_id: str, block_number: int) -> None: ...

    @abstractmethod
    async def record_filter_error(self, filter_id: str, error: str) -> None: ...

    @abstractmethod
    async def delete_filter(self, filter_id: str) -> None: ...

    @abstractmethod
    async def create_event_log(self, log: EventLog) -> EventLog: ...

    @abstractmethod
    async def mark_log_processed(self, log_id: str, error: Optional[str] = None) -> None: ...

    @abstractmethod
    async def list_event_logs(
        self, filter_id: str, offset: int = 0, limit: int = 50
    ) -> Tuple[List[EventLog], int]: ...


class ICrossChainRepository(ABC):
    @abstractmethod
    async def create_transaction(self, tx: CrossChainTransaction) -> CrossChainTransaction: ...

    @abstractmethod
    async def create_message(self, message: CrossChainMessage) -> CrossChainMessage: ...

    @abstractmethod
    async def get_transaction(self, tx_id: str) -> Optional[CrossChainTransaction]: ...

    @abstractmethod
    async def get_transaction_with_messages(self, tx_id: str) -> Optional[CrossChainTransaction]: ...

    @abstractmethod
    async def list_transactions(
        self,
        source_chain: Optional[int] = None,
        target_chain: Optional[int] = None,
        status: Optional[str] = None,
        address: Optional[str] = None,
        offset: int = 0,
        limit: int = 50,
    ) -> Tuple[List[CrossChainTransaction], int]: ...

    @abstractmethod
    async def update_transaction_status(self, tx_id: str, status: str, **kwargs) -> None: ...

    @abstractmethod
    async def update_source_confirmations(self, tx_id: str, confirmations: int) -> None: ...

    @abstractmethod
    async def update_target_confirmations(self, tx_id: str, confirmations: int) -> None: ...
