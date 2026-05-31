最后一轮了，看看下面这些任务你能不能一口气搞定：对工业协议适配网关的边缘推理调度模块和数据流边缘聚合模块进行性能与质量双维度的重构。性能上，减少不必要的内存分配和拷贝，优化锁粒度；质量上，消除超长函数、统一错误处理、规范命名。重构原则是行为不变、结构更优。交付全部重构后代码。from datetime import datetime, timedelta
from typing import Any, Dict, List, Optional, Tuple

from sqlalchemy import select, and_, or_
from sqlalchemy.ext.asyncio import AsyncSession

from core.exceptions import ValidationError, NotFoundError, ConflictError
from core.utils import validate_params, generate_id, utc_now
from .models import (
    ApprovalRule,
    ApprovalProcess,
    ApprovalRecord,
    ApprovalRuleCreate,
    ApprovalRuleResponse,
    ApprovalProcessCreate,
    ApprovalProcessResponse,
    ApprovalActionRequest,
    ApprovalRecordResponse,
    ConditionEvaluationResult,
    ApprovalType,
    ApprovalStatus,
    ApprovalAction,
    RuleConditionOperator,
    RuleCombinationOperator,
    DynamicApproverType,
)


class RuleConditionEvaluator:
    def __init__(self):
        self.operators = {
            RuleConditionOperator.EQUALS: self._op_equals,
            RuleConditionOperator.NOT_EQUALS: self._op_not_equals,
            RuleConditionOperator.GREATER_THAN: self._op_greater_than,
            RuleConditionOperator.LESS_THAN: self._op_less_than,
            RuleConditionOperator.CONTAINS: self._op_contains,
            RuleConditionOperator.NOT_CONTAINS: self._op_not_contains,
            RuleConditionOperator.IN: self._op_in,
            RuleConditionOperator.NOT_IN: self._op_not_in,
            RuleConditionOperator.STARTS_WITH: self._op_starts_with,
            RuleConditionOperator.ENDS_WITH: self._op_ends_with,
            RuleConditionOperator.REGEX: self._op_regex,
        }

    def evaluate_condition(
        self, condition: Dict[str, Any], context: Dict[str, Any]
    ) -> Tuple[bool, ConditionEvaluationResult]:
        field = condition.get("field")
        operator_str = condition.get("operator")
        try:
            operator = RuleConditionOperator(operator_str)
        except ValueError:
            operator = RuleConditionOperator.EQUALS
        expected_value = condition.get("value")
        condition_id = condition.get("id", generate_id("cond"))

        actual_value = self._get_nested_value(context, field)

        evaluator = self.operators.get(operator, self._op_equals)
        result = evaluator(actual_value, expected_value)

        return result, ConditionEvaluationResult(
            condition_id=condition_id,
            field=field,
            operator=operator,
            expected_value=expected_value,
            actual_value=actual_value,
            result=result,
        )

    def evaluate_conditions(
        self,
        conditions: List[Dict[str, Any]],
        context: Dict[str, Any],
        operator: RuleCombinationOperator = RuleCombinationOperator.AND,
    ) -> Tuple[bool, List[ConditionEvaluationResult]]:
        if not conditions:
            return True, []

        results = []
        for condition in conditions:
            result, eval_result = self.evaluate_condition(condition, context)
            results.append(eval_result)

            if operator == RuleCombinationOperator.AND and not result:
                return False, results
            if operator == RuleCombinationOperator.OR and result:
                return True, results

        if operator == RuleCombinationOperator.AND:
            return all(r.result for r in results), results
        else:
            return any(r.result for r in results), results

    def _get_nested_value(self, data: Dict[str, Any], path: str) -> Any:
        parts = path.split(".")
        current = data
        for part in parts:
            if isinstance(current, dict) and part in current:
                current = current[part]
            else:
                return None
        return current

    def _op_equals(self, actual: Any, expected: Any) -> bool:
        return actual == expected

    def _op_not_equals(self, actual: Any, expected: Any) -> bool:
        return actual != expected

    def _op_greater_than(self, actual: Any, expected: Any) -> bool:
        try:
            return float(actual) > float(expected)
        except (TypeError, ValueError):
            return False

    def _op_less_than(self, actual: Any, expected: Any) -> bool:
        try:
            return float(actual) < float(expected)
        except (TypeError, ValueError):
            return False

    def _op_contains(self, actual: Any, expected: Any) -> bool:
        if isinstance(actual, str) and isinstance(expected, str):
            return expected in actual
        if isinstance(actual, (list, tuple, set)):
            return expected in actual
        return False

    def _op_not_contains(self, actual: Any, expected: Any) -> bool:
        return not self._op_contains(actual, expected)

    def _op_in(self, actual: Any, expected: Any) -> bool:
        if isinstance(expected, (list, tuple, set)):
            return actual in expected
        return False

    def _op_not_in(self, actual: Any, expected: Any) -> bool:
        return not self._op_in(actual, expected)

    def _op_starts_with(self, actual: Any, expected: Any) -> bool:
        if isinstance(actual, str) and isinstance(expected, str):
            return actual.startswith(expected)
        return False

    def _op_ends_with(self, actual: Any, expected: Any) -> bool:
        if isinstance(actual, str) and isinstance(expected, str):
            return actual.endswith(expected)
        return False

    def _op_regex(self, actual: Any, expected: Any) -> bool:
        import re

        if isinstance(actual, str) and isinstance(expected, str):
            try:
                return bool(re.match(expected, actual))
            except re.error:
                return False
        return False


