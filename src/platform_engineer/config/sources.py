import asyncio
import hashlib
import json
import os
from abc import ABC, abstractmethod
from datetime import datetime, timezone
from typing import Any, Dict, Optional
from urllib.parse import urlparse

try:
    import yaml
    HAS_YAML = True
except ImportError:
    HAS_YAML = False

from ..core.exceptions import ConfigNotFoundError


class ConfigSource(ABC):
    def __init__(self, priority: int = 100):
        self.priority = priority
        self._last_checksum: Optional[str] = None
        self._last_modified: Optional[datetime] = None

    @abstractmethod
    async def load(self) -> Dict[str, Any]:
        pass

    async def has_changed(self) -> bool:
        checksum = await self.get_checksum()
        if self._last_checksum is None:
            self._last_checksum = checksum
            return False
        changed = checksum != self._last_checksum
        if changed:
            self._last_checksum = checksum
            self._last_modified = datetime.now(timezone.utc)
        return changed

    async def get_checksum(self) -> str:
        data = await self.load()
        return hashlib.sha256(json.dumps(data, sort_keys=True).encode()).hexdigest()

    def get_priority(self) -> int:
        return self.priority


class EnvironmentSource(ConfigSource):
    def __init__(self, prefix: str = "APP_", priority: int = 50):
        super().__init__(priority)
        self.prefix = prefix

    async def load(self) -> Dict[str, Any]:
        config: Dict[str, Any] = {}
        for key, value in os.environ.items():
            if key.startswith(self.prefix):
                config_key = key[len(self.prefix):].lower().replace("__", ".")
                self._set_nested(config, config_key, self._parse_value(value))
        return config

    def _set_nested(self, data: Dict[str, Any], key: str, value: Any) -> None:
        keys = key.split(".")
        for k in keys[:-1]:
            data = data.setdefault(k, {})
        data[keys[-1]] = value

    def _parse_value(self, value: str) -> Any:
        value = value.strip()
        if value.lower() in ("true", "false"):
            return value.lower() == "true"
        try:
            return int(value)
        except ValueError:
            pass
        try:
            return float(value)
        except ValueError:
            pass
        try:
            return json.loads(value)
        except json.JSONDecodeError:
            pass
        return value


class JSONFileSource(ConfigSource):
    def __init__(self, file_path: str, priority: int = 200, auto_reload: bool = True):
        super().__init__(priority)
        self.file_path = file_path
        self.auto_reload = auto_reload
        self._file_mtime: Optional[float] = None

    async def load(self) -> Dict[str, Any]:
        if not os.path.exists(self.file_path):
            raise ConfigNotFoundError(f"Config file not found: {self.file_path}")
        with open(self.file_path, "r", encoding="utf-8") as f:
            data = json.load(f)
        self._file_mtime = os.path.getmtime(self.file_path)
        return data

    async def has_changed(self) -> bool:
        if not self.auto_reload:
            return False
        if not os.path.exists(self.file_path):
            return False
        current_mtime = os.path.getmtime(self.file_path)
        changed = self._file_mtime is not None and current_mtime != self._file_mtime
        return changed


class YAMLFileSource(ConfigSource):
    def __init__(self, file_path: str, priority: int = 200, auto_reload: bool = True):
        super().__init__(priority)
        self.file_path = file_path
        self.auto_reload = auto_reload
        self._file_mtime: Optional[float] = None

    async def load(self) -> Dict[str, Any]:
        if not HAS_YAML:
            raise RuntimeError("PyYAML not installed. Install with 'pip install pyyaml'")
        if not os.path.exists(self.file_path):
            raise ConfigNotFoundError(f"Config file not found: {self.file_path}")
        with open(self.file_path, "r", encoding="utf-8") as f:
            data = yaml.safe_load(f) or {}
        self._file_mtime = os.path.getmtime(self.file_path)
        return data

    async def has_changed(self) -> bool:
        if not self.auto_reload:
            return False
        if not os.path.exists(self.file_path):
            return False
        current_mtime = os.path.getmtime(self.file_path)
        changed = self._file_mtime is not None and current_mtime != self._file_mtime
        return changed


class RemoteSource(ConfigSource):
    def __init__(
        self,
        url: str,
        priority: int = 150,
        timeout: float = 10.0,
        headers: Optional[Dict[str, str]] = None,
        auth_token: Optional[str] = None,
    ):
        super().__init__(priority)
        self.url = url
        self.timeout = timeout
        self.headers = headers or {}
        if auth_token:
            self.headers["Authorization"] = f"Bearer {auth_token}"
        self._etag: Optional[str] = None

    async def load(self) -> Dict[str, Any]:
        import aiohttp

        try:
            async with aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=self.timeout)) as session:
                headers = dict(self.headers)
                if self._etag:
                    headers["If-None-Match"] = self._etag
                async with session.get(self.url, headers=headers) as response:
                    if response.status == 304:
                        return {}
                    if response.status >= 400:
                        raise ConfigNotFoundError(f"Failed to fetch config from {self.url}: {response.status}")
                    data = await response.json()
                    etag = response.headers.get("ETag")
                    if etag:
                        self._etag = etag
                    return data
        except ImportError:
            raise RuntimeError("aiohttp not installed. Install with 'pip install aiohttp'")


class MemorySource(ConfigSource):
    def __init__(self, data: Dict[str, Any], priority: int = 300):
        super().__init__(priority)
        self._data = data

    async def load(self) -> Dict[str, Any]:
        return dict(self._data)

    def update(self, key: str, value: Any) -> None:
        keys = key.split(".")
        data = self._data
        for k in keys[:-1]:
            data = data.setdefault(k, {})
        data[keys[-1]] = value
        self._last_checksum = None

    def set(self, data: Dict[str, Any]) -> None:
        self._data = dict(data)
        self._last_checksum = None
