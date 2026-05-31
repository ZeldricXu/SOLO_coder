use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use uuid::Uuid;

pub fn generate_id(prefix: &str) -> String {
    format!("{}_{}", prefix, Uuid::new_v4().to_string().replace("-", ""))
}

pub fn now_utc() -> DateTime<Utc> {
    Utc::now()
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct CoreEntity {
    pub id: String,
    pub r#type: String,
    pub status: EntityStatus,
    pub attributes: HashMap<String, serde_json::Value>,
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum EntityStatus {
    Pending,
    Active,
    Inactive,
    Completed,
    Provisioning,
    Deprovisioning,
    Failed,
    Cancelled,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct ConfigDefinition {
    pub config_id: String,
    pub namespace: String,
    pub version: u32,
    pub parameters: HashMap<String, serde_json::Value>,
    pub enabled: bool,
    pub applied_at: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct RunInstance {
    pub run_id: String,
    pub entity_id: String,
    pub phase: RunPhase,
    pub progress: f64,
    pub started_at: DateTime<Utc>,
    pub completed_at: Option<DateTime<Utc>>,
    pub error_detail: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum RunPhase {
    Pending,
    Initializing,
    Running,
    Finalizing,
    Completed,
    Failed,
    Rollback,
    Cancelled,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct MetricsSnapshot {
    pub snapshot_id: String,
    pub timestamp: DateTime<Utc>,
    pub metrics: MetricsData,
    pub dimensions: HashMap<String, String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct MetricsData {
    pub throughput: u64,
    pub latency_p99: u64,
    pub error_rate: f64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct HandlerRequest {
    pub trace_id: String,
    pub namespace: String,
    pub params: serde_json::Value,
    pub payload: serde_json::Value,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct HandlerResponse {
    pub code: u16,
    pub data: Option<serde_json::Value>,
    pub message: Option<String>,
    pub trace_id: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ResourceCreateRequest {
    pub r#type: String,
    pub config: serde_json::Value,
    pub labels: HashMap<String, String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ResourceCreateResponse {
    pub id: String,
    pub status: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ResourceStatusResponse {
    pub id: String,
    pub status: String,
    pub progress: f64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct BatchOperationRequest {
    pub operations: Vec<BatchOperation>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct BatchOperation {
    pub action: String,
    pub id: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct BatchOperationResponse {
    pub batch_id: String,
    pub results: Vec<BatchResult>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct BatchResult {
    pub id: String,
    pub success: bool,
    pub message: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ApiResponse<T> {
    pub code: u16,
    pub data: Option<T>,
    pub message: Option<String>,
}

impl<T> ApiResponse<T> {
    pub fn success(data: T) -> Self {
        Self {
            code: 200,
            data: Some(data),
            message: None,
        }
    }

    pub fn created(data: T) -> Self {
        Self {
            code: 201,
            data: Some(data),
            message: None,
        }
    }

    pub fn error(code: u16, message: impl Into<String>) -> Self {
        Self {
            code,
            data: None,
            message: Some(message.into()),
        }
    }
}

#[derive(Debug, thiserror::Error)]
pub enum AppError {
    #[error("参数验证失败: {0}")]
    ValidationError(String),
    #[error("上游服务响应超时")]
    TimeoutError,
    #[error("资源未找到: {0}")]
    NotFound(String),
    #[error("认证失败: {0}")]
    Unauthorized(String),
    #[error("权限不足: {0}")]
    Forbidden(String),
    #[error("服务限流")]
    RateLimited,
    #[error("内部处理错误: {0}")]
    InternalError(String),
    #[error("配置错误: {0}")]
    ConfigError(String),
    #[error("并发冲突: {0}")]
    Conflict(String),
    #[error("序列化错误: {0}")]
    SerializationError(String),
    #[error("存储错误: {0}")]
    StorageError(String),
    #[error("数据库错误: {0}")]
    DatabaseError(String),
    #[error("CDC错误: {0}")]
    CdcError(String),
    #[error("数据质量错误: {0}")]
    DataQualityError(String),
    #[error("元数据采集错误: {0}")]
    MetadataCrawlerError(String),
    #[error("血缘解析错误: {0}")]
    LineageError(String),
    #[error("通知错误: {0}")]
    NotificationError(String),
}

pub type AppResult<T> = Result<T, AppError>;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct StoredObject {
    pub object_id: String,
    pub key: String,
    pub bucket: String,
    pub size: u64,
    pub content_type: String,
    pub etag: String,
    pub metadata: HashMap<String, String>,
    pub storage_backend: StorageBackend,
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum StorageBackend {
    Local,
    S3,
    Gcs,
    AzureBlob,
    Minio,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ObjectMetadataIndex {
    pub index_id: String,
    pub object_id: String,
    pub tags: Vec<String>,
    pub custom_fields: HashMap<String, serde_json::Value>,
    pub indexed_at: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CdcEvent {
    pub event_id: String,
    pub source: CdcSource,
    pub operation: CdcOperation,
    pub database: String,
    pub table: String,
    pub primary_key: serde_json::Value,
    pub before: Option<serde_json::Value>,
    pub after: Option<serde_json::Value>,
    pub timestamp: DateTime<Utc>,
    pub binlog_position: Option<String>,
    pub lsn: Option<String>,
    pub transaction_id: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum CdcSource {
    MysqlBinlog,
    PostgresWal,
    MongodbOplog,
    SqlserverCdc,
    OracleLogminer,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum CdcOperation {
    Insert,
    Update,
    Delete,
    Truncate,
    Ddl,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CdcEventBatch {
    pub batch_id: String,
    pub events: Vec<CdcEvent>,
    pub created_at: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct QualityRule {
    pub rule_id: String,
    pub name: String,
    pub description: String,
    pub rule_type: QualityRuleType,
    pub dataset: String,
    pub expression: String,
    pub severity: SeverityLevel,
    pub threshold: f64,
    pub schedule: String,
    pub enabled: bool,
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum QualityRuleType {
    Completeness,
    Uniqueness,
    Accuracy,
    Consistency,
    Timeliness,
    Validity,
    Custom,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum SeverityLevel {
    Low,
    Medium,
    High,
    Critical,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct QualityCheckResult {
    pub check_id: String,
    pub rule_id: String,
    pub dataset: String,
    pub passed: bool,
    pub actual_value: f64,
    pub expected_value: f64,
    pub anomaly_count: u64,
    pub sample_data: Vec<serde_json::Value>,
    pub started_at: DateTime<Utc>,
    pub completed_at: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AnomalyRecord {
    pub anomaly_id: String,
    pub rule_id: String,
    pub dataset: String,
    pub record_key: String,
    pub field_name: Option<String>,
    pub expected_value: Option<serde_json::Value>,
    pub actual_value: Option<serde_json::Value>,
    pub severity: SeverityLevel,
    pub detected_at: DateTime<Utc>,
    pub resolved: bool,
    pub resolved_at: Option<DateTime<Utc>>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DataSourceSchema {
    pub source_id: String,
    pub source_type: DataSourceType,
    pub connection_string: String,
    pub tables: Vec<TableSchema>,
    pub scanned_at: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum DataSourceType {
    Postgres,
    Mysql,
    SqlServer,
    Oracle,
    Mongodb,
    Elasticsearch,
    S3,
    LocalFile,
    Kafka,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TableSchema {
    pub table_name: String,
    pub schema_name: String,
    pub columns: Vec<ColumnSchema>,
    pub row_count: Option<u64>,
    pub size_bytes: Option<u64>,
    pub statistics: TableStatistics,
    pub sample_data: Vec<HashMap<String, serde_json::Value>>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ColumnSchema {
    pub column_name: String,
    pub data_type: String,
    pub nullable: bool,
    pub primary_key: bool,
    pub foreign_key: Option<ForeignKeyInfo>,
    pub statistics: ColumnStatistics,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ForeignKeyInfo {
    pub foreign_table: String,
    pub foreign_column: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TableStatistics {
    pub last_analyzed: Option<DateTime<Utc>>,
    pub distinct_count: Option<u64>,
    pub null_count: Option<u64>,
}

impl Default for TableStatistics {
    fn default() -> Self {
        Self {
            last_analyzed: None,
            distinct_count: None,
            null_count: None,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ColumnStatistics {
    pub min_value: Option<serde_json::Value>,
    pub max_value: Option<serde_json::Value>,
    pub avg_value: Option<f64>,
    pub distinct_count: Option<u64>,
    pub null_count: Option<u64>,
    pub top_values: Vec<(serde_json::Value, u64)>,
    pub histogram: Option<Vec<HistogramBucket>>,
}

impl Default for ColumnStatistics {
    fn default() -> Self {
        Self {
            min_value: None,
            max_value: None,
            avg_value: None,
            distinct_count: None,
            null_count: None,
            top_values: Vec::new(),
            histogram: None,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct HistogramBucket {
    pub lower_bound: f64,
    pub upper_bound: f64,
    pub count: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CrawlSchedule {
    pub schedule_id: String,
    pub source_id: String,
    pub cron_expression: String,
    pub enabled: bool,
    pub last_run: Option<DateTime<Utc>>,
    pub next_run: Option<DateTime<Utc>>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct LineageGraph {
    pub graph_id: String,
    pub nodes: Vec<LineageNode>,
    pub edges: Vec<LineageEdge>,
    pub created_at: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq, Hash)]
pub struct LineageNode {
    pub node_id: String,
    pub node_type: LineageNodeType,
    pub name: String,
    pub fully_qualified_name: String,
    pub metadata: HashMap<String, serde_json::Value>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq, Hash)]
#[serde(rename_all = "snake_case")]
pub enum LineageNodeType {
    Database,
    Schema,
    Table,
    Column,
    View,
    Query,
    Transformation,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct LineageEdge {
    pub edge_id: String,
    pub source_node_id: String,
    pub target_node_id: String,
    pub edge_type: LineageEdgeType,
    pub transformation_logic: Option<String>,
    pub sql_query: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum LineageEdgeType {
    Select,
    Join,
    Filter,
    Aggregate,
    Transform,
    Insert,
    Update,
    Delete,
    Create,
    Alter,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ParsedSqlLineage {
    pub query_id: String,
    pub raw_sql: String,
    pub source_tables: Vec<String>,
    pub target_tables: Vec<String>,
    pub source_columns: Vec<(String, String)>,
    pub target_columns: Vec<(String, String)>,
    pub column_mappings: Vec<ColumnMapping>,
    pub parsed_at: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ColumnMapping {
    pub source_table: String,
    pub source_column: String,
    pub target_table: String,
    pub target_column: String,
    pub transformation: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct NotificationMessage {
    pub message_id: String,
    pub channel: NotificationChannel,
    pub template_id: String,
    pub recipient: String,
    pub subject: String,
    pub content: String,
    pub variables: HashMap<String, serde_json::Value>,
    pub priority: NotificationPriority,
    pub status: NotificationStatus,
    pub created_at: DateTime<Utc>,
    pub sent_at: Option<DateTime<Utc>>,
    pub error: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum NotificationChannel {
    Email,
    Sms,
    Slack,
    Dingtalk,
    Wechat,
    Webhook,
    InApp,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum NotificationPriority {
    Low,
    Medium,
    High,
    Urgent,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum NotificationStatus {
    Pending,
    Sending,
    Sent,
    Failed,
    Retrying,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct NotificationTemplate {
    pub template_id: String,
    pub name: String,
    pub channel: NotificationChannel,
    pub subject_template: String,
    pub content_template: String,
    pub variables: Vec<String>,
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct NotificationResult {
    pub message_id: String,
    pub channel: NotificationChannel,
    pub success: bool,
    pub sent_at: Option<DateTime<Utc>>,
    pub error: Option<String>,
}

#[derive(Debug, Clone, Deserialize)]
pub struct ServerConfig {
    pub host: String,
    pub port: u16,
    pub shutdown_timeout: u64,
}

#[derive(Debug, Clone, Deserialize)]
pub struct DatabaseConfig {
    pub url: String,
    pub pool_size: u32,
    pub connect_timeout: u64,
    pub idle_timeout: u64,
    pub max_lifetime: u64,
    pub acquire_timeout: u64,
}

#[derive(Debug, Clone, Deserialize)]
pub struct RedisConfig {
    pub url: String,
    pub pool_size: u32,
    pub connect_timeout: u64,
    pub idle_timeout: u64,
    pub max_lifetime: u64,
}

#[derive(Debug, Clone, Deserialize)]
pub struct LoggingConfig {
    pub dir: String,
    pub level: String,
    pub format: String,
    pub rotation: String,
    pub retention_days: u32,
    pub compression: bool,
    pub ansi_colors: bool,
}

#[derive(Debug, Clone, Deserialize)]
pub struct StorageConfig {
    pub backend: String,
    pub local_path: String,
    pub s3_bucket: String,
    pub s3_region: String,
    pub s3_access_key: String,
    pub s3_secret_key: String,
    pub s3_endpoint: String,
}

#[derive(Debug, Clone, Deserialize)]
pub struct CdcConfig {
    pub enabled: bool,
    pub source_type: String,
    pub connection_string: String,
    pub server_id: u32,
    pub slot_name: String,
    pub include_tables: Vec<String>,
    pub exclude_tables: Vec<String>,
    pub output_kafka_brokers: String,
    pub output_topic: String,
    pub batch_size: u32,
    pub polling_interval_ms: u64,
}

#[derive(Debug, Clone, Deserialize)]
pub struct DataQualityConfig {
    pub enabled: bool,
    pub schedule_pool_size: u32,
    pub alert_enabled: bool,
    pub alert_channels: Vec<String>,
    pub anomaly_storage_enabled: bool,
}

#[derive(Debug, Clone, Deserialize)]
pub struct MetadataCrawlerConfig {
    pub enabled: bool,
    pub schedule_pool_size: u32,
    pub sample_data_count: u32,
    pub histogram_buckets: u32,
    pub statistics_enabled: bool,
}

#[derive(Debug, Clone, Deserialize)]
pub struct LineageConfig {
    pub enabled: bool,
    pub sql_dialect: String,
    pub store_parsed_queries: bool,
    pub build_dag: bool,
}

#[derive(Debug, Clone, Deserialize)]
pub struct NotificationConfig {
    pub enabled: bool,
    pub default_channel: String,
    pub rate_limit_per_minute: u32,
    pub retry_count: u32,
    pub retry_interval_ms: u64,
    pub smtp_host: String,
    pub smtp_port: u16,
    pub smtp_username: String,
    pub smtp_password: String,
    pub slack_webhook: String,
    pub dingtalk_webhook: String,
    pub wechat_webhook: String,
    pub webhook_timeout_ms: u64,
}

#[derive(Debug, Clone, Deserialize)]
pub struct AppConfig {
    pub server: ServerConfig,
    pub database: DatabaseConfig,
    pub redis: RedisConfig,
    pub logging: LoggingConfig,
    pub storage: StorageConfig,
    pub cdc: CdcConfig,
    pub data_quality: DataQualityConfig,
    pub metadata_crawler: MetadataCrawlerConfig,
    pub lineage: LineageConfig,
    pub notification: NotificationConfig,
}
