from functools import lru_cache
from typing import Dict, List, Optional
from pydantic import Field, field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class ChainConfig(BaseSettings):
    chain_id: int
    name: str
    rpc_url: str
    symbol: str = "ETH"
    block_time: int = 12
    explorer_url: Optional[str] = None


class DatabaseConfig(BaseSettings):
    url: str = "sqlite+aiosqlite:///./wallethub.db"
    echo: bool = False
    pool_size: int = 10
    max_overflow: int = 20


class RedisConfig(BaseSettings):
    host: str = "localhost"
    port: int = 6379
    db: int = 0
    password: Optional[str] = None
    url: Optional[str] = None


class IPFSConfig(BaseSettings):
    gateway_url: str = "https://ipfs.io/ipfs/"
    api_url: str = "http://localhost:5001"
    pinata_api_key: Optional[str] = None
    pinata_secret_api_key: Optional[str] = None


class ArweaveConfig(BaseSettings):
    gateway_url: str = "https://arweave.net/"
    wallet_path: Optional[str] = None


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        env_nested_delimiter="__",
        extra="ignore"
    )

    app_name: str = "WalletHub"
    environment: str = "development"
    debug: bool = False
    log_level: str = "info"

    api_host: str = "0.0.0.0"
    api_port: int = 8000

    secret_key: str = "dev-secret-key-change-in-production"
    access_token_expire_minutes: int = 30

    database: DatabaseConfig = Field(default_factory=DatabaseConfig)
    redis: RedisConfig = Field(default_factory=RedisConfig)
    ipfs: IPFSConfig = Field(default_factory=IPFSConfig)
    arweave: ArweaveConfig = Field(default_factory=ArweaveConfig)

    chains: Dict[str, ChainConfig] = Field(
        default_factory=lambda: {
            "ethereum": ChainConfig(
                chain_id=1,
                name="Ethereum Mainnet",
                rpc_url="https://eth.llamarpc.com",
                symbol="ETH",
                block_time=12,
                explorer_url="https://etherscan.io"
            ),
            "sepolia": ChainConfig(
                chain_id=11155111,
                name="Ethereum Sepolia",
                rpc_url="https://rpc.sepolia.org",
                symbol="ETH",
                block_time=12,
                explorer_url="https://sepolia.etherscan.io"
            ),
            "polygon": ChainConfig(
                chain_id=137,
                name="Polygon Mainnet",
                rpc_url="https://polygon.llamarpc.com",
                symbol="MATIC",
                block_time=2,
                explorer_url="https://polygonscan.com"
            ),
            "bsc": ChainConfig(
                chain_id=56,
                name="BNB Smart Chain",
                rpc_url="https://bsc-dataseed.binance.org",
                symbol="BNB",
                block_time=3,
                explorer_url="https://bscscan.com"
            )
        }
    )

    default_chain: str = "ethereum"

    gas_estimate_blocks: int = 100
    gas_estimate_percentile: int = 50

    event_listener_poll_interval: float = 2.0
    event_listener_max_blocks_per_poll: int = 100

    max_concurrent_tasks: int = 100
    task_timeout_seconds: int = 300

    @field_validator("chains")
    @classmethod
    def validate_chain_configs(cls, v: Dict[str, ChainConfig]) -> Dict[str, ChainConfig]:
        if not v:
            raise ValueError("At least one chain must be configured")
        return v


@lru_cache()
def get_settings() -> Settings:
    return Settings()
