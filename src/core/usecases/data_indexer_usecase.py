from __future__ import annotations

import asyncio
from datetime import datetime
from typing import Any, AsyncIterator, Dict, List, Optional

from src.core.ports.chain_interaction_port import IChainInteractionPort
from src.core.ports.indexer_port import IBlockIterator, IDataIndexerPort, IDataStore
from src.shared.config import settings
from src.shared.errors import BlockParsingError, IndexingError
from src.shared.logger import get_logger
from src.shared.types import BlockHeader, BlockNumber, EventLog, Hash, Transaction, TransactionReceipt

logger = get_logger(__name__)


class BlockIterator(IBlockIterator):
    def __init__(self, chain_adapter: IChainInteractionPort):
        self._chain = chain_adapter

    async def iter_blocks(
        self,
        start_block: BlockNumber,
        end_block: Optional[BlockNumber] = None,
        batch_size: int = 100,
    ) -> AsyncIterator[List[BlockHeader]]:
        current = start_block
        latest = end_block or await self._chain.get_block_number()

        while current <= latest:
            batch_end = min(current + batch_size - 1, latest)
            headers: List[BlockHeader] = []

            tasks = [self._chain.get_block_header(num) for num in range(current, batch_end + 1)]
            results = await asyncio.gather(*tasks, return_exceptions=True)

            for num, result in zip(range(current, batch_end + 1), results):
                if isinstance(result, Exception):
                    logger.warning(f"Failed to fetch block {num}: {result}")
                    continue
                headers.append(result)

            if headers:
                yield headers

            current = batch_end + 1

    async def iter_transactions(
        self,
        start_block: BlockNumber,
        end_block: Optional[BlockNumber] = None,
    ) -> AsyncIterator[Transaction]:
        latest = end_block or await self._chain.get_block_number()

        for block_num in range(start_block, latest + 1):
            try:
                block = await self._chain.get_block(block_num, full_transactions=True)
                for tx in block.get("transactions", []):
                    if isinstance(tx, dict):
                        yield Transaction(
                            hash=tx["hash"].hex(),
                            block_hash=tx.get("blockHash", None).hex() if tx.get("blockHash") else None,
                            block_number=tx.get("blockNumber"),
                            from_address=tx["from"],
                            to_address=tx.get("to"),
                            value=tx["value"],
                            gas=tx["gas"],
                            gas_price=tx.get("gasPrice"),
                            max_fee_per_gas=tx.get("maxFeePerGas"),
                            max_priority_fee_per_gas=tx.get("maxPriorityFeePerGas"),
                            input=tx["input"].hex(),
                            nonce=tx["nonce"],
                            transaction_index=tx.get("transactionIndex"),
                            chain_id=tx.get("chainId"),
                            type=tx.get("type", 0),
                        )
            except Exception as e:
                logger.warning(f"Failed to iterate block {block_num}: {e}")

    async def iter_logs(
        self,
        start_block: BlockNumber,
        end_block: Optional[BlockNumber] = None,
        address: Optional[str] = None,
    ) -> AsyncIterator[EventLog]:
        latest = end_block or await self._chain.get_block_number()
        batch_size = 100

        current = start_block
        while current <= latest:
            batch_end = min(current + batch_size - 1, latest)
            try:
                logs = await self._chain.get_logs(
                    from_block=current,
                    to_block=batch_end,
                    address=address,
                )
                for log in logs:
                    yield log
            except Exception as e:
                logger.warning(f"Failed to fetch logs for blocks {current}-{batch_end}: {e}")
            current = batch_end + 1


