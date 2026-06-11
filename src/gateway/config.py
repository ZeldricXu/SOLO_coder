"""
网关配置模块

配置加载优先级（从高到低）：
    1. 环境变量（通过 env_prefix 自动映射）
    2. .env 文件（开发环境）
    3. 代码中的默认值

修改配置后的生效方式：
    - 需要重启：进程级配置（端口、Worker 数、中间件开关等）
    - 无需重启：路由级配置（热更新，5 秒内生效）、动态加载的配置
"""

from functools import lru_cache
from typing import Any, Dict, List, Optional
from pydantic import Field, field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class DatabaseSettings(BaseSettings):
    """
    PostgreSQL 数据库配置

    环境变量前缀：POSTGRES_
    修改后是否需要重启：是
    """

    model_config = SettingsConfigDict(env_prefix="POSTGRES_", extra="ignore")

    # 数据库主机地址
    # 环境变量：POSTGRES_HOST
    # 默认值：localhost
    host: str = "localhost"

    # 数据库端口
    # 环境变量：POSTGRES_PORT
    # 默认值：5432
    port: int = 5432

    # 数据库用户名
    # 环境变量：POSTGRES_USER
    # 默认值：postgres
    user: str = "postgres"

    # 数据库密码 ⚠️ 敏感配置
    # 环境变量：POSTGRES_PASSWORD
    # 生产环境必须通过 K8s Secret 或环境变量注入，禁止写死在配置文件中
    # 默认值：postgres（仅开发环境）
    password: str = "postgres"

    # 数据库名
    # 环境变量：POSTGRES_DATABASE
    # 默认值：api_gateway
    database: str = "api_gateway"

    # 连接池大小
    # 环境变量：POSTGRES_POOL_SIZE
    # 默认值：20
    pool_size: int = 20

    # 连接池最大溢出数
    # 环境变量：POSTGRES_MAX_OVERFLOW
    # 默认值：10
    max_overflow: int = 10

    @property
    def dsn(self) -> str:
        """
        构造异步 PostgreSQL DSN 连接字符串

        Returns:
            postgresql+asyncpg://user:password@host:port/database
        """
        return f"postgresql+asyncpg://{self.user}:{self.password}@{self.host}:{self.port}/{self.database}"


class RedisSettings(BaseSettings):
    """
    Redis 配置

    环境变量前缀：REDIS_
    修改后是否需要重启：是
    """

    model_config = SettingsConfigDict(env_prefix="REDIS_", extra="ignore")

    # Redis 主机地址
    # 环境变量：REDIS_HOST
    # 默认值：localhost
    host: str = "localhost"

    # Redis 端口
    # 环境变量：REDIS_PORT
    # 默认值：6379
    port: int = 6379

    # Redis 密码 ⚠️ 敏感配置（可空）
    # 环境变量：REDIS_PASSWORD
    # 生产环境建议设置密码，通过 K8s Secret 注入
    # 默认值：None（无密码）
    password: Optional[str] = None

    # Redis 数据库编号
    # 环境变量：REDIS_DB
    # 可选值：0 ~ 15
    # 默认值：0
    db: int = 0

    # 最大连接数
    # 环境变量：REDIS_MAX_CONNECTIONS
    # 默认值：50
    max_connections: int = 50

    # 是否自动解码响应（返回 str 而非 bytes）
    # 环境变量：REDIS_DECODE_RESPONSES
    # 默认值：True
    decode_responses: bool = True

    @property
    def url(self) -> str:
        """
        构造 Redis URL 连接字符串

        Returns:
            redis://[:password@]host:port/db
        """
        if self.password:
            return f"redis://:{self.password}@{self.host}:{self.port}/{self.db}"
        return f"redis://{self.host}:{self.port}/{self.db}"


