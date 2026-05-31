from __future__ import annotations

from typing import Any, Dict, List, Optional

from fastapi import APIRouter, Depends, HTTPException, Query

from src.shared.container import Container, container
from src.shared.types import (
    Address,
    APIResponse,
    BlockNumber,
    Chain,
    EventLog,
    Transaction,
)

router = APIRouter(prefix="/indexer", tags=["indexer"])


async def get_container() -> Container:
    return container


@router.post("/{chain}/start", response_model=APIResponse[bool])
async def start_indexing(
    chain: Chain,
    start_block: Optional[BlockNumber] = None,
    end_block: Optional[BlockNumber] = None,
    container: Container = Depends(get_container),
):
    try:
        indexer = container.get_indexer(chain)
        await indexer.start_indexing(start_block, end_block)
        return APIResponse.success(data=True)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/{chain}/stop", response_model=APIResponse[bool])
async def stop_indexing(
    chain: Chain,
    container: Container = Depends(get_container),
):
    try:
        indexer = container.get_indexer(chain)
        await indexer.stop_indexing()
        return APIResponse.success(data=True)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/{chain}/status", response_model=APIResponse[Dict[str, Any]])
async def get_indexer_status(
    chain: Chain,
    container: Container = Depends(get_container),
):
    try:
        indexer = container.get_indexer(chain)
        status = await indexer.get_index_status()
        return APIResponse.success(data=status)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/{chain}/reindex", response_model=APIResponse[bool])
async def reindex_blocks(
    chain: Chain,
    request: Dict[str, Any],
    container: Container = Depends(get_container),
):
    try:
        indexer = container.get_indexer(chain)
        success = await indexer.reindex(
            from_block=request["from_block"],
            to_block=request.get("to_block"),
        )
        return APIResponse.success(data=success)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/{chain}/block", response_model=APIResponse[Dict[str, Any]])
async def index_single_block(
    chain: Chain,
    block_number: BlockNumber,
    container: Container = Depends(get_container),
):
    try:
        indexer = container.get_indexer(chain)
        result = await indexer.index_block(block_number)
        return APIResponse.success(data=result)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/{chain}/block/{block_number}", response_model=APIResponse[Optional[Dict[str, Any]]])
async def get_indexed_block(
    chain: Chain,
    block_number: BlockNumber,
    container: Container = Depends(get_container),
):
    try:
        indexer = container.get_indexer(chain)
        block = await indexer._store.get_block(block_number)
        return APIResponse.success(data=block.model_dump() if block else None)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/{chain}/transaction/{tx_hash}", response_model=APIResponse[Optional[Transaction]])
async def get_indexed_transaction(
    chain: Chain,
    tx_hash: str,
    container: Container = Depends(get_container),
):
    try:
        indexer = container.get_indexer(chain)
        tx = await indexer._store.get_transaction(tx_hash)
        return APIResponse.success(data=tx)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/{chain}/address/{address}/transactions", response_model=APIResponse[List[Transaction]])
async def get_transactions_by_address(
    chain: Chain,
    address: Address,
    limit: int = Query(100, ge=1, le=1000),
    offset: int = Query(0, ge=0),
    container: Container = Depends(get_container),
):
    try:
        indexer = container.get_indexer(chain)
        txs = await indexer._store.get_transactions_by_address(
            address=address,
            limit=limit,
            offset=offset,
        )
        return APIResponse.success(data=txs)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/{chain}/contract/{contract_address}/logs", response_model=APIResponse[List[EventLog]])
async def get_logs_by_contract(
    chain: Chain,
    contract_address: Address,
    event_signature: Optional[str] = None,
    limit: int = Query(100, ge=1, le=1000),
    offset: int = Query(0, ge=0),
    container: Container = Depends(get_container),
):
    try:
        indexer = container.get_indexer(chain)
        logs = await indexer._store.get_logs_by_contract(
            contract_address=contract_address,
            event_signature=event_signature,
            limit=limit,
            offset=offset,
        )
        return APIResponse.success(data=logs)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/{chain}/latest", response_model=APIResponse[BlockNumber])
async def get_latest_indexed_block(
    chain: Chain,
    container: Container = Depends(get_container),
):
    try:
        indexer = container.get_indexer(chain)
        block = await indexer.get_latest_indexed_block()
        return APIResponse.success(data=block)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))
