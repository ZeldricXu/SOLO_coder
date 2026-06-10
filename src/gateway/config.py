from functools import lru_cache
from typing import Any, Dict, List, Optional
from pydantic import Field, field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class DatabaseSettings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="POSTGRES_", extra="ignore")

    host: str = "localhost"
    port: int = 5432
    user: str = "postgres"
    password: str = "postgres"
    database: str = "api_gateway"
    pool_size: int = 20
    max_overflow: int = 10

    @property
    def dsn(self) -> str:
        return f"postgresql+asyncpg://{self.user}:{self.password}@{self.host}:{self.port}/{self.database}"


class RedisSettings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="REDIS_", extra="ignore")

    host: str = "localhost"
    port: int = 6379
    password: Optional[str] = None
    db: int = 0
    max_connections: int = 50
    decode_responses: bool = True

    @property
    def url(self) -> str:
        if self.password:
            return f"redis://:{self.password}@{self.host}:{self.port}/{self.db}"
        return f"redis://{self.host}:{self.port}/{self.db}"


class ClickHouseSettings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="CLICKHOUSE_", extra="ignore")

    host: str = "localhost"
    port: int = 8123
    user: str = "default"
    password: str = ""
    database: str = "api_gateway"
    secure: bool = False
    connect_timeout: int = 10
    send_receive_timeout: int = 30

    @property
    def dsn(self) -> str:
        scheme = "https" if self.secure else "http"
        if self.password:
            return f"{scheme}://{self.user}:{self.password}@{self.host}:{self.port}/{self.database}"
        return f"{scheme}://{self.host}:{self.port}/{self.database}"


class JWTSettings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="JWT_", extra="ignore")

    secret_key: str = "your-secret-key-change-in-production"
    algorithm: str = "HS256"
    access_token_expire_minutes: int = 30
    refresh_token_expire_days: int = 7
    issuer: str = "api-gateway"
    audience: str = "api-services"


class RateLimitDimension(BaseSettings):
    name: str
    resolver: str
    enabled: bool = True
    pattern: Optional[str] = None


class RateLimitSettings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="RATE_LIMIT_", extra="ignore")

    default_user_limit: int = 1000
    default_api_limit: int = 10000
    burst_multiplier: float = 2.0
    window_seconds: int = 60
    redis_key_prefix: str = "rate_limit:"

    multi_dimension_enabled: bool = False
    dimensions: List[RateLimitDimension] = Field(default_factory=lambda: [
        RateLimitDimension(name="user_id", resolver="user_id", enabled=True),
        RateLimitDimension(name="api_path", resolver="api_path", enabled=True),
        RateLimitDimension(name="ip", resolver="ip", enabled=False),
        RateLimitDimension(name="api_key", resolver="api_key", enabled=False),
        RateLimitDimension(name="service_name", resolver="header", enabled=False, pattern="X-Service-Name"),
    ])
    dimension_separator: str = ":"
    pattern_rules: List[Dict[str, Any]] = Field(default_factory=list)


class CircuitBreakerSettings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="CIRCUIT_BREAKER_", extra="ignore")

    failure_threshold: float = 0.5
    slow_request_threshold: float = 0.5
    slow_request_duration: float = 5.0
    wait_duration_in_open_state: int = 30
    permitted_num_of_calls_in_half_open: int = 5
    rolling_window_size: int = 100
    redis_key_prefix: str = "circuit_breaker:"


class AuthStrategy(BaseSettings):
    path_prefix: str
    strategy: str
    idp: Optional[str] = None
    mtls_ca_cert: Optional[str] = None


class GatewaySettings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="GATEWAY_", extra="ignore")

    host: str = "0.0.0.0"
    port: int = 8080
    workers: int = 4
    log_level: str = "info"
    request_timeout: int = 30
    max_request_size: int = 10 * 1024 * 1024
    route_reload_interval: int = 5
    admin_api_key: str = "admin-api-key-change-in-production"

    auth_strategies: List[AuthStrategy] = Field(default_factory=lambda: [
        AuthStrategy(path_prefix="/api/internal", strategy="mtls"),
        AuthStrategy(path_prefix="/api/public", strategy="jwt", idp="default"),
        AuthStrategy(path_prefix="/api/admin", strategy="api_key"),
    ])

    cors_origins: List[str] = Field(default_factory=lambda: ["*"])
    cors_methods: List[str] = Field(default_factory=lambda: ["GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"])
    cors_headers: List[str] = Field(default_factory=lambda: ["*"])


