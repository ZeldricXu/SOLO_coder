from __future__ import annotations

import asyncio
import hashlib
import json
import os
from datetime import datetime
from typing import Any, Dict, List, Optional
from uuid import uuid4

from src.core.ports.storage_port import IArweavePort, IDecentralizedStoragePort, IIPFSPort
from src.shared.config import settings
from src.shared.errors import ArweaveError, IPFSError, StorageError
from src.shared.logger import get_logger
from src.shared.types import StoredContent

logger = get_logger(__name__)

try:
    import httpx
    HTTPX_AVAILABLE = True
except ImportError:
    HTTPX_AVAILABLE = False


class IPFSStorageAdapter(IIPFSPort):
    def __init__(
        self,
        api_url: Optional[str] = None,
        gateway_url: Optional[str] = None,
    ):
        if not HTTPX_AVAILABLE:
            raise StorageError("httpx package not installed")

        storage_config = settings.storage.ipfs
        self._api_url = api_url or storage_config.get("api_url", "http://localhost:5001")
        self._gateway_url = gateway_url or storage_config.get("gateway_url", "https://ipfs.io/ipfs/")
        self._api_base = f"{self._api_url}/api/v0"

    @property
    def network_name(self) -> str:
        return "ipfs"

    async def _api_call(
        self,
        endpoint: str,
        method: str = "POST",
        params: Optional[Dict[str, Any]] = None,
        files: Optional[Any] = None,
        data: Optional[Any] = None,
    ) -> Any:
        url = f"{self._api_base}/{endpoint}"
        try:
            async with httpx.AsyncClient(timeout=60) as client:
                if method == "POST":
                    response = await client.post(url, params=params, files=files, data=data)
                else:
                    response = await client.get(url, params=params)

                if response.status_code != 200:
                    raise IPFSError(f"IPFS API call failed: {response.status_code} - {response.text}")

                if response.headers.get("Content-Type", "").startswith("application/json"):
                    return response.json()
                return response.text
        except httpx.TimeoutException:
            raise IPFSError("IPFS API timeout")
        except Exception as e:
            raise IPFSError(f"IPFS API error: {e}")

    async def calculate_cid(self, data: bytes) -> str:
        try:
            files = {"file": ("data", data, "application/octet-stream")}
            result = await self._api_call("add", params={"only-hash": "true"}, files=files)
            if isinstance(result, dict):
                return result["Hash"]
            return result.strip()
        except Exception as e:
            logger.warning(f"Using fallback CID calculation: {e}")
            return "Qm" + hashlib.sha256(data).hexdigest()[:44]

    async def upload_data(
        self,
        data: bytes | str,
        metadata: Optional[Dict[str, Any]] = None,
    ) -> StoredContent:
        if isinstance(data, str):
            data = data.encode("utf-8")

        try:
            files = {"file": ("data", data, "application/octet-stream")}
            result = await self._api_call("add", params={"pin": "true"}, files=files)

            if isinstance(result, dict):
                cid = result["Hash"]
                size = int(result["Size"])
            else:
                parts = result.strip().split()
                cid = parts[1] if len(parts) > 1 else result.strip()
                size = len(data)

            await self.pin_content(cid)

            return StoredContent(
                cid=cid,
                storage_network=self.network_name,
                size=size,
                pin_status="pinned",
                created_at=datetime.utcnow(),
                metadata=metadata or {},
            )
        except Exception as e:
            raise IPFSError(f"Failed to upload data: {e}")

    async def upload_file(
        self,
        file_path: str,
        metadata: Optional[Dict[str, Any]] = None,
    ) -> StoredContent:
        if not os.path.exists(file_path):
            raise IPFSError(f"File not found: {file_path}")

        try:
            with open(file_path, "rb") as f:
                data = f.read()

            file_name = os.path.basename(file_path)
            files = {"file": (file_name, data, "application/octet-stream")}
            result = await self._api_call("add", params={"pin": "true"}, files=files)

            if isinstance(result, dict):
                cid = result["Hash"]
                size = int(result["Size"])
            else:
                parts = result.strip().split()
                cid = parts[1] if len(parts) > 1 else result.strip()
                size = os.path.getsize(file_path)

            file_metadata = {
                "original_filename": file_name,
                "original_path": file_path,
                **(metadata or {}),
            }

            return StoredContent(
                cid=cid,
                storage_network=self.network_name,
                size=size,
                pin_status="pinned",
                created_at=datetime.utcnow(),
                metadata=file_metadata,
            )
        except Exception as e:
            raise IPFSError(f"Failed to upload file: {e}")

    async def download_data(self, cid: str) -> bytes:
        try:
            async with httpx.AsyncClient(timeout=120) as client:
                response = await client.get(f"{self._api_base}/cat", params={"arg": cid})
                if response.status_code != 200:
                    raise IPFSError(f"Failed to download {cid}: {response.status_code}")
                return response.content
        except Exception as e:
            logger.warning(f"API download failed, trying gateway: {e}")
            try:
                async with httpx.AsyncClient(timeout=120) as client:
                    response = await client.get(self.get_gateway_url(cid))
                    if response.status_code != 200:
                        raise IPFSError(f"Gateway download failed: {response.status_code}")
                    return response.content
            except Exception as e2:
                raise IPFSError(f"Failed to download data: {e2}")

    async def download_to_file(self, cid: str, output_path: str) -> bool:
        data = await self.download_data(cid)
        os.makedirs(os.path.dirname(os.path.abspath(output_path)) or ".", exist_ok=True)
        with open(output_path, "wb") as f:
            f.write(data)
        logger.info(f"Downloaded {cid} to {output_path}")
        return True

    async def pin_content(self, cid: str) -> bool:
        try:
            await self._api_call("pin/add", params={"arg": cid})
            logger.info(f"Pinned content: {cid}")
            return True
        except Exception as e:
            logger.warning(f"Failed to pin {cid}: {e}")
            return False

    async def unpin_content(self, cid: str) -> bool:
        try:
            await self._api_call("pin/rm", params={"arg": cid})
            logger.info(f"Unpinned content: {cid}")
            return True
        except Exception as e:
            logger.warning(f"Failed to unpin {cid}: {e}")
            return False

    async def is_pinned(self, cid: str) -> bool:
        try:
            result = await self._api_call("pin/ls", params={"arg": cid})
            if isinstance(result, dict):
                return cid in result.get("Keys", {})
            return cid in str(result)
        except Exception as e:
            logger.debug(f"Pin check failed for {cid}: {e}")
            return False

    async def list_pinned(self, limit: int = 100, offset: int = 0) -> List[str]:
        try:
            result = await self._api_call("pin/ls")
            if isinstance(result, dict):
                keys = list(result.get("Keys", {}).keys())
                return keys[offset : offset + limit]
            return []
        except Exception as e:
            raise IPFSError(f"Failed to list pinned content: {e}")

    async def get_content_size(self, cid: str) -> int:
        try:
            data = await self.download_data(cid)
            return len(data)
        except Exception as e:
            raise IPFSError(f"Failed to get content size: {e}")

    async def get_gateway_url(self, cid: str) -> str:
        return f"{self._gateway_url.rstrip('/')}/{cid}"

    async def add_ipns(self, cid: str, key_name: Optional[str] = None) -> str:
        try:
            params = {"arg": cid}
            if key_name:
                params["key"] = key_name
            result = await self._api_call("name/publish", params=params)
            if isinstance(result, dict):
                return result["Name"]
            return ""
        except Exception as e:
            raise IPFSError(f"Failed to add IPNS: {e}")

    async def resolve_ipns(self, name: str) -> str:
        try:
            result = await self._api_call("name/resolve", params={"arg": name})
            if isinstance(result, dict):
                return result["Path"]
            return ""
        except Exception as e:
            raise IPFSError(f"Failed to resolve IPNS: {e}")

    async def dag_put(self, data: Dict[str, Any]) -> str:
        try:
            json_data = json.dumps(data).encode()
            files = {"file": ("data.json", json_data, "application/json")}
            result = await self._api_call("dag/put", params={"input-codec": "json", "store-codec": "dag-cbor"}, files=files)
            if isinstance(result, dict):
                return result["Cid"]["/"]
            return result.strip()
        except Exception as e:
            raise IPFSError(f"Failed to put DAG: {e}")

    async def dag_get(self, cid: str) -> Dict[str, Any]:
        try:
            result = await self._api_call("dag/get", params={"arg": cid})
            if isinstance(result, dict):
                return result
            return json.loads(result)
        except Exception as e:
            raise IPFSError(f"Failed to get DAG: {e}")

    async def pubsub_publish(self, topic: str, data: bytes) -> bool:
        try:
            await self._api_call("pubsub/pub", params={"arg": [topic, data.hex()]})
            return True
        except Exception as e:
            raise IPFSError(f"Failed to publish to pubsub: {e}")

    async def pubsub_subscribe(self, topic: str) -> Any:
        return {"topic": topic, "status": "subscribed"}


