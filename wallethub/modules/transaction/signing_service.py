from dataclasses import dataclass, field
from typing import Any, Callable, Dict, Optional, Union
from eth_account import Account
from eth_account.datastructures import SignedTransaction
from eth_account.messages import encode_defunct, SignableMessage
from eth_utils import to_checksum_address
import threading

from wallethub.core import SigningError, Address
from wallethub.config import get_settings
from wallethub.utils import decrypt_data, encrypt_data, generate_id


@dataclass
class KeyStoreEntry:
    key_id: str
    address: str
    encrypted_private_key: str
    chain: str
    metadata: Dict[str, Any] = field(default_factory=dict)


class TransactionSigner:
    def __init__(self, private_key: str, chain: str = "ethereum"):
        self._private_key = private_key
        self._chain = chain
        self._account = Account.from_key(private_key)

    @property
    def address(self) -> str:
        return self._account.address

    def sign_transaction(self, tx_dict: Dict[str, Any]) -> SignedTransaction:
        try:
            return Account.sign_transaction(tx_dict, self._private_key)
        except Exception as e:
            raise SigningError(
                message=f"Failed to sign transaction: {str(e)}",
                details={"operation": "sign_transaction", "error_type": type(e).__name__}
            )

    def sign_message(self, message: Union[str, SignableMessage]) -> str:
        if isinstance(message, str):
            message = encode_defunct(text=message)
        try:
            signed = Account.sign_message(message, self._private_key)
            return signed.signature.hex()
        except Exception as e:
            raise SigningError(
                message=f"Failed to sign message: {str(e)}",
                details={"operation": "sign_message", "error_type": type(e).__name__}
            )

    def sign_typed_data(self, structured_data: Dict[str, Any]) -> str:
        try:
            signed = Account.sign_typed_data(self._private_key, structured_data)
            return signed.signature.hex()
        except Exception as e:
            raise SigningError(
                message=f"Failed to sign typed data: {str(e)}",
                details={"operation": "sign_typed_data", "error_type": type(e).__name__}
            )


