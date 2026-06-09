from datetime import datetime, date
from enum import Enum as PyEnum
from typing import Optional, List, Dict, Any
from pydantic import BaseModel, Field, ConfigDict



class ReplenishmentStatusEnum(str, PyEnum):
    PENDING = "PENDING"
    APPROVED = "APPROVED"
    REJECTED = "REJECTED"
    CONVERTED = "CONVERTED"


class ForecastPeriodEnum(str, PyEnum):
    DAILY = "DAILY"
    WEEKLY = "WEEKLY"
    MONTHLY = "MONTHLY"


class ForecastMethodEnum(str, PyEnum):
    MOVING_AVERAGE = "MOVING_AVERAGE"
    EXPONENTIAL_SMOOTHING = "EXPONENTIAL_SMOOTHING"
    ARIMA = "ARIMA"
    LINEAR_REGRESSION = "LINEAR_REGRESSION"
    SEASONAL = "SEASONAL"


class ReplenishmentSuggestionBase(BaseModel):
    sku_id: int = Field(description="SKU ID")
    supplier_id: int = Field(description="供应商ID")
    warehouse_id: int = Field(description="仓库ID")
    suggested_quantity: int = Field(ge=1, description="建议补货数量")
    suggested_unit_price: float = Field(ge=0, description="建议单价")
    reason: str = Field(..., max_length=500, description="补货原因")
    demand_forecast: int = Field(ge=0, description="预测需求量")
    current_stock: int = Field(ge=0, description="当前库存")
    safety_stock: int = Field(ge=0, description="安全库存")
    lead_time_days: int = Field(ge=0, description="交货周期(天)")
    expected_delivery_date: date = Field(description="预计到货日期")


class ReplenishmentSuggestionCreate(ReplenishmentSuggestionBase):
    pass


class ReplenishmentSuggestion(ReplenishmentSuggestionBase):
    model_config = ConfigDict(from_attributes=True)

    id: int
    estimated_total_cost: float
    status: ReplenishmentStatusEnum
    purchase_order_id: Optional[int]
    created_by: int
    created_at: datetime
    updated_at: datetime
    reviewed_by: Optional[int]
    reviewed_at: Optional[datetime]

    sku_code: Optional[str] = Field(default=None, description="SKU编码")
    sku_name: Optional[str] = Field(default=None, description="SKU名称")
    supplier_name: Optional[str] = Field(default=None, description="供应商名称")
    warehouse_name: Optional[str] = Field(default=None, description="仓库名称")
    created_by_name: Optional[str] = Field(default=None, description="创建人姓名")
    reviewed_by_name: Optional[str] = Field(default=None, description="审核人姓名")


class ReplenishmentSuggestionDetail(ReplenishmentSuggestion):
    model_config = ConfigDict(from_attributes=True)

    forecast_data: Optional[Dict[str, Any]] = Field(default=None, description="预测数据")
    seasonal_indices: Optional[List[float]] = Field(default=None, description="季节指数")
    historical_sales: Optional[List[Dict[str, Any]]] = Field(
        default=None, description="历史销售数据"
    )
    inventory_trend: Optional[List[Dict[str, Any]]] = Field(
        default=None, description="库存趋势"
    )
    similar_suggestions: Optional[List["ReplenishmentSuggestion"]] = Field(
        default=None, description="类似补货建议"
    )


class ReplenishmentReviewRequest(BaseModel):
    approved: bool = Field(description="是否通过")
    remark: Optional[str] = Field(default=None, max_length=500, description="审核备注")
    adjusted_quantity: Optional[int] = Field(
        default=None, ge=1, description="调整后的补货数量"
    )


class ReplenishmentConvertRequest(BaseModel):
    purchase_order_id: Optional[int] = Field(
        default=None, description="关联到现有采购订单ID，为空则创建新订单"
    )
    remark: Optional[str] = Field(default=None, max_length=500, description="备注")
    order_date: Optional[datetime] = Field(default=None, description="订单日期")
    expected_date: Optional[datetime] = Field(default=None, description="预计到货日期")


