from datetime import datetime
from typing import Optional
from pydantic import BaseModel, Field, ConfigDict

from app.models.stocktake import (
    StocktakePlanType,
    StocktakePlanStatus,
    StocktakeTaskStatus,
    StocktakeResultStatus,
    AdjustmentType,
    AdjustmentStatus,
)


class StocktakePlanBase(BaseModel):
    plan_no: Optional[str] = Field(None, max_length=50, description="盘点计划编号")
    warehouse_id: int = Field(..., description="仓库ID")
    plan_type: StocktakePlanType = Field(..., description="盘点计划类型")
    scheduled_date: datetime = Field(..., description="计划盘点日期")
    description: Optional[str] = Field(None, description="描述")


class StocktakePlanCreate(StocktakePlanBase):
    sku_ids: Optional[list[int]] = Field(None, description="指定盘点的SKU ID列表")
    zone_ids: Optional[list[int]] = Field(None, description="指定盘点的库区ID列表")
    category_ids: Optional[list[int]] = Field(None, description="指定盘点的商品分类ID列表")


class StocktakePlanUpdate(BaseModel):
    warehouse_id: Optional[int] = Field(None, description="仓库ID")
    plan_type: Optional[StocktakePlanType] = Field(None, description="盘点计划类型")
    scheduled_date: Optional[datetime] = Field(None, description="计划盘点日期")
    description: Optional[str] = Field(None, description="描述")
    status: Optional[StocktakePlanStatus] = Field(None, description="状态")


class StocktakePlan(StocktakePlanBase):
    model_config = ConfigDict(from_attributes=True)

    id: int
    status: StocktakePlanStatus = Field(description="状态")
    actual_start_date: Optional[datetime] = Field(None, description="实际开始日期")
    actual_end_date: Optional[datetime] = Field(None, description="实际结束日期")
    created_by: int = Field(description="创建人")
    created_at: datetime
    updated_at: datetime
    warehouse_name: Optional[str] = Field(None, description="仓库名称")
    task_count: Optional[int] = Field(0, description="任务数量")
    completed_task_count: Optional[int] = Field(0, description="已完成任务数量")
    sku_count: Optional[int] = Field(0, description="盘点SKU数量")


class StocktakeTaskBase(BaseModel):
    plan_id: int = Field(..., description="盘点计划ID")
    sku_ids: list[int] = Field(..., description="SKU ID列表")
    zone_ids: list[int] = Field(..., description="库区ID列表")
    assignee_id: Optional[int] = Field(None, description="负责人ID")
    remark: Optional[str] = Field(None, description="备注")


class StocktakeTaskCreate(StocktakeTaskBase):
    pass


class StocktakeTask(StocktakeTaskBase):
    model_config = ConfigDict(from_attributes=True)

    id: int
    status: StocktakeTaskStatus = Field(description="状态")
    assigned_at: Optional[datetime] = Field(None, description="分配时间")
    started_at: Optional[datetime] = Field(None, description="开始时间")
    completed_at: Optional[datetime] = Field(None, description="完成时间")
    created_at: datetime
    assignee_name: Optional[str] = Field(None, description="负责人名称")
    result_count: Optional[int] = Field(0, description="盘点结果数量")
    difference_count: Optional[int] = Field(0, description="差异数量")


class CountResultRequest(BaseModel):
    sku_id: int = Field(..., description="SKU ID")
    batch_id: Optional[str] = Field(None, description="批次号")
    counted_quantity: int = Field(..., ge=0, description="盘点数量")
    serial_numbers: Optional[list[str]] = Field(None, description="序列号列表")
    remark: Optional[str] = Field(None, description="备注")


class StocktakeResultBase(BaseModel):
    task_id: int = Field(..., description="盘点任务ID")
    sku_id: int = Field(..., description="SKU ID")
    batch_id: Optional[str] = Field(None, description="批次号")
    expected_quantity: int = Field(default=0, description="系统库存数量")
    counted_quantity: int = Field(default=0, description="实际盘点数量")
    variance_reason: Optional[str] = Field(None, description="差异原因")


class StocktakeResultCreate(StocktakeResultBase):
    pass


class StocktakeResult(StocktakeResultBase):
    model_config = ConfigDict(from_attributes=True)

    id: int
    difference_quantity: int = Field(description="差异数量")
    status: StocktakeResultStatus = Field(description="状态")
    created_at: datetime
    sku_code: Optional[str] = Field(None, description="SKU编码")
    sku_name: Optional[str] = Field(None, description="SKU名称")
    unit_cost: Optional[float] = Field(None, description="单位成本")
    difference_value: Optional[float] = Field(None, description="差异金额")
    has_adjustment: Optional[bool] = Field(False, description="是否已生成调整单")


class StocktakeAdjustmentBase(BaseModel):
    result_id: int = Field(..., description="盘点结果ID")
    adjustment_type: AdjustmentType = Field(..., description="调整类型")
    quantity: int = Field(..., description="调整数量")
    unit_cost: float = Field(..., ge=0, description="单位成本")


