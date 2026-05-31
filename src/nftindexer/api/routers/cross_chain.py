from typing import Any, Dict, List, Optional
from fastapi import APIRouter, HTTPException, Query
from pydantic import BaseModel

from ...core.schemas import ResourceResponse
from ...utils import get_logger
from ...dataclasses.requests import BridgeRequest, MessageProof
from ..deps import CrossChainModuleDep, TraceIdDep, ApiKeyDep

logger = get_logger(__name__)
router = APIRouter(prefix="/api/v1/crosschain", tags=["Cross-Chain Bridge"])


class InitiateBridgeRequest(BaseModel):
    source_chain_id: int
    target_chain_id: int
    sender: str
    recipient: str
    amount: str
    token_address: str
    message_payload: Dict[str, Any] = {}


class VerifyProofRequest(BaseModel):
    tx_id: str
    proof_data: Dict[str, Any]


@router.post("/bridge", response_model=ResourceResponse, dependencies=[ApiKeyDep])
async def initiate_bridge(
    request: InitiateBridgeRequest,
    cross_chain: CrossChainModuleDep,
    trace_id: TraceIdDep,
):
    try:
        req_dc = BridgeRequest(
            source_chain_id=request.source_chain_id,
            target_chain_id=request.target_chain_id,
            sender=request.sender,
            recipient=request.recipient,
            amount=int(request.amount),
            token_address=request.token_address,
            message_payload=request.message_payload,
        )
        tx = await cross_chain.initiate_bridge(req_dc)
        return ResourceResponse(
            code=201,
            message="Bridge transaction initiated successfully",
            request_id=trace_id,
            data=tx,
        )
    except Exception as e:
        logger.error(f"Error initiating bridge: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/transactions", response_model=ResourceResponse, dependencies=[ApiKeyDep])
async def list_transactions(
    source_chain_id: Optional[int] = None,
    target_chain_id: Optional[int] = None,
    status: Optional[str] = None,
    sender: Optional[str] = None,
    recipient: Optional[str] = None,
    limit: int = Query(20, ge=1, le=100),
    offset: int = Query(0, ge=0),
    cross_chain: CrossChainModuleDep = ...,
    trace_id: TraceIdDep = ...,
):
    try:
        txs = await cross_chain.list_transactions(
            source_chain_id=source_chain_id,
            target_chain_id=target_chain_id,
            status=status,
            sender=sender,
            recipient=recipient,
            limit=limit,
            offset=offset,
        )
        return ResourceResponse(
            code=200,
            message="success",
            request_id=trace_id,
            data={"transactions": txs, "total": len(txs)},
        )
    except Exception as e:
        logger.error(f"Error listing cross-chain transactions: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/transactions/{tx_id}", response_model=ResourceResponse, dependencies=[ApiKeyDep])
async def get_transaction(
    tx_id: str,
    cross_chain: CrossChainModuleDep,
    trace_id: TraceIdDep,
):
    try:
        tx = await cross_chain.get_transaction(tx_id)
        if not tx:
            raise HTTPException(status_code=404, detail="Transaction not found")
        return ResourceResponse(
            code=200,
            message="success",
            request_id=trace_id,
            data=tx,
        )
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Error getting cross-chain transaction {tx_id}: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/transactions/{tx_id}/confirm", response_model=ResourceResponse, dependencies=[ApiKeyDep])
async def confirm_source_transaction(
    tx_id: str,
    cross_chain: CrossChainModuleDep,
    trace_id: TraceIdDep,
):
    try:
        result = await cross_chain.confirm_source_transaction(tx_id)
        return ResourceResponse(
            code=200,
            message="Source transaction confirmed",
            request_id=trace_id,
            data=result,
        )
    except Exception as e:
        logger.error(f"Error confirming source transaction {tx_id}: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/transactions/{tx_id}/verify-proof", response_model=ResourceResponse, dependencies=[ApiKeyDep])
