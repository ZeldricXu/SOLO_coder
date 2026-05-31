from fastapi import APIRouter, HTTPException
from typing import List, Dict, Any

from wallethub.api.models.indexer_models import (
    IndexerStatusResponse,
    IndexedBlockResponse,
    IndexedTransactionResponse,
)
from wallethub.core import IndexerError

router = APIRouter(prefix="/indexer", tags=["Indexer"])


@router.get("/status", response_model=List[IndexerStatusResponse])
async def get_indexer_statuses():
    from wallethub.modules.indexer import IndexManager

    manager = IndexManager()
    statuses = manager.get_all_statuses()

    return [
        IndexerStatusResponse(
            chain=chain,
            status=status["status"],
            current_block=status["current_block"],
            latest_block=status["latest_block"],
            blocks_behind=status["blocks_behind"],
            progress_percent=manager.get_sync_progress(chain).get("progress_percent", 0),
        )
        for chain, status in statuses.items()
    ]


@router.get("/{chain}/status", response_model=IndexerStatusResponse)
async def get_indexer_status(chain: str):
    from wallethub.modules.indexer import IndexManager

    manager = IndexManager()
    progress = manager.get_sync_progress(chain)

    if not progress.get("exists"):
        raise HTTPException(status_code=404, detail=f"Indexer for chain {chain} not found")

    return IndexerStatusResponse(
        chain=chain,
        status=progress["status"],
        current_block=progress["current_block"],
        latest_block=progress["latest_block"],
        blocks_behind=progress["blocks_behind"],
        progress_percent=progress["progress_percent"],
    )


@router.post("/{chain}/start")
async def start_indexer(chain: str):
    try:
        from wallethub.modules.indexer import IndexManager

        manager = IndexManager()
        manager.get_or_create_indexer(chain)
        await manager.start_indexer(chain)
        return {"message": f"Indexer started for {chain}", "chain": chain}
    except IndexerError as e:
        raise HTTPException(status_code=400, detail=e.message)


@router.post("/{chain}/stop")
async def stop_indexer(chain: str):
    try:
        from wallethub.modules.indexer import IndexManager

        manager = IndexManager()
        await manager.stop_indexer(chain)
        return {"message": f"Indexer stopped for {chain}", "chain": chain}
    except IndexerError as e:
        raise HTTPException(status_code=400, detail=e.message)


@router.post("/start-all")
async def start_all_indexers():
    from wallethub.modules.indexer import IndexManager

    manager = IndexManager()
    await manager.start_all()
    return {"message": "All indexers started"}


@router.post("/stop-all")
async def stop_all_indexers():
    from wallethub.modules.indexer import IndexManager

    manager = IndexManager()
    await manager.stop_all()
    return {"message": "All indexers stopped"}


@router.get("/{chain}/blocks/{block_number}", response_model=IndexedBlockResponse)
async def get_indexed_block(chain: str, block_number: int):
    try:
        from wallethub.modules.indexer import IndexManager

        manager = IndexManager()
        indexer = manager.get_or_create_indexer(chain)
        block_data = await indexer.index_single_block(block_number)

        return IndexedBlockResponse(
            chain=chain,
            block_number=block_data.block_number,
            block_hash=block_data.block_hash,
            parent_hash=block_data.parent_hash,
            timestamp=block_data.timestamp,
            difficulty=block_data.difficulty,
            gas_limit=block_data.gas_limit,
            gas_used=block_data.gas_used,
            base_fee_per_gas=block_data.base_fee_per_gas,
            miner=block_data.miner,
            transaction_count=block_data.transaction_count,
            indexed_at=block_data.created_at if hasattr(block_data, "created_at") else __import__("datetime").datetime.now(__import__("datetime").timezone.utc),
        )
    except IndexerError as e:
        raise HTTPException(status_code=400, detail=e.message)


@router.get("/{chain}/transactions/{tx_hash}", response_model=IndexedTransactionResponse)
async def get_indexed_transaction(chain: str, tx_hash: str):
    from wallethub.modules.chain_adapter import ChainAdapter
    from wallethub.modules.indexer import IndexManager

    adapter = ChainAdapter()
    client = adapter.get_client(chain)
    tx = await client.get_transaction(tx_hash)
    receipt = await client.get_transaction_receipt(tx_hash)

    manager = IndexManager()
    decoded = manager.decoder.decode_transaction(tx)

    return IndexedTransactionResponse(
        chain=chain,
        tx_hash=tx_hash,
        block_number=tx.get("blockNumber"),
        transaction_index=tx.get("transactionIndex"),
        from_address=tx.get("from", ""),
        to_address=tx.get("to"),
        value=int(tx.get("value", 0)),
        input=tx.get("input", ""),
        gas=int(tx.get("gas", 0)),
        gas_price=int(tx.get("gasPrice", 0)),
        nonce=int(tx.get("nonce", 0)),
        status=receipt.get("status") if receipt else None,
        contract_address=receipt.get("contractAddress") if receipt else None,
        decoded_method=decoded.function_name if decoded else None,
        decoded_params={p.name: p.value for p in decoded.params} if decoded else None,
    )


@router.post("/{chain}/reindex")
async def reindex_blocks(chain: str, from_block: int, to_block: int):
    try:
        from wallethub.modules.indexer import IndexManager

        manager = IndexManager()
        count = await manager.reindex(chain, from_block, to_block)
        return {"message": f"Reindexed {count} blocks", "chain": chain, "count": count}
    except IndexerError as e:
        raise HTTPException(status_code=400, detail=e.message)
