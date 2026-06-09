from datetime import datetime
from enum import Enum as PyEnum
from typing import Optional, List, Dict, Any
from pydantic import BaseModel, Field, ConfigDict

from app.models.inventory_alert import (
    AlertRuleType,
    ThresholdType,
    AlertLevel,
    AlertStatus,
)


class AlertRuleTypeEnum(str, PyEnum):
    LOW_STOCK = "LOW_STOCK"
    HIGH_STOCK = "HIGH_STOCK"
    OUT_OF_STOCK = "OUT_OF_STOCK"
    EXPIRING = "EXPIRING"
    SLOW_MOVING = "SLOW_MOVING"


class ThresholdTypeEnum(str, PyEnum):
    QUANTITY = "QUANTITY"
    PERCENTAGE = "PERCENTAGE"
    DAYS = "DAYS"


class AlertLevelEnum(str, PyEnum):
    WARNING = "WARNING"
    CRITICAL = "CRITICAL"


class AlertStatusEnum(str, PyEnum):
    OPEN = "OPEN"
    ACKNOWLEDGED = "ACKNOWLEDGED"
    RESOLVED = "RESOLVED"
    CLOSED = "CLOSED"


class NotifyChannelEnum(str, PyEnum):
    EMAIL = "EMAIL"
    DINGTALK = "DINGTALK"
    WECHAT_WORK = "WECHAT_WORK"
    WEBHOOK = "WEBHOOK"
    SMS = "SMS"


class AlertRuleBase(BaseModel):
    name: str = Field(..., max_length=200, description="规则名称")
    rule_type: AlertRuleTypeEnum = Field(..., description="预警类型")
    threshold_type: ThresholdTypeEnum = Field(..., description="阈值类型")
    threshold_value: float = Field(..., gt=0, description="预警阈值")
    warning_value: float = Field(..., gt=0, description="警告级别阈值")
    critical_value: float = Field(..., gt=0, description="严重级别阈值")
    sku_ids: Optional[List[int]] = Field(default=None, description="指定SKU ID列表")
    category_id: Optional[int] = Field(default=None, description="商品分类ID")
    warehouse_ids: Optional[List[int]] = Field(default=None, description="仓库ID列表")
    is_active: bool = Field(default=True, description="是否启用")
    notify_channels: Optional[List[NotifyChannelEnum]] = Field(
        default=None, description="通知渠道"
    )


class AlertRuleCreate(AlertRuleBase):
    pass


class AlertRuleUpdate(BaseModel):
    name: Optional[str] = Field(default=None, max_length=200, description="规则名称")
    rule_type: Optional[AlertRuleTypeEnum] = Field(default=None, description="预警类型")
    threshold_type: Optional[ThresholdTypeEnum] = Field(default=None, description="阈值类型")
    threshold_value: Optional[float] = Field(default=None, gt=0, description="预警阈值")
    warning_value: Optional[float] = Field(default=None, gt=0, description="警告级别阈值")
    critical_value: Optional[float] = Field(default=None, gt=0, description="严重级别阈值")
    sku_ids: Optional[List[int]] = Field(default=None, description="指定SKU ID列表")
    category_id: Optional[int] = Field(default=None, description="商品分类ID")
    warehouse_ids: Optional[List[int]] = Field(default=None, description="仓库ID列表")
    is_active: Optional[bool] = Field(default=None, description="是否启用")
    notify_channels: Optional[List[NotifyChannelEnum]] = Field(
        default=None, description="通知渠道"
    )


class AlertRule(AlertRuleBase):
    model_config = ConfigDict(from_attributes=True)

    id: int
    created_at: datetime
    updated_at: datetime


class AlertRuleDetail(AlertRule):
    model_config = ConfigDict(from_attributes=True)

    category_name: Optional[str] = Field(default=None, description="分类名称")
    warehouse_names: Optional[List[str]] = Field(default=None, description="仓库名称列表")
    sku_count: Optional[int] = Field(default=None, description="关联SKU数量")
    alert_count: Optional[int] = Field(default=None, description="触发预警次数")
    last_triggered_at: Optional[datetime] = Field(default=None, description="最后触发时间")


class AlertRuleListFilter(BaseModel):
    rule_type: Optional[AlertRuleTypeEnum] = Field(default=None, description="预警类型")
    is_active: Optional[bool] = Field(default=None, description="是否启用")
    threshold_type: Optional[ThresholdTypeEnum] = Field(default=None, description="阈值类型")
    keyword: Optional[str] = Field(default=None, description="关键词搜索")


