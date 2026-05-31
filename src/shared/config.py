from __future__ import annotations

import os
from pathlib import Path
from typing import Any, Dict, Optional

import yaml
from pydantic import BaseModel, Field
from pydantic_settings import BaseSettings

from src.shared.types import Chain


class AppConfig(BaseModel):
    name: str = "blockchain-infra"
    version: str = "1.0.0"
    host: str = "0.0.0.0"
    port: int = 8000
    debug: bool = False


class ChainConfig(BaseModel):
    rpc_url: str
    chain_id: int
    name: str


class DatabaseConfig(BaseModel):
    url: str = "sqlite:///./data/blockchain_infra.db"
    echo: bool = False


class RedisConfig(BaseModel):
    host: str = "localhost"
    port: int = 6379
    db: int = 0


class CeleryConfig(BaseModel):
    broker_url: str = "redis://localhost:6379/0"
    result_backend: str = "redis://localhost:6379/0"


class StorageConfig(BaseModel):
    ipfs: Dict[str, str] = Field(default_factory=dict)
    arweave: Dict[str, str] = Field(default_factory=dict)


class GasEstimatorConfig(BaseModel):
    history_blocks: int = 100
    priority_fee_percentile: int = 60
    max_priority_fee: int = 50000000000
    gas_price_multiplier: float = 1.2


class EventListenerConfig(BaseModel):
    max_retry: int = 3
    retry_delay: int = 5
    poll_interval: int = 2
    confirmation_blocks: int = 12


class IndexerConfig(BaseModel):
    batch_size: int = 100
    start_block: int = 0
    concurrent_workers: int = 4


class WalletConfig(BaseModel):
    hd_path: str = "m/44'/60'/0'/0"
    default_chain: str = "ethereum"


class Settings(BaseSettings):
    app: AppConfig = Field(default_factory=AppConfig)
    chains: Dict[str, ChainConfig] = Field(default_factory=dict)
    database: DatabaseConfig = Field(default_factory=DatabaseConfig)
    redis: RedisConfig = Field(default_factory=RedisConfig)
    celery: CeleryConfig = Field(default_factory=CeleryConfig)
    storage: StorageConfig = Field(default_factory=StorageConfig)
    gas_estimator: GasEstimatorConfig = Field(default_factory=GasEstimatorConfig)
    event_listener: EventListenerConfig = Field(default_factory=EventListenerConfig)
    indexer: IndexerConfig = Field(default_factory=IndexerConfig)
    wallet: WalletConfig = Field(default_factory=WalletConfig)

    model_config = {
        "env_prefix": "BCI_",
        "env_file": ".env",
        "env_nested_delimiter": "__",
    }


def load_config(config_path: Optional[str] = None) -> Settings:
    config_path = config_path or os.environ.get("BCI_CONFIG_PATH")

    if config_path and Path(config_path).exists():
        with open(config_path, "r", encoding="utf-8") as f:
            config_data = yaml.safe_load(f) or {}
        return Settings(**config_data)

    default_paths = [
        Path.cwd() / "configs" / "config.yaml",
        Path.cwd() / "config.yaml",
        Path(__file__).parent.parent.parent / "configs" / "config.yaml",
    ]

    for path in default_paths:
        if path.exists():
            with open(path, "r", encoding="utf-8") as f:
                config_data = yaml.safe_load(f) or {}
            return Settings(**config_data)

    return Settings()


def get_chain_config(chain: Chain) -> ChainConfig:
    settings = load_config()
    chain_key = chain.value if isinstance(chain, Chain) else str(chain)
    if chain_key not in settings.chains:
        raise ValueError(f"Chain {chain_key} not configured")
    return settings.chains[chain_key]


settings = load_config()