class ClickHouseSettings(BaseSettings):
    """
    ClickHouse 分析数据库配置

    环境变量前缀：CLICKHOUSE_
    修改后是否需要重启：是
    """

    model_config = SettingsConfigDict(env_prefix="CLICKHOUSE_", extra="ignore")

    # ClickHouse 主机地址
    # 环境变量：CLICKHOUSE_HOST
    # 默认值：localhost
    host: str = "localhost"

    # ClickHouse HTTP 端口
    # 环境变量：CLICKHOUSE_PORT
    # 默认值：8123
    port: int = 8123

    # ClickHouse 用户名
    # 环境变量：CLICKHOUSE_USER
    # 默认值：default
    user: str = "default"

    # ClickHouse 密码 ⚠️ 敏感配置（可空）
    # 环境变量：CLICKHOUSE_PASSWORD
    # 默认值：""（空）
    password: str = ""

    # ClickHouse 数据库名
    # 环境变量：CLICKHOUSE_DATABASE
    # 默认值：api_gateway
    database: str = "api_gateway"

    # 是否启用 TLS 加密连接
    # 环境变量：CLICKHOUSE_SECURE
    # 默认值：False
    secure: bool = False

    # 连接超时时间（秒）
    # 环境变量：CLICKHOUSE_CONNECT_TIMEOUT
    # 默认值：10
    connect_timeout: int = 10

    # 发送接收超时时间（秒）
    # 环境变量：CLICKHOUSE_SEND_RECEIVE_TIMEOUT
    # 默认值：30
    send_receive_timeout: int = 30

    @property
    def dsn(self) -> str:
        """
        构造 ClickHouse HTTP DSN

        Returns:
            http[s]://[user:password@]host:port/database
        """
        scheme = "https" if self.secure else "http"
        if self.password:
            return f"{scheme}://{self.user}:{self.password}@{self.host}:{self.port}/{self.database}"
        return f"{scheme}://{self.host}:{self.port}/{self.database}"


class JWTSettings(BaseSettings):
    """
    JWT 认证配置

    环境变量前缀：JWT_
    修改后是否需要重启：是
    """

    model_config = SettingsConfigDict(env_prefix="JWT_", extra="ignore")

    # JWT 签名密钥 ⚠️ 敏感配置
    # 环境变量：JWT_SECRET_KEY
    # ⚠️ 生产环境必须使用强随机密钥，通过 K8s Secret 注入
    # 默认值：弱密钥（仅开发环境使用）
    secret_key: str = "your-secret-key-change-in-production"

    # JWT 签名算法
    # 环境变量：JWT_ALGORITHM
    # 可选值：HS256, HS384, HS512, RS256, RS384, RS512
    # 默认值：HS256
    algorithm: str = "HS256"

    # Access Token 过期时间（分钟）
    # 环境变量：JWT_ACCESS_TOKEN_EXPIRE_MINUTES
    # 默认值：30
    access_token_expire_minutes: int = 30

    # Refresh Token 过期时间（天）
    # 环境变量：JWT_REFRESH_TOKEN_EXPIRE_DAYS
    # 默认值：7
    refresh_token_expire_days: int = 7

    # JWT Issuer（签发者）
    # 环境变量：JWT_ISSUER
    # 默认值：api-gateway
    issuer: str = "api-gateway"

    # JWT Audience（受众）
    # 环境变量：JWT_AUDIENCE
    # 默认值：api-services
    audience: str = "api-services"


class RateLimitDimension(BaseSettings):
    """
    多维度限流的单个维度配置

    用于定义一个限流维度（如 user_id、ip、api_key 等）
    """

    # 维度名称（唯一标识）
    name: str

    # 使用的解析器名称
    # 可选值：user_id, ip, api_key, header, api_path
    resolver: str

    # 是否启用该维度
    # 默认值：True
    enabled: bool = True

    # 维度参数（如 header 解析器需要指定 header 名）
    pattern: Optional[str] = None