async def verify_message_proof(
    tx_id: str,
    request: VerifyProofRequest,
    cross_chain: CrossChainModuleDep,
    trace_id: TraceIdDep,
):
    try:
        if request.tx_id != tx_id:
            raise HTTPException(status_code=400, detail="Transaction ID mismatch")
        proof = MessageProof(
            message_hash=request.proof_data.get("message_hash", ""),
            merkle_proof=request.proof_data.get("merkle_proof", []),
            merkle_root=request.proof_data.get("merkle_root", ""),
            proof_data=request.proof_data,
        )
        result = await cross_chain.verify_message_proof(tx_id, proof)
        return ResourceResponse(
            code=200,
            message="Proof verification complete",
            request_id=trace_id,
            data=result,
        )
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Error verifying proof for transaction {tx_id}: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/transactions/{tx_id}/complete", response_model=ResourceResponse, dependencies=[ApiKeyDep])
async def complete_transaction(
    tx_id: str,
    cross_chain: CrossChainModuleDep,
    trace_id: TraceIdDep,
):
    try:
        result = await cross_chain.complete_transaction(tx_id)
        return ResourceResponse(
            code=200,
            message="Transaction completed successfully",
            request_id=trace_id,
            data=result,
        )
    except Exception as e:
        logger.error(f"Error completing transaction {tx_id}: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/transactions/{tx_id}/atomic-status", response_model=ResourceResponse, dependencies=[ApiKeyDep])
async def get_atomic_status(
    tx_id: str,
    cross_chain: CrossChainModuleDep,
    trace_id: TraceIdDep,
):
    try:
        status = await cross_chain.get_atomic_status(tx_id)
        return ResourceResponse(
            code=200,
            message="success",
            request_id=trace_id,
            data=status,
        )
    except Exception as e:
        logger.error(f"Error getting atomic status for transaction {tx_id}: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/transactions/{tx_id}/rollback", response_model=ResourceResponse, dependencies=[ApiKeyDep])
async def rollback_transaction(
    tx_id: str,
    cross_chain: CrossChainModuleDep,
    trace_id: TraceIdDep,
):
    try:
        result = await cross_chain.rollback_transaction(tx_id)
        return ResourceResponse(
            code=200,
            message="Transaction rolled back successfully",
            request_id=trace_id,
            data=result,
        )
    except Exception as e:
        logger.error(f"Error rolling back transaction {tx_id}: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/tasks", response_model=ResourceResponse, dependencies=[ApiKeyDep])
async def list_tasks(
    status: Optional[str] = None,
    task_type: Optional[str] = None,
    limit: int = Query(100, ge=1, le=1000),
    cross_chain: CrossChainModuleDep = ...,
    trace_id: TraceIdDep = ...,
):
    try:
        result = await cross_chain.list_tasks(status=status, task_type=task_type, limit=limit)
        return ResourceResponse(
            code=200,
            message="success",
            request_id=trace_id,
            data=result,
        )
    except Exception as e:
        logger.error(f"Error listing tasks: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/tasks/{task_id}", response_model=ResourceResponse, dependencies=[ApiKeyDep])
async def get_task(
    task_id: str,
    cross_chain: CrossChainModuleDep,
    trace_id: TraceIdDep,
):
    try:
        task = await cross_chain.get_task(task_id)
        if not task:
            raise HTTPException(status_code=404, detail="Task not found")
        return ResourceResponse(
            code=200,
            message="success",
            request_id=trace_id,
            data=task,
        )
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Error getting task {task_id}: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/tasks/{task_id}/cancel", response_model=ResourceResponse, dependencies=[ApiKeyDep])
async def cancel_task(
    task_id: str,
    cross_chain: CrossChainModuleDep,
    trace_id: TraceIdDep,
):
    try:
        result = await cross_chain.cancel_task(task_id)
        return ResourceResponse(
            code=200,
            message="Task cancelled successfully",
            request_id=trace_id,
            data=result,
        )
    except Exception as e:
        logger.error(f"Error cancelling task {task_id}: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/transactions/{tx_id}/webhook", response_model=ResourceResponse, dependencies=[ApiKeyDep])
async def register_webhook(
    tx_id: str,
    request: dict,
    cross_chain: CrossChainModuleDep,
    trace_id: TraceIdDep,
):
    try:
        webhook_url = request.get("webhook_url")
        if not webhook_url:
            raise HTTPException(status_code=400, detail="webhook_url is required")
        cross_chain.register_webhook(tx_id, webhook_url)
        return ResourceResponse(
            code=200,
            message="Webhook registered successfully",
            request_id=trace_id,
            data={"tx_id": tx_id, "webhook_url": webhook_url},
        )
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Error registering webhook for transaction {tx_id}: {e}")
        raise HTTPException(status_code=500, detail=str(e))
