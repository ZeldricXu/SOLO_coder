from __future__ import annotations
import json
import uuid
import time
from dataclasses import dataclass, field, asdict
from typing import Any, Dict, List, Optional, Union
from enum import Enum


class Priority(str, Enum):
    LOW = "low"
    MEDIUM = "medium"
    HIGH = "high"
    CRITICAL = "critical"


class NotificationType(str, Enum):
    ALERT = "alert"
    INFO = "info"
    WARNING = "warning"
    CRITICAL = "critical"


class SpanStatus(str, Enum):
    OK = "OK"
    ERROR = "ERROR"
    UNSET = "UNSET"


class SamplingType(str, Enum):
    HEAD = "head"
    TAIL = "tail"


class ProfileType(str, Enum):
    CPU = "cpu"
    MEMORY = "memory"
    WALL = "wall"


def generate_id(prefix: str = "") -> str:
    return f"{prefix}{uuid.uuid4().hex[:12]}"


def now_iso() -> str:
    return time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())


@dataclass
class TraceSpanBuilder:
    trace_id: str = field(default_factory=lambda: generate_id("trace_"))
    span_id: str = field(default_factory=lambda: generate_id("span_"))
    parent_span_id: Optional[str] = None
    name: str = "default-span"
    service_name: str = "default-service"
    start_time: str = field(default_factory=now_iso)
    end_time: Optional[str] = None
    duration: Optional[int] = None
    status: SpanStatus = SpanStatus.OK
    attributes: Dict[str, Any] = field(default_factory=dict)
    events: List[Dict[str, Any]] = field(default_factory=list)
    links: List[Dict[str, Any]] = field(default_factory=list)
    sampled: Optional[bool] = None

    def with_trace_id(self, trace_id: str) -> "TraceSpanBuilder":
        self.trace_id = trace_id
        return self

    def with_span_id(self, span_id: str) -> "TraceSpanBuilder":
        self.span_id = span_id
        return self

    def with_parent(self, parent_span_id: str) -> "TraceSpanBuilder":
        self.parent_span_id = parent_span_id
        return self

    def with_name(self, name: str) -> "TraceSpanBuilder":
        self.name = name
        return self

    def with_service(self, service_name: str) -> "TraceSpanBuilder":
        self.service_name = service_name
        return self

    def with_duration(self, duration_ms: int) -> "TraceSpanBuilder":
        self.duration = duration_ms
        start = time.time()
        self.start_time = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(start - duration_ms / 1000))
        self.end_time = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(start))
        return self

    def with_status(self, status: SpanStatus) -> "TraceSpanBuilder":
        self.status = status
        return self

    def with_attributes(self, **attrs) -> "TraceSpanBuilder":
        self.attributes.update(attrs)
        return self

    def with_error(self) -> "TraceSpanBuilder":
        self.status = SpanStatus.ERROR
        self.attributes["error"] = True
        return self

    def add_event(self, name: str, **attrs) -> "TraceSpanBuilder":
        self.events.append({
            "name": name,
            "timestamp": now_iso(),
            "attributes": attrs
        })
        return self

    def build(self) -> Dict[str, Any]:
        data = {
            "traceId": self.trace_id,
            "spanId": self.span_id,
            "name": self.name,
            "serviceName": self.service_name,
            "startTime": self.start_time,
            "status": self.status.value,
            "attributes": self.attributes,
            "events": self.events,
            "links": self.links,
        }
        if self.parent_span_id is not None:
            data["parentSpanId"] = self.parent_span_id
        if self.end_time is not None:
            data["endTime"] = self.end_time
        if self.duration is not None:
            data["duration"] = self.duration
        if self.sampled is not None:
            data["sampled"] = self.sampled
        return {k: v for k, v in data.items() if v is not None}


