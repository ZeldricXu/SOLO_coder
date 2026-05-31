import os
import shutil
import hashlib
import mimetypes
from datetime import datetime, timedelta
from typing import Any, BinaryIO, Dict, List, Optional, Tuple, Union
from pathlib import Path
from abc import ABC, abstractmethod

from config import settings
from core import NotFoundError, ValidationError


class StorageBackend(ABC):
    @abstractmethod
    async def save(self, path: str, data: Union[bytes, BinaryIO]) -> str:
        pass

    @abstractmethod
    async def load(self, path: str) -> bytes:
        pass

    @abstractmethod
    async def delete(self, path: str) -> bool:
        pass

    @abstractmethod
    async def exists(self, path: str) -> bool:
        pass

    @abstractmethod
    async def get_url(self, path: str, expires_in: int = 3600) -> str:
        pass


class LocalFileStorage(StorageBackend):
    def __init__(self, base_path: str):
        self.base_path = Path(base_path)
        self.base_path.mkdir(parents=True, exist_ok=True)

    def _get_full_path(self, path: str) -> Path:
        full_path = (self.base_path / path).resolve()
        if not str(full_path).startswith(str(self.base_path.resolve())):
            raise ValidationError("Invalid path: path traversal detected")
        return full_path

    async def save(self, path: str, data: Union[bytes, BinaryIO]) -> str:
        full_path = self._get_full_path(path)
        full_path.parent.mkdir(parents=True, exist_ok=True)

        if isinstance(data, bytes):
            full_path.write_bytes(data)
        else:
            with open(full_path, "wb") as f:
                shutil.copyfileobj(data, f)

        return path

    async def load(self, path: str) -> bytes:
        full_path = self._get_full_path(path)
        if not full_path.exists():
            raise NotFoundError("File", path)
        return full_path.read_bytes()

    async def delete(self, path: str) -> bool:
        full_path = self._get_full_path(path)
        if full_path.exists():
            full_path.unlink()
            return True
        return False

    async def exists(self, path: str) -> bool:
        full_path = self._get_full_path(path)
        return full_path.exists()

    async def get_url(self, path: str, expires_in: int = 3600) -> str:
        return f"/api/v1/storage/files/{path}"

    async def get_metadata(self, path: str) -> Dict[str, Any]:
        full_path = self._get_full_path(path)
        if not full_path.exists():
            raise NotFoundError("File", path)

        stat = full_path.stat()
        mime_type, _ = mimetypes.guess_type(str(full_path))

        return {
            "path": path,
            "size": stat.st_size,
            "created_at": datetime.fromtimestamp(stat.st_ctime),
            "modified_at": datetime.fromtimestamp(stat.st_mtime),
            "mime_type": mime_type or "application/octet-stream",
            "extension": full_path.suffix,
        }

    async def list_files(self, prefix: str = "", recursive: bool = True) -> List[Dict[str, Any]]:
        dir_path = self._get_full_path(prefix) if prefix else self.base_path
        if not dir_path.exists():
            return []

        files = []
        pattern = "**/*" if recursive else "*"

        for item in dir_path.glob(pattern):
            if item.is_file():
                rel_path = item.relative_to(self.base_path)
                stat = item.stat()
                mime_type, _ = mimetypes.guess_type(str(item))
                files.append({
                    "path": str(rel_path),
                    "size": stat.st_size,
                    "modified_at": datetime.fromtimestamp(stat.st_mtime),
                    "mime_type": mime_type or "application/octet-stream",
                })

        return files