class RateLimitSettings(BaseSettings):
    """
    限流配置

    环境变量前缀：RATE_LIMIT_
    修改后是否需要重启：是（多维度开关、维度配置）
                     否（路由级限流阈值，热更新）
    """

    model_config = SettingsConfigDict(env_prefix="RATE_LIMIT_", extra="ignore")

    # 默认每用户限流阈值（请求数/窗口）
    # 环境变量：RATE_LIMIT_DEFAULT_USER_LIMIT
    # 默认值：1000
    default_user_limit: int = 1000

    # 默认每 API 限流阈值（请求数/窗口）
    # 环境变量：RATE_LIMIT_DEFAULT_API_LIMIT
    # 默认值：10000
    default_api_limit: int = 10000

    # 突发流量倍数（令牌桶满时允许短时超发的倍数）
    # 环境变量：RATE_LIMIT_BURST_MULTIPLIER
    # 可选值：1.0 ~ 10.0
    # 默认值：2.0（允许 2 倍突发）
    burst_multiplier: float = 2.0

    # 限流窗口大小（秒）
    # 环境变量：RATE_LIMIT_WINDOW_SECONDS
    # 默认值：60（1 分钟窗口）
    window_seconds: int = 60

    # Redis 中限流 Key 的前缀
    # 环境变量：RATE_LIMIT_REDIS_KEY_PREFIX
    # 默认值：rate_limit:
    redis_key_prefix: str = "rate_limit:"

    # 是否启用多维度限流
    # 环境变量：RATE_LIMIT_MULTI_DIMENSION_ENABLED
    # 启用后使用 CompositeKeyResolver 组合多个维度生成限流 key
    # 默认值：False（使用传统的用户+API二级限流，向后兼容）
    multi_dimension_enabled: bool = False

    # 多维度限流的维度列表
    # 按数组顺序拼接 key，如 ["user_id", "api_path", "ip"]
    # 生成的 key 格式：user_123:api_/api/data:ip_10.0.1.5
    dimensions: List[RateLimitDimension] = Field(default_factory=lambda: [
        RateLimitDimension(name="user_id", resolver="user_id", enabled=True),
        RateLimitDimension(name="api_path", resolver="api_path", enabled=True),
        RateLimitDimension(name="ip", resolver="ip", enabled=False),
        RateLimitDimension(name="api_key", resolver="api_key", enabled=False),
        RateLimitDimension(name="service_name", resolver="header", enabled=False, pattern="X-Service-Name"),
    ])

    # 多维度 key 各部分之间的分隔符
    # 环境变量：RATE_LIMIT_DIMENSION_SEPARATOR
    # 默认值：":"
    dimension_separator: str = ":"

    # Pattern 批量规则列表
    # 用于给一组匹配特定 pattern 的 key 单独设置限流值
    # 格式示例：
    # [
    #   {"pattern": "user_*:api_/api/export*", "key_prefix": "export_group", "limit": 100}
    # ]
    # 环境变量：不支持，需通过代码或配置文件设置
    pattern_rules: List[Dict[str, Any]] = Field(default_factory=list)


class CircuitBreakerSettings(BaseSettings):
    """
    熔断器配置

    环境变量前缀：CIRCUIT_BREAKER_
    修改后是否需要重启：是
    """

    model_config = SettingsConfigDict(env_prefix="CIRCUIT_BREAKER_", extra="ignore")

    # 错误率阈值（触发熔断的错误比例）
    # 环境变量：CIRCUIT_BREAKER_FAILURE_THRESHOLD
    # 可选值：0.0 ~ 1.0
    # 默认值：0.5（50% 错误率触发熔断）
    failure_threshold: float = 0.5

    # 慢请求比例阈值
    # 环境变量：CIRCUIT_BREAKER_SLOW_REQUEST_THRESHOLD
    # 可选值：0.0 ~ 1.0
    # 默认值：0.5（50% 慢请求触发熔断）
    slow_request_threshold: float = 0.5

    # 慢请求定义（秒）
    # 环境变量：CIRCUIT_BREAKER_SLOW_REQUEST_DURATION
    # 默认值：5.0（超过 5 秒算慢请求）
    slow_request_duration: float = 5.0

    # 熔断 Open 状态持续时间（秒）
    # 环境变量：CIRCUIT_BREAKER_WAIT_DURATION_IN_OPEN_STATE
    # 默认值：30（30 秒后进入半开状态试探）
    wait_duration_in_open_state: int = 30

    # 半开状态允许通过的探测请求数
    # 环境变量：CIRCUIT_BREAKER_PERMITTED_NUM_OF_CALLS_IN_HALF_OPEN
    # 默认值：5（半开时放 5 个请求试探）
    permitted_num_of_calls_in_half_open: int = 5

    # 统计滚动窗口大小（请求数）
    # 环境变量：CIRCUIT_BREAKER_ROLLING_WINDOW_SIZE
    # 默认值：100（最近 100 个请求统计错误率）
    rolling_window_size: int = 100

    # Redis 中熔断器状态 Key 的前缀
    # 环境变量：CIRCUIT_BREAKER_REDIS_KEY_PREFIX
    # 默认值：circuit_breaker:
    redis_key_prefix: str = "circuit_breaker:"