@dataclass
class SamplingStrategyBuilder:
    id: str = field(default_factory=lambda: generate_id("strategy_"))
    name: str = "default-strategy"
    type: SamplingType = SamplingType.HEAD
    rule: Dict[str, Any] = field(default_factory=lambda: {"sampleRate": 1.0})
    priority: int = 1
    enabled: bool = True

    def with_head_sampling(self, sample_rate: float = 1.0) -> "SamplingStrategyBuilder":
        self.type = SamplingType.HEAD
        self.rule["sampleRate"] = sample_rate
        return self

    def with_tail_sampling(self, sample_rate: float = 1.0) -> "SamplingStrategyBuilder":
        self.type = SamplingType.TAIL
        self.rule["sampleRate"] = sample_rate
        return self

    def for_service(self, service_name: str) -> "SamplingStrategyBuilder":
        self.rule["serviceName"] = service_name
        return self

    def for_span_name(self, span_name: str) -> "SamplingStrategyBuilder":
        self.rule["spanName"] = span_name
        return self

    def with_min_duration(self, duration_ms: int) -> "SamplingStrategyBuilder":
        self.rule["minDuration"] = duration_ms
        return self

    def error_only(self) -> "SamplingStrategyBuilder":
        self.rule["errorOnly"] = True
        return self

    def with_priority(self, priority: int) -> "SamplingStrategyBuilder":
        self.priority = priority
        return self

    def disabled(self) -> "SamplingStrategyBuilder":
        self.enabled = False
        return self

    def build(self) -> Dict[str, Any]:
        return {
            "id": self.id,
            "name": self.name,
            "type": self.type.value,
            "rule": self.rule,
            "priority": self.priority,
            "enabled": self.enabled
        }


@dataclass
class NotificationBuilder:
    id: str = field(default_factory=lambda: generate_id("notif_"))
    type: NotificationType = NotificationType.INFO
    priority: Priority = Priority.LOW
    title: str = "Test Notification"
    message: str = "This is a test notification"
    source: str = "test-system"
    tags: List[str] = field(default_factory=list)
    created_at: str = field(default_factory=now_iso)

    def with_type(self, notif_type: NotificationType) -> "NotificationBuilder":
        self.type = notif_type
        return self

    def with_priority(self, priority: Priority) -> "NotificationBuilder":
        self.priority = priority
        return self

    def with_title(self, title: str) -> "NotificationBuilder":
        self.title = title
        return self

    def with_message(self, message: str) -> "NotificationBuilder":
        self.message = message
        return self

    def from_source(self, source: str) -> "NotificationBuilder":
        self.source = source
        return self

    def with_tags(self, *tags: str) -> "NotificationBuilder":
        self.tags.extend(tags)
        return self

    def critical(self) -> "NotificationBuilder":
        self.type = NotificationType.CRITICAL
        self.priority = Priority.CRITICAL
        return self

    def warning(self) -> "NotificationBuilder":
        self.type = NotificationType.WARNING
        self.priority = Priority.MEDIUM
        return self

    def alert(self) -> "NotificationBuilder":
        self.type = NotificationType.ALERT
        self.priority = Priority.HIGH
        return self

    def build(self) -> Dict[str, Any]:
        return {
            "id": self.id,
            "type": self.type.value,
            "priority": self.priority.value,
            "title": self.title,
            "message": self.message,
            "source": self.source,
            "tags": self.tags,
            "createdAt": self.created_at
        }


@dataclass
class NotificationChannelBuilder:
    id: str = field(default_factory=lambda: generate_id("channel_"))
    type: str = "webhook"
    config: Dict[str, Any] = field(default_factory=lambda: {"url": "http://localhost/webhook"})
    enabled: bool = True
    priority_threshold: Priority = Priority.LOW

    def with_id(self, channel_id: str) -> "NotificationChannelBuilder":
        self.id = channel_id
        return self

    def as_email(self, to: List[str], from_addr: str = "alerts@test.com") -> "NotificationChannelBuilder":
        self.type = "email"
        self.config = {"to": to, "from": from_addr}
        return self

    def as_slack(self, webhook_url: str) -> "NotificationChannelBuilder":
        self.type = "slack"
        self.config = {"webhookUrl": webhook_url}
        return self

    def as_webhook(self, url: str) -> "NotificationChannelBuilder":
        self.type = "webhook"
        self.config = {"url": url}
        return self

    def as_sms(self, phone: str) -> "NotificationChannelBuilder":
        self.type = "sms"
        self.config = {"phone": phone}
        return self

    def with_priority_threshold(self, threshold: Priority) -> "NotificationChannelBuilder":
        self.priority_threshold = threshold
        return self

    def disabled(self) -> "NotificationChannelBuilder":
        self.enabled = False
        return self

    def build(self) -> Dict[str, Any]:
        return {
            "id": self.id,
            "type": self.type,
            "config": self.config,
            "enabled": self.enabled,
            "priorityThreshold": self.priority_threshold.value
        }


