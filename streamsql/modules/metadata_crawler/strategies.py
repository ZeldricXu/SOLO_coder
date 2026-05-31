from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from datetime import datetime
from enum import Enum
from typing import Any, Optional

from streamsql.core.models import TableSchema


class ScanMode(str, Enum):
    FULL = "full"
    INCREMENTAL = "incremental"
    SAMPLE = "sample"
    SCHEMA_ONLY = "schema_only"


class SamplingStrategy(str, Enum):
    RANDOM = "random"
    HEAD = "head"
    TAIL = "tail"
    STRATIFIED = "stratified"
    SYSTEM = "system"


class RefreshMode(str, Enum):
    MANUAL = "manual"
    SCHEDULED = "scheduled"
    AUTO = "auto"
    ON_DEMAND = "on_demand"


@dataclass
class CrawlStrategyConfig:
    scan_mode: ScanMode = ScanMode.FULL
    sampling_strategy: SamplingStrategy = SamplingStrategy.HEAD
    refresh_mode: RefreshMode = RefreshMode.ON_DEMAND
    sample_size: int = 100
    max_tables_per_batch: int = 10
    timeout_per_table: int = 30
    timeout_total: int = 300
    retry_attempts: int = 3
    retry_delay_ms: int = 100
    include_tables: Optional[list[str]] = None
    exclude_tables: Optional[list[str]] = None
    include_schemas: Optional[list[str]] = None
    exclude_schemas: Optional[list[str]] = None
    collect_stats: bool = True
    collect_samples: bool = True
    track_history: bool = False
    validate_types: bool = True
    infer_primary_keys: bool = True
    infer_foreign_keys: bool = False
    custom_parameters: dict[str, Any] = field(default_factory=dict)
    created_at: datetime = field(default_factory=datetime.utcnow)
    updated_at: datetime = field(default_factory=datetime.utcnow)

    def to_dict(self) -> dict[str, Any]:
        return {
            "scan_mode": self.scan_mode.value,
            "sampling_strategy": self.sampling_strategy.value,
            "refresh_mode": self.refresh_mode.value,
            "sample_size": self.sample_size,
            "max_tables_per_batch": self.max_tables_per_batch,
            "timeout_per_table": self.timeout_per_table,
            "timeout_total": self.timeout_total,
            "retry_attempts": self.retry_attempts,
            "retry_delay_ms": self.retry_delay_ms,
            "include_tables": self.include_tables,
            "exclude_tables": self.exclude_tables,
            "include_schemas": self.include_schemas,
            "exclude_schemas": self.exclude_schemas,
            "collect_stats": self.collect_stats,
            "collect_samples": self.collect_samples,
            "track_history": self.track_history,
            "validate_types": self.validate_types,
            "infer_primary_keys": self.infer_primary_keys,
            "infer_foreign_keys": self.infer_foreign_keys,
            "custom_parameters": self.custom_parameters,
        }

    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> "CrawlStrategyConfig":
        return cls(
            scan_mode=ScanMode(data.get("scan_mode", "full")),
            sampling_strategy=SamplingStrategy(data.get("sampling_strategy", "head")),
            refresh_mode=RefreshMode(data.get("refresh_mode", "on_demand")),
            sample_size=data.get("sample_size", 100),
            max_tables_per_batch=data.get("max_tables_per_batch", 10),
            timeout_per_table=data.get("timeout_per_table", 30),
            timeout_total=data.get("timeout_total", 300),
            retry_attempts=data.get("retry_attempts", 3),
            retry_delay_ms=data.get("retry_delay_ms", 100),
            include_tables=data.get("include_tables"),
            exclude_tables=data.get("exclude_tables"),
            include_schemas=data.get("include_schemas"),
            exclude_schemas=data.get("exclude_schemas"),
            collect_stats=data.get("collect_stats", True),
            collect_samples=data.get("collect_samples", True),
            track_history=data.get("track_history", False),
            validate_types=data.get("validate_types", True),
            infer_primary_keys=data.get("infer_primary_keys", True),
            infer_foreign_keys=data.get("infer_foreign_keys", False),
            custom_parameters=data.get("custom_parameters", {}),
        )

    def clone(self) -> "CrawlStrategyConfig":
        return CrawlStrategyConfig.from_dict(self.to_dict())