class StocktakeAdjustment(StocktakeAdjustmentBase):
    model_config = ConfigDict(from_attributes=True)

    id: int
    total_cost: float = Field(description="调整总金额")
    status: AdjustmentStatus = Field(description="状态")
    approved_by: Optional[int] = Field(None, description="审批人ID")
    approved_at: Optional[datetime] = Field(None, description="审批时间")
    created_by: int = Field(description="创建人")
    created_at: datetime
    sku_code: Optional[str] = Field(None, description="SKU编码")
    sku_name: Optional[str] = Field(None, description="SKU名称")
    approved_by_name: Optional[str] = Field(None, description="审批人名称")
    created_by_name: Optional[str] = Field(None, description="创建人名称")


class AdjustmentReviewRequest(BaseModel):
    status: AdjustmentStatus = Field(..., description="审批状态")
    remark: Optional[str] = Field(None, description="审批备注")


class StocktakeGenerateRequest(BaseModel):
    warehouse_id: int = Field(..., description="仓库ID")
    plan_type: StocktakePlanType = Field(..., description="盘点计划类型")
    generate_strategy: str = Field(..., description="生成策略: ABC/CYCLE/RANDOM")
    cycle_days: Optional[int] = Field(None, description="循环盘点周期(天)")
    abc_category: Optional[list[str]] = Field(None, description="ABC分类: A/B/C")
    random_count: Optional[int] = Field(None, description="随机抽盘数量")
    zone_ids: Optional[list[int]] = Field(None, description="指定库区")
    category_ids: Optional[list[int]] = Field(None, description="指定商品分类")
    scheduled_date: Optional[datetime] = Field(None, description="计划日期")
    description: Optional[str] = Field(None, description="描述")


class StocktakeGenerateResponse(BaseModel):
    plan_id: int = Field(description="盘点计划ID")
    plan_no: str = Field(description="盘点计划编号")
    task_count: int = Field(description="生成任务数量")
    sku_count: int = Field(description="盘点SKU数量")
    message: str = Field(description="消息")


class StocktakeDifferenceResponse(BaseModel):
    result_id: int = Field(description="盘点结果ID")
    sku_id: int = Field(description="SKU ID")
    sku_code: str = Field(description="SKU编码")
    sku_name: str = Field(description="SKU名称")
    batch_id: Optional[str] = Field(None, description="批次号")
    expected_quantity: int = Field(description="系统库存")
    counted_quantity: int = Field(description="实际盘点")
    difference_quantity: int = Field(description="差异数量")
    difference_type: str = Field(description="差异类型: 盘盈/盘亏")
    unit_cost: float = Field(description="单位成本")
    difference_value: float = Field(description="差异金额")
    variance_reason: Optional[str] = Field(None, description="差异原因")
    status: StocktakeResultStatus = Field(description="状态")
    has_adjustment: bool = Field(description="是否已调整")


class StocktakePlanListFilter(BaseModel):
    warehouse_id: Optional[int] = Field(None, description="仓库ID")
    plan_type: Optional[StocktakePlanType] = Field(None, description="计划类型")
    status: Optional[StocktakePlanStatus] = Field(None, description="状态")
    created_by: Optional[int] = Field(None, description="创建人")
    start_date: Optional[datetime] = Field(None, description="开始日期")
    end_date: Optional[datetime] = Field(None, description="结束日期")
    plan_no: Optional[str] = Field(None, description="计划编号(模糊查询)")


class StocktakeTaskListFilter(BaseModel):
    plan_id: Optional[int] = Field(None, description="盘点计划ID")
    assignee_id: Optional[int] = Field(None, description="负责人ID")
    status: Optional[StocktakeTaskStatus] = Field(None, description="状态")
    warehouse_id: Optional[int] = Field(None, description="仓库ID")


class StocktakeResultListFilter(BaseModel):
    plan_id: Optional[int] = Field(None, description="盘点计划ID")
    task_id: Optional[int] = Field(None, description="盘点任务ID")
    sku_id: Optional[int] = Field(None, description="SKU ID")
    status: Optional[StocktakeResultStatus] = Field(None, description="状态")
    has_difference: Optional[bool] = Field(None, description="是否有差异")


class StocktakePlanStatisticsResponse(BaseModel):
    total_plans: int = Field(description="盘点计划总数")
    planned_count: int = Field(description="待开始数量")
    in_progress_count: int = Field(description="进行中数量")
    completed_count: int = Field(description="已完成数量")
    cancelled_count: int = Field(description="已取消数量")
    total_sku_count: int = Field(description="总盘点SKU数")
    total_difference_count: int = Field(description="总差异数")
    total_difference_value: float = Field(description="总差异金额")
    accuracy_rate: float = Field(description="盘点准确率")


class ABCCategoryAnalysis(BaseModel):
    category: str = Field(description="ABC分类")
    sku_count: int = Field(description="SKU数量")
    sku_percentage: float = Field(description="SKU占比")
    total_value: float = Field(description="总价值")
    value_percentage: float = Field(description="价值占比")
    stocktake_frequency: int = Field(description="建议盘点频率(次/年)")


class StocktakeSyncRequest(BaseModel):
    last_sync_at: Optional[datetime] = Field(None, description="上次同步时间")
    task_ids: Optional[list[int]] = Field(None, description="任务ID列表")
    include_results: bool = Field(default=True, description="是否包含盘点结果")


class StocktakeSyncResponse(BaseModel):
    tasks: list[StocktakeTask] = Field(description="任务列表")
    results: list[StocktakeResult] = Field(default_factory=list, description="结果列表")
    sync_timestamp: datetime = Field(description="同步时间戳")
    has_more: bool = Field(default=False, description="是否还有更多数据")
