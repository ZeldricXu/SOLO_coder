import hashlib
import os
import secrets
from typing import Optional, Union
from cryptography.fernet import Fernet, InvalidToken
from eth_account import Account
from eth_account.messages import encode_defunct
from eth_utils import keccak
import base58
import bip39


def generate_mnemonic(strength: int = 128) -> str:
    return bip39.generate_mnemonic(strength=strength)


def derive_seed_from_mnemonic(mnemonic: str, passphrase: str = "") -> bytes:
    return bip39.mnemonic_to_seed(mnemonic, passphrase)


def get_fernet_key(secret_key: str) -> bytes:
    hashed = hashlib.sha256(secret_key.encode()).digest()
    return base58.b58encode(hashed)


def encrypt_data(data: str, secret_key: str) -> str:
    key = get_fernet_key(secret_key)
    f = Fernet(key)
    return f.encrypt(data.encode()).decode()


def decrypt_data(encrypted_data: str, secret_key: str) -> str:
    try:
        key = get_fernet_key(secret_key)
        f = Fernet(key)
        return f.decrypt(encrypted_data.encode()).decode()
    except InvalidToken:
        raise ValueError("Invalid encryption key or corrupted data")


def sha256_hash(data: Union[bytes, str]) -> str:
    if isinstance(data, str):
        data = data.encode()
    return hashlib.sha256(data).hexdigest()


def keccak256_hash(data: Union[bytes, str]) -> str:
    if isinstance(data, str):
        data = data.encode()
    return "0x" + keccak(data).hex()


def verify_signature(message: str, signature: str, expected_address: str) -> bool:
    try:
        encoded_message = encode_defunct(text=message)
        recovered_address = Account.recover_message(
            encoded_message, signature=signature
        )
        return recovered_address.lower() == expected_address.lower()
    except Exception:
        return False


def random_bytes(length: int = 32) -> bytes:
    return secrets.token_bytes(length)


def random_hex(length: int = 32) -> str:
    return "0x" + secrets.token_hex(length)
