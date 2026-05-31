import asyncio
import logging
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional, Union
from datetime import datetime, timezone

from web3 import Web3
from web3.eth import AsyncEth
from web3.middleware import geth_poa_middleware
from eth_account.datastructures import SignedTransaction

from wallethub.core import (
    ChainInteractionError,
    TransactionError,
    Address,
    Hash,
    Wei,
)
from wallethub.config import get_settings
from wallethub.utils import async_retry, generate_id

logger = logging.getLogger(__name__)


@dataclass
class ChainConfig:
    chain_id: int
    name: str
    rpc_url: str
    symbol: str = "ETH"
    block_time: int = 12
    explorer_url: Optional[str] = None


class ChainClient:
    def __init__(self, chain: str, rpc_url: Optional[str] = None):
        self.settings = get_settings()

        if chain not in self.settings.chains:
            raise ChainInteractionError(f"Chain {chain} is not configured")

        self.chain = chain
        self.chain_config = self.settings.chains[chain]
        self.rpc_url = rpc_url or self.chain_config.rpc_url

        self._w3: Optional[Web3] = None
        self._request_count = 0
        self._last_request_time = 0.0

    @property
    def w3(self) -> Web3:
        if self._w3 is None:
            provider = Web3.AsyncHTTPProvider(self.rpc_url)
            self._w3 = Web3(provider, modules={"eth": (AsyncEth,)}, middlewares=[])

            if self.chain_config.chain_id in [56, 97, 137, 80001]:
                self._w3.middleware_onion.inject(geth_poa_middleware, layer=0)

        return self._w3

    @property
    def eth(self) -> AsyncEth:
        return self.w3.eth

    @async_retry(max_attempts=3, delay=1.0, backoff=2.0)
    async def get_block_number(self) -> int:
        return await self.eth.block_number

    @async_retry(max_attempts=3, delay=1.0, backoff=2.0)
    async def get_block(
        self,
        block_identifier: Union[int, str],
        full_transactions: bool = False,
    ) -> Dict[str, Any]:
        block = await self.eth.get_block(block_identifier, full_transactions=full_transactions)
        return dict(block)

    @async_retry(max_attempts=3, delay=1.0, backoff=2.0)
    async def get_transaction(self, tx_hash: str) -> Dict[str, Any]:
        tx = await self.eth.get_transaction(tx_hash)
        return dict(tx)

    @async_retry(max_attempts=3, delay=1.0, backoff=2.0)
    async def get_transaction_receipt(self, tx_hash: str) -> Optional[Dict[str, Any]]:
        receipt = await self.eth.get_transaction_receipt(tx_hash)
        return dict(receipt) if receipt else None

    @async_retry(max_attempts=3, delay=1.0, backoff=2.0)
    async def get_balance(self, address: str, block_identifier: Union[int, str] = "latest") -> int:
        return int(await self.eth.get_balance(address, block_identifier))

    @async_retry(max_attempts=3, delay=1.0, backoff=2.0)
    async def get_nonce(self, address: str, block_identifier: Union[int, str] = "pending") -> int:
        return int(await self.eth.get_transaction_count(address, block_identifier))

    @async_retry(max_attempts=3, delay=1.0, backoff=2.0)
    async def get_gas_price(self) -> int:
        return int(await self.eth.gas_price)

    @async_retry(max_attempts=3, delay=1.0, backoff=2.0)
    async def get_priority_fee(self) -> int:
        return int(await self.eth.max_priority_fee_per_gas)

    @async_retry(max_attempts=3, delay=1.0, backoff=2.0)
    async def get_fee_history(
        self,
        block_count: int = 10,
        newest_block: Union[int, str] = "latest",
        reward_percentiles: Optional[List[int]] = None,
    ) -> Dict[str, Any]:
        if reward_percentiles is None:
            reward_percentiles = [25, 50, 75]

        history = await self.eth.fee_history(
            block_count,
            newest_block,
            reward_percentiles,
        )
        return dict(history)

    @async_retry(max_attempts=3, delay=1.0, backoff=2.0)
    async def estimate_gas(self, tx_params: Dict[str, Any]) -> int:
        return int(await self.eth.estimate_gas(tx_params))

    @async_retry(max_attempts=3, delay=1.0, backoff=2.0)
    async def call(self, tx_params: Dict[str, Any], block_identifier: Union[int, str] = "latest") -> str:
        return await self.eth.call(tx_params, block_identifier)

    @async_retry(max_attempts=3, delay=2.0, backoff=2.0)
    async def send_raw_transaction(self, raw_tx: Union[bytes, str]) -> str:
        if isinstance(raw_tx, bytes):
            raw_tx = "0x" + raw_tx.hex()
        tx_hash = await self.eth.send_raw_transaction(raw_tx)
        return tx_hash.hex()

    async def broadcast_transaction(
        self,
        signed_tx: SignedTransaction,
        wait_for_receipt: bool = False,
        timeout: int = 120,
    ) -> Dict[str, Any]:
        try:
            tx_hash = await self.send_raw_transaction(signed_tx.rawTransaction)

            result = {
                "tx_hash": tx_hash,
                "raw_tx": signed_tx.rawTransaction.hex(),
                "broadcasted": True,
            }

            if wait_for_receipt:
                receipt = await self.wait_for_transaction_receipt(tx_hash, timeout)
                result["receipt"] = receipt

            return result
        except Exception as e:
            raise TransactionError(f"Failed to broadcast transaction: {str(e)}")

    async def wait_for_transaction_receipt(
        self,
        tx_hash: str,
        timeout: int = 120,
        poll_interval: float = 1.0,
    ) -> Dict[str, Any]:
        start_time = asyncio.get_event_loop().time()

        while (asyncio.get_event_loop().time() - start_time) < timeout:
            receipt = await self.get_transaction_receipt(tx_hash)
            if receipt:
                return receipt
            await asyncio.sleep(poll_interval)

        raise TransactionError(f"Transaction {tx_hash} not mined within {timeout} seconds")

    async def get_logs(self, filter_params: Dict[str, Any]) -> List[Dict[str, Any]]:
        logs = await self.eth.get_logs(filter_params)
        return [dict(log) for log in logs]

    async def get_code(self, address: str, block_identifier: Union[int, str] = "latest") -> str:
        return await self.eth.get_code(address, block_identifier)

    async def get_storage_at(
        self,
        address: str,
        position: int,
        block_identifier: Union[int, str] = "latest",
    ) -> str:
        return await self.eth.get_storage_at(address, position, block_identifier)

    def to_checksum_address(self, address: str) -> str:
        return self.w3.to_checksum_address(address)

    def is_connected(self) -> bool:
        try:
            return self.w3.is_connected()
        except Exception:
            return False

    async def get_chain_info(self) -> Dict[str, Any]:
        block_number = await self.get_block_number()
        gas_price = await self.get_gas_price()
        latest_block = await self.get_block(block_number)

        return {
            "chain": self.chain,
            "chain_id": self.chain_config.chain_id,
            "block_number": block_number,
            "gas_price_wei": gas_price,
            "block_timestamp": latest_block.get("timestamp"),
            "is_connected": self.is_connected(),
        }