class SigningService:
    def __init__(self):
        self.settings = get_settings()
        self._key_store: Dict[str, KeyStoreEntry] = {}
        self._signers: Dict[str, TransactionSigner] = {}
        self._external_signers: Dict[str, Callable[[Any], str]] = {}
        self._lock = threading.RLock()

    def import_private_key(
        self,
        private_key: str,
        chain: str = "ethereum",
        passphrase: Optional[str] = None,
    ) -> KeyStoreEntry:
        if not private_key:
            raise SigningError(
                message="Private key cannot be empty",
                details={"operation": "import_private_key", "validation_failed": "empty_key"}
            )

        encryption_key = passphrase or self.settings.secret_key
        if not encryption_key:
            raise SigningError(
                message="Encryption key not configured",
                details={"operation": "import_private_key", "validation_failed": "no_encryption_key"}
            )

        try:
            account = Account.from_key(private_key)
        except Exception as e:
            raise SigningError(
                message=f"Invalid private key: {str(e)}",
                details={"operation": "import_private_key", "error_type": type(e).__name__}
            )

        try:
            encrypted_key = encrypt_data(private_key, encryption_key)
        except Exception as e:
            raise SigningError(
                message=f"Failed to encrypt private key: {str(e)}",
                details={"operation": "import_private_key", "step": "encryption", "error_type": type(e).__name__}
            )

        key_id = generate_id("key")
        entry = KeyStoreEntry(
            key_id=key_id,
            address=account.address,
            encrypted_private_key=encrypted_key,
            chain=chain,
        )

        with self._lock:
            if account.address in [e.address for e in self._key_store.values()]:
                raise SigningError(
                    message=f"Private key for address {account.address} already exists",
                    details={
                        "operation": "import_private_key",
                        "address": account.address,
                        "conflict": "duplicate_address"
                    }
                )
            self._key_store[key_id] = entry

        return entry

    def create_signer(
        self,
        key_id: str,
        passphrase: Optional[str] = None,
    ) -> TransactionSigner:
        if not key_id:
            raise SigningError(
                message="Key ID cannot be empty",
                details={"operation": "create_signer", "validation_failed": "empty_key_id"}
            )

        with self._lock:
            if key_id in self._signers:
                return self._signers[key_id]

            entry = self._key_store.get(key_id)
            if not entry:
                raise SigningError(
                    message=f"Key {key_id} not found",
                    details={"operation": "create_signer", "key_id": key_id, "error": "key_not_found"}
                )

            encryption_key = passphrase or self.settings.secret_key
            try:
                private_key = decrypt_data(entry.encrypted_private_key, encryption_key)
            except Exception as e:
                raise SigningError(
                    message=f"Failed to decrypt key: {str(e)}",
                    details={
                        "operation": "create_signer",
                        "key_id": key_id,
                        "step": "decryption",
                        "error_type": type(e).__name__
                    }
                )

            try:
                signer = TransactionSigner(private_key, entry.chain)
            except Exception as e:
                raise SigningError(
                    message=f"Failed to create signer: {str(e)}",
                    details={
                        "operation": "create_signer",
                        "key_id": key_id,
                        "step": "signer_creation",
                        "error_type": type(e).__name__
                    }
                )

            self._signers[key_id] = signer
            return signer

    def get_signer(self, key_id: str) -> Optional[TransactionSigner]:
        return self._signers.get(key_id)

    def remove_signer(self, key_id: str) -> None:
        with self._lock:
            if key_id in self._signers:
                del self._signers[key_id]

    def register_external_signer(
        self,
        signer_id: str,
        signer_fn: Callable[[Any], str],
    ) -> None:
        if not signer_id:
            raise SigningError(
                message="Signer ID cannot be empty",
                details={"operation": "register_external_signer", "validation_failed": "empty_signer_id"}
            )
        if not callable(signer_fn):
            raise SigningError(
                message="Signer function must be callable",
                details={"operation": "register_external_signer", "validation_failed": "not_callable"}
            )
        with self._lock:
            self._external_signers[signer_id] = signer_fn

    def sign_with_external(
        self,
        signer_id: str,
        data: Any,
    ) -> str:
        if signer_id not in self._external_signers:
            raise SigningError(
                message=f"External signer {signer_id} not registered",
                details={"operation": "sign_with_external", "signer_id": signer_id, "error": "signer_not_found"}
            )
        try:
            return self._external_signers[signer_id](data)
        except Exception as e:
            raise SigningError(
                message=f"External signing failed: {str(e)}",
                details={
                    "operation": "sign_with_external",
                    "signer_id": signer_id,
                    "error_type": type(e).__name__
                }
            )

    def list_keys(self) -> list[KeyStoreEntry]:
        with self._lock:
            return list(self._key_store.values())

    def get_key(self, key_id: str) -> Optional[KeyStoreEntry]:
        return self._key_store.get(key_id)

    def delete_key(self, key_id: str) -> None:
        if not key_id:
            raise SigningError(
                message="Key ID cannot be empty",
                details={"operation": "delete_key", "validation_failed": "empty_key_id"}
            )
        with self._lock:
            existed = key_id in self._key_store
            if key_id in self._key_store:
                del self._key_store[key_id]
            if key_id in self._signers:
                del self._signers[key_id]
            if not existed:
                raise SigningError(
                    message=f"Key {key_id} not found",
                    details={"operation": "delete_key", "key_id": key_id, "error": "key_not_found"}
                )

    @staticmethod
    def verify_signature(
        message: str,
        signature: str,
        expected_address: str,
    ) -> bool:
        if not message or not signature or not expected_address:
            return False
        try:
            encoded_message = encode_defunct(text=message)
            recovered_address = Account.recover_message(
                encoded_message, signature=signature
            )
            return recovered_address.lower() == expected_address.lower()
        except Exception:
            return False

    @staticmethod
    def generate_new_key() -> tuple[str, str]:
        account = Account.create()
        return account.key.hex(), account.address
