from datetime import datetime
from typing import Any, Dict, List, Optional

from ..config import get_settings
from ..db.models import MultiSigWallet, MultiSigProposal, MultiSigSignature
from ..dataclasses import CreateWalletRequest, CreateProposalRequest, AddSignatureRequest
from ..interfaces.modules import IMultiSigModule
from ..interfaces.repositories import IMultiSigRepository
from ..interfaces.services import ISignatureVerifier, IChainExecutor
from ..utils import (
    get_logger,
    generate_id,
    ValidationError,
    NotFoundError,
    SignatureError,
    ConflictError,
    to_checksum_address,
)
from .multisig_config import (
    get_config_manager,
    MultiSigConfigManager,
    MultiSigStrategyType,
    IMultiSigConfigStrategy,
)

logger = get_logger(__name__)


class MultiSigModule(IMultiSigModule):
    def __init__(
        self,
        repository: IMultiSigRepository,
        signature_verifier: ISignatureVerifier,
        chain_executor: IChainExecutor,
        config_manager: Optional[MultiSigConfigManager] = None,
    ):
        self._repository = repository
        self._signature_verifier = signature_verifier
        self._chain_executor = chain_executor
        self._settings = get_settings()
        self._config_manager = config_manager or get_config_manager()
        self._initialized = False

    async def initialize(self) -> None:
        if self._initialized:
            return
        logger.info("Initializing multi-sig module")

        await self._config_manager.initialize()
        self._config_manager.add_update_callback(self._on_strategy_updated)

        self._initialized = True
        logger.info("Multi-sig module initialized with dynamic config support")

    async def shutdown(self) -> None:
        if not self._initialized:
            return
        logger.info("Shutting down multi-sig module")

        await self._config_manager.shutdown()

        self._initialized = False
        logger.info("Multi-sig module shutdown complete")

    def _on_strategy_updated(self, new_strategy: MultiSigStrategyType) -> None:
        logger.info(f"Multi-sig strategy updated to {new_strategy}")

    async def create_wallet(self, request: CreateWalletRequest) -> Dict[str, Any]:
        context = {"chain_id": request.chain_id}
        strategy = self._config_manager.get_strategy(context)
        ms_config = strategy.get_config(context)

        if not strategy.validate_wallet_creation(
            chain_id=request.chain_id,
            signers=request.signers,
            threshold=request.threshold,
        ):
            raise ValidationError(
                f"Wallet creation failed validation for strategy {strategy.get_strategy_type()}",
                details={
                    "threshold": request.threshold,
                    "signers": len(request.signers),
                    "strategy": strategy.get_strategy_type().value,
                },
            )

        checksum_signers = [to_checksum_address(s) for s in request.signers]
        unique_signers = list(dict.fromkeys(checksum_signers))

        if len(unique_signers) != len(checksum_signers):
            raise ValidationError("Duplicate signers not allowed")

        wallet_id = generate_id("wallet")
        wallet_address = self._signature_verifier.compute_wallet_address(
            unique_signers, request.threshold, request.chain_id
        )

        wallet = MultiSigWallet(
            wallet_id=wallet_id,
            chain_id=request.chain_id,
            address=wallet_address,
            name=request.name,
            signers=unique_signers,
            threshold=request.threshold,
            nonce=0,
        )

        created_wallet = await self._repository.create_wallet(wallet)

        logger.info(f"Created multi-sig wallet {wallet_id} at address {wallet_address} "
                   f"with strategy {strategy.get_strategy_type().value}")

        return {
            "wallet_id": created_wallet.wallet_id,
            "chain_id": created_wallet.chain_id,
            "address": created_wallet.address,
            "name": created_wallet.name,
            "signers": created_wallet.signers,
            "threshold": created_wallet.threshold,
            "strategy": strategy.get_strategy_type().value,
        }

    async def get_wallet(self, wallet_id: str) -> Optional[Dict[str, Any]]:
        wallet = await self._repository.get_wallet(wallet_id)
        if not wallet:
            return None

        context = {"wallet_id": wallet_id, "chain_id": wallet.chain_id}
        strategy = self._config_manager.get_strategy(context)

        return {
            "wallet_id": wallet.wallet_id,
            "chain_id": wallet.chain_id,
            "address": wallet.address,
            "name": wallet.name,
            "signers": wallet.signers,
            "threshold": wallet.threshold,
            "nonce": wallet.nonce,
            "strategy": strategy.get_strategy_type().value,
            "created_at": wallet.created_at.isoformat() if wallet.created_at else None,
        }

    async def list_wallets(
        self, chain_id: Optional[int] = None, offset: int = 0, limit: int = 50
    ) -> Dict[str, Any]:
        wallets, total = await self._repository.list_wallets(
            chain_id=chain_id, offset=offset, limit=limit
        )

        return {
            "wallets": [
                {
                    "wallet_id": w.wallet_id,
                    "chain_id": w.chain_id,
                    "address": w.address,
                    "name": w.name,
                    "signers": w.signers,
                    "threshold": w.threshold,
                }
                for w in wallets
            ],
            "total": total,
            "offset": offset,
            "limit": limit,
        }

    async def create_proposal(self, request: CreateProposalRequest) -> Dict[str, Any]:
        wallet = await self._repository.get_wallet(request.wallet_id)
        if not wallet:
            raise NotFoundError(f"Wallet {request.wallet_id} not found")

        context = {"wallet_id": request.wallet_id, "chain_id": wallet.chain_id}
        strategy = self._config_manager.get_strategy(context)
        config = strategy.get_config(context)

        to_address = to_checksum_address(request.to)
        nonce = wallet.nonce

        safe_tx_hash = self._signature_verifier.compute_safe_tx_hash(
            wallet_address=wallet.address,
            chain_id=wallet.chain_id,
            to=to_address,
            value=request.value,
            data=request.data,
            operation=request.operation,
            safe_tx_gas=request.safe_tx_gas,
            base_gas=request.base_gas,
            gas_price=request.gas_price,
            gas_token=request.gas_token,
            refund_receiver=request.refund_receiver,
            nonce=nonce,
        )

        proposal_id = generate_id("proposal")

        proposal = MultiSigProposal(
            proposal_id=proposal_id,
            wallet_id=request.wallet_id,
            chain_id=wallet.chain_id,
            nonce=nonce,
            to=to_address,
            value=str(request.value),
            data=request.data,
            operation=request.operation,
            safe_tx_hash=safe_tx_hash,
            status="pending",
        )

        created_proposal = await self._repository.create_proposal(proposal)
        await self._repository.increment_wallet_nonce(request.wallet_id)

        logger.info(f"Created proposal {proposal_id} for wallet {request.wallet_id} "
                   f"with strategy {strategy.get_strategy_type().value}")

        return {
            "proposal_id": created_proposal.proposal_id,
            "wallet_id": created_proposal.wallet_id,
            "chain_id": created_proposal.chain_id,
            "nonce": created_proposal.nonce,
            "to": created_proposal.to,
            "value": int(created_proposal.value),
            "data": created_proposal.data,
            "safe_tx_hash": created_proposal.safe_tx_hash,
            "status": created_proposal.status,
            "signatures": [],
            "threshold": wallet.threshold,
            "strategy": strategy.get_strategy_type().value,
        }

    async def get_proposal(self, proposal_id: str) -> Optional[Dict[str, Any]]:
        proposal = await self._repository.get_proposal_with_relations(proposal_id)
        if not proposal:
            return None

        signatures = [
            {
                "signature_id": s.signature_id,
                "signer": s.signer,
                "signature": s.signature,
                "created_at": s.created_at.isoformat(),
            }
            for s in proposal.signatures
        ]

        unique_signers = list({s["signer"] for s in signatures})
        can_execute = len(unique_signers) >= proposal.wallet.threshold if proposal.wallet else False

        context = {"wallet_id": proposal.wallet_id, "chain_id": proposal.chain_id}
        strategy = self._config_manager.get_strategy(context)

        return {
            "proposal_id": proposal.proposal_id,
            "wallet_id": proposal.wallet_id,
            "chain_id": proposal.chain_id,
            "nonce": proposal.nonce,
            "to": proposal.to,
            "value": proposal.value,
            "data": proposal.data,
            "safe_tx_hash": proposal.safe_tx_hash,
            "status": proposal.status,
            "signatures": signatures,
            "signature_count": len(unique_signers),
            "threshold": proposal.wallet.threshold if proposal.wallet else 0,
            "can_execute": can_execute,
            "execution_tx_hash": proposal.execution_tx_hash,
            "executed_at": proposal.executed_at.isoformat() if proposal.executed_at else None,
            "created_at": proposal.created_at.isoformat() if proposal.created_at else None,
            "strategy": strategy.get_strategy_type().value,
        }

    async def list_proposals(
        self,
        wallet_id: Optional[str] = None,
        status: Optional[str] = None,
        offset: int = 0,
        limit: int = 50,
    ) -> Dict[str, Any]:
        proposals, total = await self._repository.list_proposals(
            wallet_id=wallet_id, status=status, offset=offset, limit=limit
        )

        return {
            "proposals": [
                {
                    "proposal_id": p.proposal_id,
                    "wallet_id": p.wallet_id,
                    "chain_id": p.chain_id,
                    "nonce": p.nonce,
                    "to": p.to,
                    "value": p.value,
                    "status": p.status,
                    "created_at": p.created_at.isoformat() if p.created_at else None,
                }
                for p in proposals
            ],
            "total": total,
            "offset": offset,
            "limit": limit,
        }

    async def add_signature(self, request: AddSignatureRequest) -> Dict[str, Any]:
        proposal = await self._repository.get_proposal(request.proposal_id)
        if not proposal:
            raise NotFoundError(f"Proposal {request.proposal_id} not found")

        if proposal.status != "pending":
            raise ConflictError(
                f"Cannot add signature to proposal with status {proposal.status}"
            )

        signer = to_checksum_address(request.signer)

        wallet = await self._repository.get_wallet(proposal.wallet_id)
        if not wallet or signer not in wallet.signers:
            raise ValidationError(f"Signer {signer} is not an authorized signer")

        existing = await self._repository.has_signature(request.proposal_id, signer)
        if existing:
            raise ConflictError(f"Signature from {signer} already exists")

        is_valid = self._signature_verifier.verify_signature(
            message_hash=proposal.safe_tx_hash,
            signer=signer,
            signature=request.signature,
            chain_id=proposal.chain_id,
        )

        if not is_valid:
            raise SignatureError("Invalid signature")

        signature_id = generate_id("sig")
        signature = MultiSigSignature(
            signature_id=signature_id,
            proposal_id=request.proposal_id,
            signer=signer,
            signature=request.signature,
        )

        created_sig = await self._repository.add_signature(signature)
        sig_count = await self._repository.get_signature_count(request.proposal_id)
        can_execute = sig_count >= wallet.threshold

        context = {"wallet_id": proposal.wallet_id, "chain_id": proposal.chain_id}
        strategy = self._config_manager.get_strategy(context)

        if strategy.should_auto_execute(sig_count, wallet.threshold, context) and can_execute:
            logger.info(f"Auto-executing proposal {request.proposal_id} based on strategy")
            asyncio.create_task(self._auto_execute_proposal(request.proposal_id))

        logger.info(
            f"Added signature {signature_id} from {signer} to proposal {request.proposal_id}"
        )

        return {
            "signature_id": created_sig.signature_id,
            "proposal_id": created_sig.proposal_id,
            "signer": created_sig.signer,
            "signature_count": sig_count,
            "threshold": wallet.threshold,
            "can_execute": can_execute,
            "auto_execute_scheduled": strategy.should_auto_execute(sig_count, wallet.threshold, context),
        }

    async def _auto_execute_proposal(self, proposal_id: str) -> None:
        try:
            await self.execute_proposal(proposal_id)
            logger.info(f"Auto-executed proposal {proposal_id} successfully")
        except Exception as e:
            logger.error(f"Failed to auto-execute proposal {proposal_id}: {e}")

    async def execute_proposal(self, proposal_id: str) -> Dict[str, Any]:
        proposal = await self._repository.get_proposal(proposal_id)
        if not proposal:
            raise NotFoundError(f"Proposal {proposal_id} not found")

        if proposal.status != "pending":
            raise ConflictError(f"Proposal is not pending: {proposal.status}")

        sig_count = await self._repository.get_signature_count(proposal_id)
        wallet = await self._repository.get_wallet(proposal.wallet_id)

        if not wallet or sig_count < wallet.threshold:
            raise ValidationError(
                f"Not enough signatures: {sig_count}/{wallet.threshold}"
            )

        signatures = await self._get_sorted_signatures(proposal_id)
        combined_signature = "".join([s.signature[2:] for s in signatures])

        context = {"wallet_id": proposal.wallet_id, "chain_id": proposal.chain_id}
        strategy = self._config_manager.get_strategy(context)
        config = strategy.get_config(context)

        await self._repository.update_proposal_status(proposal_id, "executing")

        try:
            tx_hash = await self._chain_executor.execute_transaction(
                chain_id=proposal.chain_id,
                wallet_address=wallet.address,
                to=proposal.to,
                value=int(proposal.value),
                data=proposal.data,
                operation=proposal.operation,
                signatures=f"0x{combined_signature}",
                nonce=proposal.nonce,
            )

            executed_at = datetime.utcnow()
            await self._repository.update_proposal_status(
                proposal_id,
                "executed",
                execution_tx_hash=tx_hash,
                executed_at=executed_at,
            )

            logger.info(f"Executed proposal {proposal_id} with tx {tx_hash}")

            return {
                "proposal_id": proposal_id,
                "status": "executed",
                "execution_tx_hash": tx_hash,
                "executed_at": executed_at.isoformat(),
            }

        except Exception as e:
            await self._repository.update_proposal_status(proposal_id, "failed")
            logger.error(f"Failed to execute proposal {proposal_id}: {e}")
            raise

    async def _get_sorted_signatures(self, proposal_id: str) -> List[MultiSigSignature]:
        from sqlalchemy import select
        from ..db import async_session

        async with async_session() as session:
            query = select(MultiSigSignature).where(
                MultiSigSignature.proposal_id == proposal_id
            )
            result = await session.execute(query)
            signatures = result.scalars().all()
            return sorted(signatures, key=lambda s: s.signer.lower())

    async def set_strategy(self, strategy_type: str) -> Dict[str, Any]:
        try:
            strategy_enum = MultiSigStrategyType(strategy_type)
        except ValueError:
            raise ValidationError(f"Invalid strategy type: {strategy_type}")

        self._config_manager.set_active_strategy(strategy_enum)

        return {
            "strategy": strategy_type,
            "success": True,
        }

    async def get_strategies(self) -> Dict[str, Any]:
        strategies = self._config_manager.get_available_strategies()
        return {
            "strategies": strategies,
            "active_strategy": self._config_manager._active_strategy_type.value,
        }

    async def set_chain_strategy(self, chain_id: int, strategy_type: str) -> Dict[str, Any]:
        try:
            strategy_enum = MultiSigStrategyType(strategy_type)
        except ValueError:
            raise ValidationError(f"Invalid strategy type: {strategy_type}")

        self._config_manager.set_chain_strategy(chain_id, strategy_enum)

        return {
            "chain_id": chain_id,
            "strategy": strategy_type,
            "success": True,
        }

    async def set_wallet_strategy(self, wallet_id: str, strategy_type: str) -> Dict[str, Any]:
        wallet = await self._repository.get_wallet(wallet_id)
        if not wallet:
            raise NotFoundError(f"Wallet {wallet_id} not found")

        try:
            strategy_enum = MultiSigStrategyType(strategy_type)
        except ValueError:
            raise ValidationError(f"Invalid strategy type: {strategy_type}")

        self._config_manager.set_wallet_strategy(wallet_id, strategy_enum)

        return {
            "wallet_id": wallet_id,
            "strategy": strategy_type,
            "success": True,
        }


import asyncio

_multisig_module: Optional[MultiSigModule] = None


async def create_multisig_module(container: Any = None) -> MultiSigModule:
    if container is None:
        from ..container import get_container
        container = get_container()

    repo = await container.get_multisig_repository()
    sig_verifier = await container.get_signature_verifier()
    chain_executor = await container.get_chain_executor()
    config_manager = get_config_manager()

    return MultiSigModule(
        repository=repo,
        signature_verifier=sig_verifier,
        chain_executor=chain_executor,
        config_manager=config_manager,
    )


def get_multisig_module() -> MultiSigModule:
    global _multisig_module
    if _multisig_module is None:
        raise RuntimeError("MultiSigModule not initialized. Call create_multisig_module first.")
    return _multisig_module


async def init_multisig_module(container: Any = None) -> MultiSigModule:
    global _multisig_module
    if _multisig_module is None:
        _multisig_module = await create_multisig_module(container)
        await _multisig_module.initialize()
    return _multisig_module
