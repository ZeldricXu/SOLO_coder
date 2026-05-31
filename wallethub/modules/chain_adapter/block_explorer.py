from typing import Any, Dict, List, Optional
import httpx

from wallethub.core import ChainInteractionError
from wallethub.config import get_settings


class BlockExplorer:
    def __init__(self, chain: str):
        self.settings = get_settings()
        if chain not in self.settings.chains:
            raise ChainInteractionError(f"Chain {chain} is not configured")

        self.chain = chain
        self.chain_config = self.settings.chains[chain]
        self.explorer_url = self.chain_config.explorer_url
        self._api_key = None

    def set_api_key(self, api_key: str) -> None:
        self._api_key = api_key

    def get_transaction_url(self, tx_hash: str) -> str:
        if not self.explorer_url:
            raise ChainInteractionError(f"Block explorer not configured for {self.chain}")
        return f"{self.explorer_url}/tx/{tx_hash}"

    def get_address_url(self, address: str) -> str:
        if not self.explorer_url:
            raise ChainInteractionError(f"Block explorer not configured for {self.chain}")
        return f"{self.explorer_url}/address/{address}"

    def get_block_url(self, block_number: int) -> str:
        if not self.explorer_url:
            raise ChainInteractionError(f"Block explorer not configured for {self.chain}")
        return f"{self.explorer_url}/block/{block_number}"

    async def get_transaction_status(self, tx_hash: str) -> Dict[str, Any]:
        if not self.explorer_url or not self._api_key:
            raise ChainInteractionError("Block explorer API not configured")

        params = {
            "module": "transaction",
            "action": "gettxreceiptstatus",
            "txhash": tx_hash,
            "apikey": self._api_key,
        }

        try:
            async with httpx.AsyncClient(timeout=30) as client:
                response = await client.get(
                    f"{self.explorer_url}/api",
                    params=params,
                )
                response.raise_for_status()
                return response.json()
        except Exception as e:
            raise ChainInteractionError(f"Failed to get transaction status: {str(e)}")

    async def get_address_transactions(
        self,
        address: str,
        start_block: int = 0,
        end_block: int = 99999999,
        page: int = 1,
        offset: int = 100,
    ) -> List[Dict[str, Any]]:
        if not self.explorer_url or not self._api_key:
            raise ChainInteractionError("Block explorer API not configured")

        params = {
            "module": "account",
            "action": "txlist",
            "address": address,
            "startblock": start_block,
            "endblock": end_block,
            "page": page,
            "offset": offset,
            "sort": "desc",
            "apikey": self._api_key,
        }

        try:
            async with httpx.AsyncClient(timeout=30) as client:
                response = await client.get(
                    f"{self.explorer_url}/api",
                    params=params,
                )
                response.raise_for_status()
                data = response.json()
                return data.get("result", [])
        except Exception as e:
            raise ChainInteractionError(f"Failed to get address transactions: {str(e)}")

    async def get_contract_abi(self, contract_address: str) -> Optional[List[Dict[str, Any]]]:
        if not self.explorer_url or not self._api_key:
            raise ChainInteractionError("Block explorer API not configured")

        params = {
            "module": "contract",
            "action": "getabi",
            "address": contract_address,
            "apikey": self._api_key,
        }

        try:
            async with httpx.AsyncClient(timeout=30) as client:
                response = await client.get(
                    f"{self.explorer_url}/api",
                    params=params,
                )
                response.raise_for_status()
                data = response.json()
                if data.get("status") == "1" and data.get("result"):
                    import json
                    return json.loads(data["result"])
                return None
        except Exception as e:
            raise ChainInteractionError(f"Failed to get contract ABI: {str(e)}")
