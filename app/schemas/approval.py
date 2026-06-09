from datetime import datetime
from enum import Enum as PyEnum
from typing import Optional, Any, List, Dict
from pydantic import BaseModel, Field, ConfigDict

from app.models.approval_workflow import (
    ResourceType,
    NodeType,
    ApprovalType,
    ApprovalStatus,
)
from app.schemas.common import APIResponse, PaginatedResponse


class ResourceTypeEnum(str, PyEnum):
    PURCHASE_ORDER = "PURCHASE_ORDER"
    STOCKTAKE = "STOCKTAKE"
    ADJUSTMENT = "ADJUSTMENT"


class NodeTypeEnum(str, PyEnum):
    START = "START"
    APPROVAL = "APPROVAL"
    END = "END"


class ApprovalTypeEnum(str, PyEnum):
    AND = "AND"
    OR = "OR"
    PERCENTAGE = "PERCENTAGE"


class ApprovalStatusEnum(str, PyEnum):
    PENDING = "PENDING"
    APPROVED = "APPROVED"
    REJECTED = "REJECTED"


class ApprovalActionEnum(str, PyEnum):
    APPROVE = "APPROVE"
    REJECT = "REJECT"
    WITHDRAW = "WITHDRAW"


class ApprovalTimeoutActionEnum(str, PyEnum):
    AUTO_APPROVE = "AUTO_APPROVE"
    AUTO_REJECT = "AUTO_REJECT"
    ESCALATE = "ESCALATE"
    NOTIFY_ONLY = "NOTIFY_ONLY"


class NotificationTypeEnum(str, PyEnum):
    EMAIL = "EMAIL"
    IN_APP = "IN_APP"
    SMS = "SMS"
    ALL = "ALL"


class ApprovalNodeBase(BaseModel):
    node_name: str = Field(max_length=200, description="节点名称")
    node_type: NodeTypeEnum = Field(description="节点类型")
    approval_type: Optional[ApprovalTypeEnum] = Field(default=None, description="审批类型")
    pass_percentage: Optional[float] = Field(default=None, ge=0, le=100, description="通过百分比")
    required_role_id: Optional[int] = Field(default=None, description="所需角色ID")
    required_user_id: Optional[int] = Field(default=None, description="指定审批人ID")
    sort_order: int = Field(default=0, description="排序")
    timeout_hours: Optional[int] = Field(default=None, ge=1, description="超时时间（小时）")
    timeout_action: Optional[ApprovalTimeoutActionEnum] = Field(
        default=None, description="超时处理方式"
    )
    notify_types: Optional[List[NotificationTypeEnum]] = Field(
        default=None, description="通知方式"
    )
    conditions: Optional[Dict[str, Any]] = Field(
        default=None, description="节点条件配置（金额范围、部门等）"
    )


class ApprovalNodeCreate(ApprovalNodeBase):
    pass


class ApprovalNodeUpdate(BaseModel):
    node_name: Optional[str] = Field(default=None, max_length=200, description="节点名称")
    node_type: Optional[NodeTypeEnum] = Field(default=None, description="节点类型")
    approval_type: Optional[ApprovalTypeEnum] = Field(default=None, description="审批类型")
    pass_percentage: Optional[float] = Field(default=None, ge=0, le=100, description="通过百分比")
    required_role_id: Optional[int] = Field(default=None, description="所需角色ID")
    required_user_id: Optional[int] = Field(default=None, description="指定审批人ID")
    sort_order: Optional[int] = Field(default=None, description="排序")
    timeout_hours: Optional[int] = Field(default=None, ge=1, description="超时时间（小时）")
    timeout_action: Optional[ApprovalTimeoutActionEnum] = Field(
        default=None, description="超时处理方式"
    )
    notify_types: Optional[List[NotificationTypeEnum]] = Field(
        default=None, description="通知方式"
    )
    conditions: Optional[Dict[str, Any]] = Field(
        default=None, description="节点条件配置"
    )


