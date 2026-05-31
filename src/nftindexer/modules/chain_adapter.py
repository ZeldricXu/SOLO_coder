import asyncio
from dataclasses import dataclass
from typing import Any, Callable, Dict, List, Optional, Tuple
from urllib.parse import urlparse

from aiohttp import ClientSession, ClientTimeout
from tenacity import retry, stop_after_attempt, wait_exponential, retry_if_exception_type

from ..config import get_settings, ChainRPCConfig
from ..utils import (
    get_logger,
    ChainInteractionError,
    ConfigurationError,
    ValidationError,
    retry_async,
    hex_to_bytes,
    bytes_to_hex,
    to_checksum_address,
)

logger = get_logger(__name__)


@dataclass
class BlockData:
    number: int
    hash: str
    parent_hash: str
    timestamp: int
    difficulty: int
    total_difficulty: int
    size: int
    gas_limit: int
    gas_used: int
    base_fee_per_gas: Optional[int]
    miner: str
    extra_data: str
    transactions: List[str]


@dataclass
class TransactionData:
    hash: str
    block_number: int
    block_hash: str
    transaction_index: int
    from_address: str
    to_address: Optional[str]
    value: int
    gas: int
    gas_price: Optional[int]
    max_fee_per_gas: Optional[int]
    max_priority_fee_per_gas: Optional[int]
    input: str
    nonce: int
    transaction_type: int


@dataclass
class LogData:
    address: str
    topics: List[str]
    data: str
    block_number: int
    block_hash: str
    transaction_hash: str
    transaction_index: int
    log_index: int
    removed: bool


@dataclass
class ReceiptData:
    transaction_hash: str
    block_number: int
    block_hash: str
    status: int
    gas_used: int
    cumulative_gas_used: int
    contract_address: Optional[str]
    logs: List[LogData]