class ChainAdapter:
    def __init__(self):
        self.settings = get_settings()
        self._clients: Dict[str, ChainClient] = {}

    def get_client(self, chain: str) -> ChainClient:
        if chain not in self._clients:
            self._clients[chain] = ChainClient(chain)
        return self._clients[chain]

    def register_client(self, chain: str, client: ChainClient) -> None:
        self._clients[chain] = client

    def list_chains(self) -> List[str]:
        return list(self.settings.chains.keys())

    async def get_all_chain_info(self) -> Dict[str, Dict[str, Any]]:
        results = {}
        for chain in self.settings.chains:
            try:
                client = self.get_client(chain)
                results[chain] = await client.get_chain_info()
            except Exception as e:
                results[chain] = {"error": str(e)}
        return results

    async def batch_request(
        self,
        requests: List[Dict[str, Any]],
    ) -> List[Dict[str, Any]]:
        async def execute_request(req: Dict[str, Any]) -> Dict[str, Any]:
            try:
                client = self.get_client(req["chain"])
                method = req["method"]
                params = req.get("params", {})

                if hasattr(client, method):
                    result = await getattr(client, method)(**params)
                    return {"success": True, "result": result}
                else:
                    return {"success": False, "error": f"Method {method} not found"}
            except Exception as e:
                return {"success": False, "error": str(e)}

        tasks = [execute_request(req) for req in requests]
        return await asyncio.gather(*tasks)