class InventoryAlertBase(BaseModel):
    pass


class InventoryAlert(InventoryAlertBase):
    model_config = ConfigDict(from_attributes=True)

    id: int
    rule_id: int
    sku_id: int
    warehouse_id: int
    alert_level: AlertLevelEnum
    alert_type: AlertRuleTypeEnum
    current_value: float
    threshold_value: float
    message: str
    status: AlertStatusEnum
    acknowledged_by: Optional[int]
    acknowledged_at: Optional[datetime]
    resolved_by: Optional[int]
    resolved_at: Optional[datetime]
    created_at: datetime

    rule_name: Optional[str] = Field(default=None, description="规则名称")
    sku_code: Optional[str] = Field(default=None, description="SKU编码")
    sku_name: Optional[str] = Field(default=None, description="SKU名称")
    warehouse_name: Optional[str] = Field(default=None, description="仓库名称")
    acknowledged_by_name: Optional[str] = Field(default=None, description="确认人姓名")
    resolved_by_name: Optional[str] = Field(default=None, description="解决人姓名")


class InventoryAlertDetail(InventoryAlert):
    model_config = ConfigDict(from_attributes=True)

    rule_detail: Optional[AlertRule] = Field(default=None, description="规则详情")
    inventory_data: Optional[Dict[str, Any]] = Field(default=None, description="库存数据")
    history_alerts: Optional[List["InventoryAlert"]] = Field(
        default=None, description="历史预警记录"
    )


class AlertAcknowledgeRequest(BaseModel):
    remark: Optional[str] = Field(default=None, max_length=500, description="确认备注")


class AlertResolveRequest(BaseModel):
    resolution: str = Field(..., max_length=500, description="解决方案")
    remark: Optional[str] = Field(default=None, max_length=500, description="备注")


class AlertListFilter(BaseModel):
    status: Optional[AlertStatusEnum] = Field(default=None, description="预警状态")
    alert_level: Optional[AlertLevelEnum] = Field(default=None, description="预警级别")
    alert_type: Optional[AlertRuleTypeEnum] = Field(default=None, description="预警类型")
    sku_id: Optional[int] = Field(default=None, description="SKU ID")
    warehouse_id: Optional[int] = Field(default=None, description="仓库ID")
    rule_id: Optional[int] = Field(default=None, description="规则ID")
    date_from: Optional[datetime] = Field(default=None, description="开始日期")
    date_to: Optional[datetime] = Field(default=None, description="结束日期")
    acknowledged: Optional[bool] = Field(default=None, description="是否已确认")


class AlertStatisticsResponse(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    total_count: int = Field(description="预警总数")
    open_count: int = Field(description="待处理预警数")
    acknowledged_count: int = Field(description="已确认预警数")
    resolved_count: int = Field(description="已解决预警数")
    closed_count: int = Field(description="已关闭预警数")
    warning_count: int = Field(description="警告级别预警数")
    critical_count: int = Field(description="严重级别预警数")
    low_stock_count: int = Field(description="低库存预警数")
    high_stock_count: int = Field(description="高库存预警数")
    out_of_stock_count: int = Field(description="缺货预警数")
    expiring_count: int = Field(description="临期预警数")
    slow_moving_count: int = Field(description="滞销预警数")
    today_count: int = Field(description="今日预警数")
    week_count: int = Field(description="本周预警数")
    month_count: int = Field(description="本月预警数")
    trend_data: Optional[List[Dict[str, Any]]] = Field(
        default=None, description="趋势数据"
    )
    top_skus: Optional[List[Dict[str, Any]]] = Field(
        default=None, description="预警最多的SKU"
    )
    top_warehouses: Optional[List[Dict[str, Any]]] = Field(
        default=None, description="预警最多的仓库"
    )


class AlertCheckResponse(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    checked_count: int = Field(description="检查的SKU数量")
    new_alerts_count: int = Field(description="新增预警数量")
    resolved_alerts_count: int = Field(description="自动解决预警数量")
    new_alerts: Optional[List[InventoryAlert]] = Field(
        default=None, description="新增预警列表"
    )


class AlertRuleListResponse(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    items: List[AlertRule]
    page: int
    page_size: int
    total: int
    total_pages: int
    has_next: bool
    has_prev: bool


class AlertListResponse(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    items: List[InventoryAlert]
    page: int
    page_size: int
    total: int
    total_pages: int
    has_next: bool
    has_prev: bool


InventoryAlertDetail.model_rebuild()
