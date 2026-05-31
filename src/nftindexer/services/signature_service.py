import json
from typing import Any, Dict, List, Optional

from eth_account import Account
from eth_account.messages import encode_defunct, encode_structured_data
from eth_utils import keccak

from ..interfaces.services import ISignatureVerifier
from ..utils import get_logger, to_checksum_address

logger = get_logger(__name__)


class SignatureVerifierService(ISignatureVerifier):
    EIP712_DOMAIN = {
        "name": "Gnosis Safe",
        "version": "1.3.0",
    }

    def __init__(self, domain_name: str = "Gnosis Safe", domain_version: str = "1.3.0"):
        self._domain_name = domain_name
        self._domain_version = domain_version

    def verify_signature(self, message_hash: str, signer: str, signature: str, chain_id: int) -> bool:
        try:
            message_hash_obj = encode_defunct(hexstr=message_hash)
            recovered_address = Account.recover_message(message_hash_obj, signature=signature)
            return recovered_address.lower() == signer.lower()
        except Exception as e:
            logger.error(f"Signature verification failed: {e}")
            return False

    def compute_safe_tx_hash(
        self,
        wallet_address: str,
        chain_id: int,
        to: str,
        value: int,
        data: str,
        operation: int,
        safe_tx_gas: int,
        base_gas: int,
        gas_price: int,
        gas_token: str,
        refund_receiver: str,
        nonce: int,
    ) -> str:
        domain_separator = keccak(
            encode_structured_data(
                {
                    "types": {
                        "EIP712Domain": [
                            {"name": "name", "type": "string"},
                            {"name": "version", "type": "string"},
                            {"name": "chainId", "type": "uint256"},
                            {"name": "verifyingContract", "type": "address"},
                        ],
                        "SafeTx": [
                            {"name": "to", "type": "address"},
                            {"name": "value", "type": "uint256"},
                            {"name": "data", "type": "bytes"},
                            {"name": "operation", "type": "uint8"},
                            {"name": "safeTxGas", "type": "uint256"},
                            {"name": "baseGas", "type": "uint256"},
                            {"name": "gasPrice", "type": "uint256"},
                            {"name": "gasToken", "type": "address"},
                            {"name": "refundReceiver", "type": "address"},
                            {"name": "nonce", "type": "uint256"},
                        ],
                    },
                    "domain": {
                        "name": self._domain_name,
                        "version": self._domain_version,
                        "chainId": chain_id,
                        "verifyingContract": wallet_address,
                    },
                    "primaryType": "SafeTx",
                    "message": {
                        "to": to,
                        "value": value,
                        "data": data,
                        "operation": operation,
                        "safeTxGas": safe_tx_gas,
                        "baseGas": base_gas,
                        "gasPrice": gas_price,
                        "gasToken": gas_token,
                        "refundReceiver": refund_receiver,
                        "nonce": nonce,
                    },
                }
            )
        )
        return "0x" + domain_separator.hex()

    def compute_wallet_address(self, signers: List[str], threshold: int, chain_id: int) -> str:
        sorted_signers = sorted(signers, key=lambda s: s.lower())
        data = json.dumps(
            {"signers": sorted_signers, "threshold": threshold, "chain_id": chain_id},
            sort_keys=True,
        )
        hash_bytes = keccak(text=data)
        address = "0x" + hash_bytes.hex()[-40:]
        return to_checksum_address(address)
