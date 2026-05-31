from enum import Enum
from typing import Any, Dict, List, Optional
from urllib.parse import urlparse

from pydantic import Field, SecretStr, field_validator, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class AppEnv(str, Enum):
    DEVELOPMENT = "development"
    STAGING = "staging"
    PRODUCTION = "production"


class LogFormat(str, Enum):
    JSON = "json"
    CONSOLE = "console"


class ChainConfig(BaseSettings):
    name: str
    rpc_url: str
    chain_id: int
    block_time: float = 2.0
    native_symbol: str = "ETH"
    explorer_url: Optional[str] = None


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        case_sensitive=True,
        extra="ignore",
    )

    APP_ENV: AppEnv = AppEnv.DEVELOPMENT
    APP_NAME: str = "gas-estimator-platform"
    APP_HOST: str = "0.0.0.0"
    APP_PORT: int = 8000
    API_V1_PREFIX: str = "/api/v1"
    DEBUG: bool = False

    DATABASE_URL: str = "postgresql+asyncpg://postgres:postgres@localhost:5432/gas_estimator"
    DATABASE_POOL_SIZE: int = 10
    DATABASE_MAX_OVERFLOW: int = 20
    DATABASE_POOL_TIMEOUT: int = 30

    REDIS_URL: str = "redis://localhost:6379/0"
    REDIS_POOL_SIZE: int = 50
    REDIS_TIMEOUT: int = 10

    CELERY_BROKER_URL: str = "redis://localhost:6379/1"
    CELERY_RESULT_BACKEND: str = "redis://localhost:6379/2"

    ETHEREUM_RPC_URL: str = "https://eth-mainnet.alchemyapi.io/v2/demo"
    POLYGON_RPC_URL: str = "https://polygon-mainnet.g.alchemy.com/v2/demo"
    ARBITRUM_RPC_URL: str = "https://arb-mainnet.g.alchemy.com/v2/demo"
    OPTIMISM_RPC_URL: str = "https://opt-mainnet.g.alchemy.com/v2/demo"
    BSC_RPC_URL: str = "https://bsc-dataseed.binance.org/"

    IPFS_URL: str = "http://localhost:5001"
    ARWEAVE_NODE_URL: str = "https://arweave.net"
    ARWEAVE_WALLET_PATH: str = "./arweave-wallet.json"

    HD_WALLET_MNEMONIC: SecretStr = Field(default="test test test test test test test test test test test junk")
    HD_WALLET_PASSPHRASE: SecretStr = Field(default="")
    HD_WALLET_DERIVATION_PATH: str = "m/44'/60'/0'/0"

    ZKP_CIRCUIT_PATH: str = "./circuits"
    ZKP_VERIFICATION_KEY_PATH: str = "./keys"

    LOG_LEVEL: str = "INFO"
    LOG_FORMAT: LogFormat = LogFormat.JSON

    METRICS_ENABLED: bool = True
    METRICS_PORT: int = 9090

    TRACING_ENABLED: bool = False
    TRACING_SERVICE_NAME: str = "gas-estimator"

    RATE_LIMIT_PER_MINUTE: int = 1000
    REQUEST_TIMEOUT: int = 30
    MAX_RETRIES: int = 3
    CIRCUIT_BREAKER_THRESHOLD: int = 5
    CIRCUIT_BREAKER_RECOVERY_TIMEOUT: int = 60

    GAS_HISTORY_DAYS: int = 30
    GAS_PREDICTION_MODEL: str = "linear"
    GAS_CACHE_TTL: int = 300

    BRIDGE_CONFIRMATIONS: int = 12
    BRIDGE_RELEASE_DELAY: int = 3600

    EVENT_POLL_INTERVAL: int = 15
    EVENT_BATCH_SIZE: int = 100

    @field_validator("DATABASE_URL", "REDIS_URL", "CELERY_BROKER_URL", "CELERY_RESULT_BACKEND")
    @classmethod
    def validate_url(cls, v: str) -> str:
        if not v:
            return v
        parsed = urlparse(v)
        if not parsed.scheme or not parsed.netloc:
            raise ValueError(f"Invalid URL: {v}")
        return v

    @field_validator("LOG_LEVEL")
    @classmethod
    def validate_log_level(cls, v: str) -> str:
        levels = ["DEBUG", "INFO", "WARNING", "ERROR", "CRITICAL"]
        if v.upper() not in levels:
            raise ValueError(f"Invalid log level. Must be one of: {levels}")
        return v.upper()

    @model_validator(mode="after")
    def validate_chain_configs(self) -> "Settings":
        return self

    @property
    def chain_configs(self) -> Dict[str, ChainConfig]:
        return {
            "ethereum": ChainConfig(
                name="ethereum",
                rpc_url=self.ETHEREUM_RPC_URL,
                chain_id=1,
                block_time=12.0,
                native_symbol="ETH",
                explorer_url="https://etherscan.io",
            ),
            "polygon": ChainConfig(
                name="polygon",
                rpc_url=self.POLYGON_RPC_URL,
                chain_id=137,
                block_time=2.1,
                native_symbol="MATIC",
                explorer_url="https://polygonscan.com",
            ),
            "arbitrum": ChainConfig(
                name="arbitrum",
                rpc_url=self.ARBITRUM_RPC_URL,
                chain_id=42161,
                block_time=0.25,
                native_symbol="ETH",
                explorer_url="https://arbiscan.io",
            ),
            "optimism": ChainConfig(
                name="optimism",
                rpc_url=self.OPTIMISM_RPC_URL,
                chain_id=10,
                block_time=2.0,
                native_symbol="ETH",
                explorer_url="https://optimistic.etherscan.io",
            ),
            "bsc": ChainConfig(
                name="bsc",
                rpc_url=self.BSC_RPC_URL,
                chain_id=56,
                block_time=3.0,
                native_symbol="BNB",
                explorer_url="https://bscscan.com",
            ),
        }

    @property
    def is_development(self) -> bool:
        return self.APP_ENV == AppEnv.DEVELOPMENT

    @property
    def is_production(self) -> bool:
        return self.APP_ENV == AppEnv.PRODUCTION


settings = Settings()
