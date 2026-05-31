from fastapi import APIRouter, HTTPException
from datetime import datetime, timezone

from wallethub.api.models.cross_chain_models import (
    CrossChainTransferRequest,
    CrossChainTransferResponse,
    AtomicSwapRequest,
    AtomicSwapResponse,
)
from wallethub.core import CrossChainError

router = APIRouter(prefix="/cross-chain", tags=["Cross-Chain"])


@router.post("/transfers", response_model=CrossChainTransferResponse, status_code=201)
async def initiate_cross_chain_transfer(request: CrossChainTransferRequest):
    try:
        from wallethub.modules.cross_chain import CrossChainBridge

        bridge = CrossChainBridge()
        transfer = bridge.initiate_transfer(
            source_chain=request.source_chain,
            target_chain=request.target_chain,
            source_address=request.source_address,
            target_address=request.target_address,
            token_address=request.token_address,
            amount=request.amount,
            metadata=request.metadata,
        )

        return CrossChainTransferResponse(
            transfer_id=transfer.transfer_id,
            source_chain=transfer.source_chain,
            target_chain=transfer.target_chain,
            source_address=transfer.source_address,
            target_address=transfer.target_address,
            token_address=transfer.token_address,
            amount=transfer.amount,
            source_tx_hash=transfer.source_tx_hash,
            target_tx_hash=transfer.target_tx_hash,
            message_hash=transfer.message_hash,
            status=transfer.status.value,
            created_at=transfer.created_at,
            updated_at=transfer.updated_at,
        )
    except CrossChainError as e:
        raise HTTPException(status_code=400, detail=e.message)


@router.get("/transfers/{transfer_id}", response_model=CrossChainTransferResponse)
async def get_cross_chain_transfer(transfer_id: str):
    from wallethub.modules.cross_chain import CrossChainBridge

    bridge = CrossChainBridge()
    transfer = bridge.get_transfer(transfer_id)
    if not transfer:
        raise HTTPException(status_code=404, detail="Transfer not found")

    return CrossChainTransferResponse(
        transfer_id=transfer.transfer_id,
        source_chain=transfer.source_chain,
        target_chain=transfer.target_chain,
        source_address=transfer.source_address,
        target_address=transfer.target_address,
        token_address=transfer.token_address,
        amount=transfer.amount,
        source_tx_hash=transfer.source_tx_hash,
        target_tx_hash=transfer.target_tx_hash,
        message_hash=transfer.message_hash,
        status=transfer.status.value,
        created_at=transfer.created_at,
        updated_at=transfer.updated_at,
    )


@router.post("/swaps", response_model=AtomicSwapResponse, status_code=201)
async def create_atomic_swap(request: AtomicSwapRequest):
    try:
        from wallethub.modules.cross_chain import AtomicSwapManager

        manager = AtomicSwapManager()
        swap = manager.initiate_swap(
            source_chain=request.source_chain,
            target_chain=request.target_chain,
            initiator=request.initiator,
            participant=request.participant,
            source_token=request.source_token,
            target_token=request.target_token,
            source_amount=request.source_amount,
            target_amount=request.target_amount,
            secret_hash=request.secret_hash,
            timelock=request.timelock,
        )

        return AtomicSwapResponse(
            swap_id=swap.swap_id,
            source_chain=swap.source_chain,
            target_chain=swap.target_chain,
            initiator=swap.initiator,
            participant=swap.participant,
            source_token=swap.source_token,
            target_token=swap.target_token,
            source_amount=swap.source_amount,
            target_amount=swap.target_amount,
            secret_hash=swap.secret_hash,
            secret=swap.secret,
            timelock=swap.timelock,
            status=swap.status.value,
            created_at=swap.created_at,
        )
    except CrossChainError as e:
        raise HTTPException(status_code=400, detail=e.message)


@router.get("/swaps/{swap_id}", response_model=AtomicSwapResponse)
async def get_atomic_swap(swap_id: str):
    from wallethub.modules.cross_chain import AtomicSwapManager

    manager = AtomicSwapManager()
    swap = manager.get_swap(swap_id)
    if not swap:
        raise HTTPException(status_code=404, detail="Swap not found")

    return AtomicSwapResponse(
        swap_id=swap.swap_id,
        source_chain=swap.source_chain,
        target_chain=swap.target_chain,
        initiator=swap.initiator,
        participant=swap.participant,
        source_token=swap.source_token,
        target_token=swap.target_token,
        source_amount=swap.source_amount,
        target_amount=swap.target_amount,
        secret_hash=swap.secret_hash,
        secret=swap.secret,
        timelock=swap.timelock,
        status=swap.status.value,
        created_at=swap.created_at,
    )
