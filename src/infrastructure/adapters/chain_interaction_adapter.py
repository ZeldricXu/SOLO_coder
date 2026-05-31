from __future__ import annotations

import asyncio
from typing import Any, AsyncIterator, Dict, List, Optional, Union

from eth_typing import ChecksumAddress
from eth_utils import to_checksum_address
from web3 import AsyncWeb3, Web3
from web3._utils.events import construct_event_topic_set
from web3.exceptions import BlockNotFound, TransactionNotFound

try:
    from web3.middleware import async_geth_poa_middleware
    POA_MIDDLEWARE_AVAILABLE = True
except ImportError:
    try:
        from web3.middleware import geth_poa_middleware
        async_geth_poa_middleware = geth_poa_middleware
        POA_MIDDLEWARE_AVAILABLE = True
    except ImportError:
        POA_MIDDLEWARE_AVAILABLE = False

from src.core.ports.chain_interaction_port import IChainInteractionPort
from src.shared.config import get_chain_config
from src.shared.errors import BlockParsingError, NotFoundError, RPCCallError, TimeoutError
from src.shared.logger import get_logger
from src.shared.types import (
    Address,
    BlockHeader,
    BlockNumber,
    Chain,
    ChainId,
    EventLog,
    GasAmount,
    Hash,
    HexString,
    Transaction,
    TransactionReceipt,
    WeiAmount,
)

logger = get_logger(__name__)


