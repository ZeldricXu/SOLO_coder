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
    Hash,
    HexString,
    Transaction,
    TransactionReceipt,
    WeiAmount,
)

router = APIRouter(prefix="/chain", tags=["chain"])


async def get_container() -> Container:
    return container


@router.get("/chains", response_model=APIResponse[List[str]])
async def list_chains(
    container: Container = Depends(get_container),
):
    chains = container.list_available_chains()
    return APIResponse.success(data=[c.value for c in chains])


@router.get("/{chain}/block/number", response_model=APIResponse[BlockNumber])
async def get_block_number(
    chain: Chain,
    container: Container = Depends(get_container),
):
    try:
        adapter = container.get_chain_adapter(chain)
        block_number = await adapter.get_block_number()
        return APIResponse.success(data=block_number)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/{chain}/block/{block_number}", response_model=APIResponse[Dict[str, Any]])
async def get_block(
    chain: Chain,
    block_number: BlockNumber,
    full_transactions: bool = Query(False),
    container: Container = Depends(get_container),
):
    try:
        adapter = container.get_chain_adapter(chain)
        block = await adapter.get_block(block_number, full_transactions)
        return APIResponse.success(data=block)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/{chain}/transaction/{tx_hash}", response_model=APIResponse[Optional[Transaction]])
async def get_transaction(
    chain: Chain,
    tx_hash: Hash,
    container: Container = Depends(get_container),
):
    try:
        adapter = container.get_chain_adapter(chain)
        tx = await adapter.get_transaction(tx_hash)
        return APIResponse.success(data=tx)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/{chain}/transaction/{tx_hash}/receipt", response_model=APIResponse[Optional[TransactionReceipt]])
async def get_transaction_receipt(
    chain: Chain,
    tx_hash: Hash,
    container: Container = Depends(get_container),
):
    try:
        adapter = container.get_chain_adapter(chain)
        receipt = await adapter.get_transaction_receipt(tx_hash)
        return APIResponse.success(data=receipt)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/{chain}/balance/{address}", response_model=APIResponse[WeiAmount])
async def get_balance(
    chain: Chain,
    address: Address,
    container: Container = Depends(get_container),
):
    try:
        adapter = container.get_chain_adapter(chain)
        balance = await adapter.get_balance(address)
        return APIResponse.success(data=balance)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/{chain}/nonce/{address}", response_model=APIResponse[int])
async def get_transaction_count(
    chain: Chain,
    address: Address,
    container: Container = Depends(get_container),
):
    try:
        adapter = container.get_chain_adapter(chain)
        count = await adapter.get_transaction_count(address)
        return APIResponse.success(data=count)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/{chain}/send", response_model=APIResponse[Hash])
async def send_raw_transaction(
    chain: Chain,
    raw_tx: Dict[str, Any],
    container: Container = Depends(get_container),
):
    try:
        adapter = container.get_chain_adapter(chain)
        tx_hash = await adapter.send_raw_transaction(raw_tx["raw_transaction"])
        return APIResponse.success(data=tx_hash)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/{chain}/logs", response_model=APIResponse[List[EventLog]])
async def get_logs(
    chain: Chain,
    from_block: Optional[BlockNumber] = None,
    to_block: Optional[BlockNumber] = None,
    address: Optional[Address] = None,
    container: Container = Depends(get_container),
):
    try:
        adapter = container.get_chain_adapter(chain)
        logs = await adapter.get_logs(
            from_block=from_block,
            to_block=to_block,
            address=address,
        )
        return APIResponse.success(data=logs)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/{chain}/call", response_model=APIResponse[HexString])
async def call_contract(
    chain: Chain,
    call_data: Dict[str, Any],
    container: Container = Depends(get_container),
):
    try:
        adapter = container.get_chain_adapter(chain)
        result = await adapter.call(
            to=call_data["to"],
            data=call_data["data"],
            from_address=call_data.get("from_address"),
        )
        return APIResponse.success(data=result)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))