class ChainRPCProvider:
    def __init__(self, config: ChainRPCConfig, session: ClientSession):
        self.config = config
        self.session = session
        self.chain_id = config.chain_id
        self.rpc_url = config.rpc_url
        self.ws_url = config.ws_url
        self.request_counter = 0

    async def _make_request(self, method: str, params: Optional[List[Any]] = None) -> Any:
        if params is None:
            params = []

        self.request_counter += 1
        request_id = self.request_counter

        payload = {
            "jsonrpc": "2.0",
            "method": method,
            "params": params,
            "id": request_id,
        }

        try:
            timeout = ClientTimeout(total=self.config.timeout)
            async with self.session.post(
                self.rpc_url,
                json=payload,
                timeout=timeout,
                headers={"Content-Type": "application/json"},
            ) as response:
                if response.status != 200:
                    raise ChainInteractionError(
                        f"RPC request failed with status {response.status}",
                        details={"method": method, "status": response.status},
                    )

                result = await response.json()

                if "error" in result:
                    raise ChainInteractionError(
                        f"RPC error: {result['error'].get('message', 'Unknown error')}",
                        details={
                            "method": method,
                            "error_code": result["error"].get("code"),
                            "error_data": result["error"].get("data"),
                        },
                    )

                return result.get("result")

        except asyncio.TimeoutError:
            raise ChainInteractionError(
                f"RPC request timeout for method {method}",
                details={"method": method, "timeout": self.config.timeout},
            )
        except Exception as e:
            if isinstance(e, ChainInteractionError):
                raise
            raise ChainInteractionError(
                f"RPC request failed: {str(e)}",
                details={"method": method, "error": str(e)},
            )

    @retry(
        stop=stop_after_attempt(5),
        wait=wait_exponential(multiplier=1, min=1, max=10),
        retry=retry_if_exception_type((ChainInteractionError, asyncio.TimeoutError)),
    )
    async def call(self, method: str, params: Optional[List[Any]] = None) -> Any:
        return await self._make_request(method, params)

    async def get_block_number(self) -> int:
        result = await self.call("eth_blockNumber")
        return int(result, 16)

    async def get_block_by_number(self, block_number: int, include_txs: bool = False) -> Optional[Dict[str, Any]]:
        hex_block = hex(block_number) if block_number >= 0 else "latest"
        result = await self.call("eth_getBlockByNumber", [hex_block, include_txs])
        return result

    async def get_block_by_hash(self, block_hash: str, include_txs: bool = False) -> Optional[Dict[str, Any]]:
        result = await self.call("eth_getBlockByHash", [block_hash, include_txs])
        return result

    async def get_transaction_by_hash(self, tx_hash: str) -> Optional[Dict[str, Any]]:
        result = await self.call("eth_getTransactionByHash", [tx_hash])
        return result

    async def get_transaction_receipt(self, tx_hash: str) -> Optional[Dict[str, Any]]:
        result = await self.call("eth_getTransactionReceipt", [tx_hash])
        return result

    async def get_logs(self, filter_params: Dict[str, Any]) -> List[Dict[str, Any]]:
        result = await self.call("eth_getLogs", [filter_params])
        return result or []

    async def get_balance(self, address: str, block: str = "latest") -> int:
        checksum_address = to_checksum_address(address)
        result = await self.call("eth_getBalance", [checksum_address, block])
        return int(result, 16) if result else 0

    async def get_transaction_count(self, address: str, block: str = "latest") -> int:
        checksum_address = to_checksum_address(address)
        result = await self.call("eth_getTransactionCount", [checksum_address, block])
        return int(result, 16) if result else 0

    async def get_code(self, address: str, block: str = "latest") -> str:
        checksum_address = to_checksum_address(address)
        result = await self.call("eth_getCode", [checksum_address, block])
        return result or "0x"

    async def eth_call(self, to: str, data: str, block: str = "latest") -> str:
        checksum_to = to_checksum_address(to)
        result = await self.call("eth_call", [{"to": checksum_to, "data": data}, block])
        return result or "0x"

    async def eth_gas_price(self) -> int:
        result = await self.call("eth_gasPrice")
        return int(result, 16) if result else 0

    async def eth_max_priority_fee_per_gas(self) -> int:
        try:
            result = await self.call("eth_maxPriorityFeePerGas")
            return int(result, 16) if result else 0
        except Exception:
            return 0

    async def eth_fee_history(self, block_count: int, newest_block: str = "latest", reward_percentiles: Optional[List[int]] = None) -> Dict[str, Any]:
        if reward_percentiles is None:
            reward_percentiles = [25, 50, 75]
        params = [hex(block_count), newest_block, reward_percentiles]
        result = await self.call("eth_feeHistory", params)
        return result

    async def send_raw_transaction(self, raw_tx: str) -> str:
        result = await self.call("eth_sendRawTransaction", [raw_tx])
        return result

    async def get_chain_id(self) -> int:
        result = await self.call("eth_chainId")
        return int(result, 16) if result else self.chain_id


