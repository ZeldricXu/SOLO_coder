from __future__ import annotations
from datetime import datetime, timedelta
from typing import Optional, List, Dict, Any, Tuple, Union
from sqlalchemy.orm import Session, selectinload
from sqlalchemy import and_, or_, func, desc

from app.core.cache import cache
from app.core.logging import get_logger
from app.core.audit import AuditLogger
from app.models.approval_workflow import (
    ApprovalWorkflow,
    ApprovalNode,
    ApprovalRecord,
    ResourceType,
    NodeType,
    ApprovalType,
    ApprovalStatus,
)
from app.models.approval_condition import (
    ApprovalCondition,
    ConditionType,
    ConditionOperator,
)
from app.models.user import User
from app.models.role import Role
from app.models.purchase_order import PurchaseOrder
from app.utils.constants import (
    AUTO_APPROVE_MAX_AMOUNT,
    CO_SIGN_MIN_AMOUNT,
)
from app.schemas.approval import (
    ApprovalWorkflowCreate,
    ApprovalWorkflowUpdate,
    ApprovalNodeCreate,
    ApprovalNodeUpdate,
    ApprovalActionRequest,
    ApprovalSubmissionRequest,
    ApprovalActionEnum,
    ApprovalRecordListFilter,
    ApprovalConditionCreate,
    ApprovalConditionUpdate,
)

logger = get_logger(__name__)


