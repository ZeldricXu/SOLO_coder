from sqlalchemy import Column, String, JSON, Integer, Boolean, DateTime
from sqlalchemy.dialects.sqlite import JSON as SQLiteJSON

from models import BaseModel, utc_now


class ScheduledTask(BaseModel):
    __tablename__ = "scheduled_tasks"

    name = Column(String, nullable=False)
    description = Column(String, nullable=True)
    task_type = Column(String, nullable=False)
    cron_expression = Column(String, nullable=True)
    interval_seconds = Column(Integer, nullable=True)
    run_once = Column(Boolean, default=False)
    parameters = Column(JSON().with_variant(SQLiteJSON, "sqlite"), default=dict)
    target_service = Column(String, nullable=True)
    target_endpoint = Column(String, nullable=True)
    enabled = Column(Boolean, default=True)
    status = Column(String, default="idle")
    last_run_at = Column(DateTime, nullable=True)
    next_run_at = Column(DateTime, nullable=True)
    success_count = Column(Integer, default=0)
    failure_count = Column(Integer, default=0)
    timeout_seconds = Column(Integer, default=300)
    max_retries = Column(Integer, default=3)
    retry_delay_seconds = Column(Integer, default=60)
    concurrency_limit = Column(Integer, default=1)


class TaskExecution(BaseModel):
    __tablename__ = "task_executions"

    task_id = Column(String, nullable=False, index=True)
    execution_id = Column(String, nullable=False, index=True)
    status = Column(String, default="pending")
    started_at = Column(DateTime, default=utc_now)
    completed_at = Column(DateTime, nullable=True)
    duration_ms = Column(Integer, nullable=True)
    result = Column(JSON().with_variant(SQLiteJSON, "sqlite"), default=dict)
    error = Column(String, nullable=True)
    retry_count = Column(Integer, default=0)
    worker_id = Column(String, nullable=True)