class ChainInteractionAdapter(IChainInteractionPort):
    def __init__(self, chain: Chain, rpc_url: Optional[str] = None):
        self._chain = chain
        self._chain_config = get_chain_config(chain)
        self._rpc_url = rpc_url or self._chain_config.rpc_url
        self._chain_id = self._chain_config.chain_id
        self._w3: AsyncWeb3 = AsyncWeb3(AsyncWeb3.AsyncHTTPProvider(self._rpc_url))
        if POA_MIDDLEWARE_AVAILABLE:
            self._w3.middleware_onion.inject(async_geth_poa_middleware, layer=0)
        self._sync_w3: Web3 = Web3(Web3.HTTPProvider(self._rpc_url))
        if POA_MIDDLEWARE_AVAILABLE:
            self._sync_w3.middleware_onion.inject(async_geth_poa_middleware, layer=0)

    @property
    def chain(self) -> Chain:
        return self._chain

    @property
    def chain_id(self) -> ChainId:
        return self._chain_id

    @property
    def rpc_url(self) -> str:
        return self._rpc_url

    async def _safe_call(self, method_name: str, *args: Any, **kwargs: Any) -> Any:
        try:
            method = getattr(self._w3.eth, method_name)
            return await asyncio.wait_for(method(*args, **kwargs), timeout=30)
        except asyncio.TimeoutError:
            raise TimeoutError(f"RPC call {method_name} timed out")
        except Exception as e:
            raise RPCCallError(method_name, self._rpc_url, str(e))

    async def get_block_number(self) -> BlockNumber:
        return await self._safe_call("get_block_number")

    async def get_block(
        self, block_identifier: BlockNumber | Hash, full_transactions: bool = False
    ) -> Dict[str, Any]:
        try:
            block = await self._safe_call("get_block", block_identifier, full_transactions)
            return dict(block)
        except BlockNotFound:
            raise NotFoundError(f"Block {block_identifier} not found")

    async def get_block_header(self, block_identifier: BlockNumber | Hash) -> BlockHeader:
        block = await self.get_block(block_identifier)
        try:
            return BlockHeader(
                number=block["number"],
                hash=block["hash"].hex(),
                parent_hash=block["parentHash"].hex(),
                timestamp=block["timestamp"],
                difficulty=block["difficulty"],
                total_difficulty=block["totalDifficulty"],
                gas_limit=block["gasLimit"],
                gas_used=block["gasUsed"],
                miner=block["miner"],
                extra_data=block["extraData"].hex(),
                base_fee_per_gas=block.get("baseFeePerGas"),
            )
        except KeyError as e:
            raise BlockParsingError(f"Missing required block field: {e}")

    async def get_transaction(self, tx_hash: Hash) -> Optional[Transaction]:
        try:
            tx = await self._safe_call("get_transaction", tx_hash)
            if tx is None:
                return None
            return Transaction(
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
        except TransactionNotFound:
            return None

    async def get_transaction_receipt(self, tx_hash: Hash) -> Optional[TransactionReceipt]:
        try:
            receipt = await self._safe_call("get_transaction_receipt", tx_hash)
            if receipt is None:
                return None

            logs = [
                EventLog(
                    log_index=log["logIndex"],
                    transaction_hash=log["transactionHash"].hex(),
                    transaction_index=log["transactionIndex"],
                    block_hash=log["blockHash"].hex(),
                    block_number=log["blockNumber"],
                    address=log["address"],
                    data=log["data"].hex(),
                    topics=[t.hex() for t in log["topics"]],
                    removed=log.get("removed", False),
                )
                for log in receipt["logs"]
            ]

            return TransactionReceipt(
                transaction_hash=receipt["transactionHash"].hex(),
                transaction_index=receipt["transactionIndex"],
                block_hash=receipt["blockHash"].hex(),
                block_number=receipt["blockNumber"],
                from_address=receipt["from"],
                to_address=receipt.get("to"),
                cumulative_gas_used=receipt["cumulativeGasUsed"],
                gas_used=receipt["gasUsed"],
                contract_address=receipt.get("contractAddress"),
                logs=logs,
                status=receipt["status"],
                effective_gas_price=receipt.get("effectiveGasPrice", 0),
            )
        except TransactionNotFound:
            return None

    async def get_balance(
        self, address: Address, block_identifier: BlockNumber | Hash | str = "latest"
    ) -> WeiAmount:
        checksum_addr = to_checksum_address(address)
        return await self._safe_call("get_balance", checksum_addr, block_identifier)

    async def get_transaction_count(
        self, address: Address, block_identifier: BlockNumber | Hash | str = "latest"
    ) -> int:
        checksum_addr = to_checksum_address(address)
        return await self._safe_call("get_transaction_count", checksum_addr, block_identifier)

    async def get_gas_price(self) -> WeiAmount:
        return await self._safe_call("gas_price")

    async def get_max_priority_fee_per_gas(self) -> WeiAmount:
        return await self._safe_call("max_priority_fee_per_gas")

    async def estimate_gas(
        self,
        to: Optional[Address] = None,
        from_address: Optional[Address] = None,
        value: Optional[WeiAmount] = None,
        data: Optional[HexString] = None,
        gas_price: Optional[WeiAmount] = None,
    ) -> GasAmount:
        tx: Dict[str, Any] = {}
        if to:
            tx["to"] = to_checksum_address(to)
        if from_address:
            tx["from"] = to_checksum_address(from_address)
        if value is not None:
            tx["value"] = value
        if data:
            tx["data"] = data
        if gas_price is not None:
            tx["gasPrice"] = gas_price
        return await self._safe_call("estimate_gas", tx)

    async def call(
        self,
        to: Address,
        data: HexString,
        from_address: Optional[Address] = None,
        block_identifier: BlockNumber | Hash | str = "latest",
    ) -> HexString:
        tx: Dict[str, Any] = {"to": to_checksum_address(to), "data": data}
        if from_address:
            tx["from"] = to_checksum_address(from_address)
        result = await self._safe_call("call", tx, block_identifier)
        return result.hex() if isinstance(result, bytes) else result

    async def send_raw_transaction(self, raw_tx: HexString) -> Hash:
        tx_hash = await self._safe_call("send_raw_transaction", raw_tx)
        return tx_hash.hex()

    async def get_logs(
        self,
        from_block: Optional[BlockNumber] = None,
        to_block: Optional[BlockNumber | str] = None,
        address: Optional[Address | List[Address]] = None,
        topics: Optional[List[Optional[HexString]]] = None,
        block_hash: Optional[Hash] = None,
    ) -> List[EventLog]:
        filter_params: Dict[str, Any] = {}
        if from_block is not None:
            filter_params["fromBlock"] = from_block
        if to_block is not None:
            filter_params["toBlock"] = to_block
        if address is not None:
            if isinstance(address, list):
                filter_params["address"] = [to_checksum_address(a) for a in address]
            else:
                filter_params["address"] = to_checksum_address(address)
        if topics is not None:
            filter_params["topics"] = topics
        if block_hash is not None:
            filter_params["blockHash"] = block_hash

        raw_logs = await self._safe_call("get_logs", filter_params)
        return [
            EventLog(
                log_index=log["logIndex"],
                transaction_hash=log["transactionHash"].hex(),
                transaction_index=log["transactionIndex"],
                block_hash=log["blockHash"].hex(),
                block_number=log["blockNumber"],
                address=log["address"],
                data=log["data"].hex(),
                topics=[t.hex() if isinstance(t, bytes) else t for t in log["topics"]],
                removed=log.get("removed", False),
            )
            for log in raw_logs
        ]

    async def get_chain_id(self) -> ChainId:
        return await self._safe_call("chain_id")

    async def get_code(
        self, address: Address, block_identifier: BlockNumber | Hash | str = "latest"
    ) -> HexString:
        checksum_addr = to_checksum_address(address)
        code = await self._safe_call("get_code", checksum_addr, block_identifier)
        return code.hex()

    async def get_storage_at(
        self,
        address: Address,
        position: int,
        block_identifier: BlockNumber | Hash | str = "latest",
    ) -> HexString:
        checksum_addr = to_checksum_address(address)
        data = await self._safe_call("get_storage_at", checksum_addr, position, block_identifier)
        return data.hex()

    def create_filter(
        self,
        from_block: Optional[BlockNumber] = None,
        to_block: Optional[BlockNumber | str] = None,
        address: Optional[Address | List[Address]] = None,
        topics: Optional[List[Optional[HexString]]] = None,
    ) -> Any:
        filter_params: Dict[str, Any] = {}
        if from_block is not None:
            filter_params["fromBlock"] = from_block
        if to_block is not None:
            filter_params["toBlock"] = to_block
        if address is not None:
            if isinstance(address, list):
                filter_params["address"] = [to_checksum_address(a) for a in address]
            else:
                filter_params["address"] = to_checksum_address(address)
        if topics is not None:
            filter_params["topics"] = topics

        return self._sync_w3.eth.filter(filter_params)

    async def filter_new_entries(self, filter_id: Any) -> List[EventLog]:
        try:
            raw_logs = await asyncio.to_thread(filter_id.get_new_entries)
            return [
                EventLog(
                    log_index=log["logIndex"],
                    transaction_hash=log["transactionHash"].hex(),
                    transaction_index=log["transactionIndex"],
                    block_hash=log["blockHash"].hex(),
                    block_number=log["blockNumber"],
                    address=log["address"],
                    data=log["data"].hex(),
                    topics=[t.hex() if isinstance(t, bytes) else t for t in log["topics"]],
                    removed=log.get("removed", False),
                )
                for log in raw_logs
            ]
        except Exception as e:
            logger.error(f"Failed to get filter entries: {e}")
            return []

    async def subscribe_blocks(self) -> AsyncIterator[BlockHeader]:
        last_block = await self.get_block_number()
        while True:
            current_block = await self.get_block_number()
            if current_block > last_block:
                for block_num in range(last_block + 1, current_block + 1):
                    try:
                        header = await self.get_block_header(block_num)
                        yield header
                    except Exception as e:
                        logger.warning(f"Failed to fetch block {block_num}: {e}")
                last_block = current_block
            await asyncio.sleep(2)

    async def subscribe_logs(
        self,
        address: Optional[Address | List[Address]] = None,
        topics: Optional[List[Optional[HexString]]] = None,
    ) -> AsyncIterator[EventLog]:
        last_block = await self.get_block_number()
        while True:
            current_block = await self.get_block_number()
            if current_block > last_block:
                try:
                    logs = await self.get_logs(
                        from_block=last_block + 1,
                        to_block=current_block,
                        address=address,
                        topics=topics,
                    )
                    for log in logs:
                        yield log
                except Exception as e:
                    logger.warning(f"Failed to fetch logs: {e}")
                last_block = current_block
            await asyncio.sleep(2)

    def get_event_topic(
        self, event_abi: Dict[str, Any], argument_filters: Optional[Dict[str, Any]] = None
    ) -> List[HexString]:
        return construct_event_topic_set(event_abi, argument_filters or {})
