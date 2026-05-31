from __future__ import annotations

import hashlib
import json
from datetime import datetime
from typing import Any, Dict, List, Optional
from uuid import uuid4

from eth_utils import keccak

from src.core.ports.cross_chain_port import (
    IAtomicExecutor,
    ICrossChainBridgePort,
    IMessageValidator,
)
from src.core.ports.chain_interaction_port import IChainInteractionPort
from src.shared.errors import (
    AtomicityError,
    CrossChainBridgeError,
    MessageVerificationError,
    NotFoundError,
)
from src.shared.logger import get_logger
from src.shared.types import Address, Chain, CrossChainMessage, Hash, HexString, WeiAmount

logger = get_logger(__name__)


class MessageValidator(IMessageValidator):
    def __init__(self, chain_adapters: Dict[Chain, IChainInteractionPort]):
        self._chains = chain_adapters

    async def validate_source_transaction(
        self,
        source_chain: Chain,
        tx_hash: Hash,
        message: CrossChainMessage,
    ) -> bool:
        if source_chain not in self._chains:
            raise CrossChainBridgeError(f"Source chain {source_chain} not supported")

        chain = self._chains[source_chain]
        receipt = await chain.get_transaction_receipt(tx_hash)

        if not receipt:
            raise MessageVerificationError(f"Transaction {tx_hash} not found on {source_chain}")

        if receipt.status != 1:
            raise MessageVerificationError(f"Transaction {tx_hash} failed")

        return True

    async def validate_signature(
        self,
        message: CrossChainMessage,
        signature: HexString,
        signer_address: Address,
    ) -> bool:
        try:
            message_hash = self._hash_message(message)
            from eth_account import Account
            from eth_account.messages import encode_defunct

            signable_msg = encode_defunct(hexstr=message_hash)
            recovered = Account.recover_message(signable_msg, signature=signature)

            return recovered.lower() == signer_address.lower()
        except Exception as e:
            raise MessageVerificationError(f"Signature validation failed: {e}")

    async def validate_nonce(
        self,
        message: CrossChainMessage,
    ) -> bool:
        if not message.message_id:
            raise MessageVerificationError("Message ID is required")
        return True

    def _hash_message(self, message: CrossChainMessage) -> HexString:
        message_data = {
            "message_id": message.message_id,
            "source_chain": message.source_chain.value,
            "target_chain": message.target_chain.value,
            "source_address": message.source_address,
            "target_address": message.target_address,
            "amount": str(message.amount),
            "token_address": message.token_address,
            "data": message.data,
        }
        message_bytes = json.dumps(message_data, sort_keys=True).encode()
        return "0x" + keccak(message_bytes).hex()


class AtomicExecutor(IAtomicExecutor):
    def __init__(self):
        self._operations: Dict[str, Dict[str, Any]] = {}
        self._prepared: Dict[str, bool] = {}

    async def prepare(
        self,
        operations: List[Dict[str, Any]],
    ) -> str:
        operation_id = f"atomic_{uuid4().hex[:16]}"
        self._operations[operation_id] = {
            "operations": operations,
            "status": "preparing",
            "created_at": datetime.utcnow(),
        }

        for op in operations:
            if not await self._validate_operation(op):
                raise AtomicityError(f"Invalid operation: {op}")

        self._prepared[operation_id] = True
        self._operations[operation_id]["status"] = "prepared"
        logger.info(f"Atomic operation prepared: {operation_id}")
        return operation_id

    async def commit(
        self,
        operation_id: str,
    ) -> bool:
        if operation_id not in self._operations:
            raise NotFoundError(f"Operation {operation_id} not found")

        if not self._prepared.get(operation_id, False):
            raise AtomicityError(f"Operation {operation_id} not prepared")

        op_data = self._operations[operation_id]
        executed_ops: List[Dict[str, Any]] = []

        try:
            for op in op_data["operations"]:
                await self._execute_operation(op)
                executed_ops.append(op)

            op_data["status"] = "committed"
            op_data["executed_at"] = datetime.utcnow()
            logger.info(f"Atomic operation committed: {operation_id}")
            return True

        except Exception as e:
            logger.error(f"Atomic operation failed, rolling back: {e}")
            await self._rollback_operations(executed_ops)
            op_data["status"] = "failed"
            op_data["error"] = str(e)
            return False

    async def execute_atomic(
        self,
        operations: List[Dict[str, Any]],
        timeout: int = 300,
    ) -> bool:
        operation_id = await self.prepare(operations)
        return await self.commit(operation_id)

    async def rollback(
        self,
        operation_id: str,
    ) -> bool:
        if operation_id not in self._operations:
            return False

        op_data = self._operations[operation_id]
        if op_data["status"] in ["committed", "rolled_back"]:
            return False

        await self._rollback_operations(op_data["operations"])
        op_data["status"] = "rolled_back"
        logger.info(f"Atomic operation rolled back: {operation_id}")
        return True

    async def _validate_operation(self, op: Dict[str, Any]) -> bool:
        required_fields = ["type", "chain", "payload"]
        for field in required_fields:
            if field not in op:
                raise AtomicityError(f"Missing required field: {field}")
        return True

    async def _execute_operation(self, op: Dict[str, Any]) -> None:
        logger.info(f"Executing operation: {op.get('type')}", chain=op.get("chain"))
        op["executed"] = True
        op["executed_at"] = datetime.utcnow()

    async def _rollback_operations(self, operations: List[Dict[str, Any]]) -> None:
        for op in reversed(operations):
            try:
                logger.info(f"Rolling back operation: {op.get('type')}")
                op["rolled_back"] = True
                op["rolled_back_at"] = datetime.utcnow()
            except Exception as e:
                logger.error(f"Failed to rollback operation: {e}")