@dataclass
class SuppressionRuleBuilder:
    id: str = field(default_factory=lambda: generate_id("suppress_"))
    name: str = "default-suppression"
    matcher: Dict[str, Any] = field(default_factory=dict)
    duration: int = 300000
    max_suppressions: int = 100
    enabled: bool = True

    def with_tags(self, *tags: str) -> "SuppressionRuleBuilder":
        self.matcher["tags"] = list(tags)
        return self

    def for_source(self, source: str) -> "SuppressionRuleBuilder":
        self.matcher["source"] = source
        return self

    def for_priority(self, priority: Priority) -> "SuppressionRuleBuilder":
        self.matcher["priority"] = priority.value
        return self

    def with_duration(self, duration_ms: int) -> "SuppressionRuleBuilder":
        self.duration = duration_ms
        return self

    def with_max_suppressions(self, max_count: int) -> "SuppressionRuleBuilder":
        self.max_suppressions = max_count
        return self

    def disabled(self) -> "SuppressionRuleBuilder":
        self.enabled = False
        return self

    def build(self) -> Dict[str, Any]:
        return {
            "id": self.id,
            "name": self.name,
            "matcher": self.matcher,
            "duration": self.duration,
            "maxSuppressions": self.max_suppressions,
            "enabled": self.enabled
        }


@dataclass
class ProfilingSessionBuilder:
    type: ProfileType = ProfileType.CPU
    duration: int = 1000

    def as_cpu(self) -> "ProfilingSessionBuilder":
        self.type = ProfileType.CPU
        return self

    def as_memory(self) -> "ProfilingSessionBuilder":
        self.type = ProfileType.MEMORY
        return self

    def as_wall(self) -> "ProfilingSessionBuilder":
        self.type = ProfileType.WALL
        return self

    def with_duration(self, duration_ms: int) -> "ProfilingSessionBuilder":
        self.duration = duration_ms
        return self

    def with_type(self, profile_type: ProfileType) -> "ProfilingSessionBuilder":
        self.type = profile_type
        return self

    def build(self) -> Dict[str, Any]:
        return {
            "type": self.type.value,
            "duration": self.duration
        }


@dataclass
class MetricPointBuilder:
    metric: str = "test.metric"
    value: float = 0.0
    tags: Dict[str, str] = field(default_factory=dict)
    timestamp: Optional[int] = None

    def with_metric(self, metric: str) -> "MetricPointBuilder":
        self.metric = metric
        return self

    def with_value(self, value: float) -> "MetricPointBuilder":
        self.value = value
        return self

    def with_tags(self, **tags) -> "MetricPointBuilder":
        self.tags.update(tags)
        return self

    def with_timestamp(self, timestamp: int) -> "MetricPointBuilder":
        self.timestamp = timestamp
        return self

    def with_relative_time(self, seconds_ago: int) -> "MetricPointBuilder":
        self.timestamp = int(time.time() - seconds_ago) * 1000
        return self

    def build(self) -> Dict[str, Any]:
        return {
            "metric": self.metric,
            "value": self.value,
            "tags": self.tags,
            "timestamp": self.timestamp or int(time.time() * 1000)
        }


@dataclass
class AlertRuleBuilder:
    id: str = field(default_factory=lambda: generate_id("alert_"))
    name: str = "Test Alert"
    metric: str = "test.metric"
    condition: Dict[str, Any] = field(default_factory=lambda: {"operator": "gt", "threshold": 100})
    threshold: float = 100
    duration: int = 60000
    severity: str = "warning"
    notification_channels: List[str] = field(default_factory=list)
    enabled: bool = True
    labels: Dict[str, str] = field(default_factory=dict)

    def with_name(self, name: str) -> "AlertRuleBuilder":
        self.name = name
        return self

    def for_metric(self, metric: str) -> "AlertRuleBuilder":
        self.metric = metric
        return self

    def with_operator(self, operator: str, threshold: float) -> "AlertRuleBuilder":
        self.condition = {"operator": operator, "threshold": threshold}
        self.threshold = threshold
        return self

    def with_duration(self, duration_ms: int) -> "AlertRuleBuilder":
        self.duration = duration_ms
        return self

    def critical(self) -> "AlertRuleBuilder":
        self.severity = "critical"
        return self

    def warning(self) -> "AlertRuleBuilder":
        self.severity = "warning"
        return self

    def with_channels(self, *channel_ids) -> "AlertRuleBuilder":
        self.notification_channels.extend(channel_ids)
        return self

    def with_labels(self, **labels) -> "AlertRuleBuilder":
        self.labels.update(labels)
        return self

    def disabled(self) -> "AlertRuleBuilder":
        self.enabled = False
        return self

    def build(self) -> Dict[str, Any]:
        return {
            "id": self.id,
            "name": self.name,
            "metric": self.metric,
            "condition": self.condition,
            "threshold": self.threshold,
            "duration": self.duration,
            "severity": self.severity,
            "notificationChannels": self.notification_channels,
            "enabled": self.enabled,
            "labels": self.labels
        }


