import asyncio
import hashlib
import json
import os
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional, Tuple, AsyncIterator

from aiohttp import ClientSession, ClientTimeout, FormData

from ..config import get_settings
from ..db import async_session, StoredContent
from ..utils import (
    get_logger,
    generate_id,
    StorageError,
    ValidationError,
    NotFoundError,
)

logger = get_logger(__name__)


@dataclass
class StoreContentRequest:
    data: bytes
    content_type: str = "application/octet-stream"
    name: Optional[str] = None
    description: Optional[str] = None
    storage_network: str = "ipfs"
    pin: bool = True


@dataclass
class StoredContentResult:
    content_id: str
    cid: str
    storage_network: str
    access_url: str
    size_bytes: int


class StorageModule:
    SUPPORTED_NETWORKS = ["ipfs", "arweave", "s3"]

    def __init__(self):
        self.settings = get_settings()
        self._initialized = False
        self._session: Optional[ClientSession] = None
        self._ipfs_session: Optional[ClientSession] = None

    async def initialize(self) -> None:
        if self._initialized:
            return

        logger.info("Initializing storage module")
        self._session = ClientSession()
        self._ipfs_session = ClientSession(timeout=ClientTimeout(total=60))
        self._initialized = True
        logger.info("Storage module initialized")

    async def shutdown(self) -> None:
        if not self._initialized:
            return

        logger.info("Shutting down storage module")
        if self._session:
            await self._session.close()
        if self._ipfs_session:
            await self._ipfs_session.close()
        self._session = None
        self._ipfs_session = None
        self._initialized = False
        logger.info("Storage module shutdown complete")

    async def store_content(self, request: StoreContentRequest) -> StoredContentResult:
        if request.storage_network not in self.SUPPORTED_NETWORKS:
            raise ValidationError(
                f"Unsupported storage network: {request.storage_network}",
                details={"supported": self.SUPPORTED_NETWORKS},
            )

        if not request.data:
            raise ValidationError("No data provided")

        content_hash = hashlib.sha256(request.data).hexdigest()

        if request.storage_network == "ipfs":
            cid = await self._store_to_ipfs(request.data, request.name)
        elif request.storage_network == "arweave":
            cid = await self._store_to_arweave(request.data, request.content_type)
        elif request.storage_network == "s3":
            cid = await self._store_to_s3(request.data, request.name)
        else:
            raise StorageError(f"Unsupported storage network")

        access_url = self._get_access_url(cid, request.storage_network)
        content_id = generate_id("content")

        async with async_session() as session:
            stored = StoredContent(
                content_id=content_id,
                cid=cid,
                storage_network=request.storage_network,
                content_hash=f"0x{content_hash}",
                content_type=request.content_type,
                size_bytes=len(request.data),
                name=request.name,
                description=request.description,
                is_pinned=request.pin,
                access_url=access_url,
            )
            session.add(stored)
            await session.commit()
            await session.refresh(stored)

        logger.info(f"Stored content {content_id} -> {cid} on {request.storage_network}")

        return StoredContentResult(
            content_id=content_id,
            cid=cid,
            storage_network=request.storage_network,
            access_url=access_url,
            size_bytes=len(request.data),
        )

    async def _store_to_ipfs(self, data: bytes, name: Optional[str] = None) -> str:
        storage_settings = self.settings.storage

        try:
            form = FormData()
            form.add_field("file", data, filename=name or "file")

            async with self._ipfs_session.post(
                f"{storage_settings.ipfs_rpc}/api/v0/add",
                data=form,
            ) as response:
                if response.status != 200:
                    error_text = await response.text()
                    raise StorageError(f"IPFS add failed: {error_text}")

                result = await response.json()
                cid = result.get("Hash")

                if not cid:
                    raise StorageError("IPFS did not return a CID")

                return cid

        except Exception as e:
            logger.error(f"Failed to store to IPFS: {e}")
            raise StorageError(f"Failed to store to IPFS: {e}")

    async def _store_to_arweave(self, data: bytes, content_type: str) -> str:
        try:
            cid = hashlib.sha256(data).hexdigest()
            logger.info(f"Simulating Arweave storage: {cid}")
            return cid
        except Exception as e:
            logger.error(f"Failed to store to Arweave: {e}")
            raise StorageError(f"Failed to store to Arweave: {e}")

    async def _store_to_s3(self, data: bytes, name: Optional[str]) -> str:
        try:
            cid = hashlib.sha256(data).hexdigest()
            logger.info(f"Simulating S3 storage: {cid}")
            return cid
        except Exception as e:
            logger.error(f"Failed to store to S3: {e}")
            raise StorageError(f"Failed to store to S3: {e}")

    async def retrieve_content(self, cid: str, storage_network: str = "ipfs") -> bytes:
        if storage_network == "ipfs":
            return await self._retrieve_from_ipfs(cid)
        elif storage_network == "arweave":
            return await self._retrieve_from_arweave(cid)
        else:
            raise StorageError(f"Unsupported storage network: {storage_network}")

    async def _retrieve_from_ipfs(self, cid: str) -> bytes:
        storage_settings = self.settings.storage

        try:
            async with self._ipfs_session.post(
                f"{storage_settings.ipfs_rpc}/api/v0/cat",
                params={"arg": cid},
            ) as response:
                if response.status != 200:
                    raise StorageError(f"IPFS cat failed: {response.status}")
                return await response.read()

        except Exception as e:
            logger.error(f"Failed to retrieve from IPFS: {e}")
            try:
                async with self._session.get(f"{storage_settings.ipfs_gateway}/{cid}") as response:
                    if response.status == 200:
                        return await response.read()
            except Exception as e2:
                logger.error(f"Failed to retrieve from IPFS gateway: {e2}")
            raise StorageError(f"Failed to retrieve from IPFS: {e}")

    async def _retrieve_from_arweave(self, cid: str) -> bytes:
        storage_settings = self.settings.storage

        try:
            async with self._session.get(f"{storage_settings.arweave_gateway}/{cid}") as response:
                if response.status != 200:
                    raise StorageError(f"Arweave retrieval failed: {response.status}")
                return await response.read()

        except Exception as e:
            logger.error(f"Failed to retrieve from Arweave: {e}")
            raise StorageError(f"Failed to retrieve from Arweave: {e}")

    def _get_access_url(self, cid: str, storage_network: str) -> str:
        storage_settings = self.settings.storage

        if storage_network == "ipfs":
            return f"{storage_settings.ipfs_gateway}/{cid}"
        elif storage_network == "arweave":
            return f"{storage_settings.arweave_gateway}/{cid}"
        else:
            return cid

    async def pin_content(self, cid: str, storage_network: str = "ipfs") -> bool:
        if storage_network == "ipfs":
            return await self._pin_ipfs(cid)
        else:
            raise StorageError(f"Pin not supported for {storage_network}")

    async def _pin_ipfs(self, cid: str) -> bool:
        storage_settings = self.settings.storage

        try:
            async with self._ipfs_session.post(
                f"{storage_settings.ipfs_rpc}/api/v0/pin/add",
                params={"arg": cid},
            ) as response:
                success = response.status == 200
                if success:
                    async with async_session() as session:
                        stored = await session.get(StoredContent, {"cid": cid, "storage_network": "ipfs"})
                        if stored:
                            stored.is_pinned = True
                            await session.commit()
                return success
        except Exception as e:
            logger.error(f"Failed to pin IPFS content: {e}")
            return False

    async def unpin_content(self, cid: str, storage_network: str = "ipfs") -> bool:
        if storage_network == "ipfs":
            return await self._unpin_ipfs(cid)
        else:
            raise StorageError(f"Unpin not supported for {storage_network}")

    async def _unpin_ipfs(self, cid: str) -> bool:
        storage_settings = self.settings.storage

        try:
            async with self._ipfs_session.post(
                f"{storage_settings.ipfs_rpc}/api/v0/pin/rm",
                params={"arg": cid},
            ) as response:
                success = response.status == 200
                if success:
                    async with async_session() as session:
                        stored = await session.get(StoredContent, {"cid": cid, "storage_network": "ipfs"})
                        if stored:
                            stored.is_pinned = False
                            await session.commit()
                return success
        except Exception as e:
            logger.error(f"Failed to unpin IPFS content: {e}")
            return False

    async def get_content(self, content_id: str) -> Optional[Dict[str, Any]]:
        async with async_session() as session:
            content = await session.get(StoredContent, {"content_id": content_id})
            if not content:
                return None

            return {
                "content_id": content.content_id,
                "cid": content.cid,
                "storage_network": content.storage_network,
                "content_hash": content.content_hash,
                "content_type": content.content_type,
                "size_bytes": content.size_bytes,
                "name": content.name,
                "description": content.description,
                "is_pinned": content.is_pinned,
                "access_url": content.access_url,
                "created_at": content.created_at.isoformat() if content.created_at else None,
            }

    async def list_content(
        self,
        storage_network: Optional[str] = None,
        only_pinned: bool = False,
        offset: int = 0,
        limit: int = 50,
    ) -> Dict[str, Any]:
        from sqlalchemy import select

        async with async_session() as session:
            query = select(StoredContent)
            if storage_network:
                query = query.where(StoredContent.storage_network == storage_network)
            if only_pinned:
                query = query.where(StoredContent.is_pinned == True)

            query = query.order_by(StoredContent.created_at.desc()).offset(offset).limit(limit)
            result = await session.execute(query)
            contents = result.scalars().all()

            return {
                "contents": [
                    {
                        "content_id": c.content_id,
                        "cid": c.cid,
                        "storage_network": c.storage_network,
                        "name": c.name,
                        "size_bytes": c.size_bytes,
                        "content_type": c.content_type,
                        "is_pinned": c.is_pinned,
                        "access_url": c.access_url,
                    }
                    for c in contents
                ],
                "total": len(contents),
                "offset": offset,
                "limit": limit,
            }

    async def delete_content(self, content_id: str) -> None:
        async with async_session() as session:
            content = await session.get(StoredContent, {"content_id": content_id})
            if not content:
                raise NotFoundError(f"Content {content_id} not found")

            await session.delete(content)
            await session.commit()

            logger.info(f"Deleted content {content_id}")

    async def store_json(self, data: Dict[str, Any], name: Optional[str] = None) -> StoredContentResult:
        json_bytes = json.dumps(data, ensure_ascii=False).encode("utf-8")
        return await self.store_content(
            StoreContentRequest(
                data=json_bytes,
                content_type="application/json",
                name=name or "data.json",
            )
        )

    async def store_file(self, filepath: str, storage_network: str = "ipfs") -> StoredContentResult:
        if not os.path.exists(filepath):
            raise ValidationError(f"File not found: {filepath}")

        with open(filepath, "rb") as f:
            data = f.read()

        filename = os.path.basename(filepath)
        import mimetypes
        content_type, _ = mimetypes.guess_type(filepath)

        return await self.store_content(
            StoreContentRequest(
                data=data,
                content_type=content_type or "application/octet-stream",
                name=filename,
                storage_network=storage_network,
            )
        )

    async def get_ipfs_pins(self) -> List[Dict[str, Any]]:
        storage_settings = self.settings.storage

        try:
            async with self._ipfs_session.post(
                f"{storage_settings.ipfs_rpc}/api/v0/pin/ls",
            ) as response:
                if response.status != 200:
                    return []
                result = await response.json()
                pins = result.get("Keys", {})
                return [
                    {
                        "cid": cid,
                        "type": pin_info.get("Type"),
                    }
                    for cid, pin_info in pins.items()
                ]
        except Exception as e:
            logger.error(f"Failed to list IPFS pins: {e}")
            return []

    async def get_ipfs_peer_id(self) -> Optional[Dict[str, Any]]:
        storage_settings = self.settings.storage

        try:
            async with self._ipfs_session.post(
                f"{storage_settings.ipfs_rpc}/api/v0/id",
            ) as response:
                if response.status == 200:
                    return await response.json()
        except Exception as e:
            logger.error(f"Failed to get IPFS peer ID: {e}")
        return None

    async def check_health(self, storage_network: str = "ipfs") -> Dict[str, Any]:
        if storage_network == "ipfs":
            peer_id = await self.get_ipfs_peer_id()
            return {
                "network": "ipfs",
                "connected": peer_id is not None,
                "peer_id": peer_id.get("ID") if peer_id else None,
            }
        else:
            return {
                "network": storage_network,
                "connected": False,
                "error": f"Health check not implemented for {storage_network}",
            }

    async def batch_store(self, requests: List[StoreContentRequest]) -> List[StoredContentResult]:
        results = []
        for request in requests:
            try:
                result = await self.store_content(request)
                results.append(result)
            except Exception as e:
                logger.error(f"Failed to store content: {e}")
        return results

    async def batch_retrieve(self, cids: List[str], storage_network: str = "ipfs") -> Dict[str, bytes]:
        results = {}
        for cid in cids:
            try:
                data = await self.retrieve_content(cid, storage_network)
                results[cid] = data
            except Exception as e:
                logger.error(f"Failed to retrieve {cid}: {e}")
        return results


_storage_module: Optional[StorageModule] = None


def get_storage_module() -> StorageModule:
    global _storage_module
    if _storage_module is None:
        _storage_module = StorageModule()
    return _storage_module
