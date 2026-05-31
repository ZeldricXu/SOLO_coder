from __future__ import annotations

from abc import ABC, abstractmethod
from typing import Dict, List, Optional

from src.shared.types import Address, Chain, HDWalletAccount, HexString


class IHDWalletPort(ABC):
    @abstractmethod
    async def generate_mnemonic(self, strength: int = 128) -> str: ...

    @abstractmethod
    async def create_wallet_from_mnemonic(
        self,
        mnemonic: str,
        passphrase: Optional[str] = None,
        hd_path: Optional[str] = None,
    ) -> bool: ...

    @abstractmethod
    async def derive_address(
        self,
        index: int,
        hd_path: Optional[str] = None,
        label: Optional[str] = None,
        tags: Optional[List[str]] = None,
    ) -> HDWalletAccount: ...

    @abstractmethod
    async def derive_next_address(
        self,
        label: Optional[str] = None,
        tags: Optional[List[str]] = None,
    ) -> HDWalletAccount: ...

    @abstractmethod
    async def get_address(self, index: int) -> Optional[HDWalletAccount]: ...

    @abstractmethod
    async def list_addresses(self) -> List[HDWalletAccount]: ...

    @abstractmethod
    async def get_public_key(self, index: int) -> Optional[HexString]: ...

    @abstractmethod
    async def get_private_key(self, index: int) -> Optional[HexString]: ...

    @abstractmethod
    async def sign_message(self, index: int, message: str | HexString) -> HexString: ...

    @abstractmethod
    async def verify_signature(
        self,
        address: Address,
        message: str | HexString,
        signature: HexString,
    ) -> bool: ...

    @abstractmethod
    def is_initialized(self) -> bool: ...


class IAddressBookPort(ABC):
    @abstractmethod
    async def add_address(
        self,
        address: Address,
        name: str,
        chain: Chain,
        labels: Optional[List[str]] = None,
        notes: Optional[str] = None,
    ) -> str: ...

    @abstractmethod
    async def remove_address(self, address: Address) -> bool: ...

    @abstractmethod
    async def get_address(self, address: Address) -> Optional[Dict[str, Any]]: ...

    @abstractmethod
    async def list_addresses(
        self,
        chain: Optional[Chain] = None,
        labels: Optional[List[str]] = None,
    ) -> List[Dict[str, Any]]: ...

    @abstractmethod
    async def update_address(
        self,
        address: Address,
        name: Optional[str] = None,
        labels: Optional[List[str]] = None,
        notes: Optional[str] = None,
    ) -> bool: ...

    @abstractmethod
    async def search_addresses(self, query: str) -> List[Dict[str, Any]]: ...

    @abstractmethod
    async def add_label(self, address: Address, label: str) -> bool: ...

    @abstractmethod
    async def remove_label(self, address: Address, label: str) -> bool: ...
