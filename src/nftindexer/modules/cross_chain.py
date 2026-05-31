import asyncio
from datetime import datetime, timezone
from typing import Any, Dict, List, Optional

from ..config import get_settings
from ..db.models import CrossChainTransaction, CrossChainMessage
from ..dataclasses import BridgeRequest, MessageProof
from ..interfaces.modules import ICrossChainModule
from ..interfaces.repositories import ICrossChainRepository
from ..interfaces.services import IMessageVerifier
from ..utils import (
    get_logger,
    generate_id,
    ValidationError,
    NotFoundError,
    CrossChainError,
    to_checksum_address,
)
from .cross_chain_async import (
    get_async_executor,
    AsyncTaskExecutor,
    AsyncTask,
    TaskType,
    TaskStatus,
    TaskNotification,
    WebhookResultHandler,
    CallbackResultHandler,
)

logger = get_logger(__name__)


class CrossChainConfirmationService:
    def __init__(
        self,
        repository: ICrossChainRepository,
        chain_adapter: Any,
    ):
        self._repository = repository
        self._chain_adapter = chain_adapter
        self._settings = get_settings()

    async def wait_for_source_confirmations(self, tx_id: str) -> None:
        cc_settings = self._settings.crosschain
        required_confirmations = cc_settings.min_confirmations_source

        try:
            tx = await self._repository.get_transaction(tx_id)
            if not tx:
                return

            current_confirmations = 0

            while current_confirmations < required_confirmations:
                try:
                    receipt = await self._chain_adapter.get_receipt(
                        tx.source_chain_id, tx.source_tx_hash or ""
                    )
                    if not receipt:
                        await asyncio.sleep(5)
                        continue

                    tx_block_number = int(receipt.get("blockNumber", "0x0"), 16)
                    current_block = await self._chain_adapter.get_block_number(tx.source_chain_id)
                    current_confirmations = current_block - tx_block_number

                    await self._repository.update_source_confirmations(
                        tx_id, current_confirmations
                    )

                    if current_confirmations >= required_confirmations:
                        break

                    await asyncio.sleep(10)
                except Exception as e:
                    logger.warning(f"Error checking confirmations for {tx_id}: {e}")
                    await asyncio.sleep(5)

            await self._repository.update_transaction_status(tx_id, "confirmed_source")
            logger.info(f"Source confirmations complete for {tx_id}")

        except Exception as e:
            logger.error(f"Failed to wait for source confirmations for {tx_id}: {e}")
            await self._repository.update_transaction_status(tx_id, "failed", error_details=str(e))

    async def wait_for_target_confirmations(self, tx_id: str) -> None:
        cc_settings = self._settings.crosschain
        required_confirmations = cc_settings.min_confirmations_target

        try:
            tx = await self._repository.get_transaction(tx_id)
            if not tx:
                return

            current_confirmations = 0

            while current_confirmations < required_confirmations:
                try:
                    receipt = await self._chain_adapter.get_receipt(
                        tx.target_chain_id, tx.target_tx_hash or ""
                    )
                    if not receipt:
                        await asyncio.sleep(5)
                        continue

                    tx_block_number = int(receipt.get("blockNumber", "0x0"), 16)
                    current_block = await self._chain_adapter.get_block_number(tx.target_chain_id)
                    current_confirmations = current_block - tx_block_number

                    await self._repository.update_target_confirmations(
                        tx_id, current_confirmations
                    )

                    if current_confirmations >= required_confirmations:
                        break

                    await asyncio.sleep(10)
                except Exception as e:
                    logger.warning(f"Error checking target confirmations for {tx_id}: {e}")
                    await asyncio.sleep(5)

            await self._repository.update_transaction_status(tx_id, "completed")
            logger.info(f"Transaction {tx_id} completed successfully")

        except Exception as e:
            logger.error(f"Failed to wait for target confirmations for {tx_id}: {e}")
            await self._repository.update_transaction_status(tx_id, "failed", error_details=str(e))


