from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional
from datetime import datetime, timezone

from eth_account import Account
from eth_utils import to_checksum_address
from hdwallet import BIP44HDWallet
from hdwallet.cryptocurrencies import EthereumMainnet
from hdwallet.derivations import BIP44Derivation

from wallethub.core import AddressError
from wallethub.config import get_settings
from wallethub.utils import generate_id, generate_mnemonic, encrypt_data, decrypt_data


@dataclass
class DerivedAddress:
    address: str
    path: str
    index: int
    public_key: str
    private_key: Optional[str] = None
    label: Optional[str] = None
    tags: List[str] = field(default_factory=list)


class HDWalletManager:
    def __init__(self):
        self.settings = get_settings()
        self._wallets: Dict[str, Dict[str, Any]] = {}
        self._derived_addresses: Dict[str, Dict[int, DerivedAddress]] = {}

    def create_wallet(
        self,
        name: str,
        passphrase: str = "",
        language: str = "english",
        strength: int = 128,
        store_mnemonic: bool = True,
    ) -> Dict[str, Any]:
        try:
            mnemonic = generate_mnemonic(strength=strength)
            hdwallet = BIP44HDWallet(cryptocurrency=EthereumMainnet)
            hdwallet.from_mnemonic(mnemonic=mnemonic, passphrase=passphrase, language=language)

            wallet_id = generate_id("wallet")

            encrypted_mnemonic = None
            if store_mnemonic:
                encrypted_mnemonic = encrypt_data(mnemonic, self.settings.secret_key)

            wallet_data = {
                "wallet_id": wallet_id,
                "name": name,
                "mnemonic_encrypted": encrypted_mnemonic,
                "master_xpub": hdwallet.xpublic_key(),
                "master_xprv": encrypt_data(hdwallet.xprivate_key(), self.settings.secret_key),
                "chain_code": hdwallet.chain_code(),
                "parent_fingerprint": hdwallet.parent_fingerprint(),
                "depth": hdwallet.depth(),
                "network": "ethereum",
                "created_at": datetime.now(timezone.utc),
            }

            self._wallets[wallet_id] = wallet_data
            self._derived_addresses[wallet_id] = {}

            return {
                "wallet_id": wallet_id,
                "name": name,
                "mnemonic": mnemonic if store_mnemonic else None,
                "master_xpub": wallet_data["master_xpub"],
            }
        except Exception as e:
            raise AddressError(f"Failed to create wallet: {str(e)}")

    def import_wallet(
        self,
        name: str,
        mnemonic: str,
        passphrase: str = "",
        language: str = "english",
    ) -> Dict[str, Any]:
        try:
            hdwallet = BIP44HDWallet(cryptocurrency=EthereumMainnet)
            hdwallet.from_mnemonic(mnemonic=mnemonic, passphrase=passphrase, language=language)

            wallet_id = generate_id("wallet")

            wallet_data = {
                "wallet_id": wallet_id,
                "name": name,
                "mnemonic_encrypted": encrypt_data(mnemonic, self.settings.secret_key),
                "master_xpub": hdwallet.xpublic_key(),
                "master_xprv": encrypt_data(hdwallet.xprivate_key(), self.settings.secret_key),
                "chain_code": hdwallet.chain_code(),
                "parent_fingerprint": hdwallet.parent_fingerprint(),
                "depth": hdwallet.depth(),
                "network": "ethereum",
                "created_at": datetime.now(timezone.utc),
            }

            self._wallets[wallet_id] = wallet_data
            self._derived_addresses[wallet_id] = {}

            return {
                "wallet_id": wallet_id,
                "name": name,
                "master_xpub": wallet_data["master_xpub"],
            }
        except Exception as e:
            raise AddressError(f"Failed to import wallet: {str(e)}")

    def derive_address(
        self,
        wallet_id: str,
        index: int = 0,
        derivation_path: Optional[str] = None,
        include_private_key: bool = False,
    ) -> DerivedAddress:
        if wallet_id not in self._wallets:
            raise AddressError(f"Wallet {wallet_id} not found")

        if wallet_id in self._derived_addresses and index in self._derived_addresses[wallet_id]:
            addr = self._derived_addresses[wallet_id][index]
            if not include_private_key:
                addr.private_key = None
            return addr

        try:
            wallet_data = self._wallets[wallet_id]
            master_xprv = decrypt_data(wallet_data["master_xprv"], self.settings.secret_key)

            hdwallet = BIP44HDWallet(cryptocurrency=EthereumMainnet)
            hdwallet.from_xprivate_key(xprivate_key=master_xprv)

            if derivation_path:
                hdwallet.from_path(path=derivation_path)
            else:
                derivation = BIP44Derivation(
                    cryptocurrency=EthereumMainnet,
                    account=0,
                    change=False,
                    address=index,
                )
                hdwallet.from_derivation(derivation=derivation)
                derivation_path = str(derivation)

            private_key = hdwallet.private_key()

            derived = DerivedAddress(
                address=to_checksum_address(hdwallet.address()),
                path=derivation_path,
                index=index,
                public_key=hdwallet.public_key(),
                private_key=private_key if include_private_key else None,
            )

            if wallet_id not in self._derived_addresses:
                self._derived_addresses[wallet_id] = {}
            self._derived_addresses[wallet_id][index] = derived

            return derived
        except Exception as e:
            raise AddressError(f"Failed to derive address: {str(e)}")

    def derive_addresses(
        self,
        wallet_id: str,
        start_index: int = 0,
        count: int = 10,
        include_private_keys: bool = False,
    ) -> List[DerivedAddress]:
        return [
            self.derive_address(wallet_id, i, include_private_key=include_private_keys)
            for i in range(start_index, start_index + count)
        ]

    def get_wallet(self, wallet_id: str) -> Optional[Dict[str, Any]]:
        wallet = self._wallets.get(wallet_id)
        if not wallet:
            return None

        return {
            "wallet_id": wallet["wallet_id"],
            "name": wallet["name"],
            "master_xpub": wallet["master_xpub"],
            "network": wallet["network"],
            "depth": wallet["depth"],
            "created_at": wallet["created_at"],
        }

    def list_wallets(self) -> List[Dict[str, Any]]:
        return [self.get_wallet(wid) for wid in self._wallets.keys()]

    def delete_wallet(self, wallet_id: str) -> None:
        if wallet_id in self._wallets:
            del self._wallets[wallet_id]
        if wallet_id in self._derived_addresses:
            del self._derived_addresses[wallet_id]

    def get_mnemonic(self, wallet_id: str) -> Optional[str]:
        wallet = self._wallets.get(wallet_id)
        if not wallet or not wallet.get("mnemonic_encrypted"):
            return None
        return decrypt_data(wallet["mnemonic_encrypted"], self.settings.secret_key)

    def get_private_key(self, wallet_id: str, index: int) -> Optional[str]:
        if wallet_id not in self._wallets:
            return None

        if wallet_id in self._derived_addresses and index in self._derived_addresses[wallet_id]:
            return self._derived_addresses[wallet_id][index].private_key

        derived = self.derive_address(wallet_id, index, include_private_key=True)
        return derived.private_key

    @staticmethod
    def from_private_key(private_key: str) -> Dict[str, str]:
        account = Account.from_key(private_key)
        return {
            "address": account.address,
            "private_key": private_key,
        }

    @staticmethod
    def generate_private_key() -> str:
        account = Account.create()
        return account.key.hex()

    @staticmethod
    def is_valid_address(address: str) -> bool:
        try:
            to_checksum_address(address)
            return True
        except Exception:
            return False

    def list_derived_addresses(
        self,
        wallet_id: str,
        include_private_keys: bool = False,
    ) -> List[DerivedAddress]:
        if wallet_id not in self._derived_addresses:
            return []

        addresses = list(self._derived_addresses[wallet_id].values())
        if not include_private_keys:
            for addr in addresses:
                addr.private_key = None
        return addresses
