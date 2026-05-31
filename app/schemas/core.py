from datetime import datetime
from typing import Optional, Dict, Any, List
from uuid import UUID
from pydantic import BaseModel, Field, ConfigDict


class ResourceCreate(BaseModel):
    type: str = Field(..., description="资源类型")
    config: Dict[str, Any] = Field(default_factory=dict, description="资源配置")
    labels: Dict[str, str] = Field(default_factory=dict, description="资源标签")
    namespace: str = Field("default", description="命名空间")
    metadata: Dict[str, Any] = Field(default_factory=dict, description="元数据")


class ResourceResponse(BaseModel):
    id: str = Field(..., description="资源ID")
    type: str = Field(..., description="资源类型")
    status: str = Field(..., description="资源状态")
    config: Dict[str, Any] = Field(default_factory=dict, description="资源配置")
    labels: Dict[str, str] = Field(default_factory=dict, description="资源标签")
    namespace: str = Field(..., description="命名空间")
    created_at: datetime = Field(..., description="创建时间")
    metadata: Dict[str, Any] = Field(default_factory=dict, description="元数据")


class ResourceStatusResponse(BaseModel):
    id: str = Field(..., description="资源ID")
    status: str = Field(..., description="资源状态")
    progress: Optional[float] = Field(None, description="进度")
    phase: Optional[str] = Field(None, description="阶段")
    started_at: Optional[datetime] = Field(None, description="开始时间")
    completed_at: Optional[datetime] = Field(None, description="完成时间")
    error_detail: Optional[str] = Field(None, description="错误详情")
    metadata: Dict[str, Any] = Field(default_factory=dict, description="元数据")


class Operation(BaseModel):
    action: str = Field(..., description="操作类型")
    id: str = Field(..., description="资源ID")
    params: Dict[str, Any] = Field(default_factory=dict, description="操作参数")


class BatchOperationRequest(BaseModel):
    operations: List[Operation] = Field(..., description="操作列表")
    timeout_seconds: int = Field(60, description="超时时间(秒)")


class OperationResult(BaseModel):
    id: str = Field(..., description="资源ID")
    action: str = Field(..., description="操作类型")
    status: str = Field(..., description="操作状态")
    message: Optional[str] = Field(None, description="操作消息")
    result: Optional[Dict[str, Any]] = Field(None, description="操作结果")


class BatchOperationResponse(BaseModel):
    batch_id: str = Field(..., description="批次ID")
    results: List[OperationResult] = Field(..., description="操作结果列表")
    total_count: int = Field(..., description="总操作数")
    success_count: int = Field(..., description="成功数")
    failed_count: int = Field(..., description="失败数")


class TaskExecuteRequest(BaseModel):
    task_type: str = Field(..., description="任务类型")
    namespace: str = Field("default", description="命名空间")
    payload: Dict[str, Any] = Field(default_factory=dict, description="任务负载")
    priority: int = Field(2, description="优先级")
    callback_url: Optional[str] = Field(None, description="回调URL")
    metadata: Dict[str, Any] = Field(default_factory=dict, description="元数据")


class TaskExecuteResponse(BaseModel):
    task_id: str = Field(..., description="任务ID")
    status: str = Field(..., description="任务状态")
    run_id: Optional[str] = Field(None, description="运行ID")
    message: Optional[str] = Field(None, description="消息")
    estimated_duration_seconds: Optional[int] = Field(None, description="预计时长(秒)")