class ReplenishmentGenerateRequest(BaseModel):
    warehouse_id: Optional[int] = Field(default=None, description="仓库ID，为空则所有仓库")
    sku_ids: Optional[List[int]] = Field(default=None, description="指定SKU ID列表")
    category_id: Optional[int] = Field(default=None, description="商品分类ID")
    supplier_id: Optional[int] = Field(default=None, description="供应商ID")
    forecast_days: int = Field(default=30, ge=7, le=365, description="预测天数")
    safety_stock_factor: float = Field(
        default=1.5, ge=0.5, le=5.0, description="安全库存系数"
    )
    consider_seasonality: bool = Field(default=True, description="是否考虑季节性")
    consider_lead_time: bool = Field(default=True, description="是否考虑交货周期")
    min_order_quantity: Optional[int] = Field(
        default=None, ge=1, description="最小订货量"
    )


class SalesForecastBase(BaseModel):
    sku_id: int = Field(description="SKU ID")
    forecast_date: date = Field(description="预测日期")
    forecast_period: ForecastPeriodEnum = Field(description="预测周期")
    forecast_method: ForecastMethodEnum = Field(description="预测方法")
    confidence_level: float = Field(ge=0, le=1, description="置信度")


class SalesForecastCreate(SalesForecastBase):
    historical_data: Optional[List[Dict[str, Any]]] = Field(
        default=None, description="历史数据"
    )
    forecast_data: Optional[List[Dict[str, Any]]] = Field(
        default=None, description="预测数据"
    )
    mape: Optional[float] = Field(default=None, ge=0, description="平均绝对百分比误差")
    rmse: Optional[float] = Field(default=None, ge=0, description="均方根误差")
    mae: Optional[float] = Field(default=None, ge=0, description="平均绝对误差")


class SalesForecast(SalesForecastBase):
    model_config = ConfigDict(from_attributes=True)

    id: int
    historical_data: Optional[Dict[str, Any]] = Field(default=None, description="历史数据")
    forecast_data: Optional[Dict[str, Any]] = Field(default=None, description="预测数据")
    mape: Optional[float] = Field(default=None, description="平均绝对百分比误差")
    rmse: Optional[float] = Field(default=None, description="均方根误差")
    mae: Optional[float] = Field(default=None, description="平均绝对误差")
    created_at: datetime

    sku_code: Optional[str] = Field(default=None, description="SKU编码")
    sku_name: Optional[str] = Field(default=None, description="SKU名称")
    category_name: Optional[str] = Field(default=None, description="分类名称")


class ForecastRequest(BaseModel):
    sku_ids: Optional[List[int]] = Field(default=None, description="SKU ID列表")
    category_id: Optional[int] = Field(default=None, description="分类ID")
    warehouse_id: Optional[int] = Field(default=None, description="仓库ID")
    forecast_period: ForecastPeriodEnum = Field(
        default=ForecastPeriodEnum.DAILY, description="预测周期"
    )
    forecast_method: ForecastMethodEnum = Field(
        default=ForecastMethodEnum.SEASONAL, description="预测方法"
    )
    forecast_days: int = Field(default=30, ge=1, le=365, description="预测天数")
    historical_days: int = Field(default=90, ge=30, le=730, description="历史数据天数")
    consider_seasonality: bool = Field(default=True, description="是否考虑季节性")
    consider_holidays: bool = Field(default=True, description="是否考虑节假日")
    consider_weather: bool = Field(default=False, description="是否考虑天气影响")


