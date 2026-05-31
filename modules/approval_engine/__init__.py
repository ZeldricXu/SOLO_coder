from .models import (
    ApprovalRule,
    ApprovalProcess,
    ApprovalRecord,
    ApprovalType,
    ApprovalStatus,
    ApprovalAction,
    RuleConditionOperator,
    RuleCombinationOperator,
    DynamicApproverType,
    ApprovalRuleCreate,
    ApprovalRuleResponse,
    ApprovalProcessCreate,
    ApprovalProcessResponse,
    ApprovalActionRequest,
    ApprovalRecordResponse,
    ConditionEvaluationResult,
)
from .service import (
    RuleConditionEvaluator,
    DynamicApproverResolver,
    ApprovalRuleService,
    ApprovalProcessService,
)
from .router import router

__all__ = [
    "ApprovalRule",
    "ApprovalProcess",
    "ApprovalRecord",
    "ApprovalType",
    "ApprovalStatus",
    "ApprovalAction",
    "RuleConditionOperator",
    "RuleCombinationOperator",
    "DynamicApproverType",
    "ApprovalRuleCreate",
    "ApprovalRuleResponse",
    "ApprovalProcessCreate",
    "ApprovalProcessResponse",
    "ApprovalActionRequest",
    "ApprovalRecordResponse",
    "ConditionEvaluationResult",
    "RuleConditionEvaluator",
    "DynamicApproverResolver",
    "ApprovalRuleService",
    "ApprovalProcessService",
    "router",
]


class ApprovalEngineModule:
    name = "approval_engine"
    description = "条件分支审批、会签/或签策略、动态审批人解析模块"
    router = router

    def __init__(self):
        pass
