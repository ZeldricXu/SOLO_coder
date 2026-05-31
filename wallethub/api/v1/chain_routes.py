from fastapi import APIRouter, HTTPException
from typing import List

from wallethub.api.models.chain_models import (
    ChainInfoResponse,
    BlockResponse,
    TransactionResponse,
)
from wallethub.core import ChainInteractionError

router = APIRouter(prefix="/chains", tags=["Chain"])


@router.get("", response_model=List[ChainInfoResponse])
async def list_chains():
    try:
        from wallethub.modules.chain_adapter import ChainAdapter

        adapter = ChainAdapter()
        all_info = await adapter.get_all_chain_info()

        results = []
        for chain, info in all_info.items():
            if "error" not in info:
                results.append(
                    ChainInfoResponse(
                        chain=chain,
                        chain_id=info.get("chain_id", 0),
                        name=info.get("chain", chain),
                        rpc_url=adapter.get_client(chain).rpc_url,
                        symbol=adapter.get_client(chain).chain_config.symbol,
                        block_number=info.get("block_number", 0),
                        gas_price_wei=info.get("gas_price_wei", 0),
                        is_connected=info.get("is_connected", False),
                    )
                )
        return results
    except ChainInteractionError as e:
        raise HTTPException(status_code=502, detail=e.message)


@router.get("/{chain}", response_model=ChainInfoResponse)
async def get_chain_info(chain: str):
    try:
        from wallethub.modules.chain_adapter import ChainAdapter

        adapter = ChainAdapter()
        client = adapter.get_client(chain)
        info = await client.get_chain_info()

        return ChainInfoResponse(
            chain=chain,
            chain_id=info.get("chain_id", 0),
            name=info.get("chain", chain),
            rpc_url=client.rpc_url,
            symbol=client.chain_config.symbol,
            block_number=info.get("block_number", 0),
            gas_price_wei=info.get("gas_price_wei", 0),
            is_connected=info.get("is_connected", False),
        )
    except ChainInteractionError as e:
        raise HTTPException(status_code=502, detail=e.message)


@router.get("/{chain}/blocks/latest", response_model=BlockResponse)
async def get_latest_block(chain: str):
    try:
        from wallethub.modules.chain_adapter import ChainAdapter

        adapter = ChainAdapter()
        client = adapter.get_client(chain)

        block_number = await client.get_block_number()
        block = await client.get_block(block_number, full_transactions=True)

        return BlockResponse(
            chain=chain,
            block_number=block_number,
            block_hash=block.get("hash", "").hex() if hasattr(block.get("hash"), "hex") else str(block.get("hash", "")),
            parent_hash=block.get("parentHash", "").hex() if hasattr(block.get("parentHash"), "hex") else str(block.get("parentHash", "")),
            timestamp=int(block.get("timestamp", 0)),
            difficulty=int(block.get("difficulty", 0)),
            gas_limit=int(block.get("gasLimit", 0)),
            gas_used=int(block.get("gasUsed", 0)),
            base_fee_per_gas=int(block.get("baseFeePerGas", 0)) if block.get("baseFeePerGas") else None,
            miner=block.get("miner", ""),
            transaction_count=len(block.get("transactions", [])),
            transactions=block.get("transactions", []),
        )
    except ChainInteractionError as e:
        raise HTTPException(status_code=502, detail=e.message)


@router.get("/{chain}/blocks/{block_number}", response_model=BlockResponse)
async def get_block(chain: str, block_number: int):
    try:
        from wallethub.modules.chain_adapter import ChainAdapter

        adapter = ChainAdapter()
        client = adapter.get_client(chain)
        block = await client.get_block(block_number, full_transactions=True)

        return BlockResponse(
            chain=chain,
            block_number=block_number,
            block_hash=block.get("hash", "").hex() if hasattr(block.get("hash"), "hex") else str(block.get("hash", "")),
            parent_hash=block.get("parentHash", "").hex() if hasattr(block.get("parentHash"), "hex") else str(block.get("parentHash", "")),
            timestamp=int(block.get("timestamp", 0)),
            difficulty=int(block.get("difficulty", 0)),
            gas_limit=int(block.get("gasLimit", 0)),
            gas_used=int(block.get("gasUsed", 0)),
            base_fee_per_gas=int(block.get("baseFeePerGas", 0)) if block.get("baseFeePerGas") else None,
            miner=block.get("miner", ""),
            transaction_count=len(block.get("transactions", [])),
            transactions=block.get("transactions", []),
        )
    except ChainInteractionError as e:
        raise HTTPException(status_code=502, detail=e.message)


@router.get("/{chain}/transactions/{tx_hash}", response_model=TransactionResponse)
async def get_transaction(chain: str, tx_hash: str):
    try:
        from wallethub.modules.chain_adapter import ChainAdapter

        adapter = ChainAdapter()
        client = adapter.get_client(chain)

        tx = await client.get_transaction(tx_hash)
        receipt = await client.get_transaction_receipt(tx_hash)

        return TransactionResponse(
            chain=chain,
            tx_hash=tx_hash,
            block_number=tx.get("blockNumber"),
            from_address=tx.get("from", ""),
            to_address=tx.get("to"),
            value=int(tx.get("value", 0)),
            gas=int(tx.get("gas", 0)),
            gas_price=int(tx.get("gasPrice", 0)),
            max_fee_per_gas=int(tx.get("maxFeePerGas", 0)) if tx.get("maxFeePerGas") else None,
            max_priority_fee_per_gas=int(tx.get("maxPriorityFeePerGas", 0)) if tx.get("maxPriorityFeePerGas") else None,
            nonce=int(tx.get("nonce", 0)),
            input=tx.get("input", ""),
            status=receipt.get("status") if receipt else None,
            contract_address=receipt.get("contractAddress") if receipt else None,
        )
    except ChainInteractionError as e:
        raise HTTPException(status_code=502, detail=e.message)


@router.get("/{chain}/address/{address}/balance")
async def get_address_balance(chain: str, address: str):
    try:
        from wallethub.modules.chain_adapter import ChainAdapter
        from wallethub.utils import from_wei

        adapter = ChainAdapter()
        client = adapter.get_client(chain)
        balance_wei = await client.get_balance(address)

        return {
            "address": address,
            "chain": chain,
            "balance_wei": balance_wei,
            "balance_eth": from_wei(balance_wei),
            "symbol": client.chain_config.symbol,
        }
    except ChainInteractionError as e:
        raise HTTPException(status_code=502, detail=e.message)
