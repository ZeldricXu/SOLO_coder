import hashlib
import json
from dataclasses import dataclass
from typing import Any, Dict, List, Optional
from datetime import datetime, timezone

from eth_account import Account
from eth_utils import keccak
import bip32
import bip39

from ..config import get_settings
from ..db import async_session, WalletAddress, AddressTag
from ..utils import (
    get_logger,
    generate_id,
    WalletError,
    ValidationError,
    NotFoundError,
    to_checksum_address,
    validate_address,
)

logger = get_logger(__name__)


@dataclass
class DerivedAddress:
    address: str
    path: str
    index: int
    public_key: str
    private_key: Optional[str] = None


@dataclass
class CreateAddressTagRequest:
    address: str
    tag: str
    label: str
    category: str = "general"
    chain_id: int = 1
    metadata: Dict[str, Any] = None


class HDWalletModule:
    def __init__(self):
        self.settings = get_settings()
        self._initialized = False
        self._master_key = None
        self._address_cache: Dict[int, Dict[int, DerivedAddress]] = {}

    async def initialize(self) -> None:
        if self._initialized:
            return

        logger.info("Initializing HD wallet module")
        wallet_settings = self.settings.wallet

        if wallet_settings.mnemonic:
            try:
                seed = bip39.mnemonic_to_seed(wallet_settings.mnemonic, wallet_settings.passphrase)
                self._master_key = bip32.BIP32.from_seed(seed)
                logger.info("HD wallet master key derived from mnemonic")
            except Exception as e:
                logger.error(f"Failed to initialize HD wallet: {e}")
                raise WalletError(f"Failed to initialize HD wallet: {e}")
        else:
            logger.warning("No mnemonic configured, HD wallet functionality limited")

        self._initialized = True
        logger.info("HD wallet module initialized")

    async def shutdown(self) -> None:
        if not self._initialized:
            return
        logger.info("Shutting down HD wallet module")
        self._master_key = None
        self._address_cache.clear()
        self._initialized = False
        logger.info("HD wallet module shutdown complete")

    async def generate_mnemonic(self, word_count: int = 12) -> str:
        if word_count not in [12, 15, 18, 21, 24]:
            raise ValidationError("Word count must be 12, 15, 18, 21, or 24")

        strength = (word_count // 3) * 32
        return bip39.generate_mnemonic(strength)

    async def derive_address(
        self,
        index: int,
        derivation_path: Optional[str] = None,
        chain_id: int = 1,
        include_private_key: bool = False,
    ) -> DerivedAddress:
        if self._master_key is None:
            raise WalletError("HD wallet not initialized with mnemonic")

        path_template = derivation_path or self.settings.wallet.derivation_path
        path = path_template.format(index=index)

        if chain_id in self._address_cache and index in self._address_cache[chain_id]:
            cached = self._address_cache[chain_id][index]
            if not include_private_key:
                return DerivedAddress(
                    address=cached.address,
                    path=cached.path,
                    index=cached.index,
                    public_key=cached.public_key,
                )
            return cached

        try:
            child_key = self._master_key.derive_path(path)
            private_key_bytes = child_key.private_key

            if private_key_bytes is None:
                raise WalletError("Failed to derive private key")

            private_key_hex = private_key_bytes.hex()
            account = Account.from_key(private_key_hex)
            public_key = account.public_key.hex() if hasattr(account, 'public_key') else ""

            derived = DerivedAddress(
                address=account.address,
                path=path,
                index=index,
                public_key=public_key,
                private_key=private_key_hex if include_private_key else None,
            )

            if chain_id not in self._address_cache:
                self._address_cache[chain_id] = {}

            self._address_cache[chain_id][index] = DerivedAddress(
                address=derived.address,
                path=derived.path,
                index=derived.index,
                public_key=derived.public_key,
                private_key=private_key_hex,
            )

            if not include_private_key:
                derived.private_key = None

            return derived

        except Exception as e:
            logger.error(f"Failed to derive address at index {index}: {e}")
            raise WalletError(f"Failed to derive address: {e}")

    async def derive_addresses(
        self,
        start_index: int = 0,
        count: int = 10,
        derivation_path: Optional[str] = None,
        chain_id: int = 1,
    ) -> List[DerivedAddress]:
        addresses = []
        for i in range(start_index, start_index + count):
            addr = await self.derive_address(i, derivation_path, chain_id)
            addresses.append(addr)
        return addresses

    async def import_address(
        self,
        address: str,
        derivation_path: str,
        index: int,
        public_key: Optional[str] = None,
        chain_id: int = 1,
        tags: Optional[List[str]] = None,
        metadata: Optional[Dict[str, Any]] = None,
    ) -> Dict[str, Any]:
        if not validate_address(address):
            raise ValidationError(f"Invalid address: {address}")

        checksum_address = to_checksum_address(address)
        address_id = generate_id("addr")

        async with async_session() as session:
            existing = await session.get(WalletAddress, {"address": checksum_address})
            if existing:
                raise ValidationError(f"Address already imported: {checksum_address}")

            wallet_addr = WalletAddress(
                address_id=address_id,
                address=checksum_address,
                chain_id=chain_id,
                derivation_path=derivation_path,
                index=index,
                public_key=public_key,
                tags=tags or [],
                metadata=metadata or {},
            )
            session.add(wallet_addr)
            await session.commit()
            await session.refresh(wallet_addr)

            logger.info(f"Imported address {checksum_address} with ID {address_id}")

            return {
                "address_id": address_id,
                "address": checksum_address,
                "chain_id": chain_id,
                "derivation_path": derivation_path,
                "index": index,
                "tags": tags or [],
            }

    async def get_address(self, address_id: str) -> Optional[Dict[str, Any]]:
        async with async_session() as session:
            addr = await session.get(WalletAddress, {"address_id": address_id})
            if not addr:
                return None

            return {
                "address_id": addr.address_id,
                "address": addr.address,
                "chain_id": addr.chain_id,
                "derivation_path": addr.derivation_path,
                "index": addr.index,
                "public_key": addr.public_key,
                "is_used": addr.is_used,
                "balance": addr.balance,
                "tags": addr.tags,
                "metadata": addr.metadata,
                "created_at": addr.created_at.isoformat() if addr.created_at else None,
            }

    async def list_addresses(
        self,
        chain_id: Optional[int] = None,
        tag: Optional[str] = None,
        only_unused: bool = False,
        offset: int = 0,
        limit: int = 50,
    ) -> Dict[str, Any]:
        from sqlalchemy import select

        async with async_session() as session:
            query = select(WalletAddress)
            if chain_id:
                query = query.where(WalletAddress.chain_id == chain_id)
            if only_unused:
                query = query.where(WalletAddress.is_used == False)

            query = query.order_by(WalletAddress.created_at.desc()).offset(offset).limit(limit)
            result = await session.execute(query)
            addresses = result.scalars().all()

            if tag:
                addresses = [a for a in addresses if tag in a.tags]

            return {
                "addresses": [
                    {
                        "address_id": a.address_id,
                        "address": a.address,
                        "chain_id": a.chain_id,
                        "index": a.index,
                        "is_used": a.is_used,
                        "balance": a.balance,
                        "tags": a.tags,
                    }
                    for a in addresses
                ],
                "total": len(addresses),
                "offset": offset,
                "limit": limit,
            }

    async def update_address_tags(
        self,
        address_id: str,
        tags: List[str],
    ) -> Dict[str, Any]:
        async with async_session() as session:
            addr = await session.get(WalletAddress, {"address_id": address_id})
            if not addr:
                raise NotFoundError(f"Address {address_id} not found")

            addr.tags = list(set(tags))
            await session.commit()
            await session.refresh(addr)

            return {
                "address_id": address_id,
                "tags": addr.tags,
            }

    async def mark_address_used(self, address_id: str, used: bool = True) -> Dict[str, Any]:
        async with async_session() as session:
            addr = await session.get(WalletAddress, {"address_id": address_id})
            if not addr:
                raise NotFoundError(f"Address {address_id} not found")

            addr.is_used = used
            await session.commit()

            return {
                "address_id": address_id,
                "is_used": used,
            }

    async def create_address_tag(self, request: CreateAddressTagRequest) -> Dict[str, Any]:
        if not validate_address(request.address):
            raise ValidationError(f"Invalid address: {request.address}")

        checksum_address = to_checksum_address(request.address)
        tag_id = generate_id("tag")

        async with async_session() as session:
            address_tag = AddressTag(
                tag_id=tag_id,
                address=checksum_address,
                chain_id=request.chain_id,
                tag=request.tag,
                label=request.label,
                category=request.category,
                metadata=request.metadata or {},
            )
            session.add(address_tag)

            try:
                await session.commit()
                await session.refresh(address_tag)
            except Exception as e:
                raise ValidationError(f"Tag already exists for this address: {request.tag}")

            logger.info(f"Created tag {request.tag} for address {checksum_address}")

            return {
                "tag_id": tag_id,
                "address": checksum_address,
                "chain_id": request.chain_id,
                "tag": request.tag,
                "label": request.label,
                "category": request.category,
            }

    async def get_address_tags(self, address: str, chain_id: Optional[int] = None) -> List[Dict[str, Any]]:
        from sqlalchemy import select

        if not validate_address(address):
            raise ValidationError(f"Invalid address: {address}")

        checksum_address = to_checksum_address(address)

        async with async_session() as session:
            query = select(AddressTag).where(AddressTag.address == checksum_address)
            if chain_id:
                query = query.where(AddressTag.chain_id == chain_id)

            result = await session.execute(query)
            tags = result.scalars().all()

            return [
                {
                    "tag_id": t.tag_id,
                    "tag": t.tag,
                    "label": t.label,
                    "category": t.category,
                    "chain_id": t.chain_id,
                    "metadata": t.metadata,
                    "created_at": t.created_at.isoformat() if t.created_at else None,
                }
                for t in tags
            ]

    async def delete_address_tag(self, tag_id: str) -> None:
        async with async_session() as session:
            tag = await session.get(AddressTag, {"tag_id": tag_id})
            if not tag:
                raise NotFoundError(f"Tag {tag_id} not found")

            await session.delete(tag)
            await session.commit()

            logger.info(f"Deleted tag {tag_id}")

    async def search_by_tag(self, tag: str, chain_id: Optional[int] = None) -> List[Dict[str, Any]]:
        from sqlalchemy import select

        async with async_session() as session:
            query = select(AddressTag).where(AddressTag.tag.ilike(f"%{tag}%"))
            if chain_id:
                query = query.where(AddressTag.chain_id == chain_id)

            result = await session.execute(query)
            tags = result.scalars().all()

            return [
                {
                    "address": t.address,
                    "tag": t.tag,
                    "label": t.label,
                    "category": t.category,
                    "chain_id": t.chain_id,
                }
                for t in tags
            ]

    async def sign_message(self, address_index: int, message: str) -> Dict[str, Any]:
        derived = await self.derive_address(address_index, include_private_key=True)

        if not derived.private_key:
            raise WalletError("Private key not available for signing")

        try:
            from eth_account.messages import encode_defunct

            message_hash = encode_defunct(text=message)
            signed = Account.sign_message(message_hash, derived.private_key)

            return {
                "address": derived.address,
                "message": message,
                "signature": signed.signature.hex(),
                "v": signed.v,
                "r": hex(signed.r),
                "s": hex(signed.s),
            }
        except Exception as e:
            logger.error(f"Failed to sign message: {e}")
            raise WalletError(f"Failed to sign message: {e}")

    async def sign_transaction(self, address_index: int, transaction: Dict[str, Any]) -> Dict[str, Any]:
        derived = await self.derive_address(address_index, include_private_key=True)

        if not derived.private_key:
            raise WalletError("Private key not available for signing")

        try:
            signed = Account.sign_transaction(transaction, derived.private_key)

            return {
                "address": derived.address,
                "raw_transaction": signed.raw_transaction.hex(),
                "hash": signed.hash.hex(),
                "r": hex(signed.r),
                "s": hex(signed.s),
                "v": signed.v,
            }
        except Exception as e:
            logger.error(f"Failed to sign transaction: {e}")
            raise WalletError(f"Failed to sign transaction: {e}")

    async def verify_signature(self, message: str, signature: str, expected_address: str) -> bool:
        try:
            from eth_account.messages import encode_defunct

            message_hash = encode_defunct(text=message)
            recovered_address = Account.recover_message(message_hash, signature=signature)

            return recovered_address.lower() == expected_address.lower()
        except Exception as e:
            logger.error(f"Failed to verify signature: {e}")
            return False

    async def get_address_balance(self, address: str, chain_id: int = 1) -> Dict[str, Any]:
        from .chain_adapter import get_chain_adapter

        if not validate_address(address):
            raise ValidationError(f"Invalid address: {address}")

        chain_adapter = get_chain_adapter()
        balance = await chain_adapter.get_balance(chain_id, address)

        return {
            "address": address,
            "chain_id": chain_id,
            "balance_wei": balance,
            "balance_eth": balance / 1e18,
        }


_hd_wallet_module: Optional[HDWalletModule] = None


def get_hd_wallet_module() -> HDWalletModule:
    global _hd_wallet_module
    if _hd_wallet_module is None:
        _hd_wallet_module = HDWalletModule()
    return _hd_wallet_module