class DynamicApproverResolver:
    def __init__(self, db: AsyncSession):
        self.db = db

    async def resolve_approvers(
        self,
        dynamic_config: List[Dict[str, Any]],
        context: Dict[str, Any],
        tenant_id: Optional[str] = None,
    ) -> List[Dict[str, Any]]:
        approvers = []

        for config in dynamic_config:
            approver_type = DynamicApproverType(config.get("type"))

            if approver_type == DynamicApproverType.USER:
                approvers.extend(self._resolve_user(config))
            elif approver_type == DynamicApproverType.ROLE:
                approvers.extend(self._resolve_role(config))
            elif approver_type == DynamicApproverType.DEPARTMENT:
                approvers.extend(self._resolve_department(config))
            elif approver_type == DynamicApproverType.MANAGER:
                approvers.extend(await self._resolve_manager(config, context))
            elif approver_type == DynamicApproverType.FORMULA:
                approvers.extend(self._resolve_formula(config, context))
            elif approver_type == DynamicApproverType.SCRIPT:
                approvers.extend(self._resolve_script(config, context))

        return approvers

    def _resolve_user(self, config: Dict[str, Any]) -> List[Dict[str, Any]]:
        user_ids = config.get("user_ids", [])
        return [{"id": uid, "type": "user", "name": config.get(f"name_{uid}", uid)} for uid in user_ids]

    def _resolve_role(self, config: Dict[str, Any]) -> List[Dict[str, Any]]:
        role = config.get("role")
        return [{"id": f"role_{role}", "type": "role", "name": f"角色: {role}", "role": role}]

    def _resolve_department(self, config: Dict[str, Any]) -> List[Dict[str, Any]]:
        dept = config.get("department")
        return [{"id": f"dept_{dept}", "type": "department", "name": f"部门: {dept}", "department": dept}]

    async def _resolve_manager(
        self, config: Dict[str, Any], context: Dict[str, Any]
    ) -> List[Dict[str, Any]]:
        user_id = context.get("started_by") or context.get("user_id")
        level = config.get("level", 1)

        if not user_id:
            return []

        return [
            {
                "id": f"manager_{user_id}_{level}",
                "type": "manager",
                "name": f"{user_id}的{level}级主管",
                "user_id": user_id,
                "level": level,
            }
        ]

    def _resolve_formula(
        self, config: Dict[str, Any], context: Dict[str, Any]
    ) -> List[Dict[str, Any]]:
        formula = config.get("formula", "")
        try:
            result = eval(formula, {"__builtins__": {}}, context)
            if isinstance(result, str):
                return [{"id": result, "type": "formula", "name": result}]
            elif isinstance(result, list):
                return [{"id": str(r), "type": "formula", "name": str(r)} for r in result]
        except Exception:
            pass
        return []

    def _resolve_script(
        self, config: Dict[str, Any], context: Dict[str, Any]
    ) -> List[Dict[str, Any]]:
        script = config.get("script", "")
        try:
            local_vars = {"context": context, "result": []}
            exec(script, {"__builtins__": {}}, local_vars)
            result = local_vars.get("result", [])
            return [{"id": str(r), "type": "script", "name": str(r)} for r in result]
        except Exception:
            pass
        return []


