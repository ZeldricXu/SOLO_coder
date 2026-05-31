from __future__ import annotations

import hashlib
from typing import Dict, List, Optional

from eth_account import Account
from eth_account.messages import encode_defunct, SignableMessage
from eth_utils import to_checksum_address, keccak

from src.core.ports.wallet_port import IAddressBookPort, IHDWalletPort
from src.shared.config import settings
from src.shared.errors import (
    AddressNotFoundError,
    InvalidMnemonicError,
    WalletError,
)
from src.shared.logger import get_logger
from src.shared.types import Address, Chain, HDWalletAccount, HexString

logger = get_logger(__name__)

try:
    from bip_utils import Bip39SeedGenerator, Bip44, Bip44Coins, Bip44Changes
    BIP_UTILS_AVAILABLE = True
except ImportError:
    BIP_UTILS_AVAILABLE = False

try:
    import mnemonic
    MNEMONIC_AVAILABLE = True
except ImportError:
    MNEMONIC_AVAILABLE = False


class HDWalletService(IHDWalletPort):
    def __init__(
        self,
        hd_path: Optional[str] = None,
        default_chain: Optional[str] = None,
    ):
        self._hd_path = hd_path or settings.wallet.hd_path
        self._default_chain = default_chain or settings.wallet.default_chain
        self._seed: Optional[bytes] = None
        self._accounts: Dict[int, HDWalletAccount] = {}
        self._next_index = 0
        self._private_keys: Dict[int, HexString] = {}

    def is_initialized(self) -> bool:
        return self._seed is not None

    async def generate_mnemonic(self, strength: int = 128) -> str:
        if not MNEMONIC_AVAILABLE:
            raise WalletError("mnemonic package not installed")

        if strength not in [128, 160, 192, 224, 256]:
            raise ValueError(f"Invalid strength: {strength}. Must be 128, 160, 192, 224, or 256")

        m = mnemonic.Mnemonic("english")
        return m.generate(strength)

    async def create_wallet_from_mnemonic(
        self,
        mnemonic: str,
        passphrase: Optional[str] = None,
        hd_path: Optional[str] = None,
    ) -> bool:
        if not MNEMONIC_AVAILABLE or not BIP_UTILS_AVAILABLE:
            raise WalletError("Required packages (mnemonic, bip-utils) not installed")

        m = mnemonic.Mnemonic("english")
        if not m.check(mnemonic):
            raise InvalidMnemonicError("Invalid mnemonic phrase")

        try:
            self._seed = Bip39SeedGenerator(mnemonic).Generate(passphrase or "")
            self._hd_path = hd_path or self._hd_path
            self._accounts.clear()
            self._private_keys.clear()
            self._next_index = 0

            logger.info("HD wallet created from mnemonic", hd_path=self._hd_path)
            return True
        except Exception as e:
            raise WalletError(f"Failed to create wallet: {e}")

    def _derive_key_pair(self, index: int, hd_path: Optional[str] = None) -> tuple[str, str, str]:
        if not self._seed:
            raise WalletError("Wallet not initialized")

        path = hd_path or self._hd_path
        full_path = f"{path}/{index}"

        try:
            bip44_mst = Bip44.FromSeed(self._seed, Bip44Coins.ETHEREUM)
            bip44_acc = bip44_mst.Purpose().Coin().Account(0).Change(Bip44Changes.CHAIN_EXT).AddressIndex(index)

            private_key = bip44_acc.PrivateKey().Raw().ToHex()
            public_key = bip44_acc.PublicKey().RawCompressed().ToHex()
            address = bip44_acc.PublicKey().ToAddress()

            return private_key, public_key, address
        except Exception as e:
            raise WalletError(f"Failed to derive address: {e}")

    async def derive_address(
        self,
        index: int,
        hd_path: Optional[str] = None,
        label: Optional[str] = None,
        tags: Optional[List[str]] = None,
    ) -> HDWalletAccount:
        if index in self._accounts:
            account = self._accounts[index]
            if label:
                account.label = label
            if tags:
                account.tags = list(set(account.tags + tags))
            return account

        private_key, public_key, address = self._derive_key_pair(index, hd_path)

        account = HDWalletAccount(
            address=to_checksum_address(address),
            path=f"{hd_path or self._hd_path}/{index}",
            index=index,
            public_key=public_key,
            label=label,
            tags=tags or [],
        )

        self._accounts[index] = account
        self._private_keys[index] = private_key
        self._next_index = max(self._next_index, index + 1)

        logger.info(f"Address derived: {account.address}", index=index, path=account.path)
        return account

    async def derive_next_address(
        self,
        label: Optional[str] = None,
        tags: Optional[List[str]] = None,
    ) -> HDWalletAccount:
        return await self.derive_address(
            index=self._next_index,
            label=label,
            tags=tags,
        )

    async def get_address(self, index: int) -> Optional[HDWalletAccount]:
        if index not in self._accounts:
            try:
                await self.derive_address(index)
            except Exception:
                return None
        return self._accounts.get(index)

    async def list_addresses(self) -> List[HDWalletAccount]:
        return list(self._accounts.values())

    async def get_public_key(self, index: int) -> Optional[HexString]:
        account = await self.get_address(index)
        return account.public_key if account else None

    async def get_private_key(self, index: int) -> Optional[HexString]:
        if index not in self._private_keys:
            try:
                await self.derive_address(index)
            except Exception:
                return None
        return self._private_keys.get(index)

    async def sign_message(self, index: int, message: str | HexString) -> HexString:
        private_key = await self.get_private_key(index)
        if not private_key:
            raise AddressNotFoundError(f"No account at index {index}")

        try:
            if isinstance(message, str) and message.startswith("0x"):
                msg_bytes = bytes.fromhex(message[2:])
                signable_msg = SignableMessage(
                    b"\x19Ethereum Signed Message:\n" + str(len(msg_bytes)).encode() + msg_bytes,
                    b"E",
                    msg_bytes,
                )
            else:
                signable_msg = encode_defunct(text=str(message))

            signed = Account.sign_message(signable_msg, private_key=private_key)
            return signed.signature.hex()
        except Exception as e:
            raise WalletError(f"Failed to sign message: {e}")

    async def verify_signature(
        self,
        address: Address,
        message: str | HexString,
        signature: HexString,
    ) -> bool:
        try:
            if isinstance(message, str) and message.startswith("0x"):
                msg_bytes = bytes.fromhex(message[2:])
                signable_msg = SignableMessage(
                    b"\x19Ethereum Signed Message:\n" + str(len(msg_bytes)).encode() + msg_bytes,
                    b"E",
                    msg_bytes,
                )
            else:
                signable_msg = encode_defunct(text=str(message))

            recovered = Account.recover_message(signable_msg, signature=signature)
            return to_checksum_address(recovered) == to_checksum_address(address)
        except Exception as e:
            logger.warning(f"Signature verification failed: {e}")
            return False


