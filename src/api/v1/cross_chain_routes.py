from __future__ import annotations

from typing import Any, Dict, List, Optional

from fastapi import APIRouter, Depends, HTTPException, Query

from src.shared.container import Container, container
from src.shared.types import (
    Address,
    APIResponse,
    Chain,
    CrossChainMessage,
    Hash,
    HexString,
    WeiAmount,
)

router = APIRouter(prefix="/crosschain", tags=["crosschain"])


async def get_container() -> Container:
    return container


@router.post("/lock", response_model=APIResponse[CrossChainMessage])
async def lock_assets(
    chain: Chain,
    request: Dict[str, Any],
    container: Container = Depends(get_container),
):
    try:
        bridge = container.get_cross_chain_bridge(chain)
        message = await bridge.lock_assets(
            source_chain=Chain(request["source_chain"]),
            target_chain=Chain(request["target_chain"]),
            source_address=request["source_address"],
            target_address=request["target_address"],
            amount=request["amount"],
            token_address=request.get("token_address"),
            data=request.get("data", "0x"),
        )
        return APIResponse.success(data=message)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/mint", response_model=APIResponse[Hash])
async def mint_assets(
    chain: Chain,
    request: Dict[str, Any],
    container: Container = Depends(get_container),
):
    try:
        bridge = container.get_cross_chain_bridge(chain)
        message = CrossChainMessage(**request["message"])
        tx_hash = await bridge.mint_assets(
            message=message,
            proof=request["proof"],
        )
        return APIResponse.success(data=tx_hash)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/verify", response_model=APIResponse[bool])
async def verify_message(
    chain: Chain,
    request: Dict[str, Any],
    container: Container = Depends(get_container),
):
    try:
        bridge = container.get_cross_chain_bridge(chain)
        message = CrossChainMessage(**request["message"])
        verified = await bridge.verify_message(
            message=message,
            proof=request["proof"],
        )
        return APIResponse.success(data=verified)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/message/{message_id}", response_model=APIResponse[CrossChainMessage])
async def get_message_status(
    chain: Chain,
    message_id: str,
    container: Container = Depends(get_container)),
):
    try:
        bridge = container.get_cross_chain_bridge(chain)
        message = await bridge.get_message_status(message_id)
        return APIResponse.success(data=message)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/messages", response_model=APIResponse[List[CrossChainMessage]])
async def list_messages(
    chain: Chain,
    source_chain: Optional[str] = None,
    target_chain: Optional[str] = None,
    status: Optional[str] = None,
    limit: int = Query(100, ge=1, le=1000),
    container: Container = Depends(get_container)),
):
    try:
        bridge = container.get_cross_chain_bridge(chain)
        messages = await bridge.list_messages(
            source_chain=Chain(source_chain) if source_chain else None,
            target_chain=Chain(target_chain) if target_chain else None,
            status=status,
            limit=limit,
        )
        return APIResponse.success(data=messages)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/retry/{message_id}", response_model=APIResponse[bool])
async def retry_message(
    chain: Chain,
    message_id: str,
    container: Container = Depends(get_container)),
):
    try:
        bridge = container.get_cross_chain_bridge(chain)
        success = await bridge.retry_message(message_id)
        return APIResponse.success(data=success)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/cancel/{message_id}", response_model=APIResponse[bool])
async def cancel_message(
    chain: Chain,
    message_id: str,
    container: Container = Depends(get_container)),
):
    try:
        bridge = container.get_cross_chain_bridge(chain)
        success = await bridge.cancel_message(message_id)
        return APIResponse.success(data=success)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/proof", response_model=APIResponse[HexString])
async def generate_proof(
    chain: Chain,
    request: Dict[str, Any],
    container: Container = Depends(get_container)),
):
    try:
        bridge = container.get_cross_chain_bridge(chain)
        message = CrossChainMessage(**request["message"])
        proof = await bridge.generate_proof(
            message=message,
            source_tx_hash=request["source_tx_hash"],
        )
        return APIResponse.success(data=proof)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))