class AuthStrategy(BaseSettings):
    """
    单条认证策略配置

    每条策略绑定一个路径前缀和对应的认证方式。
    匹配规则：最长前缀匹配。
    """

    # 路径前缀（如 /api/public, /api/internal）
    path_prefix: str

    # 认证策略类型
    # 可选值：jwt, oauth2, mtls, api_key, none（公开）
    strategy: str

    # 使用的 IdP 标识（OAuth2 策略时指定）
    # 对应 idp_configs 表中的 id
    idp: Optional[str] = None

    # mTLS 的 CA 证书内容（mTLS 策略时使用）
    mtls_ca_cert: Optional[str] = None


class GatewaySettings(BaseSettings):
    """
    网关全局配置

    环境变量前缀：GATEWAY_
    修改后是否需要重启：是
    """

    model_config = SettingsConfigDict(env_prefix="GATEWAY_", extra="ignore")

    # 监听地址
    # 环境变量：GATEWAY_HOST
    # 默认值：0.0.0.0
    host: str = "0.0.0.0"

    # 监听端口
    # 环境变量：GATEWAY_PORT
    # 默认值：8080
    port: int = 8080

    # Worker 进程数（Uvicorn 多进程模式）
    # 环境变量：GATEWAY_WORKERS
    # 建议设置为 CPU 核心数
    # 默认值：4
    workers: int = 4

    # 日志级别
    # 环境变量：GATEWAY_LOG_LEVEL
    # 可选值：debug, info, warning, error, critical
    # 默认值：info
    log_level: str = "info"

    # 请求超时时间（秒）
    # 环境变量：GATEWAY_REQUEST_TIMEOUT
    # 默认值：30
    request_timeout: int = 30

    # 最大请求体大小（字节）
    # 环境变量：GATEWAY_MAX_REQUEST_SIZE
    # 默认值：10MB
    max_request_size: int = 10 * 1024 * 1024

    # 路由配置热更新轮询间隔（秒）
    # 环境变量：GATEWAY_ROUTE_RELOAD_INTERVAL
    # 默认值：5（每 5 秒检查一次数据库变更）
    route_reload_interval: int = 5

    # 管理 API Key ⚠️ 敏感配置
    # 环境变量：GATEWAY_ADMIN_API_KEY
    # 用于访问管理类 API（如路由配置管理、规则热更新等）
    # 默认值：弱密钥（仅开发环境）
    admin_api_key: str = "admin-api-key-change-in-production"

    # 认证策略列表
    # 按路径前缀匹配，最长前缀优先
    auth_strategies: List[AuthStrategy] = Field(default_factory=lambda: [
        AuthStrategy(path_prefix="/api/internal", strategy="mtls"),
        AuthStrategy(path_prefix="/api/public", strategy="jwt", idp="default"),
        AuthStrategy(path_prefix="/api/admin", strategy="api_key"),
    ])

    # CORS 允许的来源
    # 环境变量：GATEWAY_CORS_ORIGINS（逗号分隔）
    # 默认值：["*"]（允许所有，生产环境建议限制）
    cors_origins: List[str] = Field(default_factory=lambda: ["*"])

    # CORS 允许的 HTTP 方法
    # 环境变量：GATEWAY_CORS_METHODS（逗号分隔）
    # 默认值：所有常用方法
    cors_methods: List[str] = Field(default_factory=lambda: ["GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"])

    # CORS 允许的请求头
    # 环境变量：GATEWAY_CORS_HEADERS（逗号分隔）
    # 默认值：["*"]
    cors_headers: List[str] = Field(default_factory=lambda: ["*"])