class StorageManager:
    def __init__(self, default_backend: Optional[StorageBackend] = None):
        self._backends: Dict[str, StorageBackend] = {}
        self._default_backend = default_backend or LocalFileStorage(settings.file_storage_path)
        self._backends["default"] = self._default_backend

    def register_backend(self, name: str, backend: StorageBackend) -> None:
        self._backends[name] = backend

    def get_backend(self, name: Optional[str] = None) -> StorageBackend:
        if name is None:
            return self._default_backend
        if name not in self._backends:
            raise ValidationError(f"Unknown storage backend: {name}")
        return self._backends[name]

    @staticmethod
    def _calculate_checksum(data: bytes) -> str:
        return hashlib.sha256(data).hexdigest()

    async def upload_file(
        self,
        filename: str,
        data: Union[bytes, BinaryIO],
        directory: str = "",
        backend: Optional[str] = None,
        metadata: Optional[Dict[str, Any]] = None,
    ) -> Dict[str, Any]:
        storage = self.get_backend(backend)

        timestamp = datetime.utcnow().strftime("%Y%m%d_%H%M%S")
        name_parts = os.path.splitext(filename)
        safe_filename = f"{name_parts[0]}_{timestamp}{name_parts[1]}"

        if directory:
            path = f"{directory.rstrip('/')}/{safe_filename}"
        else:
            path = safe_filename

        await storage.save(path, data)

        file_info = await storage.get_metadata(path)

        if isinstance(data, bytes):
            checksum = self._calculate_checksum(data)
        else:
            checksum = None

        return {
            "path": path,
            "filename": filename,
            "size": file_info["size"],
            "mime_type": file_info["mime_type"],
            "checksum": checksum,
            "backend": backend or "default",
            "metadata": metadata or {},
            "uploaded_at": datetime.utcnow(),
        }

    async def download_file(
        self,
        path: str,
        backend: Optional[str] = None,
    ) -> bytes:
        storage = self.get_backend(backend)
        return await storage.load(path)

    async def delete_file(
        self,
        path: str,
        backend: Optional[str] = None,
    ) -> bool:
        storage = self.get_backend(backend)
        return await storage.delete(path)

    async def get_file_metadata(
        self,
        path: str,
        backend: Optional[str] = None,
    ) -> Dict[str, Any]:
        storage = self.get_backend(backend)
        if hasattr(storage, 'get_metadata'):
            return await storage.get_metadata(path)
        exists = await storage.exists(path)
        return {"path": path, "exists": exists}

    async def get_file_url(
        self,
        path: str,
        backend: Optional[str] = None,
        expires_in: int = 3600,
    ) -> str:
        storage = self.get_backend(backend)
        return await storage.get_url(path, expires_in)

    async def list_files(
        self,
        prefix: str = "",
        backend: Optional[str] = None,
        recursive: bool = True,
    ) -> List[Dict[str, Any]]:
        storage = self.get_backend(backend)
        if hasattr(storage, 'list_files'):
            return await storage.list_files(prefix, recursive)
        return []

    async def cleanup_old_files(
        self,
        max_age_days: int = 30,
        prefix: str = "",
        backend: Optional[str] = None,
    ) -> int:
        storage = self.get_backend(backend)
        if not hasattr(storage, 'list_files'):
            return 0

        files = await storage.list_files(prefix, recursive=True)
        cutoff = datetime.utcnow() - timedelta(days=max_age_days)
        deleted_count = 0

        for file_info in files:
            modified_at = file_info.get("modified_at", datetime.utcnow())
            if isinstance(modified_at, datetime) and modified_at < cutoff:
                if await storage.delete(file_info["path"]):
                    deleted_count += 1

        return deleted_count

    async def get_storage_stats(self, backend: Optional[str] = None) -> Dict[str, Any]:
        storage = self.get_backend(backend)
        if not hasattr(storage, 'list_files'):
            return {"backend": backend or "default", "files": 0, "total_size": 0}

        files = await storage.list_files(recursive=True)
        total_size = sum(f.get("size", 0) for f in files)

        return {
            "backend": backend or "default",
            "file_count": len(files),
            "total_size_bytes": total_size,
            "total_size_mb": total_size / (1024 * 1024),
        }


storage_manager = StorageManager()