class ChainAdapter:
    def __init__(self):
        self.settings = get_settings()
        self._providers: Dict[int, ChainRPCProvider] = {}
        self._session: Optional[ClientSession] = None
        self._initialized = False

    async def initialize(self) -> None:
        if self._initialized:
            return

        logger.info("Initializing chain adapter")
        self._session = ClientSession()

        for chain_id, chain_config in self.settings.chain.chains.items():
            try:
                provider = ChainRPCProvider(chain_config, self._session)
                self._providers[chain_id] = provider
                logger.info(f"Initialized provider for chain {chain_id} ({chain_config.name})")
            except Exception as e:
                logger.error(f"Failed to initialize provider for chain {chain_id}: {e}")

        self._initialized = True
        logger.info(f"Chain adapter initialized with {len(self._providers)} providers")

    async def shutdown(self) -> None:
        if not self._initialized:
            return

        logger.info("Shutting down chain adapter")
        if self._session:
            await self._session.close()
            self._session = None

        self._providers.clear()
        self._initialized = False
        logger.info("Chain adapter shutdown complete")

    def get_provider(self, chain_id: int) -> ChainRPCProvider:
        if chain_id not in self._providers:
            raise ConfigurationError(f"No provider configured for chain {chain_id}")
        return self._providers[chain_id]

    def has_chain(self, chain_id: int) -> bool:
        return chain_id in self._providers

    def get_supported_chains(self) -> List[int]:
        return list(self._providers.keys())

    async def get_block_number(self, chain_id: int) -> int:
        provider = self.get_provider(chain_id)
        return await provider.get_block_number()

    async def get_block(self, chain_id: int, block_identifier: int | str, include_txs: bool = False) -> Optional[Dict[str, Any]]:
        provider = self.get_provider(chain_id)
        if isinstance(block_identifier, int):
            return await provider.get_block_by_number(block_identifier, include_txs)
        else:
            return await provider.get_block_by_hash(block_identifier, include_txs)

    async def get_transaction(self, chain_id: int, tx_hash: str) -> Optional[Dict[str, Any]]:
        provider = self.get_provider(chain_id)
        return await provider.get_transaction_by_hash(tx_hash)

    async def get_receipt(self, chain_id: int, tx_hash: str) -> Optional[Dict[str, Any]]:
        provider = self.get_provider(chain_id)
        return await provider.get_transaction_receipt(tx_hash)

    async def get_logs(self, chain_id: int, from_block: int, to_block: int, address: Optional[str] = None, topics: Optional[List[str]] = None) -> List[Dict[str, Any]]:
        provider = self.get_provider(chain_id)
        filter_params: Dict[str, Any] = {
            "fromBlock": hex(from_block),
            "toBlock": hex(to_block),
        }
        if address:
            filter_params["address"] = to_checksum_address(address)
        if topics:
            filter_params["topics"] = topics
        return await provider.get_logs(filter_params)

    async def get_balance(self, chain_id: int, address: str, block: str = "latest") -> int:
        provider = self.get_provider(chain_id)
        return await provider.get_balance(address, block)

    async def get_nonce(self, chain_id: int, address: str, block: str = "latest") -> int:
        provider = self.get_provider(chain_id)
        return await provider.get_transaction_count(address, block)

    async def call_contract(self, chain_id: int, to: str, data: str, block: str = "latest") -> str:
        provider = self.get_provider(chain_id)
        return await provider.eth_call(to, data, block)

    async def send_transaction(self, chain_id: int, raw_tx: str) -> str:
        provider = self.get_provider(chain_id)
        return await provider.send_raw_transaction(raw_tx)

    async def get_gas_price(self, chain_id: int) -> int:
        provider = self.get_provider(chain_id)
        return await provider.eth_gas_price()

    async def get_priority_fee(self, chain_id: int) -> int:
        provider = self.get_provider(chain_id)
        return await provider.eth_max_priority_fee_per_gas()

    async def get_fee_history(self, chain_id: int, block_count: int = 10, reward_percentiles: Optional[List[int]] = None) -> Dict[str, Any]:
        provider = self.get_provider(chain_id)
        return await provider.eth_fee_history(block_count, "latest", reward_percentiles)

    async def get_chain_info(self, chain_id: int) -> Dict[str, Any]:
        provider = self.get_provider(chain_id)
        config = self.settings.chain.chains.get(chain_id)
        if not config:
            raise ConfigurationError(f"No config for chain {chain_id}")

        try:
            latest_block = await provider.get_block_number()
            chain_id_onchain = await provider.get_chain_id()
            gas_price = await provider.eth_gas_price()

            return {
                "chain_id": chain_id,
                "name": config.name,
                "rpc_url": config.rpc_url,
                "block_time": config.block_time,
                "confirmations": config.confirmations,
                "latest_block": latest_block,
                "on_chain_id": chain_id_onchain,
                "gas_price": gas_price,
            }
        except Exception as e:
            logger.error(f"Failed to get chain info for chain {chain_id}: {e}")
            return {
                "chain_id": chain_id,
                "name": config.name,
                "error": str(e),
            }


_chain_adapter: Optional[ChainAdapter] = None


def get_chain_adapter() -> ChainAdapter:
    global _chain_adapter
    if _chain_adapter is None:
        _chain_adapter = ChainAdapter()
    return _chain_adapter