class SecurityFilterSettings(BaseSettings):
    """
    安全过滤器配置

    环境变量前缀：SECURITY_FILTER_
    修改后是否需要重启：是（开关类配置）
                     否（规则集，支持远程热更新）
    """

    model_config = SettingsConfigDict(env_prefix="SECURITY_FILTER_", extra="ignore")

    # 是否启用安全过滤器
    # 环境变量：SECURITY_FILTER_ENABLED
    # 默认值：False（默认关闭，向后兼容）
    enabled: bool = False

    # 全局工作模式
    # 环境变量：SECURITY_FILTER_MODE
    # 可选值：
    #   - block: 拦截模式，检测到攻击直接返回 403
    #   - sanitize: 清洗模式，将危险字符转义后继续转发
    #   - mixed: 混合模式，高风险规则拦截，低风险规则清洗
    # 默认值：block
    mode: str = "block"

    # 默认动作（规则未指定时使用）
    # 环境变量：SECURITY_FILTER_DEFAULT_ACTION
    # 可选值：block, sanitize
    # 默认值：block
    default_action: str = "block"

    # 是否扫描请求体
    # 环境变量：SECURITY_FILTER_SCAN_BODY
    # 默认值：True
    scan_body: bool = True

    # 是否扫描 Query 参数
    # 环境变量：SECURITY_FILTER_SCAN_QUERY
    # 默认值：True
    scan_query: bool = True

    # 是否扫描请求头
    # 环境变量：SECURITY_FILTER_SCAN_HEADERS
    # 默认值：True
    scan_headers: bool = True

    # 是否启用 OWASP Top-10 规则集
    # 环境变量：SECURITY_FILTER_OWASP_TOP10_ENABLED
    # 默认值：True
    owasp_top10_enabled: bool = True

    # 自定义规则文件路径
    # 环境变量：SECURITY_FILTER_CUSTOM_RULES_PATH
    # JSON 格式的自定义安全规则
    # 默认值：None（无自定义规则）
    custom_rules_path: Optional[str] = None

    # 是否启用远程规则更新
    # 环境变量：SECURITY_FILTER_REMOTE_RULES_ENABLED
    # 默认值：False
    remote_rules_enabled: bool = False

    # 远程规则 URL
    # 环境变量：SECURITY_FILTER_REMOTE_RULES_URL
    # 安全团队的规则仓库地址
    remote_rules_url: Optional[str] = None

    # 远程规则刷新间隔（秒）
    # 环境变量：SECURITY_FILTER_REMOTE_RULES_REFRESH_INTERVAL
    # 默认值：300（5 分钟拉一次）
    remote_rules_refresh_interval: int = 300

    # 远程规则认证 Token ⚠️ 敏感配置
    # 环境变量：SECURITY_FILTER_REMOTE_RULES_AUTH_TOKEN
    # 用于访问私有规则仓库的 Bearer Token
    remote_rules_auth_token: Optional[str] = None

    # 是否启用 SQL 注入检测
    # 环境变量：SECURITY_FILTER_SQL_INJECTION_ENABLED
    # 默认值：True
    sql_injection_enabled: bool = True

    # 是否启用 XSS 检测
    # 环境变量：SECURITY_FILTER_XSS_ENABLED
    # 默认值：True
    xss_enabled: bool = True

    # 是否启用路径遍历检测
    # 环境变量：SECURITY_FILTER_PATH_TRAVERSAL_ENABLED
    # 默认值：True
    path_traversal_enabled: bool = True

    # 是否启用命令注入检测
    # 环境变量：SECURITY_FILTER_COMMAND_INJECTION_ENABLED
    # 默认值：True
    command_injection_enabled: bool = True

    # 是否启用 SSRF 检测
    # 环境变量：SECURITY_FILTER_SSRF_ENABLED
    # 默认值：False（误报率较高，默认关闭）
    ssrf_enabled: bool = False

    # 拦截响应的 HTTP 状态码
    # 环境变量：SECURITY_FILTER_BLOCKED_RESPONSE_CODE
    # 默认值：403
    blocked_response_code: int = 403

    # 拦截响应的消息
    # 环境变量：SECURITY_FILTER_BLOCKED_RESPONSE_MESSAGE
    # 默认值：Request blocked by security filter
    blocked_response_message: str = "Request blocked by security filter"

    # 是否记录被拦截的请求日志
    # 环境变量：SECURITY_FILTER_LOG_BLOCKED_REQUESTS
    # 默认值：True
    log_blocked_requests: bool = True

    # 是否记录被清洗的请求日志
    # 环境变量：SECURITY_FILTER_LOG_CLEANED_REQUESTS
    # 默认值：False（避免日志量过大）
    log_cleaned_requests: bool = False


