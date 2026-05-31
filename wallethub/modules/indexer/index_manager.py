import asyncio
import logging
from typing import Any, Dict, List, Optional

from wallethub.core import IndexerError, IndexerStatus
from wallethub.config import get_settings
from wallethub.modules.chain_adapter import ChainAdapter

from .block_indexer import BlockIndexer
from .transaction_decoder import TransactionDecoder

logger = logging.getLogger(__name__)


class IndexManager:
    def __init__(self, chain_adapter: Optional[ChainAdapter] = None):
        self.settings = get_settings()
        self.chain_adapter = chain_adapter or ChainAdapter()
        self._indexers: Dict[str, BlockIndexer] = {}
        self._decoder = TransactionDecoder()

    @property
    def decoder(self) -> TransactionDecoder:
        return self._decoder

    def create_indexer(
        self,
        chain: str,
        start_block: Optional[int] = None,
        batch_size: int = 10,
        poll_interval: float = 2.0,
    ) -> BlockIndexer:
        if chain in self._indexers:
            return self._indexers[chain]

        chain_client = self.chain_adapter.get_client(chain)
        indexer = BlockIndexer(
            chain_client=chain_client,
            start_block=start_block,
            batch_size=batch_size,
            poll_interval=poll_interval,
        )
        self._indexers[chain] = indexer
        return indexer

    def get_indexer(self, chain: str) -> Optional[BlockIndexer]:
        return self._indexers.get(chain)

    def get_or_create_indexer(self, chain: str) -> BlockIndexer:
        if chain not in self._indexers:
            return self.create_indexer(chain)
        return self._indexers[chain]

    async def start_indexer(self, chain: str) -> None:
        indexer = self._indexers.get(chain)
        if not indexer:
            raise IndexerError(f"Indexer for chain {chain} not found")
        await indexer.start()

    async def stop_indexer(self, chain: str) -> None:
        indexer = self._indexers.get(chain)
        if not indexer:
            raise IndexerError(f"Indexer for chain {chain} not found")
        await indexer.stop()

    async def start_all(self) -> None:
        for chain in self.settings.chains:
            if chain not in self._indexers:
                self.create_indexer(chain)

        tasks = [indexer.start() for indexer in self._indexers.values()]
        await asyncio.gather(*tasks, return_exceptions=True)
        logger.info("Started all indexers")

    async def stop_all(self) -> None:
        tasks = [indexer.stop() for indexer in self._indexers.values()]
        await asyncio.gather(*tasks, return_exceptions=True)
        logger.info("Stopped all indexers")

    def get_all_statuses(self) -> Dict[str, Dict[str, Any]]:
        return {
            chain: indexer.get_status()
            for chain, indexer in self._indexers.items()
        }

    def register_contract_abi(self, contract_address: str, abi: List[Dict[str, Any]]) -> None:
        self._decoder.register_abi(contract_address, abi)

    def list_indexers(self) -> List[str]:
        return list(self._indexers.keys())

    def remove_indexer(self, chain: str) -> None:
        indexer = self._indexers.pop(chain, None)
        if indexer:
            asyncio.create_task(indexer.stop())

    async def reindex(
        self,
        chain: str,
        from_block: int,
        to_block: int,
    ) -> int:
        indexer = self.get_or_create_indexer(chain)
        count = 0

        for block_num in range(from_block, to_block + 1):
            try:
                await indexer._index_block(block_num)
                count += 1
            except Exception as e:
                logger.error(f"Failed to reindex block {block_num}: {str(e)}")

        return count

    def get_sync_progress(self, chain: str) -> Dict[str, Any]:
        indexer = self._indexers.get(chain)
        if not indexer:
            return {"exists": False}

        status = indexer.get_status()
        if status["latest_block"] > 0:
            progress = (status["current_block"] / status["latest_block"]) * 100
        else:
            progress = 0

        return {
            "exists": True,
            "current_block": status["current_block"],
            "latest_block": status["latest_block"],
            "blocks_behind": status["blocks_behind"],
            "progress_percent": round(progress, 2),
            "status": status["status"],
        }