class TestDataGenerator:
    @staticmethod
    def create_trace_spans(count: int = 5, service_name: str = "test-service") -> List[Dict[str, Any]]:
        trace_id = generate_id("trace_")
        spans = []
        parent_id = None

        for i in range(count):
            builder = TraceSpanBuilder() \
                .with_trace_id(trace_id) \
                .with_name(f"span-{i}") \
                .with_service(service_name) \
                .with_duration(10 + i * 5) \
                .with_attributes(index=i, phase=f"phase-{i}")

            if parent_id:
                builder.with_parent(parent_id)

            span = builder.build()
            spans.append(span)
            parent_id = span["spanId"]

        return spans

    @staticmethod
    def create_error_trace(service_name: str = "error-service") -> List[Dict[str, Any]]:
        trace_id = generate_id("trace_error_")
        spans = []

        root_span = TraceSpanBuilder() \
            .with_trace_id(trace_id) \
            .with_name("root-operation") \
            .with_service(service_name) \
            .with_duration(500) \
            .build()
        spans.append(root_span)

        child_span = TraceSpanBuilder() \
            .with_trace_id(trace_id) \
            .with_parent(root_span["spanId"]) \
            .with_name("child-operation") \
            .with_service(service_name) \
            .with_duration(300) \
            .with_error() \
            .with_attributes(error_type="DatabaseError", error_message="Connection timeout") \
            .build()
        spans.append(child_span)

        return spans

    @staticmethod
    def create_metric_series(
        metric_name: str,
        count: int = 10,
        base_value: float = 100,
        variance: float = 10,
        tags: Optional[Dict[str, str]] = None
    ) -> List[Dict[str, Any]]:
        points = []
        for i in range(count):
            value = base_value + (i % 3) * variance
            point = MetricPointBuilder() \
                .with_metric(metric_name) \
                .with_value(value) \
                .with_relative_time((count - i) * 60) \
                .with_tags(**(tags or {})) \
                .build()
            points.append(point)
        return points

    @staticmethod
    def create_anomaly_metric_series(
        metric_name: str,
        normal_count: int = 20,
        anomaly_count: int = 3,
        base_value: float = 100,
        anomaly_multiplier: float = 5.0,
        tags: Optional[Dict[str, str]] = None
    ) -> List[Dict[str, Any]]:
        points = TestDataGenerator.create_metric_series(
            metric_name, normal_count, base_value, 5, tags
        )
        for i in range(anomaly_count):
            point = MetricPointBuilder() \
                .with_metric(metric_name) \
                .with_value(base_value * anomaly_multiplier) \
                .with_relative_time((anomaly_count - i) * 60) \
                .with_tags(**(tags or {}), anomaly="true") \
                .build()
            points.append(point)
        return points

    @staticmethod
    def create_notification_batch(
        count: int = 10,
        base_priority: Priority = Priority.LOW
    ) -> List[Dict[str, Any]]:
        notifications = []
        priorities = [Priority.LOW, Priority.MEDIUM, Priority.HIGH, Priority.CRITICAL]
        for i in range(count):
            priority = priorities[i % len(priorities)] if count > 1 else base_priority
            notif = NotificationBuilder() \
                .with_priority(priority) \
                .with_title(f"Test Alert {i}") \
                .with_message(f"This is test notification #{i}") \
                .with_tags("test", f"priority-{priority.value}") \
                .build()
            notifications.append(notif)
        return notifications