class WebhookSettings(BaseSettings):
    """
    Webhook 通知配置

    环境变量前缀：WEBHOOK_
    修改后是否需要重启：是
    """

    model_config = SettingsConfigDict(env_prefix="WEBHOOK_", extra="ignore")

    # 是否启用 Webhook 通知
    # 环境变量：WEBHOOK_ENABLED
    # 默认值：False
    enabled: bool = False

    # Webhook 接收 URL
    # 环境变量：WEBHOOK_URL
    url: Optional[str] = None

    # Webhook HMAC 签名密钥 ⚠️ 敏感配置
    # 环境变量：WEBHOOK_SECRET
    # 用于计算 X-Webhook-Signature 签名，接收方可以验证
    secret: Optional[str] = None

    # 请求超时时间（秒）
    # 环境变量：WEBHOOK_TIMEOUT
    # 默认值：5
    timeout: int = 5

    # 最大重试次数
    # 环境变量：WEBHOOK_MAX_RETRIES
    # 默认值：3
    max_retries: int = 3

    # 重试退避基数（秒，指数退避）
    # 环境变量：WEBHOOK_RETRY_BACKOFF
    # 重试间隔 = retry_backoff * 2^n 秒
    # 默认值：1.0
    retry_backoff: float = 1.0

    # 需要通知的事件类型列表
    # 环境变量：不支持，需代码配置
    events: List[str] = Field(default_factory=lambda: [
        "api_key.created",       # API Key 创建
        "api_key.approved",      # API Key 审批通过
        "api_key.rejected",      # API Key 审批拒绝
        "api_key.activated",     # API Key 激活
        "api_key.expired",       # API Key 过期
    ])


class DeveloperPortalSettings(BaseSettings):
    """
    开发者门户配置

    环境变量前缀：PORTAL_
    修改后是否需要重启：是
    """

    model_config = SettingsConfigDict(env_prefix="PORTAL_", extra="ignore")

    # 是否启用开发者门户
    # 环境变量：PORTAL_ENABLED
    # 默认值：True
    enabled: bool = True

    # API Key 套餐列表
    # 环境变量：不支持，需代码配置
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

    # 是否需要管理员审批
    # 环境变量：PORTAL_APPROVAL_REQUIRED
    # 默认值：True（需要审批）
    approval_required: bool = True

    # 审批通过后是否自动激活
    # 环境变量：PORTAL_AUTO_ACTIVATE_ON_APPROVAL
    # 默认值：True
    auto_activate_on_approval: bool = True

    # 通知邮件发件人
    # 环境变量：PORTAL_NOTIFICATION_EMAIL_FROM
    notification_email_from: Optional[str] = None

    # 通知邮件模板路径
    # 环境变量：PORTAL_NOTIFICATION_EMAIL_TEMPLATE
    notification_email_template: Optional[str] = None