class InMemoryDataStore(IDataStore):
    def __init__(self):
        self._blocks: Dict[BlockNumber, BlockHeader] = {}
        self._transactions: Dict[Hash, Transaction] = {}
        self._receipts: Dict[Hash, TransactionReceipt] = {}
        self._logs: Dict[str, EventLog] = {}
        self._address_txs: Dict[Address, List[Hash]] = {}
        self._contract_logs: Dict[Address, List[str]] = {}

    async def store_block(self, block: BlockHeader) -> None:
        self._blocks[block.number] = block

    async def store_transaction(self, transaction: Transaction) -> None:
        self._transactions[transaction.hash] = transaction

        for addr in [transaction.from_address, transaction.to_address]:
            if addr:
                if addr not in self._address_txs:
                    self._address_txs[addr] = []
                if transaction.hash not in self._address_txs[addr]:
                    self._address_txs[addr].append(transaction.hash)

    async def store_receipt(self, receipt: TransactionReceipt) -> None:
        self._receipts[receipt.transaction_hash] = receipt

    async def store_log(self, log: EventLog) -> None:
        log_key = f"{log.block_number}:{log.log_index}"
        self._logs[log_key] = log

        contract_addr = log.address.lower()
        if contract_addr not in self._contract_logs:
            self._contract_logs[contract_addr] = []
        self._contract_logs[contract_addr].append(log_key)

    async def get_block(self, block_number: BlockNumber) -> Optional[BlockHeader]:
        return self._blocks.get(block_number)

    async def get_transaction(self, tx_hash: Hash) -> Optional[Transaction]:
        return self._transactions.get(tx_hash)

    async def get_transactions_by_address(
        self,
        address: str,
        limit: int = 100,
        offset: int = 0,
    ) -> List[Transaction]:
        tx_hashes = self._address_txs.get(address.lower(), [])
        start = len(tx_hashes) - offset - limit
        end = len(tx_hashes) - offset
        selected = tx_hashes[max(0, start):end]

        return [self._transactions[h] for h in reversed(selected) if h in self._transactions]

    async def get_logs_by_contract(
        self,
        contract_address: str,
        event_signature: Optional[str] = None,
        limit: int = 100,
        offset: int = 0,
    ) -> List[EventLog]:
        log_keys = self._contract_logs.get(contract_address.lower(), [])
        start = len(log_keys) - offset - limit
        end = len(log_keys) - offset
        selected = log_keys[max(0, start):end]

        logs = [self._logs[k] for k in reversed(selected) if k in self._logs]

        if event_signature:
            sig_lower = event_signature.lower()
            logs = [
                log for log in logs
                if log.topics and log.topics[0].lower() == sig_lower
            ]

        return logs


