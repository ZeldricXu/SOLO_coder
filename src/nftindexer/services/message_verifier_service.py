import json
from typing import Any, Dict, List, Optional
from datetime import datetime, timezone

from eth_utils import keccak

from ..interfaces.services import IMessageVerifier
from ..utils import get_logger

logger = get_logger(__name__)


class MessageVerifierService(IMessageVerifier):
    def __init__(self, min_signatures: int = 1):
        self._min_signatures = min_signatures

    def compute_message_hash(
        self,
        source_chain_id: int,
        target_chain_id: int,
        sender: str,
        recipient: str,
        amount: int,
        token_address: str,
        payload: Dict[str, Any],
    ) -> str:
        message_data = json.dumps(
            {
                "source_chain_id": source_chain_id,
                "target_chain_id": target_chain_id,
                "sender": sender,
                "recipient": recipient,
                "amount": amount,
                "token_address": token_address,
                "payload": payload,
                "timestamp": int(datetime.now(timezone.utc).timestamp()),
            },
            sort_keys=True,
        )
        hash_bytes = keccak(text=message_data)
        return "0x" + hash_bytes.hex()

    async def verify_proof(
        self,
        tx: Any,
        proof_data: Dict[str, Any],
        signatures: List[str],
        message_hash: str,
        merkle_proof: Optional[List[str]] = None,
    ) -> bool:
        if message_hash != tx.message_hash:
            logger.warning(f"Message hash mismatch for {tx.tx_id}")
            return False

        if len(signatures) < self._min_signatures:
            logger.warning(f"No signatures provided for {tx.tx_id}")
            return False

        return True