class ForecastResponse(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    sku_id: int
    sku_code: str
    sku_name: str
    forecast_period: ForecastPeriodEnum
    forecast_method: ForecastMethodEnum
    forecast_days: int
    confidence_level: float
    forecast_values: List[Dict[str, Any]]
    historical_values: List[Dict[str, Any]]
    seasonal_indices: Optional[List[float]] = Field(default=None, description="季节指数")
    mape: Optional[float] = Field(default=None, description="MAPE")
    rmse: Optional[float] = Field(default=None, description="RMSE")
    mae: Optional[float] = Field(default=None, description="MAE")
    seasonality_detected: bool = Field(default=False, description="是否检测到季节性")
    seasonality_strength: Optional[float] = Field(default=None, description="季节性强度")


class ForecastListFilter(BaseModel):
    sku_id: Optional[int] = Field(default=None, description="SKU ID")
    forecast_period: Optional[ForecastPeriodEnum] = Field(default=None, description="预测周期")
    forecast_method: Optional[ForecastMethodEnum] = Field(default=None, description="预测方法")
    date_from: Optional[date] = Field(default=None, description="开始日期")
    date_to: Optional[date] = Field(default=None, description="结束日期")
    min_confidence: Optional[float] = Field(default=None, ge=0, le=1, description="最小置信度")


class ReplenishmentStatisticsResponse(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    total_suggestions: int = Field(description="补货建议总数")
    pending_count: int = Field(description="待审核数量")
    approved_count: int = Field(description="已通过数量")
    rejected_count: int = Field(description="已驳回数量")
    converted_count: int = Field(description="已转订单数量")
    total_suggested_quantity: int = Field(description="建议补货总数量")
    total_approved_quantity: int = Field(description="已通过补货总数量")
    total_converted_quantity: int = Field(description="已转订单补货总数量")
    total_suggested_cost: float = Field(description="建议补货总成本")
    total_approved_cost: float = Field(description="已通过补货总成本")
    total_converted_cost: float = Field(description="已转订单补货总成本")
    approval_rate: float = Field(description="审批通过率")
    conversion_rate: float = Field(description="转订单率")
    average_lead_time: float = Field(description="平均交货周期")
    forecast_accuracy: Optional[float] = Field(default=None, description="预测准确率")
    today_count: int = Field(description="今日新增建议数")
    week_count: int = Field(description="本周新增建议数")
    month_count: int = Field(description="本月新增建议数")
    trend_data: Optional[List[Dict[str, Any]]] = Field(
        default=None, description="趋势数据"
    )
    top_skus: Optional[List[Dict[str, Any]]] = Field(
        default=None, description="补货最多的SKU"
    )
    top_suppliers: Optional[List[Dict[str, Any]]] = Field(
        default=None, description="补货最多的供应商"
    )
    top_warehouses: Optional[List[Dict[str, Any]]] = Field(
        default=None, description="补货最多的仓库"
    )


class ReplenishmentGenerateResponse(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    generated_count: int = Field(description="生成的补货建议数量")
    total_suggested_quantity: int = Field(description="建议补货总数量")
    total_estimated_cost: float = Field(description="预计总成本")
    suggestions: List[ReplenishmentSuggestion]
    forecast_summary: Optional[Dict[str, Any]] = Field(
        default=None, description="预测摘要"
    )


class ReplenishmentListFilter(BaseModel):
    status: Optional[ReplenishmentStatusEnum] = Field(default=None, description="状态")
    sku_id: Optional[int] = Field(default=None, description="SKU ID")
    supplier_id: Optional[int] = Field(default=None, description="供应商ID")
    warehouse_id: Optional[int] = Field(default=None, description="仓库ID")
    created_by: Optional[int] = Field(default=None, description="创建人ID")
    date_from: Optional[datetime] = Field(default=None, description="开始日期")
    date_to: Optional[datetime] = Field(default=None, description="结束日期")
    min_quantity: Optional[int] = Field(default=None, description="最小数量")
    max_quantity: Optional[int] = Field(default=None, description="最大数量")


class ReplenishmentSuggestionListResponse(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    items: List[ReplenishmentSuggestion]
    page: int
    page_size: int
    total: int
    total_pages: int
    has_next: bool
    has_prev: bool


class SalesForecastListResponse(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    items: List[SalesForecast]
    page: int
    page_size: int
    total: int
    total_pages: int
    has_next: bool
    has_prev: bool


ReplenishmentSuggestionDetail.model_rebuild()