class CrossChainAtomicCoordinator:
    def __init__(self, repository: ICrossChainRepository):
        self._repository = repository
        self._settings = get_settings()

    async def process_lock_phase(self, tx_id: str) -> None:
        try:
            logger.info(f"Processing lock phase for {tx_id}")
            await asyncio.sleep(1)
        except Exception as e:
            logger.error(f"Failed to process lock phase for {tx_id}: {e}")
            await self._repository.update_transaction_status(tx_id, "failed", error_details=str(e))

    async def execute_mint_phase(self, tx_id: str, proof: MessageProof) -> None:
        try:
            logger.info(f"Executing mint phase for {tx_id}")
            await asyncio.sleep(1)
        except Exception as e:
            logger.error(f"Failed to execute mint phase for {tx_id}: {e}")
            await self._repository.update_transaction_status(tx_id, "failed", error_details=str(e))


class CrossChainModule(ICrossChainModule):
    def __init__(
        self,
        repository: ICrossChainRepository,
        message_verifier: IMessageVerifier,
        chain_adapter: Any,
        async_executor: Optional[AsyncTaskExecutor] = None,
    ):
        self._repository = repository
        self._message_verifier = message_verifier
        self._chain_adapter = chain_adapter
        self._async_executor = async_executor or get_async_executor()
        self._settings = get_settings()
        self._confirmation_service: Optional[CrossChainConfirmationService] = None
        self._atomic_coordinator: Optional[CrossChainAtomicCoordinator] = None
        self._initialized = False
        self._webhook_handlers: Dict[str, str] = {}

    async def initialize(self) -> None:
        if self._initialized:
            return

        logger.info("Initializing cross-chain module")

        self._confirmation_service = CrossChainConfirmationService(
            repository=self._repository,
            chain_adapter=self._chain_adapter,
        )
        self._atomic_coordinator = CrossChainAtomicCoordinator(
            repository=self._repository,
        )

        await self._async_executor.start()
        self._register_task_handlers()

        self._initialized = True
        logger.info("Cross-chain module initialized with async support")

    async def shutdown(self) -> None:
        if not self._initialized:
            return

        logger.info("Shutting down cross-chain module")

        await self._async_executor.shutdown()

        self._initialized = False
        logger.info("Cross-chain module shutdown complete")

    def _register_task_handlers(self) -> None:
        self._async_executor.register_task_handler(
            TaskType.INITIATE_BRIDGE, self._handle_initiate_bridge_task)
        self._async_executor.register_task_handler(
            TaskType.CONFIRM_SOURCE, self._handle_confirm_source_task)
        self._async_executor.register_task_handler(
            TaskType.VERIFY_PROOF, self._handle_verify_proof_task)
        self._async_executor.register_task_handler(
            TaskType.COMPLETE_TRANSACTION, self._handle_complete_transaction_task)
        self._async_executor.register_task_handler(
            TaskType.ROLLBACK, self._handle_rollback_task)

    async def _handle_initiate_bridge_task(self, task: AsyncTask) -> Dict[str, Any]:
        params = task.params
        request = BridgeRequest(**params["request"])

        if request.source_chain_id == request.target_chain_id:
            raise ValidationError("Source and target chains must be different")

        sender = to_checksum_address(request.sender)
        recipient = to_checksum_address(request.recipient)
        token_address = to_checksum_address(request.token_address)

        message_hash = self._message_verifier.compute_message_hash(
            source_chain_id=request.source_chain_id,
            target_chain_id=request.target_chain_id,
            sender=sender,
            recipient=recipient,
            amount=request.amount,
            token_address=token_address,
            payload=request.message_payload,
        )

        tx_id = generate_id("bridge")

        transaction = CrossChainTransaction(
            tx_id=tx_id,
            source_chain_id=request.source_chain_id,
            target_chain_id=request.target_chain_id,
            source_tx_hash="",
            sender=sender,
            recipient=recipient,
            amount=str(request.amount),
            token_address=token_address,
            message_hash=message_hash,
            message_payload=request.message_payload,
            status="initiated",
        )

        created_tx = await self._repository.create_transaction(transaction)

        message = CrossChainMessage(
            message_id=generate_id("msg"),
            tx_id=tx_id,
            channel_id=f"channel_{request.source_chain_id}_{request.target_chain_id}",
            message_type="lock",
            payload=request.message_payload,
            status="pending",
        )

        await self._repository.create_message(message)

        if self._atomic_coordinator:
            asyncio.create_task(self._atomic_coordinator.process_lock_phase(tx_id))

        logger.info(
            f"Initiated bridge transaction {tx_id}: {request.source_chain_id} -> {request.target_chain_id}"
        )

        return {
            "tx_id": created_tx.tx_id,
            "source_chain_id": created_tx.source_chain_id,
            "target_chain_id": created_tx.target_chain_id,
            "sender": created_tx.sender,
            "recipient": created_tx.recipient,
            "amount": int(created_tx.amount),
            "token_address": created_tx.token_address,
            "message_hash": created_tx.message_hash,
            "status": created_tx.status,
        }

    async def _handle_confirm_source_task(self, task: AsyncTask) -> Dict[str, Any]:
        params = task.params
        tx_id = params["tx_id"]
        source_tx_hash = params["source_tx_hash"]

        tx = await self._repository.get_transaction(tx_id)
        if not tx:
            raise NotFoundError(f"Transaction {tx_id} not found")

        if tx.status != "initiated":
            raise ValidationError(f"Cannot confirm transaction with status {tx.status}")

        await self._repository.update_transaction_status(
            tx_id, "confirming_source", source_tx_hash=source_tx_hash
        )

        if self._confirmation_service:
            asyncio.create_task(
                self._confirmation_service.wait_for_source_confirmations(tx_id))

        logger.info(f"Confirmed source transaction {tx_id}: {source_tx_hash}")

        return {
            "tx_id": tx_id,
            "status": "confirming_source",
            "source_tx_hash": source_tx_hash,
        }

    async def _handle_verify_proof_task(self, task: AsyncTask) -> Dict[str, Any]:
        params = task.params
        tx_id = params["tx_id"]
        proof_data = params["proof"]

        tx = await self._repository.get_transaction(tx_id)
        if not tx:
            raise NotFoundError(f"Transaction {tx_id} not found")

        if tx.status != "confirmed_source":
            raise ValidationError(f"Cannot verify proof for transaction with status {tx.status}")

        proof = MessageProof(**proof_data)

        is_valid = await self._message_verifier.verify_proof(
            tx=tx,
            proof_data=proof.proof_data,
            signatures=proof.signatures,
            message_hash=proof.message_hash,
            merkle_proof=proof.merkle_proof,
        )

        if not is_valid:
            raise CrossChainError("Invalid message proof")

        await self._repository.update_transaction_status(
            tx_id, "proven", proof_data=proof.proof_data
        )

        if self._atomic_coordinator:
            asyncio.create_task(self._atomic_coordinator.execute_mint_phase(tx_id, proof))

        logger.info(f"Verified proof for transaction {tx_id}")

        return {
            "tx_id": tx_id,
            "status": "proven",
            "verified": True,
        }

    async def _handle_complete_transaction_task(self, task: AsyncTask) -> Dict[str, Any]:
        params = task.params
        tx_id = params["tx_id"]
        target_tx_hash = params["target_tx_hash"]

        tx = await self._repository.get_transaction(tx_id)
        if not tx:
            raise NotFoundError(f"Transaction {tx_id} not found")

        if tx.status not in ["proven", "minting"]:
            raise ValidationError(f"Cannot complete transaction with status {tx.status}")

        await self._repository.update_transaction_status(
            tx_id, "confirming_target", target_tx_hash=target_tx_hash
        )

        if self._confirmation_service:
            asyncio.create_task(
                self._confirmation_service.wait_for_target_confirmations(tx_id))

        logger.info(f"Broadcasting target transaction {tx_id}: {target_tx_hash}")

        return {
            "tx_id": tx_id,
            "status": "confirming_target",
            "target_tx_hash": target_tx_hash,
        }

    async def _handle_rollback_task(self, task: AsyncTask) -> Dict[str, Any]:
        params = task.params
        tx_id = params["tx_id"]

        tx = await self._repository.get_transaction(tx_id)
        if not tx:
            raise NotFoundError(f"Transaction {tx_id} not found")

        if tx.status not in ["initiated", "confirming_source"]:
            raise ValidationError(f"Cannot rollback transaction with status {tx.status}")

        rolled_back_at = datetime.now(timezone.utc)
        await self._repository.update_transaction_status(
            tx_id, "rolled_back", rolled_back_at=rolled_back_at
        )

        logger.info(f"Rolled back transaction {tx_id}")

        return {
            "tx_id": tx_id,
            "status": "rolled_back",
            "rolled_back_at": rolled_back_at.isoformat(),
        }

    async def initiate_bridge(self, request: BridgeRequest) -> Dict[str, Any]:
        task = await self._async_executor.submit_task(
            task_type=TaskType.INITIATE_BRIDGE,
            params={"request": request.__dict__},
            timeout=300.0,
            max_retries=3,
        )

        return {
            "task_id": task.task_id,
            "status": "submitted",
            "message": "Bridge initiation task submitted successfully",
            "task": task.to_dict(),
        }

    async def get_transaction(self, tx_id: str) -> Optional[Dict[str, Any]]:
        tx = await self._repository.get_transaction_with_messages(tx_id)
        if not tx:
            return None

        return {
            "tx_id": tx.tx_id,
            "source_chain_id": tx.source_chain_id,
            "target_chain_id": tx.target_chain_id,
            "source_tx_hash": tx.source_tx_hash,
            "target_tx_hash": tx.target_tx_hash,
            "sender": tx.sender,
            "recipient": tx.recipient,
            "amount": tx.amount,
            "token_address": tx.token_address,
            "message_hash": tx.message_hash,
            "status": tx.status,
            "confirmations_source": tx.confirmations_source,
            "confirmations_target": tx.confirmations_target,
            "completed_at": tx.completed_at.isoformat() if tx.completed_at else None,
            "messages": [
                {
                    "message_id": m.message_id,
                    "type": m.message_type,
                    "status": m.status,
                    "verified_at": m.verified_at.isoformat() if m.verified_at else None,
                }
                for m in tx.messages
            ],
            "created_at": tx.created_at.isoformat() if tx.created_at else None,
        }

    async def list_transactions(
        self,
        source_chain: Optional[int] = None,
        target_chain: Optional[int] = None,
        status: Optional[str] = None,
        address: Optional[str] = None,
        offset: int = 0,
        limit: int = 50,
    ) -> Dict[str, Any]:
        transactions, total = await self._repository.list_transactions(
            source_chain=source_chain,
            target_chain=target_chain,
            status=status,
            address=address,
            offset=offset,
            limit=limit,
        )

        return {
            "transactions": [
                {
                    "tx_id": t.tx_id,
                    "source_chain_id": t.source_chain_id,
                    "target_chain_id": t.target_chain_id,
                    "sender": t.sender,
                    "recipient": t.recipient,
                    "amount": t.amount,
                    "status": t.status,
                    "created_at": t.created_at.isoformat() if t.created_at else None,
                }
                for t in transactions
            ],
            "total": total,
            "offset": offset,
            "limit": limit,
        }

    async def confirm_source_transaction(
        self, tx_id: str, source_tx_hash: str
    ) -> Dict[str, Any]:
        task = await self._async_executor.submit_task(
            task_type=TaskType.CONFIRM_SOURCE,
            params={"tx_id": tx_id, "source_tx_hash": source_tx_hash},
            timeout=600.0,
            max_retries=3,
        )

        return {
            "task_id": task.task_id,
            "status": "submitted",
            "message": "Source confirmation task submitted successfully",
            "tx_id": tx_id,
            "task": task.to_dict(),
        }

    async def verify_message_proof(self, tx_id: str, proof: MessageProof) -> Dict[str, Any]:
        task = await self._async_executor.submit_task(
            task_type=TaskType.VERIFY_PROOF,
            params={"tx_id": tx_id, "proof": proof.__dict__},
            timeout=300.0,
            max_retries=3,
        )

        return {
            "task_id": task.task_id,
            "status": "submitted",
            "message": "Proof verification task submitted successfully",
            "tx_id": tx_id,
            "task": task.to_dict(),
        }

    async def complete_transaction(
        self, tx_id: str, target_tx_hash: str
    ) -> Dict[str, Any]:
        task = await self._async_executor.submit_task(
            task_type=TaskType.COMPLETE_TRANSACTION,
            params={"tx_id": tx_id, "target_tx_hash": target_tx_hash},
            timeout=600.0,
            max_retries=3,
        )

        return {
            "task_id": task.task_id,
            "status": "submitted",
            "message": "Transaction completion task submitted successfully",
            "tx_id": tx_id,
            "task": task.to_dict(),
        }

    async def get_atomic_status(self, tx_id: str) -> Dict[str, Any]:
        tx = await self._repository.get_transaction(tx_id)
        if not tx:
            raise NotFoundError(f"Transaction {tx_id} not found")

        cc_settings = self._settings.crosschain

        return {
            "tx_id": tx_id,
            "status": tx.status,
            "atomic_guarantee": tx.status in ["completed", "failed"],
            "lock_phase": {
                "completed": tx.confirmations_source > 0,
                "confirmations": tx.confirmations_source,
                "required": cc_settings.min_confirmations_source,
            },
            "mint_phase": {
                "completed": tx.confirmations_target > 0,
                "confirmations": tx.confirmations_target,
                "required": cc_settings.min_confirmations_target,
            },
            "can_rollback": tx.status in ["initiated", "confirming_source"],
            "rollback_available": tx.status in ["initiated", "confirming_source"],
        }

    async def rollback_transaction(self, tx_id: str) -> Dict[str, Any]:
        task = await self._async_executor.submit_task(
            task_type=TaskType.ROLLBACK,
            params={"tx_id": tx_id},
            timeout=300.0,
            max_retries=3,
        )

        return {
            "task_id": task.task_id,
            "status": "submitted",
            "message": "Rollback task submitted successfully",
            "tx_id": tx_id,
            "task": task.to_dict(),
        }

    async def get_task(self, task_id: str) -> Optional[Dict[str, Any]]:
        task = await self._async_executor.get_task(task_id)
        if not task:
            return None
        return task.to_dict()

    async def list_tasks(
        self,
        status: Optional[str] = None,
        task_type: Optional[str] = None,
        limit: int = 100,
    ) -> Dict[str, Any]:
        status_enum = TaskStatus(status) if status else None
        type_enum = TaskType(task_type) if task_type else None

        tasks = await self._async_executor.list_tasks(
            status=status_enum,
            task_type=type_enum,
            limit=limit,
        )

        return {
            "tasks": [t.to_dict() for t in tasks],
            "total": len(tasks),
        }

    async def cancel_task(self, task_id: str) -> Dict[str, Any]:
        success = await self._async_executor.cancel_task(task_id)
        return {
            "task_id": task_id,
            "cancelled": success,
        }

    def register_webhook(self, tx_id: str, webhook_url: str) -> None:
        handler = WebhookResultHandler(webhook_url)
        self._async_executor.add_result_handler(handler)
        self._webhook_handlers[tx_id] = webhook_url
        logger.info(f"Registered webhook for transaction {tx_id}: {webhook_url}")

    def register_callback(self, callback: Callable[[TaskNotification], Any]) -> None:
        handler = CallbackResultHandler(callback)
        self._async_executor.add_result_handler(handler)
        logger.info("Registered callback handler")

    def add_event_listener(
        self,
        callback: Callable[[TaskNotification], Any],
        status: Optional[str] = None,
    ) -> None:
        status_enum = TaskStatus(status) if status else None
        self._async_executor.add_event_listener(callback, status_enum)
        logger.info(f"Added event listener")

    def remove_event_listener(
        self,
        callback: Callable[[TaskNotification], Any],
        status: Optional[str] = None,
    ) -> None:
        status_enum = TaskStatus(status) if status else None
        self._async_executor.remove_event_listener(callback, status_enum)


_cross_chain_module: Optional[CrossChainModule] = None


async def create_cross_chain_module(
    chain_adapter: Any,
    container: Any = None,
) -> CrossChainModule:
    if container is None:
        from ..container import get_container
        container = get_container()

    repo = await container.get_cross_chain_repository()
    message_verifier = await container.get_message_verifier()
    async_executor = get_async_executor()

    return CrossChainModule(
        repository=repo,
        message_verifier=message_verifier,
        chain_adapter=chain_adapter,
        async_executor=async_executor,
    )


def get_cross_chain_module() -> CrossChainModule:
    global _cross_chain_module
    if _cross_chain_module is None:
        raise RuntimeError("CrossChainModule not initialized. Call create_cross_chain_module first.")
    return _cross_chain_module


async def init_cross_chain_module(chain_adapter: Any, container: Any = None) -> CrossChainModule:
    global _cross_chain_module
    if _cross_chain_module is None:
        _cross_chain_module = await create_cross_chain_module(chain_adapter, container)
        await _cross_chain_module.initialize()
    return _cross_chain_module