class CrossChainBridgeService(ICrossChainBridgePort):
    def __init__(
        self,
        chain_adapters: Dict[Chain, IChainInteractionPort],
        validator: Optional[IMessageValidator] = None,
        executor: Optional[IAtomicExecutor] = None,
    ):
        self._chains = chain_adapters
        self._validator = validator or MessageValidator(chain_adapters)
        self._executor = executor or AtomicExecutor()
        self._messages: Dict[str, CrossChainMessage] = {}
        self._pending_locks: Dict[str, Dict[str, Any]] = {}

    async def lock_assets(
        self,
        source_chain: Chain,
        target_chain: Chain,
        source_address: Address,
        target_address: Address,
        amount: WeiAmount,
        token_address: Optional[Address] = None,
        data: HexString = "0x",
    ) -> CrossChainMessage:
        if source_chain not in self._chains:
            raise CrossChainBridgeError(f"Source chain {source_chain} not supported")
        if target_chain not in self._chains:
            raise CrossChainBridgeError(f"Target chain {target_chain} not supported")

        message = CrossChainMessage(
            source_chain=source_chain,
            target_chain=target_chain,
            source_address=source_address,
            target_address=target_address,
            amount=amount,
            token_address=token_address,
            data=data,
            status="locking",
        )

        self._messages[message.message_id] = message

        try:
            await self._execute_lock(message)
            message.status = "locked"
            message.updated_at = datetime.utcnow()

            logger.info(
                f"Assets locked: {message.message_id}",
                source_chain=source_chain.value,
                target_chain=target_chain.value,
                amount=amount,
            )

            return message

        except Exception as e:
            message.status = "failed"
            message.updated_at = datetime.utcnow()
            raise CrossChainBridgeError(f"Failed to lock assets: {e}")

    async def _execute_lock(self, message: CrossChainMessage) -> None:
        source_chain = self._chains[message.source_chain]

        balance = await source_chain.get_balance(message.source_address)
        if balance < message.amount:
            raise CrossChainBridgeError("Insufficient balance")

        self._pending_locks[message.message_id] = {
            "amount": message.amount,
            "token_address": message.token_address,
            "locked_at": datetime.utcnow(),
        }

    async def mint_assets(
        self,
        message: CrossChainMessage,
        proof: HexString,
    ) -> Hash:
        if message.message_id not in self._messages:
            raise NotFoundError(f"Message {message.message_id} not found")

        message = self._messages[message.message_id]

        if message.status != "locked":
            raise CrossChainBridgeError(f"Message {message.message_id} is not locked")

        try:
            verified = await self.verify_message(message, proof)
            if not verified:
                raise MessageVerificationError("Message verification failed")

            message.status = "minting"
            message.updated_at = datetime.utcnow()

            tx_hash = await self._execute_mint(message, proof)
            message.target_transaction_hash = tx_hash
            message.status = "completed"
            message.updated_at = datetime.utcnow()

            if message.message_id in self._pending_locks:
                del self._pending_locks[message.message_id]

            logger.info(
                f"Assets minted: {message.message_id}",
                tx_hash=tx_hash,
            )

            return tx_hash

        except Exception as e:
            message.status = "failed"
            message.updated_at = datetime.utcnow()
            raise CrossChainBridgeError(f"Failed to mint assets: {e}")

    async def _execute_mint(self, message: CrossChainMessage, proof: HexString) -> Hash:
        target_chain = self._chains.get(message.target_chain)
        if not target_chain:
            raise CrossChainBridgeError(f"Target chain {message.target_chain} not supported")

        return f"0x{uuid4().hex}{uuid4().hex}"

    async def verify_message(
        self,
        message: CrossChainMessage,
        proof: HexString,
    ) -> bool:
        try:
            await self._validator.validate_nonce(message)

            if message.source_transaction_hash:
                await self._validator.validate_source_transaction(
                    message.source_chain,
                    message.source_transaction_hash,
                    message,
                )

            return self._verify_proof(message, proof)

        except Exception as e:
            logger.warning(f"Message verification failed: {e}")
            return False

    def _verify_proof(self, message: CrossChainMessage, proof: HexString) -> bool:
        try:
            message_bytes = json.dumps(
                {
                    "message_id": message.message_id,
                    "source_chain": message.source_chain.value,
                    "target_chain": message.target_chain.value,
                    "amount": message.amount,
                },
                sort_keys=True,
            ).encode()

            expected_proof = "0x" + hashlib.sha256(message_bytes).hexdigest()

            return proof.lower() == expected_proof.lower()
        except Exception as e:
            raise MessageVerificationError(f"Proof verification failed: {e}")

    async def get_message_status(self, message_id: str) -> CrossChainMessage:
        if message_id not in self._messages:
            raise NotFoundError(f"Message {message_id} not found")
        return self._messages[message_id]

    async def list_messages(
        self,
        source_chain: Optional[Chain] = None,
        target_chain: Optional[Chain] = None,
        status: Optional[str] = None,
        limit: int = 100,
    ) -> List[CrossChainMessage]:
        messages = list(self._messages.values())

        if source_chain:
            messages = [m for m in messages if m.source_chain == source_chain]
        if target_chain:
            messages = [m for m in messages if m.target_chain == target_chain]
        if status:
            messages = [m for m in messages if m.status == status]

        messages.sort(key=lambda m: m.created_at, reverse=True)
        return messages[:limit]

    async def retry_message(self, message_id: str) -> bool:
        if message_id not in self._messages:
            return False

        message = self._messages[message_id]
        if message.status not in ["failed", "locking", "minting"]:
            return False

        message.status = "pending"
        message.updated_at = datetime.utcnow()
        logger.info(f"Message retry initiated: {message_id}")
        return True

    async def cancel_message(self, message_id: str) -> bool:
        if message_id not in self._messages:
            return False

        message = self._messages[message_id]
        if message.status in ["completed", "cancelled"]:
            return False

        if message.status == "locked":
            await self._refund_assets(message)

        message.status = "cancelled"
        message.updated_at = datetime.utcnow()

        if message_id in self._pending_locks:
            del self._pending_locks[message_id]

        logger.info(f"Message cancelled: {message_id}")
        return True

    async def _refund_assets(self, message: CrossChainMessage) -> None:
        logger.info(
            f"Refunding assets: {message.message_id}",
            address=message.source_address,
            amount=message.amount,
        )

    async def generate_proof(self, message: CrossChainMessage, source_tx_hash: Hash) -> HexString:
        message.source_transaction_hash = source_tx_hash
        message_bytes = json.dumps(
            {
                "message_id": message.message_id,
                "source_chain": message.source_chain.value,
                "target_chain": message.target_chain.value,
                "amount": message.amount,
            },
            sort_keys=True,
        ).encode()

        return "0x" + hashlib.sha256(message_bytes).hexdigest()
