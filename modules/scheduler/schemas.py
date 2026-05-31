from datetime import datetime
from typing import Any, Dict, List, Optional
from pydantic import BaseModel, Field


class ScheduledTaskCreate(BaseModel):
    name: str
    description: Optional[str] = None
    task_type: str
    cron_expression: Optional[str] = None
    interval_seconds: Optional[int] = None
    run_once: bool = False
    parameters: Dict[str, Any] = Field(default_factory=dict)
    target_service: Optional[str] = None
    target_endpoint: Optional[str] = None
    enabled: bool = True
    timeout_seconds: int = 300
    max_retries: int = 3
    retry_delay_seconds: int = 60
    concurrency_limit: int = 1
    labels: Dict[str, Any] = Field(default_factory=dict)


class ScheduledTaskUpdate(BaseModel):
    name: Optional[str] = None
    description: Optional[str] = None
    cron_expression: Optional[str] = None
    interval_seconds: Optional[int] = None
    enabled: Optional[bool] = None
    parameters: Optional[Dict[str, Any]] = None
    timeout_seconds: Optional[int] = None
    max_retries: Optional[int] = None


class ScheduledTaskResponse(BaseModel):
    id: str
    name: str
    description: Optional[str]
    task_type: str
    cron_expression: Optional[str]
    interval_seconds: Optional[int]
    run_once: bool
    parameters: Dict[str, Any]
    target_service: Optional[str]
    target_endpoint: Optional[str]
    enabled: bool
    status: str
    last_run_at: Optional[datetime]
    next_run_at: Optional[datetime]
    success_count: int
    failure_count: int
    timeout_seconds: int
    max_retries: int
    concurrency_limit: int
    created_at: datetime
    updated_at: datetime

    class Config:
        from_attributes = True


class TaskExecutionResponse(BaseModel):
    id: str
    task_id: str
    execution_id: str
    status: str
    started_at: datetime
    completed_at: Optional[datetime]
    duration_ms: Optional[int]
    result: Dict[str, Any]
    error: Optional[str]
    retry_count: int
    worker_id: Optional[str]
    created_at: datetime

    class Config:
        from_attributes = True


class TaskTriggerRequest(BaseModel):
    task_id: str
    parameters: Optional[Dict[str, Any]] = None


class TaskPauseRequest(BaseModel):
    task_id: str


class TaskResumeRequest(BaseModel):
    task_id: str
