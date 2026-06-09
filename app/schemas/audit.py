from datetime import datetime
from typing import Optional, Any
from pydantic import BaseModel, Field, ConfigDict

from app.models.audit import AuditAction


class AuditLogBase(BaseModel):
    user_id: int = Field(..., description="用户ID")
    action: AuditAction = Field(..., description="操作类型")
    resource_type: str = Field(..., max_length=100, description="资源类型")
    resource_id: Optional[int] = Field(None, description="资源ID")
    old_value: Optional[dict[str, Any]] = Field(None, description="修改前值")
    new_value: Optional[dict[str, Any]] = Field(None, description="修改后值")
    ip_address: Optional[str] = Field(None, max_length=50, description="IP地址")
    user_agent: Optional[str] = Field(None, max_length=500, description="用户代理")


class AuditLog(AuditLogBase):
    model_config = ConfigDict(from_attributes=True)

    id: int
    timestamp: datetime
    created_at: datetime
    username: Optional[str] = Field(None, description="用户名")
    resource_name: Optional[str] = Field(None, description="资源名称")


class AuditLogQuery(BaseModel):
    user_id: Optional[int] = Field(None, description="用户ID")
    action: Optional[AuditAction] = Field(None, description="操作类型")
    resource_type: Optional[str] = Field(None, description="资源类型")
    resource_id: Optional[int] = Field(None, description="资源ID")
    ip_address: Optional[str] = Field(None, description="IP地址")
    start_date: Optional[datetime] = Field(None, description="开始日期")
    end_date: Optional[datetime] = Field(None, description="结束日期")
    keyword: Optional[str] = Field(None, description="关键词搜索(资源类型、IP等)")


class AuditStatisticsResponse(BaseModel):
    total_count: int = Field(description="总操作次数")
    today_count: int = Field(description="今日操作次数")
    week_count: int = Field(description="本周操作次数")
    month_count: int = Field(description="本月操作次数")
    by_user: dict[str, int] = Field(default_factory=dict, description="按用户统计")
    by_action: dict[str, int] = Field(default_factory=dict, description="按操作类型统计")
    by_resource: dict[str, int] = Field(default_factory=dict, description="按资源类型统计")
    by_hour: dict[int, int] = Field(default_factory=dict, description="按小时统计")
    by_day: dict[str, int] = Field(default_factory=dict, description="按日期统计")


class AuditAnomalyDetectionRequest(BaseModel):
    time_window_minutes: int = Field(default=60, description="时间窗口(分钟)")
    operation_threshold: int = Field(default=50, description="操作次数阈值")
    check_non_working_hours: bool = Field(default=True, description="是否检查非工作时间")
    working_hours_start: int = Field(default=9, description="工作时间开始(小时)")
    working_hours_end: int = Field(default=18, description="工作时间结束(小时)")


class AuditAnomalyResponse(BaseModel):
    anomaly_type: str = Field(description="异常类型")
    user_id: Optional[int] = Field(None, description="用户ID")
    username: Optional[str] = Field(None, description="用户名")
    ip_address: Optional[str] = Field(None, description="IP地址")
    operation_count: int = Field(description="操作次数")
    time_window: str = Field(description="时间窗口")
    risk_level: str = Field(description="风险等级: LOW/MEDIUM/HIGH")
    description: str = Field(description="异常描述")
    first_operation_at: datetime = Field(description="首次操作时间")
    last_operation_at: datetime = Field(description="最后操作时间")


class AuditExportRequest(BaseModel):
    query: Optional[AuditLogQuery] = Field(None, description="查询条件")
    export_format: str = Field(default="csv", description="导出格式: csv/xlsx")
    include_details: bool = Field(default=True, description="是否包含详细字段")


class AuditExportResponse(BaseModel):
    download_url: str = Field(description="下载链接")
    filename: str = Field(description="文件名")
    file_size: int = Field(description="文件大小(字节)")
    record_count: int = Field(description="导出记录数")
    expires_at: datetime = Field(description="过期时间")


class UserActivityStats(BaseModel):
    user_id: int = Field(description="用户ID")
    username: str = Field(description="用户名")
    total_operations: int = Field(description="总操作数")
    create_count: int = Field(description="创建操作数")
    update_count: int = Field(description="更新操作数")
    delete_count: int = Field(description="删除操作数")
    login_count: int = Field(description="登录次数")
    last_active_at: Optional[datetime] = Field(None, description="最后活跃时间")
    risk_score: Optional[float] = Field(None, description="风险评分")


class ResourceActivityStats(BaseModel):
    resource_type: str = Field(description="资源类型")
    total_operations: int = Field(description="总操作数")
    create_count: int = Field(description="创建数")
    update_count: int = Field(description="更新数")
    delete_count: int = Field(description="删除数")
    most_active_user: Optional[str] = Field(None, description="最活跃用户")
    last_modified_at: Optional[datetime] = Field(None, description="最后修改时间")


class AuditLogDetail(AuditLog):
    changes_summary: Optional[list[dict[str, Any]]] = Field(None, description="变更摘要")
    related_logs: Optional[list[AuditLog]] = Field(None, description="相关操作日志")
