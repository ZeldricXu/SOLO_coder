import asyncio
import hashlib
import json
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional, Tuple
from datetime import datetime, timezone

from aiohttp import ClientSession
from sqlalchemy import select, func, and_
from sqlalchemy.ext.asyncio import AsyncSession

from ..config import get_settings
from ..db import async_session, IndexedBlock, IndexedTransaction, IndexedLog
from ..utils import (
    get_logger,
    generate_id,
    to_checksum_address,
    hex_to_bytes,
    bytes_to_hex,
    ValidationError,
    NotFoundError,
    IndexerError,
    retry_async,
)
from .chain_adapter import get_chain_adapter, ChainAdapter

logger = get_logger(__name__)


@dataclass
class IndexingConfig:
    chain_id: int
    start_block: int = 0
    end_block: Optional[int] = None
    batch_size: int = 10
    include_transactions: bool = True
    include_logs: bool = True
    confirmations: int = 0


@dataclass
class IndexingStatus:
    chain_id: int
    latest_block: int
    latest_indexed_block: int
    is_running: bool
    blocks_indexed: int
    transactions_indexed: int
    logs_indexed: int


@dataclass
class BlockIndexResult:
    block_number: int
    block_hash: str
    transaction_count: int
    log_count: int
    indexed_at: datetime


