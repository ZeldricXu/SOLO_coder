from __future__ import annotations

import json
import logging
import os
from abc import ABC, abstractmethod
from typing import Any, Dict, List, Optional

import yaml

from src.common.utils import async_retry
from src.config.models import ConfigSourceType

logger = logging.getLogger(__name__)


class ConfigSource(ABC):
    def __init__(self, source_type: ConfigSourceType, priority: int = 0) -> None:
        self.source_type = source_type
        self.priority = priority
        self._watchers: List[callable] = []

    @abstractmethod
    async def load(self) -> Dict[str, Any]:
        ...

    async def reload(self) -> Dict[str, Any]:
        return await self.load()

    def watch(self, callback: callable) -> None:
        self._watchers.append(callback)

    def _notify_watchers(self, changes: Dict[str, Any]) -> None:
        for callback in self._watchers:
            try:
                callback(changes)
            except Exception as e:
                logger.error(f"Error in config watcher callback: {e}")


class EnvironmentSource(ConfigSource):
    def __init__(self, prefix: str = "", priority: int = 100) -> None:
        super().__init__(ConfigSourceType.ENVIRONMENT, priority)
        self.prefix = prefix

    async def load(self) -> Dict[str, Any]:
        result: Dict[str, Any] = {}
        for key, value in os.environ.items():
            if self.prefix and not key.startswith(self.prefix):
                continue
            config_key = key[len(self.prefix):].lower() if self.prefix else key.lower()
            config_key = config_key.replace("__", ".")
            result[config_key] = self._parse_value(value)
        logger.info(f"Loaded {len(result)} configs from environment")
        return result

    def _parse_value(self, value: str) -> Any:
        try:
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
            if (value.startswith("[") and value.endswith("]")) or (value.startswith("{") and value.endswith("}")):
                return json.loads(value)
        except Exception:
            pass
        return value


class FileSource(ConfigSource):
    def __init__(self, file_path: str, priority: int = 50) -> None:
        super().__init__(ConfigSourceType.FILE, priority)
        self.file_path = file_path
        self._last_mtime: Optional[float] = None

    async def load(self) -> Dict[str, Any]:
        if not os.path.exists(self.file_path):
            logger.warning(f"Config file not found: {self.file_path}")
            return {}
        stat = os.stat(self.file_path)
        self._last_mtime = stat.st_mtime
        try:
            with open(self.file_path, "r", encoding="utf-8") as f:
                if self.file_path.endswith((".yaml", ".yml")):
                    data = yaml.safe_load(f) or {}
                elif self.file_path.endswith(".json"):
                    data = json.load(f)
                elif self.file_path.endswith(".toml"):
                    import toml
                    data = toml.load(f)
                else:
                    raise ValueError(f"Unsupported config file format: {self.file_path}")
            logger.info(f"Loaded config from file: {self.file_path}")
            return self._flatten_dict(data)
        except Exception as e:
            logger.error(f"Failed to load config file {self.file_path}: {e}")
            return {}

    def _flatten_dict(self, d: Dict[str, Any], prefix: str = "") -> Dict[str, Any]:
        result: Dict[str, Any] = {}
        for key, value in d.items():
            full_key = f"{prefix}.{key}" if prefix else key
            if isinstance(value, dict):
                result.update(self._flatten_dict(value, full_key))
            else:
                result[full_key] = value
        return result

    async def check_for_changes(self) -> Optional[Dict[str, Any]]:
        if not os.path.exists(self.file_path):
            return None
        stat = os.stat(self.file_path)
        if self._last_mtime and stat.st_mtime > self._last_mtime:
            logger.info(f"Config file changed: {self.file_path}")
            new_data = await self.load()
            self._notify_watchers(new_data)
            return new_data
        return None


class MemorySource(ConfigSource):
    def __init__(self, data: Optional[Dict[str, Any]] = None, priority: int = 10) -> None:
        super().__init__(ConfigSourceType.MEMORY, priority)
        self._data: Dict[str, Any] = data or {}

    async def load(self) -> Dict[str, Any]:
        return self._data.copy()

    def set(self, key: str, value: Any) -> None:
        self._data[key] = value
        self._notify_watchers({key: value})

    def delete(self, key: str) -> bool:
        if key in self._data:
            del self._data[key]
            return True
        return False


class HTTPSource(ConfigSource):
    def __init__(self, url: str, headers: Optional[Dict[str, str]] = None, priority: int = 30) -> None:
        super().__init__(ConfigSourceType.HTTP, priority)
        self.url = url
        self.headers = headers or {}

    @async_retry(max_attempts=3)
    async def load(self) -> Dict[str, Any]:
        import httpx
        try:
            async with httpx.AsyncClient() as client:
                response = await client.get(self.url, headers=self.headers, timeout=10)
                response.raise_for_status()
                data = response.json()
                logger.info(f"Loaded config from HTTP: {self.url}")
                if isinstance(data, dict):
                    return data
                return {}
        except Exception as e:
            logger.error(f"Failed to load config from HTTP {self.url}: {e}")
            return {}


class RedisSource(ConfigSource):
    def __init__(
        self,
        redis_url: str,
        key_prefix: str = "config:",
        priority: int = 20,
    ) -> None:
        super().__init__(ConfigSourceType.REDIS, priority)
        self.redis_url = redis_url
        self.key_prefix = key_prefix
        self._client = None
        try:
            import redis
            self._client = redis.from_url(redis_url)
        except ImportError:
            logger.warning("redis not available, Redis config source disabled")

    async def load(self) -> Dict[str, Any]:
        if not self._client:
            return {}
        try:
            result: Dict[str, Any] = {}
            keys = self._client.keys(f"{self.key_prefix}*")
            for key in keys:
                raw_value = self._client.get(key)
                if raw_value:
                    config_key = key.decode().replace(self.key_prefix, "")
                    try:
                        result[config_key] = json.loads(raw_value)
                    except (json.JSONDecodeError, TypeError):
                        result[config_key] = raw_value.decode()
            logger.info(f"Loaded {len(result)} configs from Redis")
            return result
        except Exception as e:
            logger.error(f"Failed to load config from Redis: {e}")
            return {}