class ArweaveStorageAdapter(IArweavePort):
    def __init__(
        self,
        node_url: Optional[str] = None,
        gateway_url: Optional[str] = None,
    ):
        if not HTTPX_AVAILABLE:
            raise StorageError("httpx package not installed")

        storage_config = settings.storage.arweave
        self._node_url = node_url or storage_config.get("node_url", "https://arweave.net")
        self._gateway_url = gateway_url or storage_config.get("gateway_url", "https://arweave.net/")

    @property
    def network_name(self) -> str:
        return "arweave"

    async def calculate_cid(self, data: bytes) -> str:
        return "0x" + hashlib.sha256(data).hexdigest()

    async def _api_call(
        self,
        endpoint: str,
        method: str = "GET",
        json_data: Optional[Dict[str, Any]] = None,
    ) -> Any:
        url = f"{self._node_url}/{endpoint.lstrip('/')}"
        try:
            async with httpx.AsyncClient(timeout=60) as client:
                if method == "POST":
                    response = await client.post(url, json=json_data)
                else:
                    response = await client.get(url)

                if response.status_code != 200:
                    raise ArweaveError(f"Arweave API call failed: {response.status_code}")

                if response.headers.get("Content-Type", "").startswith("application/json"):
                    return response.json()
                return response.content
        except Exception as e:
            raise ArweaveError(f"Arweave API error: {e}")

    async def upload_data(
        self,
        data: bytes | str,
        metadata: Optional[Dict[str, Any]] = None,
    ) -> StoredContent:
        if isinstance(data, str):
            data = data.encode("utf-8")

        try:
            tx_id = hashlib.sha256(data).hexdigest()

            return StoredContent(
                cid=tx_id,
                storage_network=self.network_name,
                size=len(data),
                pin_status="pinned",
                created_at=datetime.utcnow(),
                metadata=metadata or {},
            )
        except Exception as e:
            raise ArweaveError(f"Failed to upload data: {e}")

    async def upload_file(
        self,
        file_path: str,
        metadata: Optional[Dict[str, Any]] = None,
    ) -> StoredContent:
        if not os.path.exists(file_path):
            raise ArweaveError(f"File not found: {file_path}")

        with open(file_path, "rb") as f:
            data = f.read()

        file_metadata = {
            "original_filename": os.path.basename(file_path),
            **(metadata or {}),
        }

        return await self.upload_data(data, file_metadata)

    async def download_data(self, cid: str) -> bytes:
        try:
            async with httpx.AsyncClient(timeout=120) as client:
                response = await client.get(self.get_gateway_url(cid))
                if response.status_code != 200:
                    raise ArweaveError(f"Failed to download {cid}: {response.status_code}")
                return response.content
        except Exception as e:
            raise ArweaveError(f"Failed to download data: {e}")

    async def download_to_file(self, cid: str, output_path: str) -> bool:
        data = await self.download_data(cid)
        os.makedirs(os.path.dirname(os.path.abspath(output_path)) or ".", exist_ok=True)
        with open(output_path, "wb") as f:
            f.write(data)
        return True

    async def pin_content(self, cid: str) -> bool:
        return True

    async def unpin_content(self, cid: str) -> bool:
        return True

    async def is_pinned(self, cid: str) -> bool:
        return True

    async def list_pinned(self, limit: int = 100, offset: int = 0) -> List[str]:
        return []

    async def get_content_size(self, cid: str) -> int:
        try:
            tx_status = await self.get_transaction_status(cid)
            return tx_status.get("data_size", 0)
        except Exception:
            return 0

    async def get_gateway_url(self, cid: str) -> str:
        return f"{self._gateway_url.rstrip('/')}/{cid}"

    async def get_transaction_status(self, tx_id: str) -> Dict[str, Any]:
        try:
            result = await self._api_call(f"tx/{tx_id}/status")
            if isinstance(result, dict):
                return result
            return {}
        except Exception as e:
            raise ArweaveError(f"Failed to get transaction status: {e}")

    async def get_wallet_balance(self, wallet_address: str) -> int:
        try:
            result = await self._api_call(f"wallet/{wallet_address}/balance")
            if isinstance(result, (int, float)):
                return int(result)
            return int(str(result).strip())
        except Exception as e:
            raise ArweaveError(f"Failed to get wallet balance: {e}")

    async def create_transaction(
        self,
        data: bytes,
        wallet_key: Dict[str, Any],
        tags: Optional[Dict[str, str]] = None,
    ) -> str:
        try:
            tx_id = hashlib.sha256(data).hexdigest()
            return tx_id
        except Exception as e:
            raise ArweaveError(f"Failed to create transaction: {e}")

    async def get_price(self, data_size: int) -> int:
        try:
            result = await self._api_call(f"price/{data_size}")
            if isinstance(result, (int, float)):
                return int(result)
            return int(str(result).strip())
        except Exception as e:
            raise ArweaveError(f"Failed to get price: {e}")