class ApprovalRuleService:
    def __init__(self, db: AsyncSession):
        self.db = db
        self.condition_evaluator = RuleConditionEvaluator()
        self.approver_resolver = DynamicApproverResolver(db)

    async def create_rule(self, rule_data: ApprovalRuleCreate) -> ApprovalRuleResponse:
        validation_rules = {
            "name": lambda x: x is not None and len(x.strip()) > 0,
            "rule_type": lambda x: x is not None and len(x) > 0,
            "approvers": lambda x: x is not None and len(x) > 0,
        }
        validate_params(rule_data.model_dump(), validation_rules)

        rule = ApprovalRule(**rule_data.model_dump())
        self.db.add(rule)
        await self.db.flush()

        return ApprovalRuleResponse.model_validate(rule)

    async def get_rule(
        self, rule_id: str, tenant_id: Optional[str] = None
    ) -> ApprovalRuleResponse:
        query = select(ApprovalRule).where(ApprovalRule.rule_id == rule_id)
        if tenant_id:
            query = query.where(
                or_(ApprovalRule.tenant_id == tenant_id, ApprovalRule.tenant_id == None)
            )

        result = await self.db.execute(query)
        rule = result.scalar_one_or_none()

        if not rule:
            raise NotFoundError(f"审批规则 {rule_id} 不存在")

        return ApprovalRuleResponse.model_validate(rule)

    async def find_matching_rule(
        self, rule_type: str, context: Dict[str, Any], tenant_id: Optional[str] = None
    ) -> Optional[ApprovalRule]:
        query = select(ApprovalRule).where(
            ApprovalRule.rule_type == rule_type,
            ApprovalRule.is_active == True,
        )
        if tenant_id:
            query = query.where(
                or_(ApprovalRule.tenant_id == tenant_id, ApprovalRule.tenant_id == None)
            )

        query = query.order_by(ApprovalRule.priority.desc())
        result = await self.db.execute(query)
        rules = result.scalars().all()

        for rule in rules:
            match, _ = self.condition_evaluator.evaluate_conditions(
                rule.conditions, context, rule.condition_operator
            )
            if match:
                return rule

        return None

    async def list_rules(
        self,
        rule_type: Optional[str] = None,
        tenant_id: Optional[str] = None,
        is_active: Optional[bool] = None,
        limit: int = 100,
        offset: int = 0,
    ) -> List[ApprovalRuleResponse]:
        query = select(ApprovalRule)
        if rule_type:
            query = query.where(ApprovalRule.rule_type == rule_type)
        if tenant_id:
            query = query.where(
                or_(ApprovalRule.tenant_id == tenant_id, ApprovalRule.tenant_id == None)
            )
        if is_active is not None:
            query = query.where(ApprovalRule.is_active == is_active)

        query = query.order_by(ApprovalRule.priority.desc(), ApprovalRule.created_at.desc())
        query = query.limit(limit).offset(offset)
        result = await self.db.execute(query)
        rules = result.scalars().all()

        return [ApprovalRuleResponse.model_validate(r) for r in rules]

    async def evaluate_rule(
        self, rule_id: str, context: Dict[str, Any], tenant_id: Optional[str] = None
    ) -> Dict[str, Any]:
        rule = await self.get_rule(rule_id, tenant_id)

        is_match, evaluation_results = self.condition_evaluator.evaluate_conditions(
            rule.conditions, context, rule.condition_operator
        )

        dynamic_approvers = await self.approver_resolver.resolve_approvers(
            rule.dynamic_approvers, context, tenant_id
        )
        all_approvers = rule.approvers + dynamic_approvers

        return {
            "rule_id": rule_id,
            "is_match": is_match,
            "condition_results": [r.model_dump() for r in evaluation_results],
            "approvers": all_approvers,
            "approval_type": rule.approval_type,
            "approval_percentage": rule.approval_percentage,
        }


