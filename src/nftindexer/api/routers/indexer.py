from typing import Any, Dict, List, Optional
from fastapi import APIRouter, HTTPException, Query
from pydantic import BaseModel

from ...core.schemas import ResourceResponse
from ...utils import get_logger
from ..deps import IndexerModuleDep, TraceIdDep, ApiKeyDep

logger = get_logger(__name__)
router = APIRouter(prefix="/api/v1/indexer", tags=["Blockchain Indexer"])


class IndexBlockRangeRequest(BaseModel):
    chain_id: int
    start_block: int
    end_block: int
    batch_size: int = 10


class StartIndexerRequest(BaseModel):
    chain_id: int
    start_block: Optional[int] = None
    batch_size: int = 10


@router.post("/index/block", response_model=ResourceResponse, dependencies=[ApiKeyDep])
async def index_block(
    chain_id: int,
    block_number: int,
    indexer: IndexerModuleDep,
    trace_id: TraceIdDep,
):
    try:
        result = await indexer.index_block(chain_id, block_number)
        return ResourceResponse(
            code=200,
            message="Block indexed successfully",
            request_id=trace_id,
            data=result,
        )
    except Exception as e:
        logger.error(f"Error indexing block {block_number} on chain {chain_id}: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/index/range", response_model=ResourceResponse, dependencies=[ApiKeyDep])
async def index_block_range(
    request: IndexBlockRangeRequest,
    indexer: IndexerModuleDep,
    trace_id: TraceIdDep,
):
    try:
        count = await indexer.index_block_range(
            chain_id=request.chain_id,
            start_block=request.start_block,
            end_block=request.end_block,
            batch_size=request.batch_size,
        )
        return ResourceResponse(
            code=200,
            message="Block range indexed successfully",
            request_id=trace_id,
            data={"blocks_indexed": count},
        )
    except Exception as e:
        logger.error(f"Error indexing block range on chain {request.chain_id}: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/start", response_model=ResourceResponse, dependencies=[ApiKeyDep])
async def start_chain_indexer(
    request: StartIndexerRequest,
    indexer: IndexerModuleDep,
    trace_id: TraceIdDep,
):
    try:
        await indexer.start_chain_indexer(
            chain_id=request.chain_id,
            start_block=request.start_block,
            batch_size=request.batch_size,
        )
        return ResourceResponse(
            code=200,
            message="Chain indexer started successfully",
            request_id=trace_id,
            data={"chain_id": request.chain_id, "status": "running"},
        )
    except Exception as e:
        logger.error(f"Error starting indexer for chain {request.chain_id}: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/stop/{chain_id}", response_model=ResourceResponse, dependencies=[ApiKeyDep])
async def stop_chain_indexer(
    chain_id: int,
    indexer: IndexerModuleDep,
    trace_id: TraceIdDep,
):
    try:
        await indexer.stop_chain_indexer(chain_id)
        return ResourceResponse(
            code=200,
            message="Chain indexer stopped successfully",
            request_id=trace_id,
            data={"chain_id": chain_id, "status": "stopped"},
        )
    except Exception as e:
        logger.error(f"Error stopping indexer for chain {chain_id}: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/status/{chain_id}", response_model=ResourceResponse, dependencies=[ApiKeyDep])
async def get_indexing_status(
    chain_id: int,
    indexer: IndexerModuleDep,
    trace_id: TraceIdDep,
):
    try:
        status = await indexer.get_indexing_status(chain_id)
        return ResourceResponse(
            code=200,
            message="success",
            request_id=trace_id,
            data={
                "chain_id": status.chain_id,
                "latest_block": status.latest_block,
                "latest_indexed_block": status.latest_indexed_block,
                "is_running": status.is_running,
                "blocks_indexed": status.blocks_indexed,
                "transactions_indexed": status.transactions_indexed,
                "logs_indexed": status.logs_indexed,
            },
        )
    except Exception as e:
        logger.error(f"Error getting indexing status for chain {chain_id}: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/blocks/{chain_id}", response_model=ResourceResponse, dependencies=[ApiKeyDep])