class ApprovalService:
    def __init__(self, db: Session, current_user: Optional[User] = None):
        self.db = db
        self.current_user = current_user
        self.audit_logger = AuditLogger(db)

    def _get_resource_callback(self, resource_type: ResourceType):
        callbacks = {
            ResourceType.PURCHASE_ORDER: self._update_purchase_order_status,
        }
        return callbacks.get(resource_type)

    def _update_purchase_order_status(
        self,
        resource_id: int,
        approval_status: ApprovalStatus,
        approved_by: Optional[int] = None,
    ) -> None:
        try:
            from app.services.purchase_order_service import PurchaseOrderService
            po_service = PurchaseOrderService(self.db)
            po_service.process_approval_callback(resource_id, approval_status, approved_by)
        except Exception as e:
            logger.error(f"Failed to update purchase order status: {e}")

    def _send_notification(
        self,
        user_ids: List[int],
        notification_type: str,
        title: str,
        content: str,
        data: Optional[Dict[str, Any]] = None,
    ) -> None:
        if not user_ids:
            return

        notification_data = {
            "user_ids": user_ids,
            "type": notification_type,
            "title": title,
            "content": content,
            "data": data or {},
            "created_at": datetime.utcnow().isoformat(),
        }

        cache.rpush("notifications:queue", notification_data)
        logger.info(f"Notification queued for users {user_ids}: {title}")

    def get_workflow(
        self,
        workflow_id: int,
    ) -> Optional[ApprovalWorkflow]:
        cache_key = f"approval:workflow:{workflow_id}"
        cached = cache.get(cache_key)
        if cached:
            return cached

        workflow = (
            self.db.query(ApprovalWorkflow)
            .filter(ApprovalWorkflow.id == workflow_id)
            .first()
        )

        if workflow:
            cache.set(cache_key, workflow, ttl=600)
        return workflow

    def list_workflows(
        self,
        page: int = 1,
        page_size: int = 20,
        resource_type: Optional[ResourceType] = None,
        is_active: Optional[bool] = None,
        keyword: Optional[str] = None,
    ) -> Tuple[List[ApprovalWorkflow], int, int]:
        query = self.db.query(ApprovalWorkflow)

        if resource_type:
            query = query.filter(ApprovalWorkflow.resource_type == resource_type)
        if is_active is not None:
            query = query.filter(ApprovalWorkflow.is_active == is_active)
        if keyword:
            query = query.filter(
                or_(
                    ApprovalWorkflow.name.ilike(f"%{keyword}%"),
                    ApprovalWorkflow.code.ilike(f"%{keyword}%"),
                )
            )

        total = query.count()
        query = query.order_by(desc(ApprovalWorkflow.created_at))

        offset = (page - 1) * page_size
        workflows = query.offset(offset).limit(page_size).all()

        total_pages = (total + page_size - 1) // page_size

        return workflows, total, total_pages

    def create_workflow(
        self,
        workflow_data: ApprovalWorkflowCreate,
        created_by: User,
    ) -> ApprovalWorkflow:
        workflow_dict = workflow_data.model_dump()
        nodes_data = workflow_dict.pop("nodes")

        existing = (
            self.db.query(ApprovalWorkflow)
            .filter(ApprovalWorkflow.code == workflow_data.code)
            .first()
        )
        if existing:
            raise ValueError(f"Workflow code {workflow_data.code} already exists")

        workflow = ApprovalWorkflow(
            **workflow_dict,
            is_active=True,
        )

        self.db.add(workflow)
        self.db.flush()

        for idx, node_data in enumerate(nodes_data):
            node = ApprovalNode(
                workflow_id=workflow.id,
                sort_order=idx,
                **node_data.model_dump(),
            )
            self.db.add(node)

        self.db.flush()

        self.audit_logger.log_create(
            user=created_by,
            resource_type="approval_workflow",
            resource_id=workflow.id,
            new_value={
                "name": workflow.name,
                "code": workflow.code,
                "resource_type": workflow.resource_type.value,
                "node_count": len(nodes_data),
            },
        )

        cache.delete_pattern("approval:workflow:*")

        return workflow

    def update_workflow(
        self,
        workflow_id: int,
        update_data: ApprovalWorkflowUpdate,
        updated_by: User,
    ) -> Optional[ApprovalWorkflow]:
        workflow = self.get_workflow(workflow_id)
        if not workflow:
            return None

        old_value = {
            "name": workflow.name,
            "is_active": workflow.is_active,
        }

        update_dict = update_data.model_dump(exclude_unset=True)
        for key, value in update_dict.items():
            setattr(workflow, key, value)

        workflow.updated_at = datetime.utcnow()

        self.audit_logger.log_update(
            user=updated_by,
            resource_type="approval_workflow",
            resource_id=workflow.id,
            old_value=old_value,
            new_value={
                "name": workflow.name,
                "is_active": workflow.is_active,
            },
        )

        cache.delete(f"approval:workflow:{workflow_id}")
        cache.delete_pattern("approval:workflow:*")

        return workflow

    def add_node(
        self,
        workflow_id: int,
        node_data: ApprovalNodeCreate,
        added_by: User,
    ) -> ApprovalNode:
        workflow = self.get_workflow(workflow_id)
        if not workflow:
            raise ValueError("Workflow not found")

        if not workflow.is_active:
            raise ValueError("Cannot add node to inactive workflow")

        max_sort = (
            self.db.query(func.max(ApprovalNode.sort_order))
            .filter(ApprovalNode.workflow_id == workflow_id)
            .scalar()
            or 0
        )

        node = ApprovalNode(
            workflow_id=workflow_id,
            sort_order=max_sort + 1,
            **node_data.model_dump(),
        )

        self.db.add(node)
        self.db.flush()

        self.audit_logger.log_create(
            user=added_by,
            resource_type="approval_node",
            resource_id=node.id,
            new_value={
                "workflow_id": workflow_id,
                "node_name": node.node_name,
                "node_type": node.node_type.value,
                "approval_type": node.approval_type.value if node.approval_type else None,
            },
        )

        cache.delete(f"approval:workflow:{workflow_id}")

        return node

    def update_node(
        self,
        node_id: int,
        update_data: ApprovalNodeUpdate,
        updated_by: User,
    ) -> Optional[ApprovalNode]:
        node = (
            self.db.query(ApprovalNode)
            .filter(ApprovalNode.id == node_id)
            .first()
        )
        if not node:
            return None

        old_value = {
            "node_name": node.node_name,
            "approval_type": node.approval_type.value if node.approval_type else None,
        }

        update_dict = update_data.model_dump(exclude_unset=True)
        for key, value in update_dict.items():
            setattr(node, key, value)

        self.audit_logger.log_update(
            user=updated_by,
            resource_type="approval_node",
            resource_id=node.id,
            old_value=old_value,
            new_value={
                "node_name": node.node_name,
                "approval_type": node.approval_type.value if node.approval_type else None,
            },
        )

        cache.delete(f"approval:workflow:{node.workflow_id}")

        return node

    def delete_node(
        self,
        node_id: int,
        deleted_by: User,
    ) -> bool:
        node = (
            self.db.query(ApprovalNode)
            .filter(ApprovalNode.id == node_id)
            .first()
        )
        if not node:
            return False

        workflow_id = node.workflow_id

        pending_records = (
            self.db.query(ApprovalRecord)
            .filter(
                and_(
                    ApprovalRecord.node_id == node_id,
                    ApprovalRecord.status == ApprovalStatus.PENDING,
                )
            )
            .first()
        )
        if pending_records:
            raise ValueError("Cannot delete node with pending approvals")

        old_value = {
            "node_name": node.node_name,
            "workflow_id": workflow_id,
        }

        self.db.delete(node)

        self.audit_logger.log_delete(
            user=deleted_by,
            resource_type="approval_node",
            resource_id=node_id,
            old_value=old_value,
        )

        cache.delete(f"approval:workflow:{workflow_id}")

        return True

    def _get_node_approvers(
        self,
        node: ApprovalNode,
    ) -> List[int]:
        approver_ids: List[int] = []

        if node.required_user_id:
            approver_ids.append(node.required_user_id)
        elif node.required_role_id:
            role = (
                self.db.query(Role)
                .filter(Role.id == node.required_role_id)
                .first()
            )
            if role and role.users:
                approver_ids = [user.id for user in role.users]

        return approver_ids

    def _match_workflow(
        self,
        resource_type: ResourceType,
        resource_data: Optional[Dict[str, Any]] = None,
    ) -> Optional[ApprovalWorkflow]:
        query = (
            self.db.query(ApprovalWorkflow)
            .filter(
                and_(
                    ApprovalWorkflow.resource_type == resource_type,
                    ApprovalWorkflow.is_active,
                )
            )
            .order_by(ApprovalWorkflow.priority.desc(), ApprovalWorkflow.created_at.desc())
        )

        workflows = query.all()

        if not workflows:
            return None

        if len(workflows) == 1:
            return workflows[0]

        default_workflow = None
        for workflow in workflows:
            if not workflow.conditions:
                default_workflow = workflow
                continue

            if resource_data and self._evaluate_conditions(workflow.conditions, resource_data):
                logger.info(f"Matched workflow {workflow.name} with conditions {workflow.conditions}")
                return workflow

        return default_workflow or workflows[0]

    def _evaluate_conditions(
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

    def _process_auto_approve(
        self,
        node: ApprovalNode,
        resource_id: int,
        resource_type: ResourceType,
        resource_data: Dict[str, Any],
        workflow: ApprovalWorkflow,
    ) -> Tuple[bool, Optional[ApprovalNode]]:
        if not node.is_auto_approve:
            return False, None

        should_auto_approve = True
        matched_condition = None

        if node.auto_approve_condition:
            should_auto_approve = self._evaluate_conditions(node.auto_approve_condition, resource_data)
            matched_condition = node.auto_approve_condition if should_auto_approve else None
        else:
            amount = resource_data.get("amount", 0)
            should_auto_approve = amount <= AUTO_APPROVE_MAX_AMOUNT
            if should_auto_approve:
                matched_condition = {"auto_approve": True, "max_amount": AUTO_APPROVE_MAX_AMOUNT, "amount": amount}

        if not should_auto_approve:
            return False, None

        approver_ids = self._get_node_approvers(node)
        if not approver_ids:
            logger.warning(f"No approvers for auto-approve node {node.node_name}")
            return False, None

        auto_approver_id = approver_ids[0]

        record = ApprovalRecord(
            workflow_id=workflow.id,
            node_id=node.id,
            resource_id=resource_id,
            resource_type=resource_type,
            approver_id=auto_approver_id,
            status=ApprovalStatus.AUTO_APPROVED,
            approval_opinion="Auto-approved by system",
            approved_at=datetime.utcnow(),
            is_auto_approved=True,
            matched_condition=matched_condition,
        )
        self.db.add(record)
        self.db.flush()

        logger.info(f"Auto-approved node {node.node_name} for {resource_type} {resource_id}")

        return True, node

    def _get_resource_data(
        self,
        resource_type: ResourceType,
        resource_id: int,
    ) -> Dict[str, Any]:
        if resource_type == ResourceType.PURCHASE_ORDER:
            po = (
                self.db.query(PurchaseOrder)
                .filter(PurchaseOrder.id == resource_id)
                .first()
            )
            if po:
                return {
                    "id": po.id,
                    "title": f"Purchase Order {po.order_no}",
                    "amount": po.grand_total,
                    "created_by": po.created_by,
                    "url": f"/purchase-orders/{po.id}",
                }

        return {"id": resource_id, "title": f"{resource_type.value} #{resource_id}"}

    def submit_approval(
        self,
        submission_data: Union[ApprovalSubmissionRequest, Dict[str, Any]],
    ) -> Dict[str, Any]:
        if isinstance(submission_data, dict):
            submission_data = ApprovalSubmissionRequest(**submission_data)

        resource_id = submission_data.resource_id
        resource_type = submission_data.resource_type

        existing_pending = (
            self.db.query(ApprovalRecord)
            .filter(
                and_(
                    ApprovalRecord.resource_type == resource_type,
                    ApprovalRecord.resource_id == resource_id,
                    ApprovalRecord.status == ApprovalStatus.PENDING,
                )
            )
            .first()
        )
        if existing_pending:
            raise ValueError("Resource already has pending approval")

        resource_data = self._get_resource_data(resource_type, resource_id)

        if submission_data.workflow_id:
            workflow = self.get_workflow(submission_data.workflow_id)
            if not workflow or not workflow.is_active:
                raise ValueError("Invalid or inactive workflow")
        else:
            workflow = self._match_workflow(resource_type, resource_data)
            if not workflow:
                raise ValueError(f"No active workflow found for {resource_type.value}")

        nodes = sorted(workflow.nodes, key=lambda n: n.sort_order)
        approval_nodes = [n for n in nodes if n.node_type == NodeType.APPROVAL]

        if not approval_nodes:
            raise ValueError("Workflow has no approval nodes")

        first_node = approval_nodes[0]

        auto_approved, auto_node = self._process_auto_approve(
            first_node, resource_id, resource_type, resource_data, workflow
        )
        if auto_approved and auto_node:
            workflow_complete, next_node = self._move_to_next_node(
                workflow, auto_node, resource_id, resource_type, resource_data
            )

            if workflow_complete:
                callback = self._get_resource_callback(resource_type)
                if callback:
                    callback(resource_id, ApprovalStatus.AUTO_APPROVED, None)

                return {
                    "success": True,
                    "workflow_id": workflow.id,
                    "workflow_name": workflow.name,
                    "current_node_id": auto_node.id,
                    "current_node_name": auto_node.node_name,
                    "is_auto_approved": True,
                    "workflow_complete": True,
                    "final_status": "AUTO_APPROVED",
                    "records": [],
                    "next_approvers": [],
                }

        approver_ids = self._get_node_approvers(first_node)

        if not approver_ids:
            raise ValueError(f"No approvers found for node: {first_node.node_name}")

        created_records: List[ApprovalRecord] = []
        for approver_id in approver_ids:
            existing = (
                self.db.query(ApprovalRecord)
                .filter(
                    and_(
                        ApprovalRecord.workflow_id == workflow.id,
                        ApprovalRecord.node_id == first_node.id,
                        ApprovalRecord.resource_id == resource_id,
                        ApprovalRecord.resource_type == resource_type,
                        ApprovalRecord.approver_id == approver_id,
                    )
                )
                .first()
            )

            if not existing:
                record = ApprovalRecord(
                    workflow_id=workflow.id,
                    node_id=first_node.id,
                    resource_id=resource_id,
                    resource_type=resource_type,
                    approver_id=approver_id,
                    status=ApprovalStatus.PENDING,
                )
                self.db.add(record)
                created_records.append(record)

        self.db.flush()

        if self.current_user:
            self.audit_logger.log(
                user_id=self.current_user.id,
                action="submit_approval",
                resource_type=resource_type.value.lower(),
                resource_id=resource_id,
                new_value={
                    "workflow_id": workflow.id,
                    "workflow_name": workflow.name,
                    "first_node": first_node.node_name,
                    "approvers": approver_ids,
                },
            )

        self._send_notification(
            user_ids=approver_ids,
            notification_type="APPROVAL_REQUEST",
            title=f"Approval Request: {resource_data.get('title', '')}",
            content="A new approval request requires your attention.",
            data={
                "resource_id": resource_id,
                "resource_type": resource_type.value,
                "workflow_id": workflow.id,
                "node_id": first_node.id,
            },
        )

        cache.delete_pattern("approval:pending:*")

        next_approvers = []
        for uid in approver_ids:
            user = self.db.query(User).filter(User.id == uid).first()
            if user:
                next_approvers.append({
                    "id": user.id,
                    "name": user.username,
                    "email": user.email,
                })

        return {
            "success": True,
            "workflow_id": workflow.id,
            "workflow_name": workflow.name,
            "current_node_id": first_node.id,
            "current_node_name": first_node.node_name,
            "is_auto_approved": False,
            "records": created_records,
            "next_approvers": next_approvers,
        }

    def _check_node_approval(
        self,
        node: ApprovalNode,
        resource_id: int,
        resource_type: ResourceType,
    ) -> ApprovalStatus:
        records = (
            self.db.query(ApprovalRecord)
            .filter(
                and_(
                    ApprovalRecord.node_id == node.id,
                    ApprovalRecord.resource_id == resource_id,
                    ApprovalRecord.resource_type == resource_type,
                )
            )
            .all()
        )

        if not records:
            return ApprovalStatus.PENDING

        if node.approval_type == ApprovalType.AND:
            all_approved = all(r.status == ApprovalStatus.APPROVED for r in records)
            any_rejected = any(r.status == ApprovalStatus.REJECTED for r in records)

            if any_rejected:
                return ApprovalStatus.REJECTED
            if all_approved:
                return ApprovalStatus.APPROVED
            return ApprovalStatus.PENDING

        elif node.approval_type == ApprovalType.OR:
            any_approved = any(r.status == ApprovalStatus.APPROVED for r in records)
            any_rejected = any(r.status == ApprovalStatus.REJECTED for r in records)

            if any_approved:
                return ApprovalStatus.APPROVED
            if all(r.status == ApprovalStatus.REJECTED for r in records):
                return ApprovalStatus.REJECTED
            return ApprovalStatus.PENDING

        elif node.approval_type == ApprovalType.PERCENTAGE:
            total = len(records)
            approved = sum(1 for r in records if r.status == ApprovalStatus.APPROVED)
            rejected = sum(1 for r in records if r.status == ApprovalStatus.REJECTED)
            pass_percentage = node.pass_percentage or 50

            approved_pct = (approved / total) * 100 if total > 0 else 0
            rejected_pct = (rejected / total) * 100 if total > 0 else 0

            if approved_pct >= pass_percentage:
                return ApprovalStatus.APPROVED
            if rejected_pct > (100 - pass_percentage):
                return ApprovalStatus.REJECTED
            return ApprovalStatus.PENDING

        return ApprovalStatus.PENDING

    def _move_to_next_node(
        self,
        workflow: ApprovalWorkflow,
        current_node: ApprovalNode,
        resource_id: int,
        resource_type: ResourceType,
        resource_data: Dict[str, Any],
    ) -> Tuple[bool, Optional[ApprovalNode]]:
        nodes = sorted(workflow.nodes, key=lambda n: n.sort_order)
        current_idx = next(
            (i for i, n in enumerate(nodes) if n.id == current_node.id),
            -1,
        )

        for i in range(current_idx + 1, len(nodes)):
            next_node = nodes[i]

            if next_node.node_type == NodeType.END:
                return True, None

            if next_node.node_type == NodeType.CONDITION:
                target_node = self._evaluate_condition_node(next_node, resource_data)
                if target_node:
                    if target_node.node_type == NodeType.END:
                        return True, None
                    if target_node.node_type == NodeType.APPROVAL:
                        return self._activate_approval_node(
                            workflow, target_node, resource_id, resource_type, resource_data
                        )
                continue

            if next_node.node_type == NodeType.APPROVAL:
                return self._activate_approval_node(
                    workflow, next_node, resource_id, resource_type, resource_data
                )

        return True, None

    def _evaluate_condition_node(
        self,
        condition_node: ApprovalNode,
        resource_data: Dict[str, Any],
    ) -> Optional[ApprovalNode]:
        if condition_node.conditions_list:
            for condition in condition_node.conditions_list:
                if self._evaluate_single_condition(condition, resource_data):
                    if condition.target_node:
                        return condition.target_node

        if condition_node.conditions and self._evaluate_conditions(condition_node.conditions, resource_data):
            if condition_node.target_node:
                return condition_node.target_node

        if condition_node.target_node:
            return condition_node.target_node

        return None

    def _evaluate_single_condition(
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

    def _activate_approval_node(
        self,
        workflow: ApprovalWorkflow,
        node: ApprovalNode,
        resource_id: int,
        resource_type: ResourceType,
        resource_data: Dict[str, Any],
    ) -> Tuple[bool, Optional[ApprovalNode]]:
        auto_approved, auto_node = self._process_auto_approve(
            node, resource_id, resource_type, resource_data, workflow
        )
        if auto_approved and auto_node:
            return self._move_to_next_node(
                workflow, auto_node, resource_id, resource_type, resource_data
            )

        if node.conditions and not self._evaluate_conditions(node.conditions, resource_data):
            logger.info(f"Skipping node {node.node_name} as conditions not met")
            return False, None

        approver_ids = self._get_node_approvers(node)

        if not approver_ids:
            logger.warning(f"No approvers for node {node.node_name}, skipping")
            return False, None

        for approver_id in approver_ids:
            record = ApprovalRecord(
                workflow_id=workflow.id,
                node_id=node.id,
                resource_id=resource_id,
                resource_type=resource_type,
                approver_id=approver_id,
                status=ApprovalStatus.PENDING,
            )
            self.db.add(record)

        self._send_notification(
            user_ids=approver_ids,
            notification_type="APPROVAL_REQUEST",
            title=f"Approval Request: {resource_data.get('title', '')}",
            content=f"Approval request moved to {node.node_name}.",
            data={
                "resource_id": resource_id,
                "resource_type": resource_type.value,
                "workflow_id": workflow.id,
                "node_id": node.id,
            },
        )

        return False, node

    def process_approval_action(
        self,
        record_id: int,
        action_data: ApprovalActionRequest,
        processed_by: User,
    ) -> Dict[str, Any]:
        record = (
            self.db.query(ApprovalRecord)
            .filter(ApprovalRecord.id == record_id)
            .first()
        )
        if not record:
            raise ValueError("Approval record not found")

        if record.approver_id != processed_by.id and not processed_by.is_superuser:
            raise ValueError("You are not authorized to process this approval")

        if record.status != ApprovalStatus.PENDING:
            raise ValueError(f"Cannot process approval in status: {record.status}")

        if action_data.action == ApprovalActionEnum.WITHDRAW:
            return self._withdraw_approval(record, processed_by)

        workflow = record.workflow
        node = record.node
        resource_id = record.resource_id
        resource_type = record.resource_type

        if action_data.action == ApprovalActionEnum.APPROVE:
            record.status = ApprovalStatus.APPROVED
        elif action_data.action == ApprovalActionEnum.REJECT:
            record.status = ApprovalStatus.REJECTED

        record.approval_opinion = action_data.approval_opinion
        record.approved_at = datetime.utcnow()

        self.db.flush()

        resource_data = self._get_resource_data(resource_type, resource_id)

        node_status = self._check_node_approval(node, resource_id, resource_type)

        result = {
            "success": True,
            "record_id": record_id,
            "action": action_data.action.value,
            "node_id": node.id,
            "node_name": node.node_name,
            "resource_id": resource_id,
            "resource_type": resource_type.value,
            "node_status": node_status.value,
        }

        if node_status == ApprovalStatus.REJECTED:
            callback = self._get_resource_callback(resource_type)
            if callback:
                callback(resource_id, ApprovalStatus.REJECTED, processed_by.id)

            if action_data.notify_submitter:
                submitter_id = resource_data.get("created_by")
                if submitter_id:
                    self._send_notification(
                        user_ids=[submitter_id],
                        notification_type="APPROVAL_REJECTED",
                        title=f"Approval Rejected: {resource_data.get('title', '')}",
                        content=f"Your request was rejected by {processed_by.username}.",
                        data={
                            "resource_id": resource_id,
                            "resource_type": resource_type.value,
                            "approval_opinion": action_data.approval_opinion,
                        },
                    )

            result["workflow_complete"] = True
            result["final_status"] = "REJECTED"

        elif node_status == ApprovalStatus.APPROVED:
            workflow_complete, next_node = self._move_to_next_node(
                workflow, node, resource_id, resource_type, resource_data
            )

            if workflow_complete:
                callback = self._get_resource_callback(resource_type)
                if callback:
                    callback(resource_id, ApprovalStatus.APPROVED, processed_by.id)

                if action_data.notify_submitter:
                    submitter_id = resource_data.get("created_by")
                    if submitter_id:
                        self._send_notification(
                            user_ids=[submitter_id],
                            notification_type="APPROVAL_APPROVED",
                            title=f"Approval Approved: {resource_data.get('title', '')}",
                            content="Your request has been fully approved.",
                            data={
                                "resource_id": resource_id,
                                "resource_type": resource_type.value,
                            },
                        )

                result["workflow_complete"] = True
                result["final_status"] = "APPROVED"
            else:
                callback = self._get_resource_callback(resource_type)
                if callback:
                    callback(resource_id, ApprovalStatus.PENDING, processed_by.id)

                result["workflow_complete"] = False
                result["next_node_id"] = next_node.id if next_node else None
                result["next_node_name"] = next_node.node_name if next_node else None

        self.audit_logger.log(
            user_id=processed_by.id,
            action=action_data.action.value.lower(),
            resource_type="approval_record",
            resource_id=record_id,
            new_value={
                "resource_id": resource_id,
                "resource_type": resource_type.value,
                "workflow_id": workflow.id,
                "node_id": node.id,
                "status": record.status.value,
                "approval_opinion": action_data.approval_opinion,
            },
        )

        if action_data.cc_users:
            self._send_notification(
                user_ids=action_data.cc_users,
                notification_type="APPROVAL_CC",
                title=f"CC: Approval {action_data.action.value}",
                content=f"Approval action by {processed_by.username}: {action_data.approval_opinion or 'No comment'}",
                data={
                    "resource_id": resource_id,
                    "resource_type": resource_type.value,
                },
            )

        cache.delete_pattern("approval:pending:*")
        cache.delete_pattern("approval:record:*")

        return result

    def _withdraw_approval(
        self,
        record: ApprovalRecord,
        withdrawn_by: User,
    ) -> Dict[str, Any]:
        resource_data = self._get_resource_data(record.resource_type, record.resource_id)
        submitter_id = resource_data.get("created_by")

        if submitter_id != withdrawn_by.id and not withdrawn_by.is_superuser:
            raise ValueError("Only the submitter can withdraw the approval")

        pending_records = (
            self.db.query(ApprovalRecord)
            .filter(
                and_(
                    ApprovalRecord.resource_type == record.resource_type,
                    ApprovalRecord.resource_id == record.resource_id,
                    ApprovalRecord.status == ApprovalStatus.PENDING,
                )
            )
            .all()
        )

        for pending_record in pending_records:
            pending_record.status = ApprovalStatus.REJECTED
            pending_record.approval_opinion = "Approval withdrawn by submitter"
            pending_record.approved_at = datetime.utcnow()

        callback = self._get_resource_callback(record.resource_type)
        if callback:
            callback(record.resource_id, ApprovalStatus.REJECTED, withdrawn_by.id)

        self.audit_logger.log(
            user_id=withdrawn_by.id,
            action="withdraw",
            resource_type="approval_record",
            resource_id=record.id,
            new_value={
                "resource_id": record.resource_id,
                "resource_type": record.resource_type.value,
            },
        )

        cache.delete_pattern("approval:pending:*")

        return {
            "success": True,
            "record_id": record.id,
            "action": "WITHDRAW",
            "resource_id": record.resource_id,
            "resource_type": record.resource_type.value,
            "workflow_complete": True,
            "final_status": "WITHDRAWN",
        }

    def get_pending_approvals(
        self,
        user_id: int,
        page: int = 1,
        page_size: int = 20,
        resource_type: Optional[ResourceType] = None,
    ) -> Tuple[List[ApprovalRecord], int, int]:
        cache_key = f"approval:pending:{user_id}:{page}:{page_size}:{resource_type}"
        cached = cache.get(cache_key)
        if cached:
            return cached

        query = (
            self.db.query(ApprovalRecord)
            .filter(
                and_(
                    ApprovalRecord.approver_id == user_id,
                    ApprovalRecord.status == ApprovalStatus.PENDING,
                )
            )
        )

        if resource_type:
            query = query.filter(ApprovalRecord.resource_type == resource_type)

        total = query.count()
        query = query.order_by(desc(ApprovalRecord.created_at))

        offset = (page - 1) * page_size
        records = query.offset(offset).limit(page_size).all()

        total_pages = (total + page_size - 1) // page_size

        result = (records, total, total_pages)
        cache.set(cache_key, result, ttl=60)

        return result

    def get_approval_statistics(
        self,
        user_id: int,
    ) -> Dict[str, Any]:
        today_start = datetime.utcnow().replace(hour=0, minute=0, second=0, microsecond=0)

        pending = (
            self.db.query(func.count(ApprovalRecord.id))
            .filter(
                and_(
                    ApprovalRecord.approver_id == user_id,
                    ApprovalRecord.status == ApprovalStatus.PENDING,
                )
            )
            .scalar()
            or 0
        )

        today_pending = (
            self.db.query(func.count(ApprovalRecord.id))
            .filter(
                and_(
                    ApprovalRecord.approver_id == user_id,
                    ApprovalRecord.status == ApprovalStatus.PENDING,
                    ApprovalRecord.created_at >= today_start,
                )
            )
            .scalar()
            or 0
        )

        approved_today = (
            self.db.query(func.count(ApprovalRecord.id))
            .filter(
                and_(
                    ApprovalRecord.approver_id == user_id,
                    ApprovalRecord.status == ApprovalStatus.APPROVED,
                    ApprovalRecord.approved_at >= today_start,
                )
            )
            .scalar()
            or 0
        )

        rejected_today = (
            self.db.query(func.count(ApprovalRecord.id))
            .filter(
                and_(
                    ApprovalRecord.approver_id == user_id,
                    ApprovalRecord.status == ApprovalStatus.REJECTED,
                    ApprovalRecord.approved_at >= today_start,
                )
            )
            .scalar()
            or 0
        )

        submitted_by_me = (
            self.db.query(func.count(ApprovalRecord.id))
            .filter(
                and_(
                    ApprovalRecord.resource_type == ResourceType.PURCHASE_ORDER,
                )
            )
            .join(PurchaseOrder, PurchaseOrder.id == ApprovalRecord.resource_id)
            .filter(PurchaseOrder.created_by == user_id)
            .scalar()
            or 0
        )

        overdue = (
            self.db.query(func.count(ApprovalRecord.id))
            .filter(
                and_(
                    ApprovalRecord.approver_id == user_id,
                    ApprovalRecord.status == ApprovalStatus.PENDING,
                    ApprovalRecord.created_at < datetime.utcnow() - timedelta(hours=24),
                )
            )
            .scalar()
            or 0
        )

        return {
            "total_pending": pending,
            "today_pending": today_pending,
            "overdue_count": overdue,
            "approved_today": approved_today,
            "rejected_today": rejected_today,
            "submitted_by_me": submitted_by_me,
            "my_pending_approval": pending,
            "avg_processing_hours": None,
            "by_node_type": {},
            "by_resource_type": {},
        }

    def can_approve_resource(
        self,
        resource_id: int,
        resource_type: ResourceType,
        user_id: int,
    ) -> bool:
        pending = (
            self.db.query(ApprovalRecord)
            .filter(
                and_(
                    ApprovalRecord.resource_type == resource_type,
                    ApprovalRecord.resource_id == resource_id,
                    ApprovalRecord.approver_id == user_id,
                    ApprovalRecord.status == ApprovalStatus.PENDING,
                )
            )
            .first()
        )
        return pending is not None

    def get_current_approval_node(
        self,
        resource_id: int,
        resource_type: ResourceType,
    ) -> Optional[ApprovalNode]:
        pending_record = (
            self.db.query(ApprovalRecord)
            .filter(
                and_(
                    ApprovalRecord.resource_type == resource_type,
                    ApprovalRecord.resource_id == resource_id,
                    ApprovalRecord.status == ApprovalStatus.PENDING,
                )
            )
            .first()
        )
        return pending_record.node if pending_record else None

    def process_timeout_approvals(self) -> int:
        timeout_threshold = datetime.utcnow() - timedelta(hours=24)

        pending_records = (
            self.db.query(ApprovalRecord)
            .filter(
                and_(
                    ApprovalRecord.status == ApprovalStatus.PENDING,
                    ApprovalRecord.created_at < timeout_threshold,
                )
            )
            .all()
        )

        processed_count = 0
        for record in pending_records:
            try:
                node = record.node
                timeout_action = getattr(node, "timeout_action", None)

                if timeout_action == "AUTO_APPROVE":
                    record.status = ApprovalStatus.APPROVED
                    record.approval_opinion = "Auto-approved due to timeout"
                    record.approved_at = datetime.utcnow()
                    processed_count += 1
                elif timeout_action == "AUTO_REJECT":
                    record.status = ApprovalStatus.REJECTED
                    record.approval_opinion = "Auto-rejected due to timeout"
                    record.approved_at = datetime.utcnow()
                    processed_count += 1
                elif timeout_action == "ESCALATE":
                    logger.info(f"Escalating approval {record.id} due to timeout")
                    processed_count += 1

                self.db.flush()

            except Exception as e:
                logger.error(f"Error processing timeout approval {record.id}: {e}")
                continue

        if processed_count > 0:
            cache.delete_pattern("approval:pending:*")
            logger.info(f"Processed {processed_count} timeout approvals")

        return processed_count


    def _list_approval_records(
        self,
        filters: ApprovalRecordListFilter,
        skip: int = 0,
        limit: int = 20,
        sort_by: str = "submitted_at",
        sort_order: str = "desc",
    ) -> Tuple[List[ApprovalRecord], int]:
        query = self.db.query(ApprovalRecord)

        if filters.resource_type:
            query = query.filter(ApprovalRecord.resource_type == filters.resource_type)
        if filters.resource_id:
            query = query.filter(ApprovalRecord.resource_id == filters.resource_id)
        if filters.status:
            query = query.filter(ApprovalRecord.status == filters.status)
        if filters.submitter_id:
            query = query.filter(ApprovalRecord.submitted_by == filters.submitter_id)
        if filters.approver_id:
            query = query.filter(ApprovalRecord.approver_id == filters.approver_id)
        if filters.date_from:
            query = query.filter(ApprovalRecord.submitted_at >= filters.date_from)
        if filters.date_to:
            query = query.filter(ApprovalRecord.submitted_at <= filters.date_to)

        total = query.count()

        order_func = desc if sort_order == "desc" else func.asc
        if hasattr(ApprovalRecord, sort_by):
            query = query.order_by(order_func(getattr(ApprovalRecord, sort_by)))

        records = query.offset(skip).limit(limit).all()
        return records, total

    def _get_approval_record_detail(
        self,
        record_id: int,
    ) -> Optional[ApprovalRecord]:
        cache_key = f"approval:record:{record_id}"
        cached = cache.get(cache_key)
        if cached:
            return cached

        record = (
            self.db.query(ApprovalRecord)
            .options(
                selectinload(ApprovalRecord.workflow),
                selectinload(ApprovalRecord.node),
            )
            .filter(ApprovalRecord.id == record_id)
            .first()
        )

        if record:
            cache.set(cache_key, record, expire=300)

        return record

    def check_timeouts(self) -> int:
        timeout_hours = 24
        now = datetime.utcnow()
        timeout_threshold = now - timedelta(hours=timeout_hours)

        pending_records = (
            self.db.query(ApprovalRecord)
            .join(ApprovalNode, ApprovalRecord.node_id == ApprovalNode.id)
            .filter(
                and_(
                    ApprovalRecord.status == ApprovalStatus.PENDING,
                    ApprovalRecord.created_at <= timeout_threshold,
                )
            )
            .all()
        )

        escalated_count = 0

        for record in pending_records:
            node = record.node
            if node.auto_upgrade and node.upgrade_user_id:
                record.status = ApprovalStatus.ESCALATED
                record.approver_id = node.upgrade_user_id
                record.created_at = now
                escalated_count += 1

                self.audit_logger.log(
                    user_id=None,
                    action="escalate",
                    resource_type="approval_record",
                    resource_id=record.id,
                    new_value={
                        "old_approver_id": record.approver_id,
                        "new_approver_id": node.upgrade_user_id,
                        "reason": "timeout",
                    },
                )

        self.db.flush()
        cache.delete_pattern("approval:pending:*")

        return escalated_count

    def add_condition(
        self,
        workflow_id: int,
        condition_data: ApprovalConditionCreate,
        created_by: User,
    ) -> ApprovalCondition:
        workflow = self.get_workflow(workflow_id)
        if not workflow:
            raise ValueError("Workflow not found")

        node_id = condition_data.node_id
        if node_id:
            node = (
                self.db.query(ApprovalNode)
                .filter(
                    and_(
                        ApprovalNode.id == node_id,
                        ApprovalNode.workflow_id == workflow_id,
                    )
                )
                .first()
            )
            if not node:
                raise ValueError("Node not found in this workflow")

        condition_dict = condition_data.model_dump()
        condition_dict.pop("node_id", None)

        condition = ApprovalCondition(
            workflow_id=workflow_id,
            node_id=node_id,
            **condition_dict,
        )

        self.db.add(condition)
        self.db.flush()

        self.audit_logger.log_create(
            user=created_by,
            resource_type="approval_condition",
            resource_id=condition.id,
            new_value={
                "workflow_id": workflow_id,
                "node_id": node_id,
                "condition_type": condition.condition_type.value,
                "field_name": condition.field_name,
                "operator": condition.operator.value,
            },
        )

        cache.delete(f"approval:workflow:{workflow_id}")

        return condition

    def update_condition(
        self,
        workflow_id: int,
        condition_id: int,
        update_data: ApprovalConditionUpdate,
        updated_by: User,
    ) -> Optional[ApprovalCondition]:
        condition = (
            self.db.query(ApprovalCondition)
            .filter(
                and_(
                    ApprovalCondition.id == condition_id,
                    ApprovalCondition.workflow_id == workflow_id,
                )
            )
            .first()
        )
        if not condition:
            return None

        old_value = {
            "condition_type": condition.condition_type.value,
            "field_name": condition.field_name,
            "operator": condition.operator.value,
        }

        update_dict = update_data.model_dump(exclude_unset=True)
        for key, value in update_dict.items():
            setattr(condition, key, value)

        self.db.flush()

        self.audit_logger.log_update(
            user=updated_by,
            resource_type="approval_condition",
            resource_id=condition.id,
            old_value=old_value,
            new_value={
                "condition_type": condition.condition_type.value,
                "field_name": condition.field_name,
                "operator": condition.operator.value,
            },
        )

        cache.delete(f"approval:workflow:{workflow_id}")

        return condition

    def delete_condition(
        self,
        workflow_id: int,
        condition_id: int,
        deleted_by: User,
    ) -> bool:
        condition = (
            self.db.query(ApprovalCondition)
            .filter(
                and_(
                    ApprovalCondition.id == condition_id,
                    ApprovalCondition.workflow_id == workflow_id,
                )
            )
            .first()
        )
        if not condition:
            return False

        old_value = {
            "condition_type": condition.condition_type.value,
            "field_name": condition.field_name,
            "workflow_id": workflow_id,
        }

        self.db.delete(condition)
        self.db.flush()

        self.audit_logger.log_delete(
            user=deleted_by,
            resource_type="approval_condition",
            resource_id=condition_id,
            old_value=old_value,
        )

        cache.delete(f"approval:workflow:{workflow_id}")

        return True

    def list_workflow_conditions(
        self,
        workflow_id: int,
        node_id: Optional[int] = None,
    ) -> List[ApprovalCondition]:
        query = self.db.query(ApprovalCondition).filter(ApprovalCondition.workflow_id == workflow_id)

        if node_id is not None:
            query = query.filter(ApprovalCondition.node_id == node_id)

        return query.order_by(ApprovalCondition.created_at.desc()).all()

    def get_condition(
        self,
        condition_id: int,
    ) -> Optional[ApprovalCondition]:
        return self.db.query(ApprovalCondition).filter(ApprovalCondition.id == condition_id).first()


def create_approval_service(
    db: Session,
    current_user: Optional[User] = None,
) -> ApprovalService:
    return ApprovalService(db, current_user)
