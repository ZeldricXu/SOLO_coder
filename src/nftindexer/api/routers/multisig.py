from typing import Any, Dict, List, Optional
from fastapi import APIRouter, HTTPException, Query
from pydantic import BaseModel, Field

from ...core.schemas import ResourceResponse, PaginatedResponse
from ...utils import get_logger
from ...dataclasses.requests import (
    CreateWalletRequest as WalletRequestDC,
    CreateProposalRequest as ProposalRequestDC,
    AddSignatureRequest as SignatureRequestDC,
)
from ..deps import MultiSigModuleDep, TraceIdDep, ApiKeyDep

logger = get_logger(__name__)
router = APIRouter(prefix="/api/v1/multisig", tags=["Multi-Sig Wallet"])


class CreateWalletRequest(BaseModel):
    chain_id: int
    name: str
    signers: List[str]
    threshold: int = Field(..., ge=1)
    version: str = "1.0.0"


class CreateProposalRequest(BaseModel):
    wallet_id: str
    to: str
    value: str = "0"
    data: str = "0x"
    operation: int = 0
    safe_tx_gas: str = "0"
    base_gas: str = "0"
    gas_price: str = "0"
    gas_token: str = "0x0000000000000000000000000000000000000000"
    refund_receiver: str = "0x0000000000000000000000000000000000000000"


class AddSignatureRequest(BaseModel):
    proposal_id: str
    signer: str
    signature: str
    signature_type: int = 1


@router.post("/wallets", response_model=ResourceResponse, dependencies=[ApiKeyDep])
async def create_wallet(
    request: CreateWalletRequest,
    multisig: MultiSigModuleDep,
    trace_id: TraceIdDep,
):
    try:
        req_dc = WalletRequestDC(
            chain_id=request.chain_id,
            name=request.name,
            signers=request.signers,
            threshold=request.threshold,
            version=request.version,
        )
        wallet = await multisig.create_wallet(req_dc)
        return ResourceResponse(
            code=201,
            message="Wallet created successfully",
            request_id=trace_id,
            data=wallet,
        )
    except Exception as e:
        logger.error(f"Error creating multi-sig wallet: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/wallets", response_model=ResourceResponse, dependencies=[ApiKeyDep])
async def list_wallets(
    chain_id: Optional[int] = None,
    limit: int = Query(20, ge=1, le=100),
    offset: int = Query(0, ge=0),
    multisig: MultiSigModuleDep = ...,
    trace_id: TraceIdDep = ...,
):
    try:
        wallets = await multisig.list_wallets(chain_id=chain_id, limit=limit, offset=offset)
        return ResourceResponse(
            code=200,
            message="success",
            request_id=trace_id,
            data={"wallets": wallets, "total": len(wallets)},
        )
    except Exception as e:
        logger.error(f"Error listing multi-sig wallets: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/wallets/{wallet_id}", response_model=ResourceResponse, dependencies=[ApiKeyDep])
async def get_wallet(
    wallet_id: str,
    multisig: MultiSigModuleDep,
    trace_id: TraceIdDep,
):
    try:
        wallet = await multisig.get_wallet(wallet_id)
        if not wallet:
            raise HTTPException(status_code=404, detail="Wallet not found")
        return ResourceResponse(
            code=200,
            message="success",
            request_id=trace_id,
            data=wallet,
        )
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Error getting wallet {wallet_id}: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/proposals", response_model=ResourceResponse, dependencies=[ApiKeyDep])
async def create_proposal(
    request: CreateProposalRequest,
    multisig: MultiSigModuleDep,
    trace_id: TraceIdDep,
):
    try:
        req_dc = ProposalRequestDC(
            wallet_id=request.wallet_id,
            to=request.to,
            value=int(request.value),
            data=request.data,
            operation=request.operation,
            safe_tx_gas=int(request.safe_tx_gas),
            base_gas=int(request.base_gas),
            gas_price=int(request.gas_price),
            gas_token=request.gas_token,
            refund_receiver=request.refund_receiver,
        )
        proposal = await multisig.create_proposal(req_dc)
        return ResourceResponse(
            code=201,
            message="Proposal created successfully",
            request_id=trace_id,
            data=proposal,
        )
    except Exception as e:
        logger.error(f"Error creating proposal: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/proposals", response_model=ResourceResponse, dependencies=[ApiKeyDep])
