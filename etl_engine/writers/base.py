import logging
import time
from abc import ABC, abstractmethod

import pandas as pd
from pydantic import BaseModel

logger = logging.getLogger(__name__)


class WriteResult(BaseModel):
    rows_written: int
    table: str
    strategy: str
    duration_seconds: float
    success: bool
    error: str | None = None


class BaseWriter(ABC):
    def __init__(self, config: dict) -> None:
        self.config = config
        self._validate_config()

    def _validate_config(self) -> None:
        pass

    @abstractmethod
    async def write(
        self, df: pd.DataFrame, table: str, strategy: str = "insert", **kwargs
    ) -> WriteResult:
        ...

    @abstractmethod
    async def test_connection(self) -> bool:
        ...


_writer_registry: dict[str, type[BaseWriter]] = {}


def register_writer(writer_type: str):
    def decorator(cls: type[BaseWriter]) -> type[BaseWriter]:
        _writer_registry[writer_type] = cls
        logger.info("Registered writer type: %s -> %s", writer_type, cls.__name__)
        return cls

    return decorator


def get_writer(writer_type: str, config: dict) -> BaseWriter:
    cls = _writer_registry.get(writer_type)
    if cls is None:
        raise ValueError(
            f"Unknown writer type: '{writer_type}'. "
            f"Available types: {list(_writer_registry.keys())}"
        )
    return cls(config)