class SecurityFilterSettings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="SECURITY_FILTER_", extra="ignore")

    enabled: bool = False
    mode: str = "block"
    default_action: str = "block"
    scan_body: bool = True
    scan_query: bool = True
    scan_headers: bool = True

    owasp_top10_enabled: bool = True
    custom_rules_path: Optional[str] = None

    remote_rules_enabled: bool = False
    remote_rules_url: Optional[str] = None
    remote_rules_refresh_interval: int = 300
    remote_rules_auth_token: Optional[str] = None

    sql_injection_enabled: bool = True
    xss_enabled: bool = True
    path_traversal_enabled: bool = True
    command_injection_enabled: bool = True
    ssrf_enabled: bool = False

    blocked_response_code: int = 403
    blocked_response_message: str = "Request blocked by security filter"

    log_blocked_requests: bool = True
    log_cleaned_requests: bool = False


class WebhookSettings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="WEBHOOK_", extra="ignore")

    enabled: bool = False
    url: Optional[str] = None
    secret: Optional[str] = None
    timeout: int = 5
    max_retries: int = 3
    retry_backoff: float = 1.0

    events: List[str] = Field(default_factory=lambda: [
        "api_key.created",
        "api_key.approved",
        "api_key.rejected",
        "api_key.activated",
        "api_key.expired",
    ])


class DeveloperPortalSettings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="PORTAL_", extra="ignore")

    enabled: bool = True

    api_key_plans: List[Dict[str, Any]] = Field(default_factory=lambda: [
        {
            "id": "free",
            "name": "Free Tier",
            "description": "Free tier for developers",
            "rate_limit_quota": 100,
            "price": 0,
            "requires_approval": True,
        },
        {
            "id": "basic",
            "name": "Basic Plan",
            "description": "Basic plan for small teams",
            "rate_limit_quota": 1000,
            "price": 99,
            "requires_approval": True,
        },
        {
            "id": "enterprise",
            "name": "Enterprise Plan",
            "description": "Enterprise plan for large organizations",
            "rate_limit_quota": 10000,
            "price": 999,
            "requires_approval": True,
        },
    ])

    approval_required: bool = True
    auto_activate_on_approval: bool = True

    notification_email_from: Optional[str] = None
    notification_email_template: Optional[str] = None


class AnalyticsSettings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="ANALYTICS_", extra="ignore")

    enabled: bool = True
    batch_size: int = 1000
    flush_interval: int = 5
    max_retries: int = 3
    retry_backoff: float = 0.5


class Settings(BaseSettings):
    db: DatabaseSettings = Field(default_factory=DatabaseSettings)
    redis: RedisSettings = Field(default_factory=RedisSettings)
    clickhouse: ClickHouseSettings = Field(default_factory=ClickHouseSettings)
    jwt: JWTSettings = Field(default_factory=JWTSettings)
    rate_limit: RateLimitSettings = Field(default_factory=RateLimitSettings)
    circuit_breaker: CircuitBreakerSettings = Field(default_factory=CircuitBreakerSettings)
    gateway: GatewaySettings = Field(default_factory=GatewaySettings)
    analytics: AnalyticsSettings = Field(default_factory=AnalyticsSettings)
    security_filter: SecurityFilterSettings = Field(default_factory=SecurityFilterSettings)
    webhook: WebhookSettings = Field(default_factory=WebhookSettings)
    portal: DeveloperPortalSettings = Field(default_factory=DeveloperPortalSettings)

    model_config = SettingsConfigDict(env_nested_delimiter="__", extra="ignore")


@lru_cache()
def get_settings() -> Settings:
    return Settings()
