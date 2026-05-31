from sqlalchemy import Column, String, Integer, JSON, DateTime, Boolean, Float, Text
from sqlalchemy.sql import func
from .database import Base


class Entity(Base):
    __tablename__ = "entities"
    
    id = Column(String(64), primary_key=True, index=True)
    type = Column(String(50), nullable=False, index=True)
    status = Column(String(50), nullable=False, default="pending")
    attributes = Column(JSON, nullable=True, default=dict)
    created_at = Column(DateTime(timezone=True), server_default=func.now())
    updated_at = Column(DateTime(timezone=True), server_default=func.now(), onupdate=func.now())


class Config(Base):
    __tablename__ = "configs"
    
    id = Column(Integer, primary_key=True, autoincrement=True)
    config_id = Column(String(64), unique=True, index=True, nullable=False)
    namespace = Column(String(100), index=True, nullable=False)
    version = Column(Integer, nullable=False, default=1)
    parameters = Column(JSON, nullable=False, default=dict)
    enabled = Column(Boolean, default=True)
    applied_at = Column(DateTime(timezone=True), server_default=func.now())
    created_at = Column(DateTime(timezone=True), server_default=func.now())


class RunInstance(Base):
    __tablename__ = "run_instances"
    
    run_id = Column(String(64), primary_key=True, index=True)
    entity_id = Column(String(64), index=True, nullable=False)
    phase = Column(String(50), nullable=False, default="initialized")
    progress = Column(Float, nullable=False, default=0.0)
    started_at = Column(DateTime(timezone=True), server_default=func.now())
    completed_at = Column(DateTime(timezone=True), nullable=True)
    error_detail = Column(Text, nullable=True)


class MetricSnapshot(Base):
    __tablename__ = "metric_snapshots"
    
    id = Column(Integer, primary_key=True, autoincrement=True)
    snapshot_id = Column(String(64), unique=True, index=True, nullable=False)
    timestamp = Column(DateTime(timezone=True), server_default=func.now(), index=True)
    metrics = Column(JSON, nullable=False, default=dict)
    dimensions = Column(JSON, nullable=False, default=dict)


class Notification(Base):
    __tablename__ = "notifications"
    
    id = Column(String(64), primary_key=True, index=True)
    title = Column(String(200), nullable=False)
    content = Column(Text, nullable=False)
    priority = Column(Integer, nullable=False, default=5)
    channel = Column(String(50), nullable=False)
    status = Column(String(50), nullable=False, default="pending")
    recipient = Column(String(200), nullable=True)
    sent_at = Column(DateTime(timezone=True), nullable=True)
    created_at = Column(DateTime(timezone=True), server_default=func.now())
    metadata = Column(JSON, nullable=True, default=dict)


class SchemaVersion(Base):
    __tablename__ = "schema_versions"
    
    id = Column(Integer, primary_key=True, autoincrement=True)
    version = Column(Integer, unique=True, nullable=False)
    name = Column(String(200), nullable=False)
    applied_at = Column(DateTime(timezone=True), server_default=func.now())
    rollback_sql = Column(Text, nullable=True)


class NotificationQueueItem(Base):
    __tablename__ = "notification_queue_items"
    
    id = Column(String(64), primary_key=True, index=True)
    priority = Column(Integer, nullable=False, default=5)
    title = Column(String(200), nullable=False)
    content = Column(Text, nullable=False)
    channel = Column(String(50), nullable=False)
    recipient = Column(String(200), nullable=True)
    deduplication_key = Column(String(200), nullable=True, index=True)
    ttl_seconds = Column(Integer, nullable=True)
    metadata = Column(JSON, nullable=True, default=dict)
    status = Column(String(50), nullable=False, default="pending", index=True)
    retry_count = Column(Integer, nullable=False, default=0)
    next_retry_at = Column(DateTime(timezone=True), nullable=True)
    error_message = Column(Text, nullable=True)
    queued_at = Column(DateTime(timezone=True), server_default=func.now(), index=True)
    sent_at = Column(DateTime(timezone=True), nullable=True)


class NotificationSuppressionRule(Base):
    __tablename__ = "notification_suppression_rules"
    
    id = Column(Integer, primary_key=True, autoincrement=True)
    rule_id = Column(String(64), unique=True, index=True, nullable=False)
    name = Column(String(200), nullable=False)
    enabled = Column(Boolean, default=True)
    priority_threshold = Column(Integer, nullable=True)
    channel = Column(String(50), nullable=True)
    time_window_seconds = Column(Integer, nullable=False, default=60)
    max_count = Column(Integer, nullable=False, default=10)
    pattern = Column(Text, nullable=True)
    created_at = Column(DateTime(timezone=True), server_default=func.now())
    updated_at = Column(DateTime(timezone=True), server_default=func.now(), onupdate=func.now())


class DynamicConfig(Base):
    __tablename__ = "dynamic_configs"
    
    id = Column(Integer, primary_key=True, autoincrement=True)
    config_key = Column(String(200), unique=True, index=True, nullable=False)
    config_value = Column(JSON, nullable=False, default=dict)
    version = Column(Integer, nullable=False, default=1)
    description = Column(String(500), nullable=True)
    created_at = Column(DateTime(timezone=True), server_default=func.now())
    updated_at = Column(DateTime(timezone=True), server_default=func.now(), onupdate=func.now())


class CacheEntry(Base):
    __tablename__ = "cache_entries"
    
    id = Column(Integer, primary_key=True, autoincrement=True)
    cache_key = Column(String(500), unique=True, index=True, nullable=False)
    cache_value = Column(JSON, nullable=False)
    route_path = Column(String(200), nullable=True, index=True)
    expires_at = Column(DateTime(timezone=True), nullable=True, index=True)
    hit_count = Column(Integer, nullable=False, default=0)
    created_at = Column(DateTime(timezone=True), server_default=func.now())
    last_accessed_at = Column(DateTime(timezone=True), server_default=func.now(), onupdate=func.now())
