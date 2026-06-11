from __future__ import annotations
from typing import Optional, List, Dict, Any, Tuple, Union
from sqlalchemy.orm import Session

from app.core.logging import get_logger
from app.core.audit import AuditLogger
from app.models.approval_workflow import (
    ApprovalWorkflow,
    ApprovalNode,
    NodeType,
    ApprovalType,
    ResourceType,
)
from app.models.approval_condition import (
    ApprovalCondition,
    ConditionType,
    ConditionOperator,
)
from app.models.role import Role
from app.utils.constants import (
    AUTO_APPROVE_MAX_AMOUNT,
    CO_SIGN_MIN_AMOUNT,
)
from app.schemas.approval import (
    DefaultRulesInitRequest,
    AutoApproveRuleCreate,
    CoSignRuleCreate,
)

logger = get_logger(__name__)


class ApprovalRuleService:
    def __init__(self, db: Session, current_user: Optional[Any] = None):
        self.db = db
        self.current_user = current_user
        self.audit_logger = AuditLogger(db)

    def create_default_purchase_order_rules(
        self,
        request: Optional[DefaultRulesInitRequest] = None,
    ) -> Dict[str, Any]:
        results: Dict[str, Any] = {
            "auto_approve_workflow": None,
            "co_sign_workflow": None,
            "standard_workflow": None,
        }

        auto_approve_config = None
        co_sign_config = None

        if request:
            auto_approve_config = request.auto_approve_config
            co_sign_config = request.co_sign_config

        if auto_approve_config is None:
            auto_approve_config = AutoApproveRuleCreate()

        if co_sign_config is None:
            co_sign_config = CoSignRuleCreate(
                first_role_id=self._get_role_id_by_name("finance"),
                second_role_id=self._get_role_id_by_name("department_head"),
            )

        finance_role_id = co_sign_config.first_role_id
        dept_head_role_id = co_sign_config.second_role_id

        if not finance_role_id or not dept_head_role_id:
            raise ValueError("Could not find required roles (finance, department_head)")

        results["auto_approve_workflow"] = self._create_auto_approve_workflow(
            auto_approve_config, dept_head_role_id
        )
        results["co_sign_workflow"] = self._create_co_sign_workflow(
            co_sign_config, finance_role_id, dept_head_role_id
        )
        results["standard_workflow"] = self._create_standard_workflow(dept_head_role_id)

        self.db.commit()

        return results

    def _get_role_id_by_name(self, role_name: str) -> Optional[int]:
        role = self.db.query(Role).filter(Role.name.ilike(f"%{role_name}%")).first()
        return role.id if role else None

    def _create_auto_approve_workflow(
        self,
        config: AutoApproveRuleCreate,
        dept_head_role_id: int,
    ) -> ApprovalWorkflow:
        existing = (
            self.db.query(ApprovalWorkflow)
            .filter(
                ApprovalWorkflow.code == "PO_AUTO_APPROVE",
                ApprovalWorkflow.resource_type == ResourceType.PURCHASE_ORDER,
            )
            .first()
        )
        if existing:
            logger.info(f"Workflow PO_AUTO_APPROVE already exists, skipping")
            return existing

        workflow = ApprovalWorkflow(
            name=config.rule_name,
            code="PO_AUTO_APPROVE",
            resource_type=ResourceType.PURCHASE_ORDER,
            is_active=True,
            priority=100,
            conditions={
                "max_amount": config.max_amount,
            },
        )
        self.db.add(workflow)
        self.db.flush()

        start_node = ApprovalNode(
            workflow_id=workflow.id,
            node_name="开始",
            node_type=NodeType.START,
            sort_order=0,
        )
        self.db.add(start_node)

        approve_node = ApprovalNode(
            workflow_id=workflow.id,
            node_name="自动批准",
            node_type=NodeType.APPROVAL,
            approval_type=ApprovalType.AND,
            required_role_id=dept_head_role_id,
            sort_order=1,
            is_auto_approve=config.auto_approve,
            auto_approve_condition={
                "max_amount": config.max_amount,
            },
        )
        self.db.add(approve_node)

        end_node = ApprovalNode(
            workflow_id=workflow.id,
            node_name="结束",
            node_type=NodeType.END,
            sort_order=2,
        )
        self.db.add(end_node)

        logger.info(f"Created auto-approve workflow: {workflow.name}")
        return workflow

    def _create_co_sign_workflow(
        self,
        config: CoSignRuleCreate,
        finance_role_id: int,
        dept_head_role_id: int,
    ) -> ApprovalWorkflow:
        existing = (
            self.db.query(ApprovalWorkflow)
            .filter(
                ApprovalWorkflow.code == "PO_CO_SIGN",
                ApprovalWorkflow.resource_type == ResourceType.PURCHASE_ORDER,
            )
            .first()
        )
        if existing:
            logger.info(f"Workflow PO_CO_SIGN already exists, skipping")
            return existing

        workflow = ApprovalWorkflow(
            name=config.rule_name,
            code="PO_CO_SIGN",
            resource_type=ResourceType.PURCHASE_ORDER,
            is_active=True,
            priority=50,
            conditions={
                "min_amount": config.min_amount,
            },
        )
        self.db.add(workflow)
        self.db.flush()

        start_node = ApprovalNode(
            workflow_id=workflow.id,
            node_name="开始",
            node_type=NodeType.START,
            sort_order=0,
        )
        self.db.add(start_node)

        finance_node = ApprovalNode(
            workflow_id=workflow.id,
            node_name="财务审批",
            node_type=NodeType.APPROVAL,
            approval_type=config.approval_type,
            required_role_id=finance_role_id,
            sort_order=1,
        )
        self.db.add(finance_node)

        dept_head_node = ApprovalNode(
            workflow_id=workflow.id,
            node_name="部门负责人审批",
            node_type=NodeType.APPROVAL,
            approval_type=config.approval_type,
            required_role_id=dept_head_role_id,
            sort_order=2,
        )
        self.db.add(dept_head_node)

        end_node = ApprovalNode(
            workflow_id=workflow.id,
            node_name="结束",
            node_type=NodeType.END,
            sort_order=3,
        )
        self.db.add(end_node)

        logger.info(f"Created co-sign workflow: {workflow.name}")
        return workflow

    def _create_standard_workflow(
        self,
        dept_head_role_id: int,
    ) -> ApprovalWorkflow:
        existing = (
            self.db.query(ApprovalWorkflow)
            .filter(
                ApprovalWorkflow.code == "PO_STANDARD",
                ApprovalWorkflow.resource_type == ResourceType.PURCHASE_ORDER,
            )
            .first()
        )
        if existing:
            logger.info(f"Workflow PO_STANDARD already exists, skipping")
            return existing

        workflow = ApprovalWorkflow(
            name="标准采购审批",
            code="PO_STANDARD",
            resource_type=ResourceType.PURCHASE_ORDER,
            is_active=True,
            priority=0,
            conditions=None,
        )
        self.db.add(workflow)
        self.db.flush()

        start_node = ApprovalNode(
            workflow_id=workflow.id,
            node_name="开始",
            node_type=NodeType.START,
            sort_order=0,
        )
        self.db.add(start_node)

        approve_node = ApprovalNode(
            workflow_id=workflow.id,
            node_name="部门负责人审批",
            node_type=NodeType.APPROVAL,
            approval_type=ApprovalType.AND,
            required_role_id=dept_head_role_id,
            sort_order=1,
        )
        self.db.add(approve_node)

        end_node = ApprovalNode(
            workflow_id=workflow.id,
            node_name="结束",
            node_type=NodeType.END,
            sort_order=2,
        )
        self.db.add(end_node)

        logger.info(f"Created standard workflow: {workflow.name}")
        return workflow

    def evaluate_condition(
        self,
        condition: ApprovalCondition,
        resource_data: Dict[str, Any],
    ) -> bool:
        field_value = resource_data.get(condition.field_name)
        condition_value = condition.value
        operator = condition.operator

        if field_value is None:
            return False

        try:
            if operator == ConditionOperator.EQ:
                return field_value == condition_value
            elif operator == ConditionOperator.GT:
                return field_value > condition_value
            elif operator == ConditionOperator.LT:
                return field_value < condition_value
            elif operator == ConditionOperator.GTE:
                return field_value >= condition_value
            elif operator == ConditionOperator.LTE:
                return field_value <= condition_value
            elif operator == ConditionOperator.IN:
                if isinstance(condition_value, list):
                    return field_value in condition_value
                return False
            elif operator == ConditionOperator.NOT_IN:
                if isinstance(condition_value, list):
                    return field_value not in condition_value
                return False
            elif operator == ConditionOperator.CONTAINS:
                if isinstance(field_value, str) and isinstance(condition_value, str):
                    return condition_value in field_value
                if isinstance(field_value, list):
                    return condition_value in field_value
                return False
        except Exception as e:
            logger.error(f"Error evaluating condition {condition.id}: {e}")
            return False

        return False

    def evaluate_condition_group(
        self,
        conditions: List[ApprovalCondition],
        resource_data: Dict[str, Any],
        logic: str = "AND",
    ) -> Tuple[bool, List[ApprovalCondition]]:
        if not conditions:
            return True, []

        matched_conditions: List[ApprovalCondition] = []
        results: List[bool] = []

        for condition in conditions:
            result = self.evaluate_condition(condition, resource_data)
            results.append(result)
            if result:
                matched_conditions.append(condition)

        if logic == "OR":
            return any(results), matched_conditions
        return all(results), matched_conditions

    def evaluate_conditions_dict(
        self,
        conditions: Dict[str, Any],
        resource_data: Dict[str, Any],
        logic: str = "AND",
    ) -> bool:
        if not conditions:
            return True

        results: List[bool] = []

        min_amount = conditions.get("min_amount")
        max_amount = conditions.get("max_amount")
        amount = resource_data.get("amount", 0)

        if min_amount is not None:
            results.append(amount >= min_amount)
        if max_amount is not None:
            results.append(amount < max_amount)

        categories = conditions.get("categories")
        if categories is not None:
            resource_categories = resource_data.get("categories", [])
            if isinstance(resource_categories, list):
                results.append(any(cat in categories for cat in resource_categories))
            else:
                results.append(resource_categories in categories)

        warehouse_regions = conditions.get("warehouse_regions")
        if warehouse_regions is not None:
            resource_region = resource_data.get("warehouse_region")
            results.append(resource_region in warehouse_regions)

        departments = conditions.get("departments")
        if departments is not None:
            resource_department = resource_data.get("department")
            results.append(resource_department in departments)

        roles = conditions.get("roles")
        if roles is not None:
            resource_role = resource_data.get("role_id")
            results.append(resource_role in roles)

        if logic == "OR":
            return any(results) if results else True
        return all(results) if results else True


def create_approval_rule_service(
    db: Session,
    current_user: Optional[Any] = None,
) -> ApprovalRuleService:
    return ApprovalRuleService(db, current_user)