class DataIndexerService(IDataIndexerPort):
    def __init__(
        self,
        chain_adapter: IChainInteractionPort,
        data_store: Optional[IDataStore] = None,
        block_iterator: Optional[IBlockIterator] = None,
        batch_size: Optional[int] = None,
        start_block: Optional[BlockNumber] = None,
        concurrent_workers: Optional[int] = None,
    ):
        self._chain = chain_adapter
        self._store = data_store or InMemoryDataStore()
        self._iterator = block_iterator or BlockIterator(chain_adapter)
        self._batch_size = batch_size or settings.indexer.batch_size
        self._start_block = start_block or settings.indexer.start_block
        self._concurrent_workers = concurrent_workers or settings.indexer.concurrent_workers

        self._is_indexing = False
        self._indexer_task: Optional[asyncio.Task] = None
        self._latest_indexed_block: BlockNumber = self._start_block - 1
        self._indexing_metrics: Dict[str, Any] = {
            "blocks_indexed": 0,
            "transactions_indexed": 0,
            "logs_indexed": 0,
            "errors": 0,
            "start_time": None,
        }

    def is_indexing(self) -> bool:
        return self._is_indexing

    async def get_latest_indexed_block(self) -> BlockNumber:
        return self._latest_indexed_block

    async def get_index_status(self) -> Dict[str, Any]:
        current_block = await self._chain.get_block_number()
        return {
            "is_indexing": self._is_indexing,
            "latest_indexed_block": self._latest_indexed_block,
            "current_block": current_block,
            "blocks_behind": current_block - self._latest_indexed_block,
            "metrics": self._indexing_metrics.copy(),
        }

    async def start_indexing(
        self,
        start_block: Optional[BlockNumber] = None,
        end_block: Optional[BlockNumber] = None,
    ) -> None:
        if self._is_indexing:
            return

        self._is_indexing = True
        self._indexing_metrics["start_time"] = datetime.utcnow()

        if start_block is not None:
            self._latest_indexed_block = start_block - 1

        self._indexer_task = asyncio.create_task(
            self._indexing_loop(end_block)
        )

        logger.info(
            "Indexing started",
            chain=self._chain.chain.value,
            start_block=self._latest_indexed_block + 1,
            end_block=end_block,
        )

    async def stop_indexing(self) -> None:
        self._is_indexing = False
        if self._indexer_task and not self._indexer_task.done():
            self._indexer_task.cancel()
            try:
                await self._indexer_task
            except asyncio.CancelledError:
                pass
        logger.info("Indexing stopped")

    async def _indexing_loop(self, end_block: Optional[BlockNumber] = None) -> None:
        while self._is_indexing:
            try:
                latest = await self._chain.get_block_number()
                target = end_block or latest

                if self._latest_indexed_block >= target:
                    if end_block:
                        break
                    await asyncio.sleep(2)
                    continue

                next_start = self._latest_indexed_block + 1
                next_end = min(next_start + self._batch_size - 1, target)

                await self._index_block_range(next_start, next_end)

                self._latest_indexed_block = next_end

            except Exception as e:
                self._indexing_metrics["errors"] += 1
                logger.error(f"Indexing loop error: {e}")
                await asyncio.sleep(5)

    async def _index_block_range(self, start: BlockNumber, end: BlockNumber) -> None:
        logger.info(f"Indexing blocks {start}-{end}")

        async for batch in self._iterator.iter_blocks(start, end, self._batch_size):
            await asyncio.gather(*[self._store.store_block(b) for b in batch])

            block_numbers = [b.number for b in batch]
            tx_tasks = [self._index_block_transactions(n) for n in block_numbers]
            await asyncio.gather(*tx_tasks)

            self._indexing_metrics["blocks_indexed"] += len(batch)

        logger.info(f"Completed indexing blocks {start}-{end}")

    async def _index_block_transactions(self, block_number: BlockNumber) -> None:
        try:
            block = await self._chain.get_block(block_number, full_transactions=True)
            txs = block.get("transactions", [])

            for tx_data in txs:
                if isinstance(tx_data, dict):
                    tx = await self.parse_transaction(tx_data)
                    await self._store.store_transaction(tx)
                    self._indexing_metrics["transactions_indexed"] += 1

                    try:
                        receipt = await self._chain.get_transaction_receipt(tx.hash)
                        if receipt:
                            await self._store.store_receipt(receipt)
                            for log in receipt.logs:
                                await self._store.store_log(log)
                                self._indexing_metrics["logs_indexed"] += 1
                    except Exception as e:
                        logger.warning(f"Failed to fetch receipt for {tx.hash}: {e}")

        except Exception as e:
            logger.warning(f"Failed to index block {block_number}: {e}")

    async def index_block(self, block_number: BlockNumber) -> Dict[str, Any]:
        block = await self._chain.get_block(block_number, full_transactions=True)
        header = await self.parse_block(block)

        await self._store.store_block(header)
        await self._index_block_transactions(block_number)

        return {
            "block_number": block_number,
            "block_hash": header.hash,
            "transactions": len(block.get("transactions", [])),
        }

    async def parse_block(self, block_data: Dict[str, Any]) -> BlockHeader:
        try:
            return BlockHeader(
                number=block_data["number"],
                hash=block_data["hash"].hex() if isinstance(block_data["hash"], bytes) else block_data["hash"],
                parent_hash=block_data["parentHash"].hex() if isinstance(block_data["parentHash"], bytes) else block_data["parentHash"],
                timestamp=block_data["timestamp"],
                difficulty=block_data["difficulty"],
                total_difficulty=block_data["totalDifficulty"],
                gas_limit=block_data["gasLimit"],
                gas_used=block_data["gasUsed"],
                miner=block_data["miner"],
                extra_data=block_data["extraData"].hex() if isinstance(block_data["extraData"], bytes) else block_data["extraData"],
                base_fee_per_gas=block_data.get("baseFeePerGas"),
            )
        except KeyError as e:
            raise BlockParsingError(f"Missing required block field: {e}")

    async def parse_transaction(self, tx_data: Dict[str, Any]) -> Transaction:
        try:
            return Transaction(
                hash=tx_data["hash"].hex() if isinstance(tx_data["hash"], bytes) else tx_data["hash"],
                block_hash=tx_data.get("blockHash", None).hex() if isinstance(tx_data.get("blockHash"), bytes) else tx_data.get("blockHash"),
                block_number=tx_data.get("blockNumber"),
                from_address=tx_data["from"],
                to_address=tx_data.get("to"),
                value=tx_data["value"],
                gas=tx_data["gas"],
                gas_price=tx_data.get("gasPrice"),
                max_fee_per_gas=tx_data.get("maxFeePerGas"),
                max_priority_fee_per_gas=tx_data.get("maxPriorityFeePerGas"),
                input=tx_data["input"].hex() if isinstance(tx_data["input"], bytes) else tx_data["input"],
                nonce=tx_data["nonce"],
                transaction_index=tx_data.get("transactionIndex"),
                chain_id=tx_data.get("chainId"),
                type=tx_data.get("type", 0),
            )
        except KeyError as e:
            raise BlockParsingError(f"Missing required transaction field: {e}")

    async def parse_receipt(self, receipt_data: Dict[str, Any]) -> TransactionReceipt:
        try:
            logs = [await self.parse_log(log) for log in receipt_data.get("logs", [])]
            return TransactionReceipt(
                transaction_hash=receipt_data["transactionHash"].hex() if isinstance(receipt_data["transactionHash"], bytes) else receipt_data["transactionHash"],
                transaction_index=receipt_data["transactionIndex"],
                block_hash=receipt_data["blockHash"].hex() if isinstance(receipt_data["blockHash"], bytes) else receipt_data["blockHash"],
                block_number=receipt_data["blockNumber"],
                from_address=receipt_data["from"],
                to_address=receipt_data.get("to"),
                cumulative_gas_used=receipt_data["cumulativeGasUsed"],
                gas_used=receipt_data["gasUsed"],
                contract_address=receipt_data.get("contractAddress"),
                logs=logs,
                status=receipt_data["status"],
                effective_gas_price=receipt_data.get("effectiveGasPrice", 0),
            )
        except KeyError as e:
            raise BlockParsingError(f"Missing required receipt field: {e}")

    async def parse_log(self, log_data: Dict[str, Any]) -> EventLog:
        try:
            return EventLog(
                log_index=log_data["logIndex"],
                transaction_hash=log_data["transactionHash"].hex() if isinstance(log_data["transactionHash"], bytes) else log_data["transactionHash"],
                transaction_index=log_data["transactionIndex"],
                block_hash=log_data["blockHash"].hex() if isinstance(log_data["blockHash"], bytes) else log_data["blockHash"],
                block_number=log_data["blockNumber"],
                address=log_data["address"],
                data=log_data["data"].hex() if isinstance(log_data["data"], bytes) else log_data["data"],
                topics=[
                    t.hex() if isinstance(t, bytes) else t
                    for t in log_data.get("topics", [])
                ],
                removed=log_data.get("removed", False),
            )
        except KeyError as e:
            raise BlockParsingError(f"Missing required log field: {e}")

    async def reindex(
        self,
        from_block: BlockNumber,
        to_block: Optional[BlockNumber] = None,
    ) -> bool:
        if self._is_indexing:
            raise IndexingError("Cannot reindex while indexing is running")

        logger.info(f"Reindexing from block {from_block}", to_block=to_block)

        self._latest_indexed_block = from_block - 1
        await self.start_indexing(from_block, to_block)

        return True
