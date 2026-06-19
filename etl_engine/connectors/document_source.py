import logging
from abc import ABC, abstractmethod
from typing import Any, AsyncGenerator

import pandas as pd
from pydantic import BaseModel

logger = logging.getLogger(__name__)


class DocumentQuery(BaseModel):
    filter: dict
    projection: dict | None = None
    limit: int | None = None
    skip: int = 0
    sort: list[tuple[str, int]] | None = None


class DocumentAggregation(BaseModel):
    pipeline: list[dict]


class DocumentScanResult(BaseModel):
    documents: list[dict]
    total: int
    cursor: str | None = None
    has_more: bool = False


class DocumentSource(ABC):
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
    async def find(self, query: DocumentQuery) -> pd.DataFrame:
        ...

    @abstractmethod
    async def aggregate(self, pipeline: DocumentAggregation) -> pd.DataFrame:
        ...

    @abstractmethod
    async def scan(self, batch_size: int = 1000, **kwargs) -> DocumentScanResult:
        ...

    async def find_iter(self, query: DocumentQuery) -> AsyncGenerator[dict, None]:
        df = await self.find(query)
        for _, row in df.iterrows():
            yield row.to_dict()


_document_registry: dict[str, type[DocumentSource]] = {}


def register_document_source(source_type: str):
    def decorator(cls: type[DocumentSource]) -> type[DocumentSource]:
        _document_registry[source_type] = cls
        logger.info("Registered document source type: %s -> %s", source_type, cls.__name__)
        return cls
    return decorator


def get_document_source(source_type: str, config: dict) -> DocumentSource:
    cls = _document_registry.get(source_type)
    if cls is None:
        raise ValueError(
            f"Unknown document source type: '{source_type}'. "
            f"Available types: {list(_document_registry.keys())}"
        )
    return cls(config)