async def list_proposals(
    wallet_id: Optional[str] = None,
    status: Optional[str] = None,
    limit: int = Query(20, ge=1, le=100),
    offset: int = Query(0, ge=0),
    multisig: MultiSigModuleDep = ...,
    trace_id: TraceIdDep = ...,
):
    try:
        proposals = await multisig.list_proposals(
            wallet_id=wallet_id,
            status=status,
            limit=limit,
            offset=offset,
        )
        return ResourceResponse(
            code=200,
            message="success",
            request_id=trace_id,
            data={"proposals": proposals, "total": len(proposals)},
        )
    except Exception as e:
        logger.error(f"Error listing proposals: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/proposals/{proposal_id}", response_model=ResourceResponse, dependencies=[ApiKeyDep])
async def get_proposal(
    proposal_id: str,
    multisig: MultiSigModuleDep,
    trace_id: TraceIdDep,
):
    try:
        proposal = await multisig.get_proposal(proposal_id)
        if not proposal:
            raise HTTPException(status_code=404, detail="Proposal not found")
        return ResourceResponse(
            code=200,
            message="success",
            request_id=trace_id,
            data=proposal,
        )
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Error getting proposal {proposal_id}: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/proposals/{proposal_id}/signatures", response_model=ResourceResponse, dependencies=[ApiKeyDep])
async def add_signature(
    proposal_id: str,
    request: AddSignatureRequest,
    multisig: MultiSigModuleDep,
    trace_id: TraceIdDep,
):
    try:
        if request.proposal_id != proposal_id:
            raise HTTPException(status_code=400, detail="Proposal ID mismatch")
        req_dc = SignatureRequestDC(
            proposal_id=proposal_id,
            signer=request.signer,
            signature=request.signature,
            signature_type=request.signature_type,
        )
        signature = await multisig.add_signature(req_dc)
        return ResourceResponse(
            code=201,
            message="Signature added successfully",
            request_id=trace_id,
            data=signature,
        )
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Error adding signature to proposal {proposal_id}: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/proposals/{proposal_id}/execute", response_model=ResourceResponse, dependencies=[ApiKeyDep])
async def execute_proposal(
    proposal_id: str,
    multisig: MultiSigModuleDep,
    trace_id: TraceIdDep,
):
    try:
        result = await multisig.execute_proposal(proposal_id)
        return ResourceResponse(
            code=200,
            message="Proposal executed successfully",
            request_id=trace_id,
            data=result,
        )
    except Exception as e:
        logger.error(f"Error executing proposal {proposal_id}: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/strategies", response_model=ResourceResponse, dependencies=[ApiKeyDep])
async def get_strategies(
    multisig: MultiSigModuleDep,
    trace_id: TraceIdDep,
):
    try:
        result = await multisig.get_strategies()
        return ResourceResponse(
            code=200,
            message="success",
            request_id=trace_id,
            data=result,
        )
    except Exception as e:
        logger.error(f"Error getting strategies: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/strategies", response_model=ResourceResponse, dependencies=[ApiKeyDep])
async def set_strategy(
    request: dict,
    multisig: MultiSigModuleDep,
    trace_id: TraceIdDep,
):
    try:
        strategy_type = request.get("strategy_type")
        if not strategy_type:
            raise HTTPException(status_code=400, detail="strategy_type is required")
        result = await multisig.set_strategy(strategy_type)
        return ResourceResponse(
            code=200,
            message="Strategy updated successfully",
            request_id=trace_id,
            data=result,
        )
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Error setting strategy: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/chains/{chain_id}/strategy", response_model=ResourceResponse, dependencies=[ApiKeyDep])
async def set_chain_strategy(
    chain_id: int,
    request: dict,
    multisig: MultiSigModuleDep,
    trace_id: TraceIdDep,
):
    try:
        strategy_type = request.get("strategy_type")
        if not strategy_type:
            raise HTTPException(status_code=400, detail="strategy_type is required")
        result = await multisig.set_chain_strategy(chain_id, strategy_type)
        return ResourceResponse(
            code=200,
            message="Chain strategy updated successfully",
            request_id=trace_id,
            data=result,
        )
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Error setting chain strategy: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/wallets/{wallet_id}/strategy", response_model=ResourceResponse, dependencies=[ApiKeyDep])
async def set_wallet_strategy(
    wallet_id: str,
    request: dict,
    multisig: MultiSigModuleDep,
    trace_id: TraceIdDep,
):
    try:
        strategy_type = request.get("strategy_type")
        if not strategy_type:
            raise HTTPException(status_code=400, detail="strategy_type is required")
        result = await multisig.set_wallet_strategy(wallet_id, strategy_type)
        return ResourceResponse(
            code=200,
            message="Wallet strategy updated successfully",
            request_id=trace_id,
            data=result,
        )
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Error setting wallet strategy: {e}")
        raise HTTPException(status_code=500, detail=str(e))