class ApprovalNode(ApprovalNodeBase):
    model_config = ConfigDict(from_attributes=True)

    id: int
    workflow_id: int
    created_at: datetime

    required_role_name: Optional[str] = None
    required_user_name: Optional[str] = None
    approvers: List[Dict[str, Any]] = Field(default_factory=list)
    pending_count: int = Field(default=0, description="待审批数量")
    approved_count: int = Field(default=0, description="已通过数量")
    rejected_count: int = Field(default=0, description="已驳回数量")


class ApprovalWorkflowBase(BaseModel):
    name: str = Field(max_length=200, description="工作流名称")
    code: str = Field(max_length=100, description="工作流编码")
    resource_type: ResourceTypeEnum = Field(description="适用资源类型")
    is_active: bool = Field(default=True, description="是否启用")
    description: Optional[str] = Field(default=None, max_length=500, description="描述")
    default_notify_types: Optional[List[NotificationTypeEnum]] = Field(
        default=None, description="默认通知方式"
    )
    conditions: Optional[Dict[str, Any]] = Field(
        default=None, description="工作流触发条件（金额范围等）"
    )


class ApprovalWorkflowCreate(ApprovalWorkflowBase):
    nodes: List[ApprovalNodeCreate] = Field(description="审批节点列表")


class ApprovalWorkflowUpdate(BaseModel):
    name: Optional[str] = Field(default=None, max_length=200, description="工作流名称")
    is_active: Optional[bool] = Field(default=None, description="是否启用")
    description: Optional[str] = Field(default=None, max_length=500, description="描述")
    default_notify_types: Optional[List[NotificationTypeEnum]] = Field(
        default=None, description="默认通知方式"
    )
    conditions: Optional[Dict[str, Any]] = Field(
        default=None, description="工作流触发条件"
    )


class ApprovalWorkflow(ApprovalWorkflowBase):
    model_config = ConfigDict(from_attributes=True)

    id: int
    created_at: datetime
    updated_at: datetime

    nodes: List[ApprovalNode] = Field(default_factory=list)
    usage_count: int = Field(default=0, description="使用次数")
    avg_approval_hours: Optional[float] = Field(default=None, description="平均审批时长")


class ApprovalWorkflowDetail(ApprovalWorkflow):
    model_config = ConfigDict(from_attributes=True)

    recent_records: List[Dict[str, Any]] = Field(default_factory=list)
    performance_metrics: dict = Field(default_factory=dict)


class ApprovalRecordBase(BaseModel):
    workflow_id: int = Field(description="工作流ID")
    node_id: int = Field(description="节点ID")
    resource_id: int = Field(description="资源ID")
    resource_type: ResourceTypeEnum = Field(description="资源类型")
    approver_id: int = Field(description="审批人ID")


class ApprovalRecordCreate(ApprovalRecordBase):
    pass


class ApprovalActionRequest(BaseModel):
    action: ApprovalActionEnum = Field(description="审批动作")
    approval_opinion: Optional[str] = Field(
        default=None, max_length=1000, description="审批意见"
    )
    notify_submitter: bool = Field(default=True, description="是否通知提交人")
    cc_users: Optional[List[int]] = Field(default=None, description="抄送用户ID列表")


class ApprovalRecord(ApprovalRecordBase):
    model_config = ConfigDict(from_attributes=True)

    id: int
    status: ApprovalStatusEnum
    approval_opinion: Optional[str] = None
    approved_at: Optional[datetime] = None
    created_at: datetime

    workflow_name: Optional[str] = None
    node_name: Optional[str] = None
    resource_title: Optional[str] = None
    approver_name: Optional[str] = None
    approver_avatar: Optional[str] = None
    submitter_name: Optional[str] = None
    resource_url: Optional[str] = None
    is_read: bool = Field(default=False, description="是否已读")
    is_overdue: bool = Field(default=False, description="是否逾期")
    remaining_hours: Optional[float] = Field(default=None, description="剩余时间（小时）")


