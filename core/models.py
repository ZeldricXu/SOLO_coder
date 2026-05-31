from datetime import datetime
from typing import Any, Dict, Optional
from pydantic import BaseModel, Field


class EntityModel(BaseModel):
    id: str = Field(..., description="实体ID")
    type: str = Field(..., description="实体类型")
    status: str = Field(..., description="实体状态")
    attributes: Dict[str, Any] = Field(default_factory=dict, description="属性集合")
    created_at: datetime = Field(default_factory=datetime.utcnow, description="创建时间")
    updated_at: datetime = Field(default_factory=datetime.utcnow, description="更新时间")


class ConfigModel(BaseModel):
    config_id: str = Field(..., description="配置ID")
    namespace: str = Field(..., description="命名空间")
    version: int = Field(..., description="版本号")
    parameters: Dict[str, Any] = Field(default_factory=dict, description="参数集合")
    enabled: bool = Field(default=True, description="是否启用")
    applied_at: datetime = Field(default_factory=datetime.utcnow, description="应用时间")


class RunInstanceModel(BaseModel):
    run_id: str = Field(..., description="运行实例ID")
    entity_id: str = Field(..., description="关联实体ID")
    phase: str = Field(..., description="执行阶段")
    progress: float = Field(..., ge=0.0, le=1.0, description="进度")
    started_at: datetime = Field(default_factory=datetime.utcnow, description="开始时间")
    completed_at: Optional[datetime] = Field(None, description="完成时间")
    error_detail: Optional[str] = Field(None, description="错误详情")


class MetricsModel(BaseModel):
    throughput: float = Field(..., description="吞吐量")
    latency_p99: float = Field(..., description="P99延迟")
    error_rate: float = Field(..., description="错误率")


class SnapshotModel(BaseModel):
    snapshot_id: str = Field(..., description="快照ID")
    timestamp: datetime = Field(default_factory=datetime.utcnow, description="时间戳")
    metrics: MetricsModel = Field(..., description="指标数据")
    dimensions: Dict[str, str] = Field(default_factory=dict, description="维度信息")


class APIResponse(BaseModel):
    code: int = Field(..., description="响应码")
    data: Optional[Dict[str, Any]] = Field(None, description="响应数据")
    message: Optional[str] = Field(None, description="响应消息")
