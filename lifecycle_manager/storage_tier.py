import os
import shutil
from abc import ABC, abstractmethod
from datetime import datetime
from enum import Enum
from pathlib import Path
from typing import Any, Dict, List, Optional, Union


class StorageTierType(Enum):
    HOT = "hot"
    COLD = "cold"
    ARCHIVE = "archive"


class StorageTier(ABC):
    def __init__(
        self,
        name: str,
        tier_type: StorageTierType,
        base_path: str,
        config: Optional[Dict[str, Any]] = None,
    ):
        self.name = name
        self.tier_type = tier_type
        self.base_path = Path(base_path)
        self.config = config or {}
        self._initialize_storage()

    def _initialize_storage(self) -> None:
        if not self.base_path.exists():
            self.base_path.mkdir(parents=True, exist_ok=True)

    @abstractmethod
    def put(self, key: str, data: Union[bytes, str, Path]) -> bool:
        pass

    @abstractmethod
    def get(self, key: str) -> Optional[bytes]:
        pass

    @abstractmethod
    def delete(self, key: str) -> bool:
        pass

    @abstractmethod
    def exists(self, key: str) -> bool:
        pass

    @abstractmethod
    def list_keys(self, prefix: Optional[str] = None) -> List[str]:
        pass

    @abstractmethod
    def get_metadata(self, key: str) -> Optional[Dict[str, Any]]:
        pass

    def get_size(self, key: str) -> Optional[int]:
        file_path = self._get_file_path(key)
        if file_path.exists():
            return file_path.stat().st_size
        return None

    def get_access_time(self, key: str) -> Optional[datetime]:
        file_path = self._get_file_path(key)
        if file_path.exists():
            return datetime.fromtimestamp(file_path.stat().st_atime)
        return None

    def get_modification_time(self, key: str) -> Optional[datetime]:
        file_path = self._get_file_path(key)
        if file_path.exists():
            return datetime.fromtimestamp(file_path.stat().st_mtime)
        return None

    def _get_file_path(self, key: str) -> Path:
        return self.base_path / key

    def get_capacity_info(self) -> Dict[str, Any]:
        total, used, free = shutil.disk_usage(self.base_path)
        return {
            "total_bytes": total,
            "used_bytes": used,
            "free_bytes": free,
            "usage_percent": (used / total) * 100 if total > 0 else 0,
        }

    def __str__(self) -> str:
        return f"StorageTier(name={self.name}, type={self.tier_type.value}, path={self.base_path})"


class HotStorage(StorageTier):
    def __init__(
        self,
        name: str = "hot_storage",
        base_path: str = "./data/hot",
        config: Optional[Dict[str, Any]] = None,
    ):
        super().__init__(name, StorageTierType.HOT, base_path, config)
        self.cache_ttl = self.config.get("cache_ttl", 3600)
        self.max_connections = self.config.get("max_connections", 100)

    def put(self, key: str, data: Union[bytes, str, Path]) -> bool:
        try:
            file_path = self._get_file_path(key)
            file_path.parent.mkdir(parents=True, exist_ok=True)

            if isinstance(data, Path):
                shutil.copy2(data, file_path)
            elif isinstance(data, str):
                file_path.write_text(data, encoding="utf-8")
            else:
                file_path.write_bytes(data)
            return True
        except Exception:
            return False

    def get(self, key: str) -> Optional[bytes]:
        try:
            file_path = self._get_file_path(key)
            if file_path.exists():
                return file_path.read_bytes()
            return None
        except Exception:
            return None

    def delete(self, key: str) -> bool:
        try:
            file_path = self._get_file_path(key)
            if file_path.exists():
                file_path.unlink()
                return True
            return False
        except Exception:
            return False

    def exists(self, key: str) -> bool:
        return self._get_file_path(key).exists()

    def list_keys(self, prefix: Optional[str] = None) -> List[str]:
        search_path = self.base_path
        if prefix:
            search_path = self.base_path / prefix

        if not search_path.exists():
            return []

        keys = []
        for root, _, files in os.walk(search_path):
            for file in files:
                full_path = Path(root) / file
                relative_path = full_path.relative_to(self.base_path)
                keys.append(str(relative_path))
        return sorted(keys)

    def get_metadata(self, key: str) -> Optional[Dict[str, Any]]:
        file_path = self._get_file_path(key)
        if not file_path.exists():
            return None

        stat = file_path.stat()
        return {
            "key": key,
            "size": stat.st_size,
            "created_at": datetime.fromtimestamp(stat.st_birthtime),
            "modified_at": datetime.fromtimestamp(stat.st_mtime),
            "accessed_at": datetime.fromtimestamp(stat.st_atime),
            "tier": self.tier_type.value,
        }