class ApprovalProcessService:
    def __init__(self, db: AsyncSession):
        self.db = db
        self.rule_service = ApprovalRuleService(db)
        self.condition_evaluator = RuleConditionEvaluator()

    async def start_process(
        self, process_data: ApprovalProcessCreate
    ) -> ApprovalProcessResponse:
        validation_rules = {
            "entity_type": lambda x: x is not None and len(x) > 0,
            "entity_id": lambda x: x is not None and len(x) > 0,
            "title": lambda x: x is not None and len(x.strip()) > 0,
        }
        validate_params(process_data.model_dump(), validation_rules)

        context = {**process_data.context, **process_data.form_data}

        rule = None
        if process_data.rule_id:
            rule = await self.rule_service.find_matching_rule(
                "", context, process_data.tenant_id
            )
        else:
            rule = await self.rule_service.find_matching_rule(
                process_data.entity_type, context, process_data.tenant_id
            )

        approval_type = process_data.approval_type
        approval_percentage = process_data.approval_percentage
        approvers = process_data.approvers.copy()

        if rule:
            approval_type = approval_type or rule.approval_type
            approval_percentage = approval_percentage or rule.approval_percentage

            if not approvers:
                context["started_by"] = process_data.started_by
                dynamic_approvers = await self.rule_service.approver_resolver.resolve_approvers(
                    rule.dynamic_approvers, context, process_data.tenant_id
                )
                approvers = rule.approvers + dynamic_approvers

        if not approvers:
            raise ValidationError(message="无法确定审批人列表", details={})

        approval_steps = self._build_approval_steps(approvers, approval_type)

        timeout_at = None
        if process_data.timeout_seconds:
            timeout_at = utc_now() + timedelta(seconds=process_data.timeout_seconds)
        elif rule and rule.timeout_seconds:
            timeout_at = utc_now() + timedelta(seconds=rule.timeout_seconds)

        process = ApprovalProcess(
            rule_id=process_data.rule_id or (rule.rule_id if rule else None),
            entity_type=process_data.entity_type,
            entity_id=process_data.entity_id,
            title=process_data.title,
            description=process_data.description,
            approval_type=approval_type or ApprovalType.ALL,
            approval_percentage=approval_percentage,
            status=ApprovalStatus.PENDING,
            current_step=0,
            total_steps=len(approval_steps),
            context=process_data.context,
            form_data=process_data.form_data,
            approvers=approvers,
            approval_steps=approval_steps,
            timeout_at=timeout_at,
            tenant_id=process_data.tenant_id,
            started_by=process_data.started_by,
        )
        self.db.add(process)
        await self.db.flush()

        return ApprovalProcessResponse.model_validate(process)

    def _build_approval_steps(
        self, approvers: List[Dict[str, Any]], approval_type: ApprovalType
    ) -> List[Dict[str, Any]]:
        steps = []

        if approval_type == ApprovalType.SEQUENTIAL:
            for idx, approver in enumerate(approvers):
                steps.append(
                    {
                        "step_index": idx,
                        "approvers": [approver],
                        "status": ApprovalStatus.PENDING,
                        "required_count": 1,
                        "approved_count": 0,
                        "rejected_count": 0,
                    }
                )
        else:
            steps.append(
                {
                    "step_index": 0,
                    "approvers": approvers,
                    "status": ApprovalStatus.PENDING,
                    "required_count": self._get_required_count(approvers, approval_type, approval_type),
                    "approved_count": 0,
                    "rejected_count": 0,
                }
            )

        return steps

    def _get_required_count(
        self, approvers: List[Dict[str, Any]], approval_type: ApprovalType, approval_type_ref: ApprovalType
    ) -> float:
        if approval_type == ApprovalType.ALL:
            return len(approvers)
        elif approval_type == ApprovalType.ANY:
            return 1
        elif approval_type == ApprovalType.PERCENTAGE:
            return max(1, len(approvers) * 0.5)
        else:
            return 1

    async def get_process(
        self, process_id: str, tenant_id: Optional[str] = None
    ) -> ApprovalProcessResponse:
        query = select(ApprovalProcess).where(ApprovalProcess.process_id == process_id)
        if tenant_id:
            query = query.where(ApprovalProcess.tenant_id == tenant_id)

        result = await self.db.execute(query)
        process = result.scalar_one_or_none()

        if not process:
            raise NotFoundError(f"审批流程 {process_id} 不存在")

        return ApprovalProcessResponse.model_validate(process)

    async def execute_action(self, action_request: ApprovalActionRequest) -> ApprovalRecordResponse:
        validation_rules = {
            "process_id": lambda x: x is not None and len(x) > 0,
            "action": lambda x: x is not None,
            "approver_id": lambda x: x is not None and len(x) > 0,
        }
        validate_params(action_request.model_dump(), validation_rules)

        process = await self._get_process_orm(
            action_request.process_id, action_request.tenant_id
        )

        if process.status in [
            ApprovalStatus.APPROVED,
            ApprovalStatus.REJECTED,
            ApprovalStatus.CANCELLED,
            ApprovalStatus.TIMEOUT,
        ]:
            raise ConflictError(f"审批流程状态为 {process.status}，无法执行操作")

        step_index = action_request.step_index or process.current_step
        if step_index >= process.total_steps:
            raise ValidationError(message="审批步骤超出范围", details={})

        current_step = process.approval_steps[step_index]
        approver_ids = [a.get("id") for a in current_step["approvers"]]

        if action_request.approver_id not in approver_ids:
            raise ValidationError(message="您不是当前步骤的审批人", details={})

        record = ApprovalRecord(
            process_id=action_request.process_id,
            step_index=step_index,
            approver_id=action_request.approver_id,
            approver_name=action_request.approver_name,
            action=action_request.action,
            status=self._action_to_status(action_request.action),
            comment=action_request.comment,
            delegated_to=action_request.delegated_to,
            signature=action_request.signature,
            ip_address=action_request.ip_address,
            user_agent=action_request.user_agent,
            tenant_id=action_request.tenant_id,
        )
        self.db.add(record)

        self._update_step_status(process, step_index, action_request)
        self._update_process_status(process, step_index)

        self.db.add(process)
        await self.db.flush()

        return ApprovalRecordResponse.model_validate(record)

    async def _get_process_orm(
        self, process_id: str, tenant_id: Optional[str] = None
    ) -> ApprovalProcess:
        query = select(ApprovalProcess).where(ApprovalProcess.process_id == process_id)
        if tenant_id:
            query = query.where(ApprovalProcess.tenant_id == tenant_id)

        result = await self.db.execute(query)
        process = result.scalar_one_or_none()

        if not process:
            raise NotFoundError(f"审批流程 {process_id} 不存在")

        return process

    def _action_to_status(self, action: ApprovalAction) -> ApprovalStatus:
        mapping = {
            ApprovalAction.APPROVE: ApprovalStatus.APPROVED,
            ApprovalAction.REJECT: ApprovalStatus.REJECTED,
            ApprovalAction.DELEGATE: ApprovalStatus.PENDING,
            ApprovalAction.ESCALATE: ApprovalStatus.ESCALATED,
            ApprovalAction.COMMENT: ApprovalStatus.PENDING,
        }
        return mapping.get(action, ApprovalStatus.PENDING)

    def _update_step_status(
        self,
        process: ApprovalProcess,
        step_index: int,
        action_request: ApprovalAction,
    ) -> None:
        step = process.approval_steps[step_index]

        if action_request.action == ApprovalAction.APPROVE:
            step["approved_count"] = step.get("approved_count", 0) + 1
        elif action_request.action == ApprovalAction.REJECT:
            step["rejected_count"] = step.get("rejected_count", 0) + 1

        total_votes = step["approved_count"] + step["rejected_count"]
        required = step.get("required_count", 1)

        if step["rejected_count"] > 0 and process.approval_type == ApprovalType.ALL:
            step["status"] = ApprovalStatus.REJECTED
        elif step["approved_count"] >= required:
            step["status"] = ApprovalStatus.APPROVED
        elif process.approval_type == ApprovalType.PERCENTAGE:
            if total_votes > 0 and step["approved_count"] / total_votes >= (process.approval_percentage or 0.5):
                step["status"] = ApprovalStatus.APPROVED

        for approver in step["approvers"]:
            if approver.get("id") == action_request.approver_id:
                approver["status"] = self._action_to_status(action_request.action)
                approver["action_at"] = utc_now().isoformat()

    def _update_process_status(self, process: ApprovalProcess, current_step_index: int) -> None:
        current_step = process.approval_steps[current_step_index]

        if current_step["status"] == ApprovalStatus.REJECTED:
            process.status = ApprovalStatus.REJECTED
            process.completed_at = utc_now()
            return

        if current_step["status"] == ApprovalStatus.APPROVED:
            if current_step_index < process.total_steps - 1:
                process.current_step = current_step_index + 1
                process.approval_steps[current_step_index + 1]["status"] = ApprovalStatus.PENDING
            else:
                process.status = ApprovalStatus.APPROVED
                process.completed_at = utc_now()

    async def get_process_records(
        self, process_id: str, tenant_id: Optional[str] = None
    ) -> List[ApprovalRecordResponse]:
        query = select(ApprovalRecord).where(ApprovalRecord.process_id == process_id)
        if tenant_id:
            query = query.where(ApprovalRecord.tenant_id == tenant_id)

        query = query.order_by(ApprovalRecord.created_at)
        result = await self.db.execute(query)
        records = result.scalars().all()

        return [ApprovalRecordResponse.model_validate(r) for r in records]

    async def list_processes(
        self,
        entity_type: Optional[str] = None,
        status: Optional[ApprovalStatus] = None,
        started_by: Optional[str] = None,
        approver_id: Optional[str] = None,
        tenant_id: Optional[str] = None,
        limit: int = 50,
        offset: int = 0,
    ) -> List[ApprovalProcessResponse]:
        query = select(ApprovalProcess)
        if entity_type:
            query = query.where(ApprovalProcess.entity_type == entity_type)
        if status:
            query = query.where(ApprovalProcess.status == status)
        if started_by:
            query = query.where(ApprovalProcess.started_by == started_by)
        if tenant_id:
            query = query.where(ApprovalProcess.tenant_id == tenant_id)

        query = query.order_by(ApprovalProcess.created_at.desc()).limit(limit).offset(offset)
        result = await self.db.execute(query)
        processes = result.scalars().all()

        if approver_id:
            processes = [
                p for p in processes
                if any(a.get("id") == approver_id for a in p.approvers)
            ]

        return [ApprovalProcessResponse.model_validate(p) for p in processes]
