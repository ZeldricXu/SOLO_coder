use thiserror::Error;

#[derive(Error, Debug)]
pub enum SystemError {
    #[error("配置错误: {0}")]
    ConfigError(String),

    #[error("数据库错误: {0}")]
    DatabaseError(#[from] sqlx::Error),

    #[error("存储错误: {0}")]
    StorageError(String),

    #[error("网络错误: {0}")]
    NetworkError(#[from] reqwest::Error),

    #[error("序列化错误: {0}")]
    SerializationError(#[from] serde_json::Error),

    #[error("设备影子错误: {0}")]
    DeviceShadowError(String),

    #[error("调度错误: {0}")]
    SchedulerError(String),

    #[error("通知错误: {0}")]
    NotificationError(String),

    #[error("离线缓存错误: {0}")]
    OfflineCacheError(String),

    #[error("数据聚合错误: {0}")]
    AggregationError(String),

    #[error("核心处理错误: {0}")]
    CoreProcessingError(String),

    #[error("API网关错误: {0}")]
    GatewayError(String),

    #[error("IO错误: {0}")]
    IoError(#[from] std::io::Error),

    #[error("超时错误: {0}")]
    TimeoutError(String),

    #[error("未找到: {0}")]
    NotFoundError(String),

    #[error("验证错误: {0}")]
    ValidationError(String),
}

pub type Result<T> = std::result::Result<T, SystemError>;
