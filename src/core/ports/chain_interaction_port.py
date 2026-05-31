from __future__ import annotations

from abc import ABC, abstractmethod
from typing import Any, AsyncIterator, Dict, List, Optional

from src.shared.types import (
    Address,
    BlockHeader,
    BlockNumber,
    Chain,
    ChainId,
    EventLog,
    GasAmount,
    Hash,
    HexString,
    Transaction,
    TransactionReceipt,
    WeiAmount,
)


class IChainInteractionPort(ABC):
    @abstractmethod
    async def get_block_number(self) -> BlockNumber: ...

    @abstractmethod
    async def get_block(self, block_identifier: BlockNumber | Hash, full_transactions: bool = False) -> Dict[str, Any]: ...

    @abstractmethod
    async def get_block_header(self, block_identifier: BlockNumber | Hash) -> BlockHeader: ...

    @abstractmethod
    async def get_transaction(self, tx_hash: Hash) -> Optional[Transaction]: ...

    @abstractmethod
    async def get_transaction_receipt(self, tx_hash: Hash) -> Optional[TransactionReceipt]: ...

    @abstractmethod
    async def get_balance(self, address: Address, block_identifier: BlockNumber | Hash | str = "latest") -> WeiAmount: ...

    @abstractmethod
    async def get_transaction_count(self, address: Address, block_identifier: BlockNumber | Hash | str = "latest") -> int: ...

    @abstractmethod
    async def get_gas_price(self) -> WeiAmount: ...

    @abstractmethod
    async def get_max_priority_fee_per_gas(self) -> WeiAmount: ...

    @abstractmethod
    async def estimate_gas(
        self,
        to: Optional[Address] = None,
        from_address: Optional[Address] = None,
        value: Optional[WeiAmount] = None,
        data: Optional[HexString] = None,
        gas_price: Optional[WeiAmount] = None,
    ) -> GasAmount: ...

    @abstractmethod
    async def call(
        self,
        to: Address,
        data: HexString,
        from_address: Optional[Address] = None,
        block_identifier: BlockNumber | Hash | str = "latest",
    ) -> HexString: ...

    @abstractmethod
    async def send_raw_transaction(self, raw_tx: HexString) -> Hash: ...

    @abstractmethod
    async def get_logs(
        self,
        from_block: Optional[BlockNumber] = None,
        to_block: Optional[BlockNumber | str] = None,
        address: Optional[Address | List[Address]] = None,
        topics: Optional[List[Optional[HexString]]] = None,
        block_hash: Optional[Hash] = None,
    ) -> List[EventLog]: ...

    @abstractmethod
    async def get_chain_id(self) -> ChainId: ...

    @abstractmethod
    async def get_code(self, address: Address, block_identifier: BlockNumber | Hash | str = "latest") -> HexString: ...

    @abstractmethod
    async def get_storage_at(
        self,
        address: Address,
        position: int,
        block_identifier: BlockNumber | Hash | str = "latest",
    ) -> HexString: ...

    @abstractmethod
    def create_filter(
        self,
        from_block: Optional[BlockNumber] = None,
        to_block: Optional[BlockNumber | str] = None,
        address: Optional[Address | List[Address]] = None,
        topics: Optional[List[Optional[HexString]]] = None,
    ) -> Any: ...

    @abstractmethod
    async def filter_new_entries(self, filter_id: Any) -> List[EventLog]: ...

    @abstractmethod
    async def subscribe_blocks(self) -> AsyncIterator[BlockHeader]: ...

    @abstractmethod
    async def subscribe_logs(
        self,
        address: Optional[Address | List[Address]] = None,
        topics: Optional[List[Optional[HexString]]] = None,
    ) -> AsyncIterator[EventLog]: ...

    @property
    @abstractmethod
    def chain(self) -> Chain: ...

    @property
    @abstractmethod
    def chain_id(self) -> ChainId: ...

    @property
    @abstractmethod
    def rpc_url(self) -> str: ...
