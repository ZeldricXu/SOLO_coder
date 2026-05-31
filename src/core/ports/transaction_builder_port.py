from __future__ import annotations

from abc import ABC, abstractmethod
from typing import Any, Dict, List, Optional

from src.shared.types import (
    Address,
    ChainId,
    GasAmount,
    Hash,
    HexString,
    SignedTransaction,
    WeiAmount,
)


class ITransactionBuilderPort(ABC):
    @abstractmethod
    async def build_transaction(
        self,
        to: Optional[Address] = None,
        from_address: Optional[Address] = None,
        value: WeiAmount = 0,
        data: HexString = "0x",
        gas_limit: Optional[GasAmount] = None,
        gas_price: Optional[WeiAmount] = None,
        max_fee_per_gas: Optional[WeiAmount] = None,
        max_priority_fee_per_gas: Optional[WeiAmount] = None,
        nonce: Optional[int] = None,
        chain_id: Optional[ChainId] = None,
    ) -> Dict[str, Any]: ...

    @abstractmethod
    async def build_contract_deployment(
        self,
        bytecode: HexString,
        constructor_args: Optional[List[Any]] = None,
        abi: Optional[List[Dict[str, Any]]] = None,
        from_address: Optional[Address] = None,
        value: WeiAmount = 0,
        gas_limit: Optional[GasAmount] = None,
        gas_price: Optional[WeiAmount] = None,
        nonce: Optional[int] = None,
    ) -> Dict[str, Any]: ...

    @abstractmethod
    async def build_contract_call(
        self,
        contract_address: Address,
        function_name: str,
        function_args: Optional[List[Any]] = None,
        abi: List[Dict[str, Any]] = None,
        from_address: Optional[Address] = None,
        value: WeiAmount = 0,
        gas_limit: Optional[GasAmount] = None,
        gas_price: Optional[WeiAmount] = None,
        nonce: Optional[int] = None,
    ) -> Dict[str, Any]: ...

    @abstractmethod
    async def sign_transaction(
        self,
        transaction: Dict[str, Any],
        private_key: HexString,
    ) -> SignedTransaction: ...

    @abstractmethod
    async def sign_message(
        self,
        message: str | HexString,
        private_key: HexString,
        sign_type: str = "ecdsa",
    ) -> HexString: ...

    @abstractmethod
    async def verify_transaction(
        self,
        signed_tx: SignedTransaction,
    ) -> bool: ...

    @abstractmethod
    async def encode_function_call(
        self,
        function_name: str,
        function_args: Optional[List[Any]] = None,
        abi: List[Dict[str, Any]] = None,
    ) -> HexString: ...

    @abstractmethod
    async def decode_function_input(
        self,
        data: HexString,
        abi: List[Dict[str, Any]],
    ) -> Dict[str, Any]: ...


class IMultiSigPort(ABC):
    @abstractmethod
    async def create_multisig_wallet(
        self,
        owners: List[Address],
        threshold: int,
        chain_id: ChainId,
    ) -> Address: ...

    @abstractmethod
    async def propose_transaction(
        self,
        multisig_address: Address,
        to: Address,
        value: WeiAmount,
        data: HexString,
        proposer: Address,
    ) -> str: ...

    @abstractmethod
    async def approve_transaction(
        self,
        multisig_address: Address,
        tx_id: str,
        approver: Address,
    ) -> bool: ...

    @abstractmethod
    async def execute_transaction(
        self,
        multisig_address: Address,
        tx_id: str,
    ) -> Optional[Hash]: ...

    @abstractmethod
    async def get_transaction(
        self,
        multisig_address: Address,
        tx_id: str,
    ) -> Optional[Dict[str, Any]]: ...

    @abstractmethod
    async def list_transactions(
        self,
        multisig_address: Address,
        status: Optional[str] = None,
    ) -> List[Dict[str, Any]]: ...

    @abstractmethod
    async def get_owners(
        self,
        multisig_address: Address,
    ) -> List[Address]: ...

    @abstractmethod
    async def get_threshold(
        self,
        multisig_address: Address,
    ) -> int: ...


class IGasOptimizerPort(ABC):
    @abstractmethod
    async def optimize_gas_price(
        self,
        transaction: Dict[str, Any],
        speed: str = "average",
    ) -> Dict[str, Any]: ...

    @abstractmethod
    async def optimize_gas_limit(
        self,
        transaction: Dict[str, Any],
        buffer_percent: float = 10.0,
    ) -> Dict[str, Any]: ...

    @abstractmethod
    async def suggest_gas_savings(
        self,
        transaction: Dict[str, Any],
    ) -> List[Dict[str, Any]]: ...

    @abstractmethod
    async def batch_transactions(
        self,
        transactions: List[Dict[str, Any]],
        from_address: Address,
    ) -> Dict[str, Any]: ...
