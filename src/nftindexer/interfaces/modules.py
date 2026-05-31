from abc import ABC, abstractmethod
from typing import Any, Dict, List, Optional

from ..dataclasses import (
    CreateWalletRequest,
    CreateProposalRequest,
    AddSignatureRequest,
    FilterConfig,
    BridgeRequest,
    MessageProof,
)


class IMultiSigModule(ABC):
    @abstractmethod
    async def initialize(self) -> None: ...

    @abstractmethod
    async def shutdown(self) -> None: ...

    @abstractmethod
    async def create_wallet(self, request: CreateWalletRequest) -> Dict[str, Any]: ...

    @abstractmethod
    async def get_wallet(self, wallet_id: str) -> Optional[Dict[str, Any]]: ...

    @abstractmethod
    async def list_wallets(
        self, chain_id: Optional[int] = None, offset: int = 0, limit: int = 50
    ) -> Dict[str, Any]: ...

    @abstractmethod
    async def create_proposal(self, request: CreateProposalRequest) -> Dict[str, Any]: ...

    @abstractmethod
    async def get_proposal(self, proposal_id: str) -> Optional[Dict[str, Any]]: ...

    @abstractmethod
    async def list_proposals(
        self,
        wallet_id: Optional[str] = None,
        status: Optional[str] = None,
        offset: int = 0,
        limit: int = 50,
    ) -> Dict[str, Any]: ...

    @abstractmethod
    async def add_signature(self, request: AddSignatureRequest) -> Dict[str, Any]: ...

    @abstractmethod
    async def execute_proposal(self, proposal_id: str) -> Dict[str, Any]: ...

    async def set_strategy(self, strategy_type: str) -> Dict[str, Any]:
        raise NotImplementedError

    async def get_strategies(self) -> Dict[str, Any]:
        raise NotImplementedError

    async def set_chain_strategy(self, chain_id: int, strategy_type: str) -> Dict[str, Any]:
        raise NotImplementedError

    async def set_wallet_strategy(self, wallet_id: str, strategy_type: str) -> Dict[str, Any]:
        raise NotImplementedError


class IEventListenerModule(ABC):
    @abstractmethod
    async def initialize(self) -> None: ...

    @abstractmethod
    async def start(self) -> None: ...

    @abstractmethod
    async def shutdown(self) -> None: ...

    @abstractmethod
    async def create_filter(self, config: FilterConfig) -> Dict[str, Any]: ...

    @abstractmethod
    async def get_filter(self, filter_id: str) -> Optional[Dict[str, Any]]: ...

    @abstractmethod
    async def list_filters(
        self, chain_id: Optional[int] = None, active_only: bool = True, offset: int = 0, limit: int = 50
    ) -> Dict[str, Any]: ...

    @abstractmethod
    async def pause_filter(self, filter_id: str) -> Dict[str, Any]: ...

    @abstractmethod
    async def resume_filter(self, filter_id: str) -> Dict[str, Any]: ...

    @abstractmethod
    async def delete_filter(self, filter_id: str) -> None: ...

    @abstractmethod
    def register_callback_handler(self, event_type: str, handler: Any) -> None: ...

    @abstractmethod
    async def get_event_logs(self, filter_id: str, offset: int = 0, limit: int = 50) -> Dict[str, Any]: ...

    async def set_filter_strategy(self, filter_id: str, strategy_type: str) -> Dict[str, Any]:
        raise NotImplementedError

    async def set_default_strategy(self, strategy_type: str) -> Dict[str, Any]:
        raise NotImplementedError

    async def get_available_strategies(self) -> Dict[str, Any]:
        raise NotImplementedError

    async def get_filter_strategies(self) -> Dict[str, Any]:
        raise NotImplementedError


class ICrossChainModule(ABC):
    @abstractmethod
    async def initialize(self) -> None: ...

    @abstractmethod
    async def shutdown(self) -> None: ...

    @abstractmethod
    async def initiate_bridge(self, request: BridgeRequest) -> Dict[str, Any]: ...

    @abstractmethod
    async def get_transaction(self, tx_id: str) -> Optional[Dict[str, Any]]: ...

    @abstractmethod
    async def list_transactions(
        self,
        source_chain: Optional[int] = None,
        target_chain: Optional[int] = None,
        status: Optional[str] = None,
        address: Optional[str] = None,
        offset: int = 0,
        limit: int = 50,
    ) -> Dict[str, Any]: ...

    @abstractmethod
    async def confirm_source_transaction(self, tx_id: str, source_tx_hash: str) -> Dict[str, Any]: ...

    @abstractmethod
    async def verify_message_proof(self, tx_id: str, proof: MessageProof) -> Dict[str, Any]: ...

    @abstractmethod
    async def complete_transaction(self, tx_id: str, target_tx_hash: str) -> Dict[str, Any]: ...

    @abstractmethod
    async def get_atomic_status(self, tx_id: str) -> Dict[str, Any]: ...

    @abstractmethod
    async def rollback_transaction(self, tx_id: str) -> Dict[str, Any]: ...

    async def get_task(self, task_id: str) -> Optional[Dict[str, Any]]:
        raise NotImplementedError

    async def list_tasks(
        self,
        status: Optional[str] = None,
        task_type: Optional[str] = None,
        limit: int = 100,
    ) -> Dict[str, Any]:
        raise NotImplementedError

    async def cancel_task(self, task_id: str) -> Dict[str, Any]:
        raise NotImplementedError

    def register_webhook(self, tx_id: str, webhook_url: str) -> None:
        raise NotImplementedError

    def register_callback(self, callback: Any) -> None:
        raise NotImplementedError

    def add_event_listener(self, callback: Any, status: Optional[str] = None) -> None:
        raise NotImplementedError

    def remove_event_listener(self, callback: Any, status: Optional[str] = None) -> None:
        raise NotImplementedError