class ChainIndexer:
    def __init__(self, chain_id: int, adapter: ChainAdapter):
        self.chain_id = chain_id
        self.adapter = adapter
        self.settings = get_settings()
        self._running = False
        self._task: Optional[asyncio.Task] = None
        self._latest_indexed_block: int = 0
        self._stats = {
            "blocks_indexed": 0,
            "transactions_indexed": 0,
            "logs_indexed": 0,
        }

    async def get_latest_indexed_block(self) -> int:
        async with async_session() as session:
            result = await session.execute(
                select(func.max(IndexedBlock.block_number))
                .where(IndexedBlock.chain_id == self.chain_id)
            )
            max_block = result.scalar()
            return max_block or 0

    async def is_block_indexed(self, block_number: int) -> bool:
        async with async_session() as session:
            result = await session.execute(
                select(IndexedBlock.id)
                .where(
                    and_(
                        IndexedBlock.chain_id == self.chain_id,
                        IndexedBlock.block_number == block_number,
                    )
                )
            )
            return result.scalar() is not None

    async def index_block(self, block_number: int) -> Optional[BlockIndexResult]:
        if await self.is_block_indexed(block_number):
            logger.debug(f"Block {block_number} on chain {self.chain_id} already indexed, skipping")
            return None

        block_data = await self.adapter.get_block(self.chain_id, block_number, include_txs=True)
        if not block_data:
            logger.warning(f"Block {block_number} not found on chain {self.chain_id}")
            return None

        block_hash = block_data.get("hash", "")
        timestamp = int(block_data.get("timestamp", "0x0"), 16)
        transactions = block_data.get("transactions", [])

        async with async_session() as session:
            try:
                indexed_block = IndexedBlock(
                    chain_id=self.chain_id,
                    block_number=block_number,
                    block_hash=block_hash,
                    parent_hash=block_data.get("parentHash", ""),
                    timestamp=datetime.fromtimestamp(timestamp, tz=timezone.utc),
                    difficulty=str(int(block_data.get("difficulty", "0x0"), 16)),
                    total_difficulty=str(int(block_data.get("totalDifficulty", "0x0"), 16)),
                    size=int(block_data.get("size", "0x0"), 16),
                    gas_limit=str(int(block_data.get("gasLimit", "0x0"), 16)),
                    gas_used=str(int(block_data.get("gasUsed", "0x0"), 16)),
                    base_fee_per_gas=str(int(block_data.get("baseFeePerGas", "0x0"), 16)) if block_data.get("baseFeePerGas") else None,
                    miner=to_checksum_address(block_data.get("miner", "0x0000000000000000000000000000000000000000")),
                    extra_data=block_data.get("extraData", "0x"),
                    transaction_count=len(transactions),
                    log_count=0,
                    status="indexed",
                )
                session.add(indexed_block)

                tx_count = 0
                log_count = 0

                if transactions and isinstance(transactions[0], dict):
                    for tx_data in transactions:
                        tx_hash = tx_data.get("hash", "")
                        indexed_tx = IndexedTransaction(
                            chain_id=self.chain_id,
                            block_number=block_number,
                            tx_hash=tx_hash,
                            transaction_index=int(tx_data.get("transactionIndex", "0x0"), 16),
                            from_address=to_checksum_address(tx_data.get("from", "0x0000000000000000000000000000000000000000")),
                            to_address=to_checksum_address(tx_data["to"]) if tx_data.get("to") else None,
                            value=str(int(tx_data.get("value", "0x0"), 16)),
                            gas=str(int(tx_data.get("gas", "0x0"), 16)),
                            gas_price=str(int(tx_data.get("gasPrice", "0x0"), 16)) if tx_data.get("gasPrice") else None,
                            max_fee_per_gas=str(int(tx_data.get("maxFeePerGas", "0x0"), 16)) if tx_data.get("maxFeePerGas") else None,
                            max_priority_fee_per_gas=str(int(tx_data.get("maxPriorityFeePerGas", "0x0"), 16)) if tx_data.get("maxPriorityFeePerGas") else None,
                            input=tx_data.get("input", "0x"),
                            nonce=int(tx_data.get("nonce", "0x0"), 16),
                            transaction_type=int(tx_data.get("type", "0x0"), 16),
                            logs_count=0,
                        )
                        session.add(indexed_tx)
                        tx_count += 1

                await session.commit()

                self._stats["blocks_indexed"] += 1
                self._stats["transactions_indexed"] += tx_count

                logger.info(f"Indexed block {block_number} on chain {self.chain_id}: {tx_count} transactions, {log_count} logs")

                return BlockIndexResult(
                    block_number=block_number,
                    block_hash=block_hash,
                    transaction_count=tx_count,
                    log_count=log_count,
                    indexed_at=datetime.now(timezone.utc),
                )

            except Exception as e:
                await session.rollback()
                logger.error(f"Failed to index block {block_number} on chain {self.chain_id}: {e}")
                raise

    async def index_block_range(self, start_block: int, end_block: int, batch_size: int = 10) -> int:
        total_indexed = 0
        current_block = start_block

        while current_block <= end_block:
            batch_end = min(current_block + batch_size - 1, end_block)
            logger.info(f"Indexing blocks {current_block}-{batch_end} on chain {self.chain_id}")

            tasks = []
            for block_num in range(current_block, batch_end + 1):
                tasks.append(self.index_block(block_num))

            results = await asyncio.gather(*tasks, return_exceptions=True)

            for result in results:
                if isinstance(result, BlockIndexResult):
                    total_indexed += 1

            current_block = batch_end + 1

            await asyncio.sleep(0.1)

        return total_indexed

    async def start_continuous_indexing(self, start_block: Optional[int] = None, batch_size: int = 10) -> None:
        if self._running:
            return

        self._running = True
        self._latest_indexed_block = start_block or await self.get_latest_indexed_block()
        logger.info(f"Starting continuous indexing on chain {self.chain_id} from block {self._latest_indexed_block}")

        self._task = asyncio.create_task(self._continuous_indexing_loop(batch_size))

    async def stop_continuous_indexing(self) -> None:
        if not self._running:
            return

        self._running = False
        if self._task:
            self._task.cancel()
            try:
                await self._task
            except asyncio.CancelledError:
                pass
        logger.info(f"Stopped continuous indexing on chain {self.chain_id}")

    async def _continuous_indexing_loop(self, batch_size: int) -> None:
        chain_config = self.adapter.settings.chain.chains.get(self.chain_id)
        block_time = chain_config.block_time if chain_config else 2.0

        while self._running:
            try:
                latest_block = await self.adapter.get_block_number(self.chain_id)
                safe_block = latest_block - (chain_config.confirmations if chain_config else 0)

                if safe_block > self._latest_indexed_block:
                    end_block = min(self._latest_indexed_block + batch_size, safe_block)
                    await self.index_block_range(
                        self._latest_indexed_block + 1,
                        end_block,
                        batch_size=min(batch_size, end_block - self._latest_indexed_block)
                    )
                    self._latest_indexed_block = end_block

                await asyncio.sleep(block_time)

            except asyncio.CancelledError:
                break
            except Exception as e:
                logger.error(f"Error in continuous indexing loop for chain {self.chain_id}: {e}")
                await asyncio.sleep(block_time * 2)

    def get_status(self) -> IndexingStatus:
        return IndexingStatus(
            chain_id=self.chain_id,
            latest_block=0,
            latest_indexed_block=self._latest_indexed_block,
            is_running=self._running,
            blocks_indexed=self._stats["blocks_indexed"],
            transactions_indexed=self._stats["transactions_indexed"],
            logs_indexed=self._stats["logs_indexed"],
        )