class ApprovalRecordDetail(ApprovalRecord):
    model_config = ConfigDict(from_attributes=True)

    workflow: Optional[ApprovalWorkflow] = None
    node: Optional[ApprovalNode] = None
    approval_history: List[Dict[str, Any]] = Field(default_factory=list)
    next_approvers: List[Dict[str, Any]] = Field(default_factory=list)
    can_withdraw: bool = Field(default=False, description="是否可撤回")
    can_approve: bool = Field(default=False, description="是否可审批")


class ApprovalSubmissionRequest(BaseModel):
    resource_id: int = Field(description="资源ID")
    resource_type: ResourceTypeEnum = Field(description="资源类型")
    workflow_id: Optional[int] = Field(default=None, description="指定工作流ID，为空则自动匹配")
    submitter_remark: Optional[str] = Field(
        default=None, max_length=1000, description="提交备注"
    )
    notify_types: Optional[List[NotificationTypeEnum]] = Field(
        default=None, description="通知方式"
    )


class ApprovalSubmissionResponse(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    success: bool
    workflow_id: int
    workflow_name: str
    current_node_id: int
    current_node_name: str
    records: List[ApprovalRecord] = Field(default_factory=list)
    next_approvers: List[Dict[str, Any]] = Field(default_factory=list)
    estimated_completion_hours: Optional[float] = None


class ApprovalStatisticsResponse(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    total_pending: int = Field(default=0, description="待我审批总数")
    today_pending: int = Field(default=0, description="今日待审批")
    overdue_count: int = Field(default=0, description="已逾期数量")
    approved_today: int = Field(default=0, description="今日已通过")
    rejected_today: int = Field(default=0, description="今日已驳回")
    submitted_by_me: int = Field(default=0, description="我提交的")
    my_pending_approval: int = Field(default=0, description="我待审批的")
    avg_processing_hours: Optional[float] = Field(default=None, description="我的平均处理时长")
    by_node_type: Dict[str, int] = Field(default_factory=dict, description="按节点类型统计")
    by_resource_type: Dict[str, int] = Field(default_factory=dict, description="按资源类型统计")


class ApprovalWorkflowListFilter(BaseModel):
    resource_type: Optional[ResourceTypeEnum] = Field(default=None, description="资源类型")
    is_active: Optional[bool] = Field(default=None, description="是否启用")
    keyword: Optional[str] = Field(default=None, description="关键词搜索")


class ApprovalRecordListFilter(BaseModel):
    status: Optional[List[ApprovalStatusEnum]] = Field(default=None, description="审批状态")
    resource_type: Optional[ResourceTypeEnum] = Field(default=None, description="资源类型")
    approver_id: Optional[int] = Field(default=None, description="审批人ID")
    submitter_id: Optional[int] = Field(default=None, description="提交人ID")
    start_date: Optional[datetime] = Field(default=None, description="开始日期")
    end_date: Optional[datetime] = Field(default=None, description="结束日期")
    workflow_id: Optional[int] = Field(default=None, description="工作流ID")
    is_overdue: Optional[bool] = Field(default=None, description="是否逾期")


class ApprovalWorkflowListResponse(APIResponse[PaginatedResponse[ApprovalWorkflow]]):
    pass


class ApprovalWorkflowDetailResponse(APIResponse[ApprovalWorkflowDetail]):
    pass


class ApprovalRecordListResponse(APIResponse[PaginatedResponse[ApprovalRecord]]):
    pass


class ApprovalRecordDetailResponse(APIResponse[ApprovalRecordDetail]):
    pass


class ApprovalSubmissionResultResponse(APIResponse[ApprovalSubmissionResponse]):
    pass


class ApprovalStatisticsResultResponse(APIResponse[ApprovalStatisticsResponse]):
    pass


class ApprovalActionResponse(APIResponse[dict]):
    pass


class ApprovalNodeResponse(APIResponse[ApprovalNode]):
    pass