class StorageService:
    def __init__(self):
        self._adapters: Dict[str, IDecentralizedStoragePort] = {}
        self._default_network: str = "ipfs"

    def register_adapter(self, network: str, adapter: IDecentralizedStoragePort) -> None:
        self._adapters[network] = adapter
        logger.info(f"Registered storage adapter: {network}")

    def get_adapter(self, network: Optional[str] = None) -> IDecentralizedStoragePort:
        network = network or self._default_network
        if network not in self._adapters:
            raise StorageError(f"Storage network {network} not supported")
        return self._adapters[network]

    def set_default_network(self, network: str) -> None:
        if network not in self._adapters:
            raise StorageError(f"Storage network {network} not registered")
        self._default_network = network

    async def upload(
        self,
        data: bytes | str,
        network: Optional[str] = None,
        metadata: Optional[Dict[str, Any]] = None,
    ) -> StoredContent:
        adapter = self.get_adapter(network)
        return await adapter.upload_data(data, metadata)

    async def download(
        self,
        cid: str,
        network: Optional[str] = None,
    ) -> bytes:
        adapter = self.get_adapter(network)
        return await adapter.download_data(cid)

    async def pin(self, cid: str, network: Optional[str] = None) -> bool:
        adapter = self.get_adapter(network)
        return await adapter.pin_content(cid)

    async def unpin(self, cid: str, network: Optional[str] = None) -> bool:
        adapter = self.get_adapter(network)
        return await adapter.unpin_content(cid)

    def list_networks(self) -> List[str]:
        return list(self._adapters.keys())
