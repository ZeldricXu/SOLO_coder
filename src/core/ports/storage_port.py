from __future__ import annotations

from abc import ABC, abstractmethod
from typing import Any, Dict, List, Optional

from src.shared.types import StoredContent


class IDecentralizedStoragePort(ABC):
    @abstractmethod
    async def upload_data(
        self,
        data: bytes | str,
        metadata: Optional[Dict[str, Any]] = None,
    ) -> StoredContent: ...

    @abstractmethod
    async def upload_file(
        self,
        file_path: str,
        metadata: Optional[Dict[str, Any]] = None,
    ) -> StoredContent: ...

    @abstractmethod
    async def download_data(
        self,
        cid: str,
    ) -> bytes: ...

    @abstractmethod
    async def download_to_file(
        self,
        cid: str,
        output_path: str,
    ) -> bool: ...

    @abstractmethod
    async def pin_content(
        self,
        cid: str,
    ) -> bool: ...

    @abstractmethod
    async def unpin_content(
        self,
        cid: str,
    ) -> bool: ...

    @abstractmethod
    async def is_pinned(
        self,
        cid: str,
    ) -> bool: ...

    @abstractmethod
    async def list_pinned(
        self,
        limit: int = 100,
        offset: int = 0,
    ) -> List[str]: ...

    @abstractmethod
    async def get_content_size(
        self,
        cid: str,
    ) -> int: ...

    @abstractmethod
    async def get_gateway_url(
        self,
        cid: str,
    ) -> str: ...

    @abstractmethod
    async def calculate_cid(
        self,
        data: bytes,
    ) -> str: ...

    @property
    @abstractmethod
    def network_name(self) -> str: ...


class IIPFSPort(IDecentralizedStoragePort):
    @abstractmethod
    async def add_ipns(
        self,
        cid: str,
        key_name: Optional[str] = None,
    ) -> str: ...

    @abstractmethod
    async def resolve_ipns(
        self,
        name: str,
    ) -> str: ...

    @abstractmethod
    async def dag_put(
        self,
        data: Dict[str, Any],
    ) -> str: ...

    @abstractmethod
    async def dag_get(
        self,
        cid: str,
    ) -> Dict[str, Any]: ...

    @abstractmethod
    async def pubsub_publish(
        self,
        topic: str,
        data: bytes,
    ) -> bool: ...

    @abstractmethod
    async def pubsub_subscribe(
        self,
        topic: str,
    ) -> Any: ...


class IArweavePort(IDecentralizedStoragePort):
    @abstractmethod
    async def get_transaction_status(
        self,
        tx_id: str,
    ) -> Dict[str, Any]: ...

    @abstractmethod
    async def get_wallet_balance(
        self,
        wallet_address: str,
    ) -> int: ...

    @abstractmethod
    async def create_transaction(
        self,
        data: bytes,
        wallet_key: Dict[str, Any],
        tags: Optional[Dict[str, str]] = None,
    ) -> str: ...

    @abstractmethod
    async def get_price(
        self,
        data_size: int,
    ) -> int: ...