class IndexerModule:
    def __init__(self):
        self.settings = get_settings()
        self._chain_adapter: Optional[ChainAdapter] = None
        self._indexers: Dict[int, ChainIndexer] = {}
        self._initialized = False
        self._running = False

    async def initialize(self) -> None:
        if self._initialized:
            return

        logger.info("Initializing indexer module")
        self._chain_adapter = get_chain_adapter()
        await self._chain_adapter.initialize()
        self._initialized = True
        logger.info("Indexer module initialized")

    async def start(self) -> None:
        if self._running:
            return

        logger.info("Starting indexer module")
        self._running = True
        logger.info("Indexer module started")

    async def shutdown(self) -> None:
        if not self._initialized:
            return

        logger.info("Shutting down indexer module")
        self._running = False

        for indexer in self._indexers.values():
            await indexer.stop_continuous_indexing()

        self._indexers.clear()
        self._initialized = False
        logger.info("Indexer module shutdown complete")

    def _get_or_create_indexer(self, chain_id: int) -> ChainIndexer:
        if chain_id not in self._indexers:
            if not self._chain_adapter or not self._chain_adapter.has_chain(chain_id):
                raise ValidationError(f"No adapter configured for chain {chain_id}")
            self._indexers[chain_id] = ChainIndexer(chain_id, self._chain_adapter)
        return self._indexers[chain_id]

    async def index_block(self, chain_id: int, block_number: int) -> Optional[BlockIndexResult]:
        indexer = self._get_or_create_indexer(chain_id)
        return await indexer.index_block(block_number)

    async def index_block_range(
        self,
        chain_id: int,
        start_block: int,
        end_block: int,
        batch_size: int = 10
    ) -> int:
        indexer = self._get_or_create_indexer(chain_id)
        return await indexer.index_block_range(start_block, end_block, batch_size)

    async def start_chain_indexer(
        self,
        chain_id: int,
        start_block: Optional[int] = None,
        batch_size: int = 10
    ) -> None:
        indexer = self._get_or_create_indexer(chain_id)
        await indexer.start_continuous_indexing(start_block, batch_size)

    async def stop_chain_indexer(self, chain_id: int) -> None:
        if chain_id in self._indexers:
            await self._indexers[chain_id].stop_continuous_indexing()

    async def get_indexing_status(self, chain_id: int) -> IndexingStatus:
        indexer = self._get_or_create_indexer(chain_id)
        status = indexer.get_status()
        if self._chain_adapter and self._chain_adapter.has_chain(chain_id):
            try:
                status.latest_block = await self._chain_adapter.get_block_number(chain_id)
            except Exception:
                pass
        return status

    async def get_indexed_block(self, chain_id: int, block_number: int) -> Optional[Dict[str, Any]]:
        async with async_session() as session:
            result = await session.execute(
                select(IndexedBlock)
                .where(
                    and_(
                        IndexedBlock.chain_id == chain_id,
                        IndexedBlock.block_number == block_number,
                    )
                )
            )
            block = result.scalar_one_or_none()
            if not block:
                return None
            return {
                "chain_id": block.chain_id,
                "block_number": block.block_number,
                "block_hash": block.block_hash,
                "parent_hash": block.parent_hash,
                "timestamp": block.timestamp.isoformat(),
                "miner": block.miner,
                "gas_limit": block.gas_limit,
                "gas_used": block.gas_used,
                "base_fee_per_gas": block.base_fee_per_gas,
                "transaction_count": block.transaction_count,
                "log_count": block.log_count,
                "status": block.status,
                "created_at": block.created_at.isoformat(),
            }

    async def get_indexed_transaction(self, chain_id: int, tx_hash: str) -> Optional[Dict[str, Any]]:
        async with async_session() as session:
            result = await session.execute(
                select(IndexedTransaction)
                .where(
                    and_(
                        IndexedTransaction.chain_id == chain_id,
                        IndexedTransaction.tx_hash == tx_hash,
                    )
                )
            )
            tx = result.scalar_one_or_none()
            if not tx:
                return None
            return {
                "chain_id": tx.chain_id,
                "block_number": tx.block_number,
                "tx_hash": tx.tx_hash,
                "transaction_index": tx.transaction_index,
                "from_address": tx.from_address,
                "to_address": tx.to_address,
                "value": tx.value,
                "gas": tx.gas,
                "gas_price": tx.gas_price,
                "max_fee_per_gas": tx.max_fee_per_gas,
                "max_priority_fee_per_gas": tx.max_priority_fee_per_gas,
                "nonce": tx.nonce,
                "transaction_type": tx.transaction_type,
                "status": tx.status,
                "gas_used": tx.gas_used,
                "contract_address": tx.contract_address,
                "logs_count": tx.logs_count,
                "decoded_data": tx.decoded_data,
                "created_at": tx.created_at.isoformat(),
            }

    async def list_indexed_blocks(
        self,
        chain_id: int,
        start_block: Optional[int] = None,
        end_block: Optional[int] = None,
        limit: int = 100,
        offset: int = 0
    ) -> List[Dict[str, Any]]:
        async with async_session() as session:
            query = select(IndexedBlock).where(IndexedBlock.chain_id == chain_id)

            if start_block is not None:
                query = query.where(IndexedBlock.block_number >= start_block)
            if end_block is not None:
                query = query.where(IndexedBlock.block_number <= end_block)

            query = query.order_by(IndexedBlock.block_number.desc()).limit(limit).offset(offset)
            result = await session.execute(query)
            blocks = result.scalars().all()

            return [
                {
                    "chain_id": b.chain_id,
                    "block_number": b.block_number,
                    "block_hash": b.block_hash,
                    "timestamp": b.timestamp.isoformat(),
                    "miner": b.miner,
                    "transaction_count": b.transaction_count,
                    "status": b.status,
                }
                for b in blocks
            ]

    async def list_address_transactions(
        self,
        chain_id: int,
        address: str,
        limit: int = 100,
        offset: int = 0
    ) -> List[Dict[str, Any]]:
        checksum_address = to_checksum_address(address)
        async with async_session() as session:
            query = select(IndexedTransaction).where(
                and_(
                    IndexedTransaction.chain_id == chain_id,
                    (IndexedTransaction.from_address == checksum_address) | (IndexedTransaction.to_address == checksum_address),
                )
            )
            query = query.order_by(IndexedTransaction.block_number.desc()).limit(limit).offset(offset)
            result = await session.execute(query)
            txs = result.scalars().all()

            return [
                {
                    "tx_hash": t.tx_hash,
                    "block_number": t.block_number,
                    "from_address": t.from_address,
                    "to_address": t.to_address,
                    "value": t.value,
                    "gas": t.gas,
                    "gas_price": t.gas_price,
                    "status": t.status,
                    "created_at": t.created_at.isoformat(),
                }
                for t in txs
            ]

    async def list_contract_transactions(
        self,
        chain_id: int,
        contract_address: str,
        limit: int = 100,
        offset: int = 0
    ) -> List[Dict[str, Any]]:
        checksum_address = to_checksum_address(contract_address)
        async with async_session() as session:
            query = select(IndexedTransaction).where(
                and_(
                    IndexedTransaction.chain_id == chain_id,
                    IndexedTransaction.to_address == checksum_address,
                )
            )
            query = query.order_by(IndexedTransaction.block_number.desc()).limit(limit).offset(offset)
            result = await session.execute(query)
            txs = result.scalars().all()

            return [
                {
                    "tx_hash": t.tx_hash,
                    "block_number": t.block_number,
                    "from_address": t.from_address,
                    "to_address": t.to_address,
                    "value": t.value,
                    "gas": t.gas,
                    "gas_price": t.gas_price,
                    "status": t.status,
                    "created_at": t.created_at.isoformat(),
                }
                for t in txs
            ]

    async def get_indexed_logs(
        self,
        chain_id: int,
        address: Optional[str] = None,
        topic0: Optional[str] = None,
        from_block: Optional[int] = None,
        to_block: Optional[int] = None,
        limit: int = 100,
        offset: int = 0
    ) -> List[Dict[str, Any]]:
        async with async_session() as session:
            query = select(IndexedLog).where(IndexedLog.chain_id == chain_id)

            if address:
                query = query.where(IndexedLog.address == to_checksum_address(address))
            if topic0:
                query = query.where(IndexedLog.topics.any(topic0))
            if from_block is not None:
                query = query.where(IndexedLog.block_number >= from_block)
            if to_block is not None:
                query = query.where(IndexedLog.block_number <= to_block)

            query = query.order_by(IndexedLog.block_number.desc()).limit(limit).offset(offset)
            result = await session.execute(query)
            logs = result.scalars().all()

            return [
                {
                    "chain_id": l.chain_id,
                    "block_number": l.block_number,
                    "tx_hash": l.tx_hash,
                    "log_index": l.log_index,
                    "address": l.address,
                    "topics": l.topics,
                    "data": l.data,
                    "event_signature": l.event_signature,
                    "decoded_data": l.decoded_data,
                    "created_at": l.created_at.isoformat(),
                }
                for l in logs
            ]

    async def get_indexer_stats(self, chain_id: Optional[int] = None) -> Dict[str, Any]:
        async with async_session() as session:
            query = select(
                IndexedBlock.chain_id,
                func.count(IndexedBlock.id).label("block_count"),
                func.max(IndexedBlock.block_number).label("latest_block"),
            )
            if chain_id is not None:
                query = query.where(IndexedBlock.chain_id == chain_id)
            query = query.group_by(IndexedBlock.chain_id)
            result = await session.execute(query)
            rows = result.all()

            stats = {}
            for row in rows:
                chain_id_val = row.chain_id
                stats[str(chain_id_val)] = {
                    "chain_id": chain_id_val,
                    "blocks_indexed": row.block_count,
                    "latest_block": row.latest_block,
                }

            if chain_id is not None and str(chain_id) in stats:
                tx_query = select(func.count(IndexedTransaction.id)).where(IndexedTransaction.chain_id == chain_id)
                tx_result = await session.execute(tx_query)
                stats[str(chain_id)]["transactions_indexed"] = tx_result.scalar() or 0

                log_query = select(func.count(IndexedLog.id)).where(IndexedLog.chain_id == chain_id)
                log_result = await session.execute(log_query)
                stats[str(chain_id)]["logs_indexed"] = log_result.scalar() or 0

            return stats


_indexer_module: Optional[IndexerModule] = None


def get_indexer_module() -> IndexerModule:
    global _indexer_module
    if _indexer_module is None:
        _indexer_module = IndexerModule()
    return _indexer_module