class ColdStorage(StorageTier):
    def __init__(
        self,
        name: str = "cold_storage",
        base_path: str = "./data/cold",
        config: Optional[Dict[str, Any]] = None,
    ):
        super().__init__(name, StorageTierType.COLD, base_path, config)
        self.retrieval_delay = self.config.get("retrieval_delay", 5)
        self.compression_enabled = self.config.get("compression_enabled", True)

    def put(self, key: str, data: Union[bytes, str, Path]) -> bool:
        try:
            file_path = self._get_file_path(key)
            file_path.parent.mkdir(parents=True, exist_ok=True)

            if isinstance(data, Path):
                shutil.copy2(data, file_path)
            elif isinstance(data, str):
                file_path.write_text(data, encoding="utf-8")
            else:
                file_path.write_bytes(data)

            os.chmod(file_path, 0o444)
            return True
        except Exception:
            return False

    def get(self, key: str) -> Optional[bytes]:
        import time
        time.sleep(self.retrieval_delay)
        try:
            file_path = self._get_file_path(key)
            if file_path.exists():
                return file_path.read_bytes()
            return None
        except Exception:
            return None

    def delete(self, key: str) -> bool:
        try:
            file_path = self._get_file_path(key)
            if file_path.exists():
                os.chmod(file_path, 0o644)
                file_path.unlink()
                return True
            return False
        except Exception:
            return False

    def exists(self, key: str) -> bool:
        return self._get_file_path(key).exists()

    def list_keys(self, prefix: Optional[str] = None) -> List[str]:
        search_path = self.base_path
        if prefix:
            search_path = self.base_path / prefix

        if not search_path.exists():
            return []

        keys = []
        for root, _, files in os.walk(search_path):
            for file in files:
                full_path = Path(root) / file
                relative_path = full_path.relative_to(self.base_path)
                keys.append(str(relative_path))
        return sorted(keys)

    def get_metadata(self, key: str) -> Optional[Dict[str, Any]]:
        file_path = self._get_file_path(key)
        if not file_path.exists():
            return None

        stat = file_path.stat()
        return {
            "key": key,
            "size": stat.st_size,
            "created_at": datetime.fromtimestamp(stat.st_birthtime),
            "modified_at": datetime.fromtimestamp(stat.st_mtime),
            "accessed_at": datetime.fromtimestamp(stat.st_atime),
            "tier": self.tier_type.value,
            "retrieval_delay": self.retrieval_delay,
        }


class ArchiveStorage(StorageTier):
    def __init__(
        self,
        name: str = "archive_storage",
        base_path: str = "./data/archive",
        config: Optional[Dict[str, Any]] = None,
    ):
        super().__init__(name, StorageTierType.ARCHIVE, base_path, config)
        self.retrieval_delay = self.config.get("retrieval_delay", 30)
        self.min_retention_days = self.config.get("min_retention_days", 90)
        self.encryption_enabled = self.config.get("encryption_enabled", True)

    def put(self, key: str, data: Union[bytes, str, Path]) -> bool:
        try:
            file_path = self._get_file_path(key)
            file_path.parent.mkdir(parents=True, exist_ok=True)

            if isinstance(data, Path):
                shutil.copy2(data, file_path)
            elif isinstance(data, str):
                file_path.write_text(data, encoding="utf-8")
            else:
                file_path.write_bytes(data)

            os.chmod(file_path, 0o400)
            return True
        except Exception:
            return False

    def get(self, key: str) -> Optional[bytes]:
        import time
        time.sleep(self.retrieval_delay)
        try:
            file_path = self._get_file_path(key)
            if file_path.exists():
                return file_path.read_bytes()
            return None
        except Exception:
            return None

    def delete(self, key: str) -> bool:
        try:
            file_path = self._get_file_path(key)
            if file_path.exists():
                stat = file_path.stat()
                age_days = (datetime.now() - datetime.fromtimestamp(stat.st_birthtime)).days
                if age_days < self.min_retention_days:
                    return False

                os.chmod(file_path, 0o600)
                file_path.unlink()
                return True
            return False
        except Exception:
            return False

    def exists(self, key: str) -> bool:
        return self._get_file_path(key).exists()

    def list_keys(self, prefix: Optional[str] = None) -> List[str]:
        search_path = self.base_path
        if prefix:
            search_path = self.base_path / prefix

        if not search_path.exists():
            return []

        keys = []
        for root, _, files in os.walk(search_path):
            for file in files:
                full_path = Path(root) / file
                relative_path = full_path.relative_to(self.base_path)
                keys.append(str(relative_path))
        return sorted(keys)

    def get_metadata(self, key: str) -> Optional[Dict[str, Any]]:
        file_path = self._get_file_path(key)
        if not file_path.exists():
            return None

        stat = file_path.stat()
        age_days = (datetime.now() - datetime.fromtimestamp(stat.st_birthtime)).days
        return {
            "key": key,
            "size": stat.st_size,
            "created_at": datetime.fromtimestamp(stat.st_birthtime),
            "modified_at": datetime.fromtimestamp(stat.st_mtime),
            "accessed_at": datetime.fromtimestamp(stat.st_atime),
            "tier": self.tier_type.value,
            "age_days": age_days,
            "min_retention_days": self.min_retention_days,
            "can_delete": age_days >= self.min_retention_days,
        }

    def can_delete(self, key: str) -> bool:
        file_path = self._get_file_path(key)
        if not file_path.exists():
            return True

        stat = file_path.stat()
        age_days = (datetime.now() - datetime.fromtimestamp(stat.st_birthtime)).days
        return age_days >= self.min_retention_days
