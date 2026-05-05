from datetime import datetime
from typing import Dict, List, Any, Optional, Union
from pydantic import BaseModel, Field
from enum import Enum


class AggregationType(str, Enum):
    COUNT = "count"
    SUM = "sum"
    AVG = "avg"


class AlertSeverity(str, Enum):
    INFO = "info"
    WARNING = "warning"
    CRITICAL = "critical"


class NotificationChannelType(str, Enum):
    SLACK = "slack"
    EMAIL = "email"
    WEBHOOK = "webhook"


class ChannelConfig(BaseModel):
    channel_type: NotificationChannelType
    enabled: bool = True
    config: Dict[str, Any] = Field(default_factory=dict)


class SlackChannelConfig(ChannelConfig):
    channel_type: NotificationChannelType = NotificationChannelType.SLACK
    webhook_url: Optional[str] = None
    channel: str = "#alerts"


class EmailChannelConfig(ChannelConfig):
    channel_type: NotificationChannelType = NotificationChannelType.EMAIL
    smtp_host: str = ""
    smtp_port: int = 587
    smtp_user: str = ""
    smtp_password: str = ""
    from_addr: str = ""
    to_addrs: List[str] = Field(default_factory=list)
    use_tls: bool = True


class AlertRule(BaseModel):
    condition: str = Field(..., description="告警条件表达式，如 'value < 10'")
    severity: AlertSeverity = Field(default=AlertSeverity.WARNING)
    notify_channel: str = Field(
        default="slack",
        description="通知通道（已废弃，使用notify_channels）"
    )
    notify_channels: List[NotificationChannelType] = Field(
        default_factory=lambda: [NotificationChannelType.SLACK],
        description="通知通道列表"
    )
    message_template: Optional[str] = Field(
        default=None,
        description="自定义告警消息模板"
    )

    def get_notify_channels(self) -> List[NotificationChannelType]:
        if self.notify_channels:
            return self.notify_channels

        try:
            return [NotificationChannelType(self.notify_channel)]
        except ValueError:
            return [NotificationChannelType.SLACK]


class MetricConfig(BaseModel):
    metric_id: Optional[str] = Field(default=None)
    metric_name: str = Field(..., description="指标名称")
    source: str = Field(..., description="数据源标识")
    aggregation: AggregationType = Field(..., description="聚合函数类型")
    field: Optional[str] = Field(
        default=None,
        description="聚合字段名，count类型可选，sum/avg必填"
    )
    time_window: str = Field(
        default="60s",
        description="时间窗口，如 '30s', '5m', '1h'"
    )
    group_by: List[str] = Field(
        default_factory=list,
        description="分组字段列表"
    )
    alert_rules: List[AlertRule] = Field(
        default_factory=list,
        description="告警规则列表"
    )
    chart_type: Optional[str] = Field(
        default="line",
        description="前端展示图表类型"
    )
    is_active: bool = Field(default=True)

    class Config:
        from_attributes = True


class MetricResult(BaseModel):
    metric_id: str
    value: float
    timestamp: datetime = Field(default_factory=datetime.utcnow)
    group_key: Dict[str, Any] = Field(default_factory=dict)
    window_end: bool = Field(default=False)
    window_start: datetime = Field(default_factory=datetime.utcnow)

    class Config:
        json_encoders = {
            datetime: lambda v: v.isoformat() + "Z"
        }


class RawDataEvent(BaseModel):
    source: str
    data: Dict[str, Any]
    timestamp: datetime = Field(default_factory=datetime.utcnow)
    event_type: str = Field(default="insert")

    message_id: Optional[str] = Field(default=None, description="唯一消息ID，用于追踪处理状态")
    kafka_topic: Optional[str] = Field(default=None, description="Kafka主题（Kafka消息专用）")
    kafka_partition: Optional[int] = Field(default=None, description="Kafka分区（Kafka消息专用）")
    kafka_offset: Optional[int] = Field(default=None, description="Kafka偏移量（Kafka消息专用）")

    class Config:
        json_encoders = {
            datetime: lambda v: v.isoformat() + "Z"
        }

    def has_kafka_metadata(self) -> bool:
        return (
            self.kafka_topic is not None
            and self.kafka_partition is not None
            and self.kafka_offset is not None
        )


class CleanedDataEvent(BaseModel):
    source: str
    data: Dict[str, Any]
    timestamp: datetime
    original_data: Optional[Dict[str, Any]] = None
    quality_score: float = Field(default=1.0)

    message_id: Optional[str] = Field(default=None, description="原始消息ID，用于追踪")
    kafka_topic: Optional[str] = Field(default=None, description="Kafka主题")
    kafka_partition: Optional[int] = Field(default=None, description="Kafka分区")
    kafka_offset: Optional[int] = Field(default=None, description="Kafka偏移量")


class WebSocketMetricMessage(BaseModel):
    event: str = Field(default="metric_update")
    data: MetricResult

    class Config:
        json_encoders = {
            datetime: lambda v: v.isoformat() + "Z"
        }


class AlertNotification(BaseModel):
    alert_id: str
    metric_id: str
    metric_name: str
    severity: AlertSeverity
    message: str
    value: float
    threshold_condition: str
    timestamp: datetime = Field(default_factory=datetime.utcnow)
    group_key: Dict[str, Any] = Field(default_factory=dict)


class FieldMapping(BaseModel):
    source_field: str
    target_field: str
    field_type: str = Field(default="string")
    default_value: Optional[Any] = None
    is_nullable: bool = Field(default=True)
    validators: List[str] = Field(default_factory=list)


class PipelineConfig(BaseModel):
    source: str
    field_mappings: List[FieldMapping] = Field(default_factory=list)
    drop_unspecified: bool = Field(default=False)
    quality_threshold: float = Field(default=0.8)


class MessageStatus(str, Enum):
    PENDING = "pending"
    SUCCESS = "success"
    FAILED = "failed"
    ACKNOWLEDGED = "acknowledged"


class DataSourceType(str, Enum):
    MYSQL = "mysql"
    POSTGRESQL = "postgresql"
    KAFKA = "kafka"
    FILE = "file"


class DataSourceConfig(BaseModel):
    source_id: str
    source_type: DataSourceType
    config: Dict[str, Any]
    is_active: bool = Field(default=True)


class KafkaOffsetRecord(BaseModel):
    topic: str
    partition: int
    offset: int
    group_id: str
    timestamp: datetime = Field(default_factory=datetime.utcnow)
    committed: bool = False


class YAMLFieldMapping(BaseModel):
    source_field: str
    target_field: str
    field_type: str = Field(default="string")
    default_value: Optional[Any] = None
    is_nullable: bool = Field(default=True)
    validators: List[str] = Field(default_factory=list)


class YAMLPipelineConfig(BaseModel):
    source: str
    field_mappings: List[YAMLFieldMapping] = Field(default_factory=list)
    drop_unspecified: bool = Field(default=False)
    quality_threshold: float = Field(default=0.8)
    description: Optional[str] = None
    enabled: bool = Field(default=True)


class YAMLConfigRoot(BaseModel):
    version: str = Field(default="1.0")
    pipelines: List[YAMLPipelineConfig] = Field(default_factory=list)
    updated_at: Optional[datetime] = None