class CrawlStrategy(ABC):
    name: str
    description: str

    @abstractmethod
    async def execute(
        self,
        tables: list[str],
        extract_table_fn,
        config: CrawlStrategyConfig,
    ) -> tuple[list[TableSchema], list[str]]:
        """
        Execute the crawl strategy on the given tables.

        Args:
            tables: List of table names to process
            extract_table_fn: Async function to extract schema for a single table
            config: Strategy configuration

        Returns:
            Tuple of (successful schemas, failed table names)
        """
        pass


class SequentialStrategy(CrawlStrategy):
    name = "sequential"
    description = "Process tables one by one in sequence"

    async def execute(
        self,
        tables: list[str],
        extract_table_fn,
        config: CrawlStrategyConfig,
    ) -> tuple[list[TableSchema], list[str]]:
        schemas: list[TableSchema] = []
        failed: list[str] = []

        for table in tables:
            try:
                schema = await extract_table_fn(table, config)
                if schema:
                    schemas.append(schema)
            except Exception:
                failed.append(table)

        return schemas, failed


class BatchStrategy(CrawlStrategy):
    name = "batch"
    description = "Process tables in concurrent batches"

    async def execute(
        self,
        tables: list[str],
        extract_table_fn,
        config: CrawlStrategyConfig,
    ) -> tuple[list[TableSchema], list[str]]:
        import asyncio

        schemas: list[TableSchema] = []
        failed: list[str] = []
        batch_size = config.max_tables_per_batch

        for i in range(0, len(tables), batch_size):
            batch = tables[i:i + batch_size]
            tasks = [extract_table_fn(table, config) for table in batch]
            results = await asyncio.gather(*tasks, return_exceptions=True)

            for table, result in zip(batch, results):
                if isinstance(result, Exception):
                    failed.append(table)
                elif result:
                    schemas.append(result)

        return schemas, failed


class PriorityStrategy(CrawlStrategy):
    name = "priority"
    description = "Process tables based on priority order"

    def __init__(self, priority_tables: Optional[list[str]] = None):
        self.priority_tables = priority_tables or []

    async def execute(
        self,
        tables: list[str],
        extract_table_fn,
        config: CrawlStrategyConfig,
    ) -> tuple[list[TableSchema], list[str]]:
        ordered_tables = sorted(
            tables,
            key=lambda t: self.priority_tables.index(t) if t in self.priority_tables else 9999
        )

        schemas: list[TableSchema] = []
        failed: list[str] = []

        for table in ordered_tables:
            try:
                schema = await extract_table_fn(table, config)
                if schema:
                    schemas.append(schema)
            except Exception:
                failed.append(table)

        return schemas, failed


class ThrottledStrategy(CrawlStrategy):
    name = "throttled"
    description = "Process tables with rate limiting to reduce load"

    def __init__(self, delay_between_tables_ms: int = 100):
        self.delay_between_tables_ms = delay_between_tables_ms

    async def execute(
        self,
        tables: list[str],
        extract_table_fn,
        config: CrawlStrategyConfig,
    ) -> tuple[list[TableSchema], list[str]]:
        import asyncio

        schemas: list[TableSchema] = []
        failed: list[str] = []

        for table in tables:
            try:
                schema = await extract_table_fn(table, config)
                if schema:
                    schemas.append(schema)
            except Exception:
                failed.append(table)

            if self.delay_between_tables_ms > 0:
                await asyncio.sleep(self.delay_between_tables_ms / 1000)

        return schemas, failed


class StrategyRegistry:
    _strategies: dict[str, CrawlStrategy] = {}

    @classmethod
    def register(cls, strategy: CrawlStrategy) -> None:
        cls._strategies[strategy.name] = strategy

    @classmethod
    def get(cls, name: str) -> Optional[CrawlStrategy]:
        return cls._strategies.get(name)

    @classmethod
    def get_all(cls) -> list[CrawlStrategy]:
        return list(cls._strategies.values())

    @classmethod
    def list_names(cls) -> list[str]:
        return list(cls._strategies.keys())


StrategyRegistry.register(SequentialStrategy())
StrategyRegistry.register(BatchStrategy())
StrategyRegistry.register(PriorityStrategy())
StrategyRegistry.register(ThrottledStrategy())
