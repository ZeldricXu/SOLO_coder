from typing import Dict, List, Optional
from pydantic import Field, field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class DatabaseSettings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="NFTINDEXER_DB_", extra="ignore")

    url: str = Field(default="sqlite+aiosqlite:///./data/nftindexer.db")
    pool_size: int = Field(default=20)
    max_overflow: int = Field(default=10)
    pool_recycle: int = Field(default=3600)
    echo: bool = Field(default=False)


class RedisSettings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="NFTINDEXER_REDIS_", extra="ignore")

    url: str = Field(default="redis://localhost:6379/0")
    max_connections: int = Field(default=50)
    socket_timeout: int = Field(default=5)
    socket_connect_timeout: int = Field(default=5)


class CelerySettings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="NFTINDEXER_CELERY_", extra="ignore")

    broker_url: str = Field(default="redis://localhost:6379/1")
    result_backend: str = Field(default="redis://localhost:6379/2")
    timezone: str = Field(default="UTC")
    task_serializer: str = Field(default="json")
    result_serializer: str = Field(default="json")
    accept_content: List[str] = Field(default_factory=lambda: ["json"])
    task_track_started: bool = Field(default=True)
    task_time_limit: int = Field(default=3600)
    worker_prefetch_multiplier: int = Field(default=1)
    worker_max_tasks_per_child: int = Field(default=1000)


class ChainRPCConfig(BaseSettings):
    chain_id: int
    name: str
    rpc_url: str
    ws_url: Optional[str] = None
    block_time: int = Field(default=2)
    confirmations: int = Field(default=12)
    max_retry: int = Field(default=5)
    timeout: int = Field(default=30)


class ChainSettings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="NFTINDEXER_CHAIN_", extra="ignore")

    chains: Dict[int, ChainRPCConfig] = Field(
        default_factory=lambda: {
            1: ChainRPCConfig(
                chain_id=1,
                name="ethereum",
                rpc_url="https://eth.llamarpc.com",
                ws_url="wss://eth.llamarpc.com",
                block_time=12,
                confirmations=12,
            ),
            5: ChainRPCConfig(
                chain_id=5,
                name="goerli",
                rpc_url="https://goerli.llamarpc.com",
                block_time=12,
                confirmations=12,
            ),
            137: ChainRPCConfig(
                chain_id=137,
                name="polygon",
                rpc_url="https://polygon.llamarpc.com",
                block_time=2,
                confirmations=64,
            ),
            42161: ChainRPCConfig(
                chain_id=42161,
                name="arbitrum",
                rpc_url="https://arbitrum.llamarpc.com",
                block_time=0.25,
                confirmations=32,
            ),
        }
    )
    default_chain_id: int = Field(default=1)

    @field_validator("chains", mode="before")
    @classmethod
    def parse_chains(cls, v):
        if isinstance(v, dict):
            return {int(k): ChainRPCConfig(**v2) for k, v2 in v.items()}
        return v


class StorageSettings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="NFTINDEXER_STORAGE_", extra="ignore")

    ipfs_gateway: str = Field(default="https://ipfs.io/ipfs/")
    ipfs_rpc: str = Field(default="http://localhost:5001")
    arweave_gateway: str = Field(default="https://arweave.net/")
    pinata_api_key: Optional[str] = None
    pinata_secret_api_key: Optional[str] = None
    default_storage: str = Field(default="ipfs")


class HDWalletSettings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="NFTINDEXER_WALLET_", extra="ignore")

    mnemonic: Optional[str] = None
    passphrase: str = Field(default="")
    derivation_path: str = Field(default="m/44'/60'/0'/0/{index}")
    default_address_count: int = Field(default=10)
    hardened_derivation: bool = Field(default=True)


class ZKPSettings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="NFTINDEXER_ZKP_", extra="ignore")

    groth16_verifier_contract: Optional[str] = None
    plonk_verifier_contract: Optional[str] = None
    circuits_dir: str = Field(default="./data/circuits")
    verification_keys_dir: str = Field(default="./data/keys")
    max_proof_size: int = Field(default=10 * 1024 * 1024)


class GasSettings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="NFTINDEXER_GAS_", extra="ignore")

    history_window_blocks: int = Field(default=100)
    percentile: int = Field(default=60)
    max_priority_fee_multiplier: float = Field(default=1.2)
    max_fee_multiplier: float = Field(default=2.0)
    cache_ttl: int = Field(default=15)
    fallback_gas_price: int = Field(default=20_000_000_000)
    min_gas_price: int = Field(default=1_000_000_000)
    max_gas_price: int = Field(default=1_000_000_000_000)


