from sqlalchemy import Column, String, JSON, Integer, DateTime, Float, Text
from sqlalchemy.dialects.sqlite import JSON as SQLiteJSON

from models import BaseModel, utc_now


class RequestLog(BaseModel):
    __tablename__ = "request_logs"

    trace_id = Column(String, index=True, nullable=False)
    span_id = Column(String, index=True, nullable=False)
    parent_span_id = Column(String, index=True, nullable=True)
    service_name = Column(String, nullable=False)
    method = Column(String, nullable=False)
    path = Column(String, nullable=False)
    status_code = Column(Integer, nullable=True)
    request_headers = Column(JSON().with_variant(SQLiteJSON, "sqlite"), default=dict)
    request_body = Column(Text, nullable=True)
    response_headers = Column(JSON().with_variant(SQLiteJSON, "sqlite"), default=dict)
    response_body = Column(Text, nullable=True)
    client_ip = Column(String, nullable=True)
    user_agent = Column(String, nullable=True)
    user_id = Column(String, nullable=True)
    duration_ms = Column(Float, nullable=True)
    error_message = Column(String, nullable=True)
    started_at = Column(DateTime, nullable=False, default=utc_now)
    completed_at = Column(DateTime, nullable=True)


class TraceSpan(BaseModel):
    __tablename__ = "trace_spans"

    trace_id = Column(String, index=True, nullable=False)
    span_id = Column(String, index=True, nullable=False)
    parent_span_id = Column(String, index=True, nullable=True)
    name = Column(String, nullable=False)
    service_name = Column(String, nullable=False)
    kind = Column(String, nullable=True)
    attributes = Column(JSON().with_variant(SQLiteJSON, "sqlite"), default=dict)
    status = Column(String, nullable=True)
    status_message = Column(String, nullable=True)
    started_at = Column(DateTime, nullable=False, default=utc_now)
    ended_at = Column(DateTime, nullable=True)
    duration_ms = Column(Float, nullable=True)


class APIRateLimit(BaseModel):
    __tablename__ = "api_rate_limits"

    path = Column(String, nullable=False)
    method = Column(String, nullable=False)
    limit_per_minute = Column(Integer, default=60)
    limit_per_hour = Column(Integer, default=1000)
    limit_per_day = Column(Integer, default=10000)
    enabled = Column(String, default="true")
    client_key = Column(String, nullable=True)
