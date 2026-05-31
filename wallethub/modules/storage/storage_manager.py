from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional, Union
from datetime import datetime, timezone
import json
import asyncio

from wallethub.core import StorageNetwork, StorageError
from wallethub.utils import generate_id, sha256_hash

from .ipfs_client import IPFSClient
from .arweave_client import ArweaveClient


@dataclass
class StoredContent:
    content_id: str
    network: StorageNetwork
    cid: str
    content_hash: str
    content_type: str
    size: int
    pinned: bool
    url: str
    metadata: Dict[str, Any] = field(default_factory=dict)
    created_at: datetime = field(default_factory=lambda: datetime.now(timezone.utc))


class StorageManager:
    def __init__(
        self,
        ipfs_timeout: int = 60,
        arweave_timeout: int = 60,
        max_connections: int = 100,
    ):
        self._ipfs_client = None
        self._arweave_client = None
        self._content_cache: Dict[str, StoredContent] = {}
        self._ipfs_timeout = ipfs_timeout
        self._arweave_timeout = arweave_timeout
        self._max_connections = max_connections
        self._lock = asyncio.Lock()

    @property
    def ipfs(self) -> IPFSClient:
        if self._ipfs_client is None:
            self._ipfs_client = IPFSClient(
                timeout=self._ipfs_timeout,
                max_connections=self._max_connections,
            )
        return self._ipfs_client

    @property
    def arweave(self) -> ArweaveClient:
        if self._arweave_client is None:
            self._arweave_client = ArweaveClient(
                timeout=self._arweave_timeout,
                max_connections=self._max_connections,
            )
        return self._arweave_client

    async def close(self) -> None:
        async with self._lock:
            if self._ipfs_client:
                await self._ipfs_client.close()
                self._ipfs_client = None
            if self._arweave_client:
                await self._arweave_client.close()
                self._arweave_client = None

    async def __aenter__(self) -> "StorageManager":
        return self

    async def __aexit__(self, exc_type, exc_val, exc_tb) -> None:
        await self.close()

    async def store(
        self,
        data: Union[str, bytes, Dict[str, Any]],
        network: StorageNetwork = StorageNetwork.IPFS,
        pin: bool = True,
        metadata: Optional[Dict[str, Any]] = None,
    ) -> StoredContent:
        content_bytes = self._to_bytes(data)
        content_hash = sha256_hash(content_bytes)
        content_type = self._detect_content_type(data)
        size = len(content_bytes)

        try:
            if network == StorageNetwork.IPFS:
                result = await self.ipfs.add(data, pin=pin)
                cid = result.get("Hash", "")
                url = self.ipfs.get_gateway_url(cid)
            elif network == StorageNetwork.ARWEAVE:
                cid = await self.arweave.upload_data(data, content_type)
                url = self.arweave.get_gateway_url(cid)
            else:
                raise StorageError(
                    message=f"Unsupported storage network: {network}",
                    details={
                        "operation": "storage_store",
                        "network": network,
                        "supported_networks": [StorageNetwork.IPFS, StorageNetwork.ARWEAVE],
                        "error": "unsupported_network"
                    }
                )

            content = StoredContent(
                content_id=generate_id("store"),
                network=network,
                cid=cid,
                content_hash=content_hash,
                content_type=content_type,
                size=size,
                pinned=pin,
                url=url,
                metadata=metadata or {},
            )

            async with self._lock:
                self._content_cache[content.content_id] = content

            return content
        except StorageError:
            raise
        except Exception as e:
            raise StorageError(
                message=f"Failed to store content: {str(e)}",
                details={
                    "operation": "storage_store",
                    "network": network,
                    "content_size": size,
                    "content_type": content_type,
                    "pin": pin,
                    "error_type": type(e).__name__
                }
            )

    async def retrieve(self, cid: str, network: StorageNetwork = StorageNetwork.IPFS) -> bytes:
        if not cid:
            raise StorageError(
                message="CID cannot be empty",
                details={"operation": "storage_retrieve", "validation_failed": "empty_cid"}
            )
        try:
            if network == StorageNetwork.IPFS:
                return await self.ipfs.get(cid)
            elif network == StorageNetwork.ARWEAVE:
                return await self.arweave.get_data(cid)
            else:
                raise StorageError(
                    message=f"Unsupported storage network: {network}",
                    details={
                        "operation": "storage_retrieve",
                        "network": network,
                        "cid": cid,
                        "error": "unsupported_network"
                    }
                )
        except StorageError:
            raise
        except Exception as e:
            raise StorageError(
                message=f"Failed to retrieve content: {str(e)}",
                details={
                    "operation": "storage_retrieve",
                    "network": network,
                    "cid": cid,
                    "error_type": type(e).__name__
                }
            )

    async def retrieve_json(self, cid: str, network: StorageNetwork = StorageNetwork.IPFS) -> Dict[str, Any]:
        if not cid:
            raise StorageError(
                message="CID cannot be empty",
                details={"operation": "storage_retrieve_json", "validation_failed": "empty_cid"}
            )
        try:
            if network == StorageNetwork.IPFS:
                return await self.ipfs.get_json(cid)
            elif network == StorageNetwork.ARWEAVE:
                return await self.arweave.get_json(cid)
            else:
                raise StorageError(
                    message=f"Unsupported storage network: {network}",
                    details={
                        "operation": "storage_retrieve_json",
                        "network": network,
                        "cid": cid,
                        "error": "unsupported_network"
                    }
                )
        except StorageError:
            raise
        except Exception as e:
            raise StorageError(
                message=f"Failed to retrieve JSON content: {str(e)}",
                details={
                    "operation": "storage_retrieve_json",
                    "network": network,
                    "cid": cid,
                    "error_type": type(e).__name__
                }
            )

    async def pin(self, cid: str, network: StorageNetwork = StorageNetwork.IPFS) -> None:
        if not cid:
            raise StorageError(
                message="CID cannot be empty",
                details={"operation": "storage_pin", "validation_failed": "empty_cid"}
            )
        try:
            if network == StorageNetwork.IPFS:
                await self.ipfs.pin(cid)
            else:
                raise StorageError(
                    message=f"Pin not supported for network: {network}",
                    details={
                        "operation": "storage_pin",
                        "network": network,
                        "cid": cid,
                        "error": "pin_not_supported"
                    }
                )
        except StorageError:
            raise
        except Exception as e:
            raise StorageError(
                message=f"Failed to pin content: {str(e)}",
                details={
                    "operation": "storage_pin",
                    "network": network,
                    "cid": cid,
                    "error_type": type(e).__name__
                }
            )

    async def unpin(self, cid: str, network: StorageNetwork = StorageNetwork.IPFS) -> None:
        if not cid:
            raise StorageError(
                message="CID cannot be empty",
                details={"operation": "storage_unpin", "validation_failed": "empty_cid"}
            )
        try:
            if network == StorageNetwork.IPFS:
                await self.ipfs.unpin(cid)
            else:
                raise StorageError(
                    message=f"Unpin not supported for network: {network}",
                    details={
                        "operation": "storage_unpin",
                        "network": network,
                        "cid": cid,
                        "error": "unpin_not_supported"
                    }
                )
        except StorageError:
            raise
        except Exception as e:
            raise StorageError(
                message=f"Failed to unpin content: {str(e)}",
                details={
                    "operation": "storage_unpin",
                    "network": network,
                    "cid": cid,
                    "error_type": type(e).__name__
                }
            )

    def get_content(self, content_id: str) -> Optional[StoredContent]:
        if not content_id:
            return None
        return self._content_cache.get(content_id)

    def list_content(
        self,
        network: Optional[StorageNetwork] = None,
    ) -> List[StoredContent]:
        contents = list(self._content_cache.values())
        if network:
            contents = [c for c in contents if c.network == network]
        return contents

    def get_url(self, cid: str, network: StorageNetwork = StorageNetwork.IPFS) -> str:
        if not cid:
            raise StorageError(
                message="CID cannot be empty",
                details={"operation": "storage_get_url", "validation_failed": "empty_cid"}
            )
        if network == StorageNetwork.IPFS:
            return self.ipfs.get_gateway_url(cid)
        elif network == StorageNetwork.ARWEAVE:
            return self.arweave.get_gateway_url(cid)
        else:
            raise StorageError(
                message=f"Unsupported storage network: {network}",
                details={
                    "operation": "storage_get_url",
                    "network": network,
                    "cid": cid,
                    "error": "unsupported_network"
                }
            )

    async def store_ipns_resolve(self, name: str) -> str:
        if not name:
            raise StorageError(
                message="IPNS name cannot be empty",
                details={"operation": "storage_ipns_resolve", "validation_failed": "empty_name"}
            )
        try:
            client = await self.ipfs._get_client()
            response = await client.post(
                f"{self.ipfs.api_url}/api/v0/name/resolve",
                params={"arg": name},
                timeout=30,
            )
            response.raise_for_status()
            result = response.json()
            return result.get("Path", "")
        except StorageError:
            raise
        except Exception as e:
            raise StorageError(
                message=f"Failed to resolve IPNS name: {str(e)}",
                details={
                    "operation": "storage_ipns_resolve",
                    "name": name,
                    "error_type": type(e).__name__
                }
            )

    async def get_stats(self) -> Dict[str, Any]:
        ipfs_stats = await self.ipfs.get_stats() if self._ipfs_client else {"client_active": False}
        arweave_stats = await self.arweave.get_stats() if self._arweave_client else {"client_active": False}

        return {
            "cached_content_count": len(self._content_cache),
            "ipfs": ipfs_stats,
            "arweave": arweave_stats,
            "max_connections": self._max_connections,
        }

    @staticmethod
    def _to_bytes(data: Union[str, bytes, Dict[str, Any]]) -> bytes:
        if isinstance(data, bytes):
            return data
        if isinstance(data, dict):
            return json.dumps(data).encode()
        if isinstance(data, str):
            return data.encode()
        return str(data).encode()

    @staticmethod
    def _detect_content_type(data: Union[str, bytes, Dict[str, Any]]) -> str:
        if isinstance(data, dict):
            return "application/json"
        if isinstance(data, bytes):
            return "application/octet-stream"
        if isinstance(data, str):
            return "text/plain"
        return "application/octet-stream"