class MultiSigSettings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="NFTINDEXER_MULTISIG_", extra="ignore")

    min_signers: int = Field(default=2)
    max_signers: int = Field(default=20)
    default_confirmations_required: int = Field(default=2)
    proposal_expiry_blocks: int = Field(default=10000)
    execution_timeout: int = Field(default=300)
    max_retry_attempts: int = Field(default=3)


class EventListenerSettings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="NFTINDEXER_EVENTS_", extra="ignore")

    max_concurrent_filters: int = Field(default=100)
    poll_interval: float = Field(default=2.0)
    max_blocks_per_poll: int = Field(default=100)
    retry_interval: int = Field(default=5)
    max_retry_interval: int = Field(default=300)
    backoff_multiplier: float = Field(default=2.0)
    max_retries: int = Field(default=10)


class CrossChainSettings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="NFTINDEXER_CROSSCHAIN_", extra="ignore")

    message_ttl: int = Field(default=86400)
    min_confirmations_source: int = Field(default=12)
    min_confirmations_target: int = Field(default=12)
    relayer_grace_period: int = Field(default=3600)
    challenge_period: int = Field(default=86400)
    max_parallel_bridges: int = Field(default=10)
    atomic_commit_timeout: int = Field(default=300)


class IndexerSettings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="NFTINDEXER_INDEXER_", extra="ignore")

    start_block: int = Field(default=0)
    batch_size: int = Field(default=50)
    max_concurrent_blocks: int = Field(default=10)
    retry_delay: int = Field(default=5)
    max_retries: int = Field(default=10)
    checkpoint_interval: int = Field(default=100)
    enable_mempool_indexing: bool = Field(default=False)
    include_transactions: bool = Field(default=True)
    include_logs: bool = Field(default=True)
    include_traces: bool = Field(default=False)


class APISettings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="NFTINDEXER_API_", extra="ignore")

    host: str = Field(default="0.0.0.0")
    port: int = Field(default=8000)
    workers: int = Field(default=4)
    reload: bool = Field(default=False)
    cors_origins: List[str] = Field(default_factory=lambda: ["*"])
    cors_methods: List[str] = Field(default_factory=lambda: ["*"])
    cors_headers: List[str] = Field(default_factory=lambda: ["*"])
    rate_limit_per_minute: int = Field(default=1000)
    request_timeout: int = Field(default=60)
    docs_url: str = Field(default="/docs")
    redoc_url: str = Field(default="/redoc")
    openapi_url: str = Field(default="/openapi.json")


class LoggingSettings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="NFTINDEXER_LOG_", extra="ignore")

    level: str = Field(default="INFO")
    format: str = Field(default="json")
    file_path: str = Field(default="./data/logs/nftindexer.log")
    rotation: str = Field(default="100 MB")
    retention: str = Field(default="30 days")
    compression: str = Field(default="gzip")


class ObservabilitySettings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="NFTINDEXER_OBS_", extra="ignore")

    enable_metrics: bool = Field(default=True)
    enable_tracing: bool = Field(default=False)
    metrics_port: int = Field(default=9090)
    otlp_endpoint: Optional[str] = None
    service_name: str = Field(default="nftindexer")


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="NFTINDEXER_", env_nested_delimiter="__", extra="ignore")

    app_name: str = Field(default="NFTIndexer")
    environment: str = Field(default="development")
    debug: bool = Field(default=False)
    secret_key: str = Field(default="change-me-in-production-please")

    api: APISettings = Field(default_factory=APISettings)
    db: DatabaseSettings = Field(default_factory=DatabaseSettings)
    redis: RedisSettings = Field(default_factory=RedisSettings)
    celery: CelerySettings = Field(default_factory=CelerySettings)
    chain: ChainSettings = Field(default_factory=ChainSettings)
    storage: StorageSettings = Field(default_factory=StorageSettings)
    wallet: HDWalletSettings = Field(default_factory=HDWalletSettings)
    zkp: ZKPSettings = Field(default_factory=ZKPSettings)
    gas: GasSettings = Field(default_factory=GasSettings)
    multisig: MultiSigSettings = Field(default_factory=MultiSigSettings)
    events: EventListenerSettings = Field(default_factory=EventListenerSettings)
    crosschain: CrossChainSettings = Field(default_factory=CrossChainSettings)
    indexer: IndexerSettings = Field(default_factory=IndexerSettings)
    logging: LoggingSettings = Field(default_factory=LoggingSettings)
    obs: ObservabilitySettings = Field(default_factory=ObservabilitySettings)


_settings: Optional[Settings] = None


def get_settings() -> Settings:
    global _settings
    if _settings is None:
        _settings = Settings()
    return _settings


def reload_settings() -> Settings:
    global _settings
    _settings = Settings()
    return _settings
