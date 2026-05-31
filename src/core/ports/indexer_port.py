from __future__ import annotations

from abc import ABC, abstractmethod
from typing import Any, AsyncIterator, Dict, List, Optional

from src.shared.types import BlockHeader, BlockNumber, EventLog, Hash, Transaction, TransactionReceipt


class IDataIndexerPort(ABC):
    @abstractmethod
    async def start_indexing(
        self,
        start_block: Optional[BlockNumber] = None,
        end_block: Optional[BlockNumber] = None,
    ) -> None: ...

    @abstractmethod
    async def stop_indexing(self) -> None: ...

    @abstractmethod
    async def index_block(
        self,
        block_number: BlockNumber,
    ) -> Dict[str, Any]: ...

    @abstractmethod
    async def parse_block(
        self,
        block_data: Dict[str, Any],
    ) -> BlockHeader: ...

    @abstractmethod
    async def parse_transaction(
        self,
        tx_data: Dict[str, Any],
    ) -> Transaction: ...

    @abstractmethod
    async def parse_receipt(
        self,
        receipt_data: Dict[str, Any],
    ) -> TransactionReceipt: ...

    @abstractmethod
    async def parse_log(
        self,
        log_data: Dict[str, Any],
    ) -> EventLog: ...

    @abstractmethod
    async def get_index_status(self) -> Dict[str, Any]: ...

    @abstractmethod
    async def get_latest_indexed_block(self) -> BlockNumber: ...

    @abstractmethod
    async def reindex(
        self,
        from_block: BlockNumber,
        to_block: Optional[BlockNumber] = None,
    ) -> bool: ...

    @abstractmethod
    def is_indexing(self) -> bool: ...


class IBlockIterator(ABC):
    @abstractmethod
    async def iter_blocks(
        self,
        start_block: BlockNumber,
        end_block: Optional[BlockNumber] = None,
        batch_size: int = 100,
    ) -> AsyncIterator[List[BlockHeader]]: ...

    @abstractmethod
    async def iter_transactions(
        self,
        start_block: BlockNumber,
        end_block: Optional[BlockNumber] = None,
    ) -> AsyncIterator[Transaction]: ...

    @abstractmethod
    async def iter_logs(
        self,
        start_block: BlockNumber,
        end_block: Optional[BlockNumber] = None,
        address: Optional[str] = None,
    ) -> AsyncIterator[EventLog]: ...


class IDataStore(ABC):
    @abstractmethod
    async def store_block(self, block: BlockHeader) -> None: ...

    @abstractmethod
    async def store_transaction(self, transaction: Transaction) -> None: ...

    @abstractmethod
    async def store_receipt(self, receipt: TransactionReceipt) -> None: ...

    @abstractmethod
    async def store_log(self, log: EventLog) -> None: ...

    @abstractmethod
    async def get_block(self, block_number: BlockNumber) -> Optional[BlockHeader]: ...

    @abstractmethod
    async def get_transaction(self, tx_hash: Hash) -> Optional[Transaction]: ...

    @abstractmethod
    async def get_transactions_by_address(
        self,
        address: str,
        limit: int = 100,
        offset: int = 0,
    ) -> List[Transaction]: ...

    @abstractmethod
    async def get_logs_by_contract(
        self,
        contract_address: str,
        event_signature: Optional[str] = None,
        limit: int = 100,
        offset: int = 0,
    ) -> List[EventLog]: ...
