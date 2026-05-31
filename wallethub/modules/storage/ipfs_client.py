import io
import json
from typing import Any, Dict, List, Optional, Union
import aiohttp
import httpx
import asyncio

from wallethub.core import StorageError
from wallethub.config import get_settings
from wallethub.utils import generate_id, sha256_hash


class IPFSClient:
    def __init__(
        self,
        api_url: Optional[str] = None,
        gateway_url: Optional[str] = None,
        timeout: int = 60,
        max_connections: int = 100,
        max_keepalive_connections: int = 20,
        keepalive_expiry: int = 30,
    ):
        settings = get_settings()
        self.api_url = api_url or settings.ipfs.api_url
        self.gateway_url = gateway_url or settings.ipfs.gateway_url
        self.pinata_api_key = settings.ipfs.pinata_api_key
        self.pinata_secret_api_key = settings.ipfs.pinata_secret_api_key
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

    async def __aenter__(self) -> "IPFSClient":
        await self._get_client()
        return self

    async def __aexit__(self, exc_type, exc_val, exc_tb) -> None:
        await self.close()

    async def add(
        self,
        data: Union[str, bytes, Dict[str, Any]],
        pin: bool = True,
    ) -> Dict[str, Any]:
        if isinstance(data, dict):
            data = json.dumps(data)
        if isinstance(data, str):
            data = data.encode()

        files = {"file": ("content", data)}
        params = {"pin": str(pin).lower()}

        try:
            client = await self._get_client()
            response = await client.post(
                f"{self.api_url}/api/v0/add",
                files=files,
                params=params,
            )
            response.raise_for_status()
            return response.json()
        except httpx.TimeoutException as e:
            raise StorageError(
                message=f"IPFS add timeout after {self._timeout}s: {str(e)}",
                details={
                    "operation": "ipfs_add",
                    "api_url": self.api_url,
                    "data_size": len(data) if isinstance(data, (bytes, bytearray)) else "unknown",
                    "pin": pin,
                    "timeout": self._timeout,
                    "error_type": "timeout"
                }
            )
        except httpx.HTTPError as e:
            raise StorageError(
                message=f"Failed to add to IPFS: {str(e)}",
                details={
                    "operation": "ipfs_add",
                    "api_url": self.api_url,
                    "data_size": len(data) if isinstance(data, (bytes, bytearray)) else "unknown",
                    "pin": pin,
                    "error_type": type(e).__name__
                }
            )
        except Exception as e:
            raise StorageError(
                message=f"Failed to add to IPFS: {str(e)}",
                details={
                    "operation": "ipfs_add",
                    "api_url": self.api_url,
                    "error_type": type(e).__name__
                }
            )

    async def get(self, cid: str) -> bytes:
        if not cid:
            raise StorageError(
                message="CID cannot be empty",
                details={"operation": "ipfs_get", "validation_failed": "empty_cid"}
            )
        try:
            client = await self._get_client()
            response = await client.post(
                f"{self.api_url}/api/v0/cat",
                params={"arg": cid},
            )
            response.raise_for_status()
            return response.content
        except httpx.TimeoutException as e:
            raise StorageError(
                message=f"IPFS get timeout after {self._timeout}s: {str(e)}",
                details={
                    "operation": "ipfs_get",
                    "cid": cid,
                    "api_url": self.api_url,
                    "timeout": self._timeout,
                    "error_type": "timeout"
                }
            )
        except httpx.HTTPError as e:
            raise StorageError(
                message=f"Failed to get from IPFS: {str(e)}",
                details={
                    "operation": "ipfs_get",
                    "cid": cid,
                    "api_url": self.api_url,
                    "error_type": type(e).__name__
                }
            )
        except Exception as e:
            raise StorageError(
                message=f"Failed to get from IPFS: {str(e)}",
                details={
                    "operation": "ipfs_get",
                    "cid": cid,
                    "api_url": self.api_url,
                    "error_type": type(e).__name__
                }
            )

    async def get_json(self, cid: str) -> Dict[str, Any]:
        content = await self.get(cid)
        try:
            return json.loads(content)
        except json.JSONDecodeError as e:
            raise StorageError(
                message=f"Content is not valid JSON: {str(e)}",
                details={
                    "operation": "ipfs_get_json",
                    "cid": cid,
                    "content_preview": content[:100].decode('utf-8', errors='replace') if len(content) > 100 else content.decode('utf-8', errors='replace'),
                    "error_type": "json_decode_error"
                }
            )

    async def pin(self, cid: str) -> Dict[str, Any]:
        if not cid:
            raise StorageError(
                message="CID cannot be empty",
                details={"operation": "ipfs_pin", "validation_failed": "empty_cid"}
            )
        try:
            client = await self._get_client()
            response = await client.post(
                f"{self.api_url}/api/v0/pin/add",
                params={"arg": cid},
            )
            response.raise_for_status()
            return response.json()
        except httpx.HTTPError as e:
            raise StorageError(
                message=f"Failed to pin IPFS content: {str(e)}",
                details={
                    "operation": "ipfs_pin",
                    "cid": cid,
                    "api_url": self.api_url,
                    "error_type": type(e).__name__
                }
            )
        except Exception as e:
            raise StorageError(
                message=f"Failed to pin IPFS content: {str(e)}",
                details={
                    "operation": "ipfs_pin",
                    "cid": cid,
                    "api_url": self.api_url,
                    "error_type": type(e).__name__
                }
            )

    async def unpin(self, cid: str) -> Dict[str, Any]:
        if not cid:
            raise StorageError(
                message="CID cannot be empty",
                details={"operation": "ipfs_unpin", "validation_failed": "empty_cid"}
            )
        try:
            client = await self._get_client()
            response = await client.post(
                f"{self.api_url}/api/v0/pin/rm",
                params={"arg": cid},
            )
            response.raise_for_status()
            return response.json()
        except httpx.HTTPError as e:
            raise StorageError(
                message=f"Failed to unpin IPFS content: {str(e)}",
                details={
                    "operation": "ipfs_unpin",
                    "cid": cid,
                    "api_url": self.api_url,
                    "error_type": type(e).__name__
                }
            )
        except Exception as e:
            raise StorageError(
                message=f"Failed to unpin IPFS content: {str(e)}",
                details={
                    "operation": "ipfs_unpin",
                    "cid": cid,
                    "api_url": self.api_url,
                    "error_type": type(e).__name__
                }
            )

    async def list_pins(self) -> Dict[str, Any]:
        try:
            client = await self._get_client()
            response = await client.post(f"{self.api_url}/api/v0/pin/ls")
            response.raise_for_status()
            return response.json()
        except httpx.HTTPError as e:
            raise StorageError(
                message=f"Failed to list IPFS pins: {str(e)}",
                details={
                    "operation": "ipfs_list_pins",
                    "api_url": self.api_url,
                    "error_type": type(e).__name__
                }
            )
        except Exception as e:
            raise StorageError(
                message=f"Failed to list IPFS pins: {str(e)}",
                details={
                    "operation": "ipfs_list_pins",
                    "api_url": self.api_url,
                    "error_type": type(e).__name__
                }
            )

    async def pin_to_pinata(self, cid: str, name: Optional[str] = None) -> Dict[str, Any]:
        if not self.pinata_api_key or not self.pinata_secret_api_key:
            raise StorageError(
                message="Pinata API keys not configured",
                details={
                    "operation": "pinata_pin",
                    "cid": cid,
                    "validation_failed": "missing_api_keys"
                }
            )
        if not cid:
            raise StorageError(
                message="CID cannot be empty",
                details={"operation": "pinata_pin", "validation_failed": "empty_cid"}
            )

        headers = {
            "pinata_api_key": self.pinata_api_key,
            "pinata_secret_api_key": self.pinata_secret_api_key,
        }
        body = {"hashToPin": cid}
        if name:
            body["pinataMetadata"] = {"name": name}

        try:
            client = await self._get_client()
            response = await client.post(
                "https://api.pinata.cloud/pinning/pinByHash",
                headers=headers,
                json=body,
            )
            response.raise_for_status()
            return response.json()
        except httpx.HTTPError as e:
            raise StorageError(
                message=f"Failed to pin to Pinata: {str(e)}",
                details={
                    "operation": "pinata_pin",
                    "cid": cid,
                    "name": name,
                    "error_type": type(e).__name__
                }
            )
        except Exception as e:
            raise StorageError(
                message=f"Failed to pin to Pinata: {str(e)}",
                details={
                    "operation": "pinata_pin",
                    "cid": cid,
                    "name": name,
                    "error_type": type(e).__name__
                }
            )

    def get_gateway_url(self, cid: str) -> str:
        return f"{self.gateway_url}{cid}"

    async def upload_file(
        self,
        file_path: str,
        pin: bool = True,
    ) -> Dict[str, Any]:
        if not file_path:
            raise StorageError(
                message="File path cannot be empty",
                details={"operation": "ipfs_upload_file", "validation_failed": "empty_file_path"}
            )
        try:
            with open(file_path, "rb") as f:
                file_content = f.read()
                filename = file_path.split("/")[-1]
                files = {"file": (filename, file_content)}
                params = {"pin": str(pin).lower()}

                client = await self._get_client()
                response = await client.post(
                    f"{self.api_url}/api/v0/add",
                    files=files,
                    params=params,
                    timeout=max(self._timeout, 120),
                )
                response.raise_for_status()
                return response.json()
        except FileNotFoundError as e:
            raise StorageError(
                message=f"File not found: {file_path}",
                details={
                    "operation": "ipfs_upload_file",
                    "file_path": file_path,
                    "error_type": "file_not_found"
                }
            )
        except httpx.TimeoutException as e:
            raise StorageError(
                message=f"IPFS upload timeout after 120s: {str(e)}",
                details={
                    "operation": "ipfs_upload_file",
                    "file_path": file_path,
                    "file_size": len(file_content) if 'file_content' in locals() else "unknown",
                    "timeout": max(self._timeout, 120),
                    "error_type": "timeout"
                }
            )
        except httpx.HTTPError as e:
            raise StorageError(
                message=f"Failed to upload file to IPFS: {str(e)}",
                details={
                    "operation": "ipfs_upload_file",
                    "file_path": file_path,
                    "error_type": type(e).__name__
                }
            )
        except Exception as e:
            raise StorageError(
                message=f"Failed to upload file to IPFS: {str(e)}",
                details={
                    "operation": "ipfs_upload_file",
                    "file_path": file_path,
                    "error_type": type(e).__name__
                }
            )

    async def dag_put(self, data: Dict[str, Any]) -> Dict[str, Any]:
        if not data:
            raise StorageError(
                message="DAG data cannot be empty",
                details={"operation": "ipfs_dag_put", "validation_failed": "empty_data"}
            )
        try:
            client = await self._get_client()
            response = await client.post(
                f"{self.api_url}/api/v0/dag/put",
                files={"": json.dumps(data)},
                params={"store-codec": "dag-json", "input-codec": "dag-json"},
            )
            response.raise_for_status()
            return response.json()
        except httpx.HTTPError as e:
            raise StorageError(
                message=f"Failed to put DAG to IPFS: {str(e)}",
                details={
                    "operation": "ipfs_dag_put",
                    "api_url": self.api_url,
                    "error_type": type(e).__name__
                }
            )
        except Exception as e:
            raise StorageError(
                message=f"Failed to put DAG to IPFS: {str(e)}",
                details={
                    "operation": "ipfs_dag_put",
                    "api_url": self.api_url,
                    "error_type": type(e).__name__
                }
            )

    async def dag_get(self, cid: str) -> Dict[str, Any]:
        if not cid:
            raise StorageError(
                message="CID cannot be empty",
                details={"operation": "ipfs_dag_get", "validation_failed": "empty_cid"}
            )
        try:
            client = await self._get_client()
            response = await client.post(
                f"{self.api_url}/api/v0/dag/get",
                params={"arg": cid},
            )
            response.raise_for_status()
            return response.json()
        except httpx.HTTPError as e:
            raise StorageError(
                message=f"Failed to get DAG from IPFS: {str(e)}",
                details={
                    "operation": "ipfs_dag_get",
                    "cid": cid,
                    "api_url": self.api_url,
                    "error_type": type(e).__name__
                }
            )
        except Exception as e:
            raise StorageError(
                message=f"Failed to get DAG from IPFS: {str(e)}",
                details={
                    "operation": "ipfs_dag_get",
                    "cid": cid,
                    "api_url": self.api_url,
                    "error_type": type(e).__name__
                }
            )

    async def get_stats(self) -> Dict[str, Any]:
        if self._client and not self._client.is_closed:
            return {
                "api_url": self.api_url,
                "gateway_url": self.gateway_url,
                "timeout": self._timeout,
                "max_connections": self._max_connections,
                "max_keepalive_connections": self._max_keepalive_connections,
                "keepalive_expiry": self._keepalive_expiry,
                "client_active": True,
            }
        return {
            "api_url": self.api_url,
            "gateway_url": self.gateway_url,
            "timeout": self._timeout,
            "max_connections": self._max_connections,
            "max_keepalive_connections": self._max_keepalive_connections,
            "keepalive_expiry": self._keepalive_expiry,
            "client_active": False,
        }
