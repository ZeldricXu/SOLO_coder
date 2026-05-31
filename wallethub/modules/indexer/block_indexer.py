import asyncio
import logging
from dataclasses import dataclass, field
from typing import Any, Callable, Dict, List, Optional
from datetime import datetime, timezone

from wallethub.core import IndexerError, IndexerStatus
from wallethub.config import get_settings
from wallethub.modules.chain_adapter import ChainClient
from wallethub.events import get_event_bus, BlockIndexedEvent, TransactionIndexedEvent
from wallethub.utils import async_retry

logger = logging.getLogger(__name__)


@dataclass
class IndexedBlockData:
    chain: str
    block_number: int
    block_hash: str
    parent_hash: str
    timestamp: int
    difficulty: int
    total_difficulty: str
    gas_limit: int
    gas_used: int
    base_fee_per_gas: Optional[int]
    miner: str
    extra_data: Optional[str]
    transaction_count: int
    transactions: List[Dict[str, Any]] = field(default_factory=list)


class BlockIndexer:
    def __init__(
        self,
        chain_client: ChainClient,
        start_block: Optional[int] = None,
        batch_size: int = 10,
        poll_interval: float = 2.0,
    ):
        self.settings = get_settings()
        self.chain_client = chain_client
        self.chain = chain_client.chain
        self.batch_size = batch_size
        self.poll_interval = poll_interval

        self.status = IndexerStatus.PAUSED
        self.current_block = start_block or 0
        self.latest_block = 0
        self._task: Optional[asyncio.Task] = None
        self._stop_event = asyncio.Event()
        self._event_bus = get_event_bus()

        self._block_callbacks: List[Callable[[IndexedBlockData], Awaitable[None]]] = []
        self._transaction_callbacks: List[Callable[[Dict[str, Any]], Awaitable[None]]] = []

    def on_block(self, callback: Callable[[IndexedBlockData], Awaitable[None]]) -> None:
        self._block_callbacks.append(callback)

    def on_transaction(self, callback: Callable[[Dict[str, Any]], Awaitable[None]]) -> None:
        self._transaction_callbacks.append(callback)

    async def start(self) -> None:
        if self.status == IndexerStatus.RUNNING and self._task and not self._task.done():
            return

        if self.current_block == 0:
            self.current_block = await self.chain_client.get_block_number() - 100

        self.status = IndexerStatus.RUNNING
        self._stop_event.clear()
        self._task = asyncio.create_task(self._index_loop())
        logger.info(f"Started block indexer for {self.chain} from block {self.current_block}")

    async def stop(self) -> None:
        self.status = IndexerStatus.PAUSED
        self._stop_event.set()
        if self._task:
            try:
                await asyncio.wait_for(self._task, timeout=5)
            except asyncio.TimeoutError:
                self._task.cancel()
        logger.info(f"Stopped block indexer for {self.chain}")

    async def _index_loop(self) -> None:
        while not self._stop_event.is_set():
            try:
                self.latest_block = await self.chain_client.get_block_number()

                if self.current_block >= self.latest_block:
                    await asyncio.sleep(self.poll_interval)
                    continue

                self.status = IndexerStatus.SYNCING
                await self._index_blocks()
                self.status = IndexerStatus.RUNNING

                await asyncio.sleep(self.poll_interval)
            except Exception as e:
                logger.error(f"Indexer error for {self.chain}: {str(e)}")
                self.status = IndexerStatus.ERROR
                await asyncio.sleep(self.poll_interval * 2)

    async def _index_blocks(self) -> None:
        from_block = self.current_block + 1
        to_block = min(from_block + self.batch_size - 1, self.latest_block)

        for block_num in range(from_block, to_block + 1):
            if self._stop_event.is_set():
                break

            try:
                block_data = await self._index_block(block_num)
                await self._notify_block_callbacks(block_data)
                await self._event_bus.publish(BlockIndexedEvent(payload={
                    "chain": self.chain,
                    "block_number": block_num,
                    "block_hash": block_data.block_hash,
                }))

                for tx in block_data.transactions:
                    await self._notify_transaction_callbacks(tx)
                    await self._event_bus.publish(TransactionIndexedEvent(payload={
                        "chain": self.chain,
                        "tx_hash": tx["hash"],
                        "block_number": block_num,
                    }))

                self.current_block = block_num
            except Exception as e:
                logger.error(f"Failed to index block {block_num} on {self.chain}: {str(e)}")
                raise

    @async_retry(max_attempts=3, delay=1.0, backoff=2.0)
    async def _index_block(self, block_number: int) -> IndexedBlockData:
        block = await self.chain_client.get_block(block_number, full_transactions=True)

        transactions = []
        for tx in block.get("transactions", []):
            if isinstance(tx, dict):
                tx_data = {
                    "hash": tx.get("hash").hex() if hasattr(tx.get("hash"), "hex") else str(tx.get("hash")),
                    "block_number": block_number,
                    "transaction_index": tx.get("transactionIndex"),
                    "from_address": tx.get("from"),
                    "to_address": tx.get("to"),
                    "value": int(tx.get("value", 0)),
                    "input": tx.get("input"),
                    "gas": int(tx.get("gas", 0)),
                    "gas_price": int(tx.get("gasPrice", 0)),
                    "max_fee_per_gas": int(tx.get("maxFeePerGas", 0)) if tx.get("maxFeePerGas") else None,
                    "max_priority_fee_per_gas": int(tx.get("maxPriorityFeePerGas", 0)) if tx.get("maxPriorityFeePerGas") else None,
                    "nonce": int(tx.get("nonce", 0)),
                    "type": int(tx.get("type", 0)),
                }
                transactions.append(tx_data)

        base_fee = block.get("baseFeePerGas")

        return IndexedBlockData(
            chain=self.chain,
            block_number=block_number,
            block_hash=block["hash"].hex() if hasattr(block["hash"], "hex") else str(block["hash"]),
            parent_hash=block["parentHash"].hex() if hasattr(block["parentHash"], "hex") else str(block["parentHash"]),
            timestamp=int(block.get("timestamp", 0)),
            difficulty=int(block.get("difficulty", 0)),
            total_difficulty=str(block.get("totalDifficulty", 0)),
            gas_limit=int(block.get("gasLimit", 0)),
            gas_used=int(block.get("gasUsed", 0)),
            base_fee_per_gas=int(base_fee) if base_fee else None,
            miner=block.get("miner", ""),
            extra_data=block.get("extraData").hex() if hasattr(block.get("extraData"), "hex") else str(block.get("extraData")),
            transaction_count=len(transactions),
            transactions=transactions,
        )

    async def _notify_block_callbacks(self, block_data: IndexedBlockData) -> None:
        if self._block_callbacks:
            tasks = [cb(block_data) for cb in self._block_callbacks]
            await asyncio.gather(*tasks, return_exceptions=True)

    async def _notify_transaction_callbacks(self, tx_data: Dict[str, Any]) -> None:
        if self._transaction_callbacks:
            tasks = [cb(tx_data) for cb in self._transaction_callbacks]
            await asyncio.gather(*tasks, return_exceptions=True)

    def get_status(self) -> Dict[str, Any]:
        return {
            "chain": self.chain,
            "status": self.status.value,
            "current_block": self.current_block,
            "latest_block": self.latest_block,
            "blocks_behind": max(0, self.latest_block - self.current_block),
        }

    async def index_single_block(self, block_number: int) -> IndexedBlockData:
        return await self._index_block(block_number)
