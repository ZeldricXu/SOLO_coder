from __future__ import annotations

from abc import ABC, abstractmethod
from typing import Dict, List, Optional

from src.shared.types import Address, Chain, CrossChainMessage, Hash, HexString, WeiAmount


class ICrossChainBridgePort(ABC):
    @abstractmethod
    async def lock_assets(
        self,
        source_chain: Chain,
        target_chain: Chain,
        source_address: Address,
        target_address: Address,
        amount: WeiAmount,
        token_address: Optional[Address] = None,
        data: HexString = "0x",
    ) -> CrossChainMessage: ...

    @abstractmethod
    async def mint_assets(
        self,
        message: CrossChainMessage,
        proof: HexString,
    ) -> Hash: ...

    @abstractmethod
    async def verify_message(
        self,
        message: CrossChainMessage,
        proof: HexString,
    ) -> bool: ...

    @abstractmethod
    async def get_message_status(
        self,
        message_id: str,
    ) -> CrossChainMessage: ...

    @abstractmethod
    async def list_messages(
        self,
        source_chain: Optional[Chain] = None,
        target_chain: Optional[Chain] = None,
        status: Optional[str] = None,
        limit: int = 100,
    ) -> List[CrossChainMessage]: ...

    @abstractmethod
    async def retry_message(
        self,
        message_id: str,
    ) -> bool: ...

    @abstractmethod
    async def cancel_message(
        self,
        message_id: str,
    ) -> bool: ...

    @abstractmethod
    async def generate_proof(
        self,
        message: CrossChainMessage,
        source_tx_hash: Hash,
    ) -> HexString: ...


class IMessageValidator(ABC):
    @abstractmethod
    async def validate_source_transaction(
        self,
        source_chain: Chain,
        tx_hash: Hash,
        message: CrossChainMessage,
    ) -> bool: ...

    @abstractmethod
    async def validate_signature(
        self,
        message: CrossChainMessage,
        signature: HexString,
        signer_address: Address,
    ) -> bool: ...

    @abstractmethod
    async def validate_nonce(
        self,
        message: CrossChainMessage,
    ) -> bool: ...


class IAtomicExecutor(ABC):
    @abstractmethod
    async def execute_atomic(
        self,
        operations: List[Dict[str, Any]],
        timeout: int = 300,
    ) -> bool: ...

    @abstractmethod
    async def rollback(
        self,
        operation_id: str,
    ) -> bool: ...

    @abstractmethod
    async def prepare(
        self,
        operations: List[Dict[str, Any]],
    ) -> str: ...

    @abstractmethod
    async def commit(
        self,
        operation_id: str,
    ) -> bool: ...
