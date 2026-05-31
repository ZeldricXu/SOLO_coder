from datetime import datetime
from typing import Optional, Dict, Any, List
from uuid import UUID
from pydantic import BaseModel, Field, ConfigDict


class MetricSnapshotCreate(BaseModel):
    metrics: Dict[str, Any] = Field(..., description="指标数据")
    dimensions: Dict[str, Any] = Field(default_factory=dict, description="维度信息")
    host: Optional[str] = Field(None, description="主机")
    region: Optional[str] = Field(None, description="区域")
    service: Optional[str] = Field(None, description="服务")
    metadata: Dict[str, Any] = Field(default_factory=dict, description="元数据")


class MetricSnapshotResponse(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: UUID = Field(..., description="快照ID")
    timestamp: datetime = Field(..., description="时间戳")
    metrics: Dict[str, Any] = Field(..., description="指标数据")
    dimensions: Dict[str, Any] = Field(default_factory=dict, description="维度信息")
    host: Optional[str] = Field(None, description="主机")
    region: Optional[str] = Field(None, description="区域")
    service: Optional[str] = Field(None, description="服务")
    created_at: datetime = Field(..., description="创建时间")
    metadata: Dict[str, Any] = Field(default_factory=dict, description="元数据")


class MetricsQuery(BaseModel):
    start_time: datetime = Field(..., description="开始时间")
    end_time: datetime = Field(..., description="结束时间")
    metric_names: Optional[List[str]] = Field(None, description="指标名称列表")
    dimensions: Optional[Dict[str, Any]] = Field(None, description="维度过滤")
    host: Optional[str] = Field(None, description="主机")
    service: Optional[str] = Field(None, description="服务")
    aggregation: Optional[str] = Field("avg", description="聚合方式")


class MetricsResponse(BaseModel):
    timestamps: List[datetime] = Field(..., description="时间戳列表")
    metrics: Dict[str, List[float]] = Field(..., description="指标数据")
    metadata: Dict[str, Any] = Field(default_factory=dict, description="元数据")


class AuditLogCreate(BaseModel):
    action: str = Field(..., description="操作")
    resource_type: Optional[str] = Field(None, description="资源类型")
    resource_id: Optional[str] = Field(None, description="资源ID")
    status: str = Field(..., description="状态")
    request_details: Dict[str, Any] = Field(default_factory=dict, description="请求详情")
    response_details: Dict[str, Any] = Field(default_factory=dict, description="响应详情")
    ip_address: Optional[str] = Field(None, description="IP地址")
    user_agent: Optional[str] = Field(None, description="User Agent")
    metadata: Dict[str, Any] = Field(default_factory=dict, description="元数据")


class AuditLogResponse(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: UUID = Field(..., description="日志ID")
    timestamp: datetime = Field(..., description="时间戳")
    user_id: Optional[UUID] = Field(None, description="用户ID")
    action: str = Field(..., description="操作")
    resource_type: Optional[str] = Field(None, description="资源类型")
    resource_id: Optional[str] = Field(None, description="资源ID")
    status: str = Field(..., description="状态")
    request_details: Dict[str, Any] = Field(default_factory=dict, description="请求详情")
    response_details: Dict[str, Any] = Field(default_factory=dict, description="响应详情")
    ip_address: Optional[str] = Field(None, description="IP地址")
    user_agent: Optional[str] = Field(None, description="User Agent")
    created_at: datetime = Field(..., description="创建时间")
    metadata: Dict[str, Any] = Field(default_factory=dict, description="元数据")
