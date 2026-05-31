from abc import ABC, abstractmethod
from typing import Any, Callable, Dict, List, Optional


class ISignatureVerifier(ABC):
    @abstractmethod
    def verify_signature(self, message_hash: str, signer: str, signature: str, chain_id: int) -> bool: ...

    @abstractmethod
    def compute_safe_tx_hash(
        self,
        wallet_address: str,
        chain_id: int,
        to: str,
        value: int,
        data: str,
        operation: int,
        safe_tx_gas: int,
        base_gas: int,
        gas_price: int,
        gas_token: str,
        refund_receiver: str,
        nonce: int,
    ) -> str: ...

    @abstractmethod
    def compute_wallet_address(self, signers: List[str], threshold: int, chain_id: int) -> str: ...


class IChainExecutor(ABC):
    @abstractmethod
    async def execute_transaction(
        self,
        chain_id: int,
        wallet_address: str,
        to: str,
        value: int,
        data: str,
        operation: int,
        signatures: str,
        nonce: int,
    ) -> str: ...


class IMessageVerifier(ABC):
    @abstractmethod
    def compute_message_hash(
        self,
        source_chain_id: int,
        target_chain_id: int,
        sender: str,
        recipient: str,
        amount: int,
        token_address: str,
        payload: Dict[str, Any],
    ) -> str: ...

    @abstractmethod
    async def verify_proof(
        self,
        tx: Any,
        proof_data: Dict[str, Any],
        signatures: List[str],
        message_hash: str,
        merkle_proof: Optional[List[str]] = None,
    ) -> bool: ...


class IWebhookSender(ABC):
    @abstractmethod
    async def send_webhook(self, url: str, headers: Dict[str, str], payload: Dict[str, Any]) -> None: ...


class ICallbackHandlerRegistry(ABC):
    @abstractmethod
    def register_handler(self, event_type: str, handler: Callable) -> None: ...

    @abstractmethod
    def get_handler(self, event_type: str) -> Optional[Callable]: ...
