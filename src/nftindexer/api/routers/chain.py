from typing import Any, Dict, List, Optional
from fastapi import APIRouter, HTTPException, Query

from ...core.schemas import ResourceResponse
from ...utils import get_logger
from ..deps import ChainAdapterDep, TraceIdDep, ApiKeyDep

logger = get_logger(__name__)
router = APIRouter(prefix="/api/v1/chain", tags=["Chain"])


@router.get("/chains", response_model=ResourceResponse, dependencies=[ApiKeyDep])
async def list_supported_chains(
    chain_adapter: ChainAdapterDep,
    trace_id: TraceIdDep,
):
    chains = chain_adapter.get_supported_chains()
    return ResourceResponse(
        code=200,
        message="success",
        request_id=trace_id,
        data={"chains": chains},
    )


@router.get("/{chain_id}/block/latest", response_model=ResourceResponse, dependencies=[ApiKeyDep])
async def get_latest_block(
    chain_id: int,
    chain_adapter: ChainAdapterDep,
    trace_id: TraceIdDep,
):
    try:
        block_number = await chain_adapter.get_block_number(chain_id)
        return ResourceResponse(
            code=200,
            message="success",
            request_id=trace_id,
            data={"chain_id": chain_id, "block_number": block_number},
        )
    except Exception as e:
        logger.error(f"Error getting latest block for chain {chain_id}: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/{chain_id}/block/{block_number}", response_model=ResourceResponse, dependencies=[ApiKeyDep])
async def get_block(
    chain_id: int,
    block_number: int,
    include_txs: bool = Query(False, description="Include full transactions"),
    chain_adapter: ChainAdapterDep = ...,
    trace_id: TraceIdDep = ...,
):
    try:
        block = await chain_adapter.get_block(chain_id, block_number, include_txs)
        if not block:
            raise HTTPException(status_code=404, detail="Block not found")
        return ResourceResponse(
            code=200,
            message="success",
            request_id=trace_id,
            data={"chain_id": chain_id, "block": block},
        )
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Error getting block {block_number} for chain {chain_id}: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/{chain_id}/transaction/{tx_hash}", response_model=ResourceResponse, dependencies=[ApiKeyDep])
async def get_transaction(
    chain_id: int,
    tx_hash: str,
    chain_adapter: ChainAdapterDep,
    trace_id: TraceIdDep,
):
    try:
        tx = await chain_adapter.get_transaction(chain_id, tx_hash)
        if not tx:
            raise HTTPException(status_code=404, detail="Transaction not found")
        return ResourceResponse(
            code=200,
            message="success",
            request_id=trace_id,
            data={"chain_id": chain_id, "transaction": tx},
        )
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Error getting transaction {tx_hash} for chain {chain_id}: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/{chain_id}/transaction/{tx_hash}/receipt", response_model=ResourceResponse, dependencies=[ApiKeyDep])
async def get_transaction_receipt(
    chain_id: int,
    tx_hash: str,
    chain_adapter: ChainAdapterDep,
    trace_id: TraceIdDep,
):
    try:
        receipt = await chain_adapter.get_receipt(chain_id, tx_hash)
        if not receipt:
            raise HTTPException(status_code=404, detail="Receipt not found")
        return ResourceResponse(
            code=200,
            message="success",
            request_id=trace_id,
            data={"chain_id": chain_id, "receipt": receipt},
        )
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Error getting receipt for {tx_hash} on chain {chain_id}: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/{chain_id}/address/{address}/balance", response_model=ResourceResponse, dependencies=[ApiKeyDep])
async def get_address_balance(
    chain_id: int,
    address: str,
    chain_adapter: ChainAdapterDep,
    trace_id: TraceIdDep,
):
    try:
        balance = await chain_adapter.get_balance(chain_id, address)
        return ResourceResponse(
            code=200,
            message="success",
            request_id=trace_id,
            data={
                "chain_id": chain_id,
                "address": address,
                "balance_wei": balance,
                "balance_eth": balance / 10**18,
            },
        )
    except Exception as e:
        logger.error(f"Error getting balance for {address} on chain {chain_id}: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/{chain_id}/gas-price", response_model=ResourceResponse, dependencies=[ApiKeyDep])
async def get_gas_price(
    chain_id: int,
    chain_adapter: ChainAdapterDep,
    trace_id: TraceIdDep,
):
    try:
        gas_price = await chain_adapter.get_gas_price(chain_id)
        priority_fee = await chain_adapter.get_priority_fee(chain_id)
        return ResourceResponse(
            code=200,
            message="success",
            request_id=trace_id,
            data={
                "chain_id": chain_id,
                "gas_price_wei": gas_price,
                "max_priority_fee_per_gas_wei": priority_fee,
                "gas_price_gwei": gas_price / 10**9,
            },
        )
    except Exception as e:
        logger.error(f"Error getting gas price for chain {chain_id}: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/{chain_id}/info", response_model=ResourceResponse, dependencies=[ApiKeyDep])
async def get_chain_info(
    chain_id: int,
    chain_adapter: ChainAdapterDep,
    trace_id: TraceIdDep,
):
    try:
        info = await chain_adapter.get_chain_info(chain_id)
        return ResourceResponse(
            code=200,
            message="success",
            request_id=trace_id,
            data=info,
        )
    except Exception as e:
        logger.error(f"Error getting chain info for chain {chain_id}: {e}")
        raise HTTPException(status_code=500, detail=str(e))
