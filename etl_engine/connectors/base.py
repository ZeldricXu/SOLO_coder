import logging
from abc import ABC, abstractmethod
from typing import Any

import pandas as pd
from pydantic import BaseModel

logger = logging.getLogger(__name__)


class SourceConfig(BaseModel):
    name: str
    type: str
    connection_params: dict[str, Any]
    pool_size: int = 5


class BaseSource(ABC):
    def __init__(self, config: dict) -> None:
        self.config = config
        self._connected: bool = False

    @property
    def is_connected(self) -> bool:
        return self._connected

    @abstractmethod
    async def connect(self) -> None:
        ...

    @abstractmethod
    async def disconnect(self) -> None:
        ...

    @abstractmethod
    async def read(self, query: str | None = None, **kwargs) -> pd.DataFrame:
        ...

    @abstractmethod
    async def test_connection(self) -> bool:
        ...


_source_registry: dict[str, type[BaseSource]] = {}


def register_source(source_type: str):
    def decorator(cls: type[BaseSource]) -> type[BaseSource]:
        _source_registry[source_type] = cls
        logger.info("Registered source type: %s -> %s", source_type, cls.__name__)
        return cls
    return decorator


def get_source(source_type: str, config: dict) -> BaseSource:
    cls = _source_registry.get(source_type)
    if cls is None:
        raise ValueError(
            f"Unknown source type: '{source_type}'. "
            f"Available types: {list(_source_registry.keys())}"
        )
    return cls(config)