async def list_indexed_blocks(
    chain_id: int,
    start_block: Optional[int] = Query(None),
    end_block: Optional[int] = Query(None),
    limit: int = Query(100, ge=1, le=1000),
    offset: int = Query(0, ge=0),
    indexer: IndexerModuleDep = ...,
    trace_id: TraceIdDep = ...,
):
    try:
        blocks = await indexer.list_indexed_blocks(
            chain_id=chain_id,
            start_block=start_block,
            end_block=end_block,
            limit=limit,
            offset=offset,
        )
        return ResourceResponse(
            code=200,
            message="success",
            request_id=trace_id,
            data={"blocks": blocks, "total": len(blocks)},
        )
    except Exception as e:
        logger.error(f"Error listing indexed blocks for chain {chain_id}: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/blocks/{chain_id}/{block_number}", response_model=ResourceResponse, dependencies=[ApiKeyDep])
async def get_indexed_block(
    chain_id: int,
    block_number: int,
    indexer: IndexerModuleDep,
    trace_id: TraceIdDep,
):
    try:
        block = await indexer.get_indexed_block(chain_id, block_number)
        if not block:
            raise HTTPException(status_code=404, detail="Block not found")
        return ResourceResponse(
            code=200,
            message="success",
            request_id=trace_id,
            data=block,
        )
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Error getting indexed block {block_number} on chain {chain_id}: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/transactions/{chain_id}/{tx_hash}", response_model=ResourceResponse, dependencies=[ApiKeyDep])
async def get_indexed_transaction(
    chain_id: int,
    tx_hash: str,
    indexer: IndexerModuleDep,
    trace_id: TraceIdDep,
):
    try:
        tx = await indexer.get_indexed_transaction(chain_id, tx_hash)
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
        logger.error(f"Error getting indexed transaction {tx_hash} on chain {chain_id}: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/address/{chain_id}/{address}/transactions", response_model=ResourceResponse, dependencies=[ApiKeyDep])
async def list_address_transactions(
    chain_id: int,
    address: str,
    limit: int = Query(100, ge=1, le=500),
    offset: int = Query(0, ge=0),
    indexer: IndexerModuleDep = ...,
    trace_id: TraceIdDep = ...,
):
    try:
        txs = await indexer.list_address_transactions(
            chain_id=chain_id,
            address=address,
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
        logger.error(f"Error listing transactions for address {address} on chain {chain_id}: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/contract/{chain_id}/{address}/transactions", response_model=ResourceResponse, dependencies=[ApiKeyDep])
async def list_contract_transactions(
    chain_id: int,
    address: str,
    limit: int = Query(100, ge=1, le=500),
    offset: int = Query(0, ge=0),
    indexer: IndexerModuleDep = ...,
    trace_id: TraceIdDep = ...,
):
    try:
        txs = await indexer.list_contract_transactions(
            chain_id=chain_id,
            contract_address=address,
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
        logger.error(f"Error listing transactions for contract {address} on chain {chain_id}: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/logs/{chain_id}", response_model=ResourceResponse, dependencies=[ApiKeyDep])
async def get_indexed_logs(
    chain_id: int,
    address: Optional[str] = Query(None),
    topic0: Optional[str] = Query(None),
    from_block: Optional[int] = Query(None),
    to_block: Optional[int] = Query(None),
    limit: int = Query(100, ge=1, le=500),
    offset: int = Query(0, ge=0),
    indexer: IndexerModuleDep = ...,
    trace_id: TraceIdDep = ...,
):
    try:
        logs = await indexer.get_indexed_logs(
            chain_id=chain_id,
            address=address,
            topic0=topic0,
            from_block=from_block,
            to_block=to_block,
            limit=limit,
            offset=offset,
        )
        return ResourceResponse(
            code=200,
            message="success",
            request_id=trace_id,
            data={"logs": logs, "total": len(logs)},
        )
    except Exception as e:
        logger.error(f"Error getting indexed logs for chain {chain_id}: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/stats", response_model=ResourceResponse, dependencies=[ApiKeyDep])
async def get_indexer_stats(
    chain_id: Optional[int] = None,
    indexer: IndexerModuleDep = ...,
    trace_id: TraceIdDep = ...,
):
    try:
        stats = await indexer.get_indexer_stats(chain_id)
        return ResourceResponse(
            code=200,
            message="success",
            request_id=trace_id,
            data=stats,
        )
    except Exception as e:
        logger.error(f"Error getting indexer stats: {e}")
        raise HTTPException(status_code=500, detail=str(e))