class AddressBookService(IAddressBookPort):
    def __init__(self):
        self._entries: Dict[str, Dict[str, Any]] = {}

    async def add_address(
        self,
        address: Address,
        name: str,
        chain: Chain,
        labels: Optional[List[str]] = None,
        notes: Optional[str] = None,
    ) -> str:
        key = f"{chain.value}:{address.lower()}"
        self._entries[key] = {
            "address": to_checksum_address(address),
            "name": name,
            "chain": chain,
            "labels": labels or [],
            "notes": notes,
            "created_at": __import__("datetime").datetime.utcnow(),
        }

        logger.info(f"Address added to address book: {address}", name=name, chain=chain.value)
        return key

    async def remove_address(self, address: Address) -> bool:
        keys_to_remove = [k for k in self._entries if k.endswith(f":{address.lower()}")]
        for key in keys_to_remove:
            del self._entries[key]
            logger.info(f"Address removed from address book: {address}")
        return len(keys_to_remove) > 0

    async def get_address(self, address: Address) -> Optional[Dict[str, Any]]:
        for key, entry in self._entries.items():
            if key.endswith(f":{address.lower()}"):
                return entry.copy()
        return None

    async def list_addresses(
        self,
        chain: Optional[Chain] = None,
        labels: Optional[List[str]] = None,
    ) -> List[Dict[str, Any]]:
        results = []
        for entry in self._entries.values():
            if chain and entry["chain"] != chain:
                continue
            if labels and not all(label in entry["labels"] for label in labels):
                continue
            results.append(entry.copy())
        return results

    async def update_address(
        self,
        address: Address,
        name: Optional[str] = None,
        labels: Optional[List[str]] = None,
        notes: Optional[str] = None,
    ) -> bool:
        entry = await self.get_address(address)
        if not entry:
            return False

        key = f"{entry['chain'].value}:{address.lower()}"
        if name:
            self._entries[key]["name"] = name
        if labels is not None:
            self._entries[key]["labels"] = labels
        if notes is not None:
            self._entries[key]["notes"] = notes

        logger.info(f"Address updated: {address}")
        return True

    async def search_addresses(self, query: str) -> List[Dict[str, Any]]:
        query_lower = query.lower()
        results = []
        for entry in self._entries.values():
            if (
                query_lower in entry["name"].lower()
                or query_lower in entry["address"].lower()
                or any(query_lower in label.lower() for label in entry["labels"])
            ):
                results.append(entry.copy())
        return results

    async def add_label(self, address: Address, label: str) -> bool:
        entry = await self.get_address(address)
        if not entry:
            return False

        key = f"{entry['chain'].value}:{address.lower()}"
        if label not in self._entries[key]["labels"]:
            self._entries[key]["labels"].append(label)
            logger.info(f"Label added: {label} to {address}")
        return True

    async def remove_label(self, address: Address, label: str) -> bool:
        entry = await self.get_address(address)
        if not entry:
            return False

        key = f"{entry['chain'].value}:{address.lower()}"
        if label in self._entries[key]["labels"]:
            self._entries[key]["labels"].remove(label)
            logger.info(f"Label removed: {label} from {address}")
            return True
        return False