class AnalyticsSettings(BaseSettings):
    """
    调用量分析配置

    环境变量前缀：ANALYTICS_
    修改后是否需要重启：是
    """

    model_config = SettingsConfigDict(env_prefix="ANALYTICS_", extra="ignore")

    # 是否启用分析采集
    # 环境变量：ANALYTICS_ENABLED
    # 默认值：True
    enabled: bool = True

    # 批量写入大小
    # 环境变量：ANALYTICS_BATCH_SIZE
    # 默认值：1000（攒够 1000 条刷一次）
    batch_size: int = 1000

    # 强制刷盘间隔（秒）
    # 环境变量：ANALYTICS_FLUSH_INTERVAL
    # 默认值：5（最多 5 秒刷一次）
    flush_interval: int = 5

    # 写入失败最大重试次数
    # 环境变量：ANALYTICS_MAX_RETRIES
    # 默认值：3
    max_retries: int = 3

    # 重试退避时间（秒，指数退避）
    # 环境变量：ANALYTICS_RETRY_BACKOFF
    # 默认值：0.5
    retry_backoff: float = 0.5


class OpenTelemetrySettings(BaseSettings):
    """
    OpenTelemetry 分布式追踪配置

    环境变量前缀：OTEL_
    修改后是否需要重启：是
    """

    model_config = SettingsConfigDict(env_prefix="OTEL_", extra="ignore")

    # 是否启用 OpenTelemetry
    # 环境变量：OTEL_ENABLED
    # 默认值：False
    enabled: bool = False

    # 服务名（在 Trace 中显示）
    # 环境变量：OTEL_SERVICE_NAME
    # 默认值：api-gateway
    service_name: str = "api-gateway"

    # OTLP Collector 端点
    # 环境变量：OTEL_ENDPOINT
    # 默认值：http://localhost:4317
    endpoint: str = "http://localhost:4317"

    # 是否使用不安全连接（不启用 TLS）
    # 环境变量：OTEL_INSECURE
    # 默认值：True（集群内部通信可关闭 TLS）
    insecure: bool = True

    # 排除的 URL（不采集 trace）
    # 环境变量：OTEL_EXCLUDED_URLS（逗号分隔）
    # 默认值：健康检查、文档、指标等内部端点
    excluded_urls: str = "/health,/live,/ready,/metrics,/docs,/openapi.json,/redoc"


class Settings(BaseSettings):
    """
    根配置对象

    包含所有子配置模块，通过嵌套结构组织。
    环境变量嵌套分隔符：__（双下划线）
    例如：POSTGRES__HOST 对应 settings.db.host
    """

    # 数据库配置
    db: DatabaseSettings = Field(default_factory=DatabaseSettings)

    # Redis 配置
    redis: RedisSettings = Field(default_factory=RedisSettings)

    # ClickHouse 配置
    clickhouse: ClickHouseSettings = Field(default_factory=ClickHouseSettings)

    # JWT 认证配置
    jwt: JWTSettings = Field(default_factory=JWTSettings)

    # 限流配置
    rate_limit: RateLimitSettings = Field(default_factory=RateLimitSettings)

    # 熔断器配置
    circuit_breaker: CircuitBreakerSettings = Field(default_factory=CircuitBreakerSettings)

    # 网关全局配置
    gateway: GatewaySettings = Field(default_factory=GatewaySettings)

    # 分析采集配置
    analytics: AnalyticsSettings = Field(default_factory=AnalyticsSettings)

    # 安全过滤器配置
    security_filter: SecurityFilterSettings = Field(default_factory=SecurityFilterSettings)

    # Webhook 通知配置
    webhook: WebhookSettings = Field(default_factory=WebhookSettings)

    # 开发者门户配置
    portal: DeveloperPortalSettings = Field(default_factory=DeveloperPortalSettings)

    # OpenTelemetry 配置
    otel: OpenTelemetrySettings = Field(default_factory=OpenTelemetrySettings)

    model_config = SettingsConfigDict(env_nested_delimiter="__", extra="ignore")


@lru_cache()
def get_settings() -> Settings:
    """
    获取配置单例（带 LRU 缓存）

    使用 LRU 缓存避免每次调用都重新解析环境变量。
    ⚠️ 注意：运行时修改环境变量不会自动生效，需要重新调用 get_settings.cache_clear()

    Returns:
        Settings 单例对象
    """
    return Settings()
