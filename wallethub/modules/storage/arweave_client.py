import json
from typing import Any, Dict, Optional, Union
import httpx
import asyncio

from wallethub.core import StorageError
from wallethub.config import get_settings
from wallethub.utils import generate_id, sha256_hash


class ArweaveClient:
    def __init__(
        self,
        gateway_url: Optional[str] = None,
        wallet_path: Optional[str] = None,
        timeout: int = 60,
        max_connections: int = 100,
        max_keepalive_connections: int = 20,
        keepalive_expiry: int = 30,
    ):
        settings = get_settings()
        self.gateway_url = gateway_url or settings.arweave.gateway_url
        self.wallet_path = wallet_path or settings.arweave.wallet_path
        self._wallet_jwk = None
        self._timeout = timeout
        self._max_connections = max_connections
        self._max_keepalive_connections = max_keepalive_connections
        self._keepalive_expiry = keepalive_expiry
        self._client: Optional[httpx.AsyncClient] = None
        self._lock = asyncio.Lock()

    async def _get_client(self) -> httpx.AsyncClient:
        if self._client is None or self._client.is_closed:
            async with self._lock:
                if self._client is None or self._client.is_closed:
                    self._client = httpx.AsyncClient(
                        timeout=self._timeout,
                        limits=httpx.Limits(
                            max_connections=self._max_connections,
                            max_keepalive_connections=self._max_keepalive_connections,
                            keepalive_expiry=self._keepalive_expiry,
                        ),
                        http2=True,
                    )
        return self._client

    async def close(self) -> None:
        if self._client and not self._client.is_closed:
            await self._client.aclose()
            self._client = None

    async def __aenter__(self) -> "ArweaveClient":
        await self._get_client()
        return self

    async def __aexit__(self, exc_type, exc_val, exc_tb) -> None:
        await self.close()

    def load_wallet(self) -> Dict[str, Any]:
        if self._wallet_jwk:
            return self._wallet_jwk

        if not self.wallet_path:
            raise StorageError(
                message="Arweave wallet path not configured",
                details={"operation": "arweave_load_wallet", "validation_failed": "no_wallet_path"}
            )

        try:
            with open(self.wallet_path, "r") as f:
                self._wallet_jwk = json.load(f)
            return self._wallet_jwk
        except FileNotFoundError as e:
            raise StorageError(
                message=f"Arweave wallet file not found: {self.wallet_path}",
                details={
                    "operation": "arweave_load_wallet",
                    "wallet_path": self.wallet_path,
                    "error_type": "file_not_found"
                }
            )
        except json.JSONDecodeError as e:
            raise StorageError(
                message=f"Arweave wallet file is not valid JSON: {str(e)}",
                details={
                    "operation": "arweave_load_wallet",
                    "wallet_path": self.wallet_path,
                    "error_type": "json_decode_error"
                }
            )
        except Exception as e:
            raise StorageError(
                message=f"Failed to load Arweave wallet: {str(e)}",
                details={
                    "operation": "arweave_load_wallet",
                    "wallet_path": self.wallet_path,
                    "error_type": type(e).__name__
                }
            )

    async def upload_data(
        self,
        data: Union[str, bytes, Dict[str, Any]],
        content_type: str = "application/octet-stream",
        tags: Optional[Dict[str, str]] = None,
    ) -> str:
        if isinstance(data, dict):
            data = json.dumps(data)
            content_type = "application/json"
        if isinstance(data, str):
            data = data.encode()

        try:
            tx_data = {
                "data": data.hex(),
                "content_type": content_type,
                "tags": [{"name": k, "value": v} for k, v in (tags or {}).items()],
            }

            client = await self._get_client()
            response = await client.post(
                f"{self.gateway_url}tx",
                json=tx_data,
                timeout=max(self._timeout, 120),
            )
            if response.status_code == 200:
                return response.json().get("id", "")

            raise StorageError(
                message=f"Arweave upload failed: {response.text}",
                details={
                    "operation": "arweave_upload",
                    "gateway_url": self.gateway_url,
                    "data_size": len(data),
                    "content_type": content_type,
                    "status_code": response.status_code,
                    "error_type": "upload_failed"
                }
            )
        except StorageError:
            raise
        except httpx.TimeoutException as e:
            raise StorageError(
                message=f"Arweave upload timeout after 120s: {str(e)}",
                details={
                    "operation": "arweave_upload",
                    "gateway_url": self.gateway_url,
                    "data_size": len(data),
                    "timeout": max(self._timeout, 120),
                    "error_type": "timeout"
                }
            )
        except httpx.HTTPError as e:
            raise StorageError(
                message=f"Failed to upload to Arweave: {str(e)}",
                details={
                    "operation": "arweave_upload",
                    "gateway_url": self.gateway_url,
                    "data_size": len(data),
                    "error_type": type(e).__name__
                }
            )
        except Exception as e:
            raise StorageError(
                message=f"Failed to upload to Arweave: {str(e)}",
                details={
                    "operation": "arweave_upload",
                    "gateway_url": self.gateway_url,
                    "data_size": len(data),
                    "error_type": type(e).__name__
                }
            )

    async def get_data(self, tx_id: str) -> bytes:
        if not tx_id:
            raise StorageError(
                message="Transaction ID cannot be empty",
                details={"operation": "arweave_get_data", "validation_failed": "empty_tx_id"}
            )
        try:
            client = await self._get_client()
            response = await client.get(
                f"{self.gateway_url}{tx_id}/data",
                timeout=self._timeout,
            )
            response.raise_for_status()
            return response.content
        except httpx.TimeoutException as e:
            raise StorageError(
                message=f"Arweave get data timeout after {self._timeout}s: {str(e)}",
                details={
                    "operation": "arweave_get_data",
                    "tx_id": tx_id,
                    "gateway_url": self.gateway_url,
                    "timeout": self._timeout,
                    "error_type": "timeout"
                }
            )
        except httpx.HTTPError as e:
            raise StorageError(
                message=f"Failed to get data from Arweave: {str(e)}",
                details={
                    "operation": "arweave_get_data",
                    "tx_id": tx_id,
                    "gateway_url": self.gateway_url,
                    "error_type": type(e).__name__
                }
            )
        except Exception as e:
            raise StorageError(
                message=f"Failed to get data from Arweave: {str(e)}",
                details={
                    "operation": "arweave_get_data",
                    "tx_id": tx_id,
                    "gateway_url": self.gateway_url,
                    "error_type": type(e).__name__
                }
            )

    async def get_json(self, tx_id: str) -> Dict[str, Any]:
        data = await self.get_data(tx_id)
        try:
            return json.loads(data)
        except json.JSONDecodeError as e:
            raise StorageError(
                message=f"Content is not valid JSON: {str(e)}",
                details={
                    "operation": "arweave_get_json",
                    "tx_id": tx_id,
                    "content_preview": data[:100].decode('utf-8', errors='replace') if len(data) > 100 else data.decode('utf-8', errors='replace'),
                    "error_type": "json_decode_error"
                }
            )

    async def get_transaction(self, tx_id: str) -> Dict[str, Any]:
        if not tx_id:
            raise StorageError(
                message="Transaction ID cannot be empty",
                details={"operation": "arweave_get_transaction", "validation_failed": "empty_tx_id"}
            )
        try:
            client = await self._get_client()
            response = await client.get(
                f"{self.gateway_url}tx/{tx_id}",
                timeout=self._timeout,
            )
            response.raise_for_status()
            return response.json()
        except httpx.HTTPError as e:
            raise StorageError(
                message=f"Failed to get transaction: {str(e)}",
                details={
                    "operation": "arweave_get_transaction",
                    "tx_id": tx_id,
                    "gateway_url": self.gateway_url,
                    "error_type": type(e).__name__
                }
            )
        except Exception as e:
            raise StorageError(
                message=f"Failed to get transaction: {str(e)}",
                details={
                    "operation": "arweave_get_transaction",
                    "tx_id": tx_id,
                    "gateway_url": self.gateway_url,
                    "error_type": type(e).__name__
                }
            )

    async def get_status(self, tx_id: str) -> Dict[str, Any]:
        if not tx_id:
            raise StorageError(
                message="Transaction ID cannot be empty",
                details={"operation": "arweave_get_status", "validation_failed": "empty_tx_id"}
            )
        try:
            client = await self._get_client()
            response = await client.get(
                f"{self.gateway_url}tx/{tx_id}/status",
                timeout=self._timeout,
            )
            if response.status_code == 200:
                return response.json()
            return {"status": "pending", "confirmed": False, "tx_id": tx_id}
        except httpx.HTTPError as e:
            raise StorageError(
                message=f"Failed to get status: {str(e)}",
                details={
                    "operation": "arweave_get_status",
                    "tx_id": tx_id,
                    "gateway_url": self.gateway_url,
                    "error_type": type(e).__name__
                }
            )
        except Exception as e:
            raise StorageError(
                message=f"Failed to get status: {str(e)}",
                details={
                    "operation": "arweave_get_status",
                    "tx_id": tx_id,
                    "gateway_url": self.gateway_url,
                    "error_type": type(e).__name__
                }
            )

    async def get_balance(self, address: Optional[str] = None) -> int:
        if address is None:
            wallet = self.load_wallet()
            address = wallet.get("n", "")
        if not address:
            raise StorageError(
                message="Address cannot be empty",
                details={"operation": "arweave_get_balance", "validation_failed": "empty_address"}
            )

        try:
            client = await self._get_client()
            response = await client.get(
                f"{self.gateway_url}wallet/{address}/balance",
                timeout=30,
            )
            response.raise_for_status()
            return int(response.text)
        except httpx.HTTPError as e:
            raise StorageError(
                message=f"Failed to get balance: {str(e)}",
                details={
                    "operation": "arweave_get_balance",
                    "address": address,
                    "gateway_url": self.gateway_url,
                    "error_type": type(e).__name__
                }
            )
        except Exception as e:
            raise StorageError(
                message=f"Failed to get balance: {str(e)}",
                details={
                    "operation": "arweave_get_balance",
                    "address": address,
                    "gateway_url": self.gateway_url,
                    "error_type": type(e).__name__
                }
            )

    async def get_price(self, data_size: int) -> int:
        if data_size < 0:
            raise StorageError(
                message="Data size cannot be negative",
                details={
                    "operation": "arweave_get_price",
                    "data_size": data_size,
                    "validation_failed": "negative_size"
                }
            )
        try:
            client = await self._get_client()
            response = await client.get(
                f"{self.gateway_url}price/{data_size}",
                timeout=30,
            )
            response.raise_for_status()
            return int(response.text)
        except httpx.HTTPError as e:
            raise StorageError(
                message=f"Failed to get price: {str(e)}",
                details={
                    "operation": "arweave_get_price",
                    "data_size": data_size,
                    "gateway_url": self.gateway_url,
                    "error_type": type(e).__name__
                }
            )
        except Exception as e:
            raise StorageError(
                message=f"Failed to get price: {str(e)}",
                details={
                    "operation": "arweave_get_price",
                    "data_size": data_size,
                    "gateway_url": self.gateway_url,
                    "error_type": type(e).__name__
                }
            )

    def get_gateway_url(self, tx_id: str) -> str:
        return f"{self.gateway_url}{tx_id}"

    async def graphql_query(self, query: str, variables: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
        if not query:
            raise StorageError(
                message="GraphQL query cannot be empty",
                details={"operation": "arweave_graphql", "validation_failed": "empty_query"}
            )
        try:
            client = await self._get_client()
            response = await client.post(
                f"{self.gateway_url}graphql",
                json={"query": query, "variables": variables or {}},
                timeout=self._timeout,
            )
            response.raise_for_status()
            return response.json()
        except httpx.HTTPError as e:
            raise StorageError(
                message=f"GraphQL query failed: {str(e)}",
                details={
                    "operation": "arweave_graphql",
                    "gateway_url": self.gateway_url,
                    "query_preview": query[:200],
                    "error_type": type(e).__name__
                }
            )
        except Exception as e:
            raise StorageError(
                message=f"GraphQL query failed: {str(e)}",
                details={
                    "operation": "arweave_graphql",
                    "gateway_url": self.gateway_url,
                    "query_preview": query[:200],
                    "error_type": type(e).__name__
                }
            )

    async def query_by_tag(self, tag_name: str, tag_value: str) -> list[str]:
        if not tag_name or not tag_value:
            raise StorageError(
                message="Tag name and value cannot be empty",
                details={"operation": "arweave_query_by_tag", "validation_failed": "empty_tag"}
            )
        query = """
        query($tagName: String!, $tagValue: String!) {
            transactions(
                tags: { name: $tagName, values: [$tagValue] },
                first: 100
            ) {
                edges {
                    node {
                        id
                    }
                }
            }
        }
        """
        result = await self.graphql_query(query, {"tagName": tag_name, "tagValue": tag_value})
        edges = result.get("data", {}).get("transactions", {}).get("edges", [])
        return [edge["node"]["id"] for edge in edges]

    async def get_stats(self) -> Dict[str, Any]:
        if self._client and not self._client.is_closed:
            return {
                "gateway_url": self.gateway_url,
                "wallet_path": self.wallet_path,
                "wallet_loaded": self._wallet_jwk is not None,
                "timeout": self._timeout,
                "max_connections": self._max_connections,
                "max_keepalive_connections": self._max_keepalive_connections,
                "keepalive_expiry": self._keepalive_expiry,
                "client_active": True,
            }
        return {
            "gateway_url": self.gateway_url,
            "wallet_path": self.wallet_path,
            "wallet_loaded": self._wallet_jwk is not None,
            "timeout": self._timeout,
            "max_connections": self._max_connections,
            "max_keepalive_connections": self._max_keepalive_connections,
            "keepalive_expiry": self._keepalive_expiry,
            "client_active": False,
        }
