import pytest
from typing import Dict, Any

from sqlalchemy.ext.asyncio import AsyncSession

from pydantic import ValidationError as PydanticValidationError

from core.exceptions import ValidationError, NotFoundError, ConflictError
from modules.approval_engine.models import (
    ApprovalRuleCreate,
    ApprovalProcessCreate,
    ApprovalActionRequest,
    ApprovalType,
    ApprovalStatus,
    ApprovalAction,
    RuleConditionOperator,
    RuleCombinationOperator,
)
from modules.approval_engine.service import (
    ApprovalRuleService,
    ApprovalProcessService,
    RuleConditionEvaluator,
)
from tests.fixtures.data_factory import ApprovalEngineDataFactory


pytestmark = pytest.mark.asyncio


class TestConditionEvaluator:
    @pytest.mark.parametrize(
        "scenario",
        ApprovalEngineDataFactory.create_condition_evaluation_scenarios(),
    )
    def test_condition_evaluation_scenarios(
        self,
        scenario: Dict[str, Any],
    ) -> None:
        evaluator = RuleConditionEvaluator()
        result, eval_result = evaluator.evaluate_condition(
            scenario["condition"], scenario["context"]
        )

        assert result == scenario["expected"], (
            f"场景 '{scenario['name']}' 失败: "
            f"期望 {scenario['expected']}, 实际 {result}"
        )

    def test_evaluate_conditions_with_and_operator(
        self,
    ) -> None:
        evaluator = RuleConditionEvaluator()
        conditions = [
            {"field": "amount", "operator": "greater_than", "value": 1000},
            {"field": "status", "operator": "equals", "value": "pending"},
        ]
        context = {"amount": 2000, "status": "pending"}

        result, results = evaluator.evaluate_conditions(
            conditions, context, RuleCombinationOperator.AND
        )

        assert result is True
        assert len(results) == 2
        assert all(r.result for r in results)

    def test_evaluate_conditions_with_or_operator(
        self,
    ) -> None:
        evaluator = RuleConditionEvaluator()
        conditions = [
            {"field": "amount", "operator": "greater_than", "value": 10000},
            {"field": "priority", "operator": "equals", "value": "high"},
        ]
        context = {"amount": 2000, "priority": "high"}

        result, results = evaluator.evaluate_conditions(
            conditions, context, RuleCombinationOperator.OR
        )

        assert result is True
        assert len(results) == 2

    def test_nested_field_access(
        self,
    ) -> None:
        evaluator = RuleConditionEvaluator()
        condition = {
            "field": "user.department.name",
            "operator": "equals",
            "value": "finance",
        }
        context = {"user": {"department": {"name": "finance"}}}

        result, eval_result = evaluator.evaluate_condition(condition, context)

        assert result is True
        assert eval_result.actual_value == "finance"

    def test_nonexistent_field_returns_none(
        self,
    ) -> None:
        evaluator = RuleConditionEvaluator()
        condition = {"field": "nonexistent", "operator": "equals", "value": "test"}
        context = {"existing": "value"}

        result, eval_result = evaluator.evaluate_condition(condition, context)

        assert result is False
        assert eval_result.actual_value is None

    def test_invalid_operator_falls_back_to_equals(
        self,
    ) -> None:
        evaluator = RuleConditionEvaluator()
        condition = {
            "field": "status",
            "operator": "invalid_operator",
            "value": "pending",
        }
        context = {"status": "pending"}

        result, eval_result = evaluator.evaluate_condition(condition, context)

        assert result is True

    def test_empty_conditions_returns_true(
        self,
    ) -> None:
        evaluator = RuleConditionEvaluator()

        result, results = evaluator.evaluate_conditions([], {})

        assert result is True
        assert len(results) == 0


class TestApprovalRuleResourceManagement:
    async def test_create_rule_with_valid_data(
        self,
        db_session: AsyncSession,
    ) -> None:
        rule_service = ApprovalRuleService(db_session)
        rule_data = ApprovalEngineDataFactory.create_rule_data()
        rule_data_obj = ApprovalRuleCreate(**rule_data)

        response = await rule_service.create_rule(rule_data_obj)
        await db_session.commit()

        assert response.rule_id is not None
        assert response.name == rule_data["name"]
        assert response.is_active is True
        assert len(response.conditions) == 1
        assert len(response.approvers) == 1

    @pytest.mark.parametrize(
        "scenario,expected_exception",
        [
            ("empty_name", ValidationError),
            ("missing_name", PydanticValidationError),
            ("empty_rule_type", ValidationError),
            ("missing_rule_type", PydanticValidationError),
            ("empty_approvers", ValidationError),
        ],
    )
    async def test_create_rule_with_invalid_parameters(
        self,
        db_session: AsyncSession,
        scenario: str,
        expected_exception: type,
    ) -> None:
        rule_service = ApprovalRuleService(db_session)
        invalid_data = ApprovalEngineDataFactory.create_invalid_rule_data(scenario)

        with pytest.raises(expected_exception):
            rule_data_obj = ApprovalRuleCreate(**invalid_data)
            await rule_service.create_rule(rule_data_obj)

    async def test_get_nonexistent_rule_should_fail(
        self,
        db_session: AsyncSession,
    ) -> None:
        rule_service = ApprovalRuleService(db_session)

        with pytest.raises(NotFoundError) as exc_info:
            await rule_service.get_rule("non_existent_rule")

        assert "不存在" in str(exc_info.value)

    async def test_list_rules_with_filters(
        self,
        db_session: AsyncSession,
    ) -> None:
        rule_service = ApprovalRuleService(db_session)

        for i in range(3):
            rule_data = ApprovalEngineDataFactory.create_rule_data(
                name=f"规则{i}",
                rule_type="expense",
            )
            rule_data_obj = ApprovalRuleCreate(**rule_data)
            await rule_service.create_rule(rule_data_obj)
        await db_session.commit()

        rules = await rule_service.list_rules(
            rule_type="expense",
            is_active=True,
        )

        assert len(rules) >= 3

    async def test_find_matching_rule(
        self,
        db_session: AsyncSession,
    ) -> None:
        rule_service = ApprovalRuleService(db_session)
        rule_data = ApprovalEngineDataFactory.create_rule_data(
            conditions=[
                {"field": "amount", "operator": "greater_than", "value": 1000}
            ],
        )
        rule_data_obj = ApprovalRuleCreate(**rule_data)
        await rule_service.create_rule(rule_data_obj)
        await db_session.commit()

        matching_rule = await rule_service.find_matching_rule(
            rule_type="expense",
            context={"amount": 2000},
        )

        assert matching_rule is not None

    async def test_no_matching_rule_returns_none(
        self,
        db_session: AsyncSession,
    ) -> None:
        rule_service = ApprovalRuleService(db_session)
        rule_data = ApprovalEngineDataFactory.create_rule_data(
            conditions=[
                {"field": "amount", "operator": "greater_than", "value": 10000}
            ],
        )
        rule_data_obj = ApprovalRuleCreate(**rule_data)
        await rule_service.create_rule(rule_data_obj)
        await db_session.commit()

        matching_rule = await rule_service.find_matching_rule(
            rule_type="expense",
            context={"amount": 2000},
        )

        assert matching_rule is None

    async def test_evaluate_rule_returns_complete_result(
        self,
        db_session: AsyncSession,
    ) -> None:
        rule_service = ApprovalRuleService(db_session)
        rule_data = ApprovalEngineDataFactory.create_rule_data(
            conditions=[
                {"field": "amount", "operator": "greater_than", "value": 1000}
            ],
        )
        rule_data_obj = ApprovalRuleCreate(**rule_data)
        rule = await rule_service.create_rule(rule_data_obj)
        await db_session.commit()

        result = await rule_service.evaluate_rule(
            rule_id=rule.rule_id,
            context={"amount": 2000},
        )

        assert result["is_match"] is True
        assert "condition_results" in result
        assert "approvers" in result
        assert len(result["condition_results"]) == 1


class TestApprovalProcessResourceRelease:
    async def test_start_process_with_valid_data(
        self,
        db_session: AsyncSession,
    ) -> None:
        process_service = ApprovalProcessService(db_session)
        process_data = ApprovalEngineDataFactory.create_process_data()
        process_data["approvers"] = [
            {"id": "usr_001", "type": "USER", "value": "usr_001", "name": "审批人1"},
            {"id": "usr_002", "type": "USER", "value": "usr_002", "name": "审批人2"},
        ]
        process_data_obj = ApprovalProcessCreate(**process_data)

        response = await process_service.start_process(process_data_obj)
        await db_session.commit()

        assert response.process_id is not None
        assert response.status == ApprovalStatus.PENDING
        assert response.total_steps == 1
        assert response.current_step == 0
        assert len(response.approvers) == 2

    async def test_start_process_without_approvers_should_fail(
        self,
        db_session: AsyncSession,
    ) -> None:
        process_service = ApprovalProcessService(db_session)
        process_data = ApprovalEngineDataFactory.create_process_data()
        process_data["approvers"] = []
        process_data_obj = ApprovalProcessCreate(**process_data)

        with pytest.raises(ValidationError) as exc_info:
            await process_service.start_process(process_data_obj)

        assert "无法确定审批人列表" in str(exc_info.value)

    async def test_execute_approve_action_completes_process(
        self,
        db_session: AsyncSession,
    ) -> None:
        process_service = ApprovalProcessService(db_session)
        process_data = ApprovalEngineDataFactory.create_process_data()
        process_data["approvers"] = [
            {"id": "usr_001", "type": "USER", "value": "usr_001", "name": "审批人1"},
        ]
        process_data["approval_type"] = ApprovalType.ALL.value
        process_data_obj = ApprovalProcessCreate(**process_data)

        process = await process_service.start_process(process_data_obj)
        await db_session.commit()

        action_data = ApprovalEngineDataFactory.create_action_data(
            action=ApprovalAction.APPROVE.value,
            approver_id="usr_001",
            comment="同意申请",
        )
        action_data["process_id"] = process.process_id
        action_data_obj = ApprovalActionRequest(**action_data)

        record = await process_service.execute_action(action_data_obj)
        await db_session.commit()

        assert record.approver_id == "usr_001"
        assert record.action == ApprovalAction.APPROVE

        updated_process = await process_service.get_process(process.process_id)
        assert updated_process.status == ApprovalStatus.APPROVED
        assert updated_process.completed_at is not None

    async def test_execute_reject_action_rejects_process(
        self,
        db_session: AsyncSession,
    ) -> None:
        process_service = ApprovalProcessService(db_session)
        process_data = ApprovalEngineDataFactory.create_process_data()
        process_data["approvers"] = [
            {"id": "usr_001", "type": "USER", "value": "usr_001", "name": "审批人1"},
            {"id": "usr_002", "type": "USER", "value": "usr_002", "name": "审批人2"},
        ]
        process_data["approval_type"] = ApprovalType.ALL.value
        process_data_obj = ApprovalProcessCreate(**process_data)

        process = await process_service.start_process(process_data_obj)
        await db_session.commit()

        action_data = ApprovalEngineDataFactory.create_action_data(
            action=ApprovalAction.REJECT.value,
            approver_id="usr_001",
            comment="拒绝申请",
        )
        action_data["process_id"] = process.process_id
        action_data_obj = ApprovalActionRequest(**action_data)

        await process_service.execute_action(action_data_obj)
        await db_session.commit()

        updated_process = await process_service.get_process(process.process_id)
        assert updated_process.status == ApprovalStatus.REJECTED
        assert updated_process.completed_at is not None

    async def test_execute_action_on_completed_process_should_fail(
        self,
        db_session: AsyncSession,
    ) -> None:
        process_service = ApprovalProcessService(db_session)
        process_data = ApprovalEngineDataFactory.create_process_data()
        process_data["approvers"] = [
            {"id": "usr_001", "type": "USER", "value": "usr_001", "name": "审批人1"},
        ]
        process_data_obj = ApprovalProcessCreate(**process_data)

        process = await process_service.start_process(process_data_obj)
        await db_session.commit()

        action_data = ApprovalEngineDataFactory.create_action_data(
            action=ApprovalAction.APPROVE.value,
            approver_id="usr_001",
        )
        action_data["process_id"] = process.process_id
        action_data_obj = ApprovalActionRequest(**action_data)

        await process_service.execute_action(action_data_obj)
        await db_session.commit()

        with pytest.raises(ConflictError) as exc_info:
            await process_service.execute_action(action_data_obj)

        assert "无法执行操作" in str(exc_info.value)

    async def test_execute_action_with_invalid_approver_should_fail(
        self,
        db_session: AsyncSession,
    ) -> None:
        process_service = ApprovalProcessService(db_session)
        process_data = ApprovalEngineDataFactory.create_process_data()
        process_data["approvers"] = [
            {"id": "usr_001", "type": "USER", "value": "usr_001", "name": "审批人1"},
        ]
        process_data_obj = ApprovalProcessCreate(**process_data)

        process = await process_service.start_process(process_data_obj)
        await db_session.commit()

        action_data = ApprovalEngineDataFactory.create_action_data(
            action=ApprovalAction.APPROVE.value,
            approver_id="invalid_user",
        )
        action_data["process_id"] = process.process_id
        action_data_obj = ApprovalActionRequest(**action_data)

        with pytest.raises(ValidationError) as exc_info:
            await process_service.execute_action(action_data_obj)

        assert "不是当前步骤的审批人" in str(exc_info.value)

    async def test_sequential_approval_process(
        self,
        db_session: AsyncSession,
    ) -> None:
        process_service = ApprovalProcessService(db_session)
        process_data = ApprovalEngineDataFactory.create_process_data()
        process_data["approvers"] = [
            {"id": "usr_001", "type": "USER", "value": "usr_001", "name": "审批人1"},
            {"id": "usr_002", "type": "USER", "value": "usr_002", "name": "审批人2"},
        ]
        process_data["approval_type"] = ApprovalType.SEQUENTIAL.value
        process_data_obj = ApprovalProcessCreate(**process_data)

        process = await process_service.start_process(process_data_obj)
        await db_session.commit()

        assert process.total_steps == 2
        assert process.current_step == 0

        action1 = ApprovalEngineDataFactory.create_action_data(
            action=ApprovalAction.APPROVE.value,
            approver_id="usr_001",
        )
        action1["process_id"] = process.process_id
        action1_obj = ApprovalActionRequest(**action1)
        await process_service.execute_action(action1_obj)
        await db_session.commit()

        process_after_step1 = await process_service.get_process(process.process_id)
        assert process_after_step1.current_step == 1
        assert process_after_step1.status == ApprovalStatus.PENDING

        action2 = ApprovalEngineDataFactory.create_action_data(
            action=ApprovalAction.APPROVE.value,
            approver_id="usr_002",
        )
        action2["process_id"] = process.process_id
        action2_obj = ApprovalActionRequest(**action2)
        await process_service.execute_action(action2_obj)
        await db_session.commit()

        process_complete = await process_service.get_process(process.process_id)
        assert process_complete.status == ApprovalStatus.APPROVED

    async def test_any_approval_strategy(
        self,
        db_session: AsyncSession,
    ) -> None:
        process_service = ApprovalProcessService(db_session)
        process_data = ApprovalEngineDataFactory.create_process_data()
        process_data["approvers"] = [
            {"id": "usr_001", "type": "USER", "value": "usr_001", "name": "审批人1"},
            {"id": "usr_002", "type": "USER", "value": "usr_002", "name": "审批人2"},
            {"id": "usr_003", "type": "USER", "value": "usr_003", "name": "审批人3"},
        ]
        process_data["approval_type"] = ApprovalType.ANY.value
        process_data_obj = ApprovalProcessCreate(**process_data)

        process = await process_service.start_process(process_data_obj)
        await db_session.commit()

        action = ApprovalEngineDataFactory.create_action_data(
            action=ApprovalAction.APPROVE.value,
            approver_id="usr_002",
        )
        action["process_id"] = process.process_id
        action_obj = ApprovalActionRequest(**action)
        await process_service.execute_action(action_obj)
        await db_session.commit()

        updated_process = await process_service.get_process(process.process_id)
        assert updated_process.status == ApprovalStatus.APPROVED

    async def test_get_process_records(
        self,
        db_session: AsyncSession,
    ) -> None:
        process_service = ApprovalProcessService(db_session)
        process_data = ApprovalEngineDataFactory.create_process_data()
        process_data["approvers"] = [
            {"id": "usr_001", "type": "USER", "value": "usr_001", "name": "审批人1"},
        ]
        process_data_obj = ApprovalProcessCreate(**process_data)

        process = await process_service.start_process(process_data_obj)
        await db_session.commit()

        action = ApprovalEngineDataFactory.create_action_data(
            action=ApprovalAction.APPROVE.value,
            approver_id="usr_001",
            comment="同意",
        )
        action["process_id"] = process.process_id
        action_obj = ApprovalActionRequest(**action)
        await process_service.execute_action(action_obj)
        await db_session.commit()

        records = await process_service.get_process_records(process.process_id)

        assert len(records) == 1
        assert records[0].approver_id == "usr_001"
        assert records[0].comment == "同意"

    async def test_list_processes_with_filters(
        self,
        db_session: AsyncSession,
    ) -> None:
        process_service = ApprovalProcessService(db_session)

        for i in range(3):
            process_data = ApprovalEngineDataFactory.create_process_data(
                entity_id=f"exp_00{i}",
                title=f"报销审批{i}",
            )
            process_data["approvers"] = [
                {"id": f"usr_00{i}", "type": "USER", "value": f"usr_00{i}", "name": f"审批人{i}"}
            ]
            process_data_obj = ApprovalProcessCreate(**process_data)
            await process_service.start_process(process_data_obj)
        await db_session.commit()

        processes = await process_service.list_processes(
            status=ApprovalStatus.PENDING,
        )

        assert len(processes) >= 3

    async def test_process_timeout_setting(
        self,
        db_session: AsyncSession,
    ) -> None:
        process_service = ApprovalProcessService(db_session)
        process_data = ApprovalEngineDataFactory.create_process_data()
        process_data["approvers"] = [
            {"id": "usr_001", "type": "USER", "value": "usr_001", "name": "审批人1"},
        ]
        process_data["timeout_seconds"] = 3600
        process_data_obj = ApprovalProcessCreate(**process_data)

        process = await process_service.start_process(process_data_obj)
        await db_session.commit()

        assert process.timeout_at is not None

    async def test_process_without_timeout(
        self,
        db_session: AsyncSession,
    ) -> None:
        process_service = ApprovalProcessService(db_session)
        process_data = ApprovalEngineDataFactory.create_process_data()
        process_data["approvers"] = [
            {"id": "usr_001", "type": "USER", "value": "usr_001", "name": "审批人1"},
        ]
        process_data["timeout_seconds"] = None
        process_data_obj = ApprovalProcessCreate(**process_data)

        process = await process_service.start_process(process_data_obj)
        await db_session.commit()

        assert process.timeout_at is None

    async def test_resource_cleanup_on_rejection(
        self,
        db_session: AsyncSession,
    ) -> None:
        process_service = ApprovalProcessService(db_session)
        process_data = ApprovalEngineDataFactory.create_process_data()
        process_data["approvers"] = [
            {"id": "usr_001", "type": "USER", "value": "usr_001", "name": "审批人1"},
            {"id": "usr_002", "type": "USER", "value": "usr_002", "name": "审批人2"},
        ]
        process_data["approval_type"] = ApprovalType.ALL.value
        process_data_obj = ApprovalProcessCreate(**process_data)

        process = await process_service.start_process(process_data_obj)
        await db_session.commit()

        action = ApprovalEngineDataFactory.create_action_data(
            action=ApprovalAction.REJECT.value,
            approver_id="usr_001",
            comment="资料不全，驳回",
        )
        action["process_id"] = process.process_id
        action_obj = ApprovalActionRequest(**action)
        await process_service.execute_action(action_obj)
        await db_session.commit()

        records = await process_service.get_process_records(process.process_id)
        assert len(records) == 1
        assert records[0].action == ApprovalAction.REJECT

        updated_process = await process_service.get_process(process.process_id)
        assert updated_process.status == ApprovalStatus.REJECTED
        assert updated_process.current_step == 0

    async def test_multiple_records_for_same_process(
        self,
        db_session: AsyncSession,
    ) -> None:
        process_service = ApprovalProcessService(db_session)
        process_data = ApprovalEngineDataFactory.create_process_data()
        process_data["approvers"] = [
            {"id": "usr_001", "type": "USER", "value": "usr_001", "name": "审批人1"},
            {"id": "usr_002", "type": "USER", "value": "usr_002", "name": "审批人2"},
        ]
        process_data["approval_type"] = ApprovalType.ALL.value
        process_data_obj = ApprovalProcessCreate(**process_data)

        process = await process_service.start_process(process_data_obj)
        await db_session.commit()

        action1 = ApprovalEngineDataFactory.create_action_data(
            action=ApprovalAction.COMMENT.value,
            approver_id="usr_001",
            comment="需要补充资料",
        )
        action1["process_id"] = process.process_id
        action1_obj = ApprovalActionRequest(**action1)
        await process_service.execute_action(action1_obj)
        await db_session.commit()

        action2 = ApprovalEngineDataFactory.create_action_data(
            action=ApprovalAction.APPROVE.value,
            approver_id="usr_001",
            comment="资料已补充，同意",
        )
        action2["process_id"] = process.process_id
        action2_obj = ApprovalActionRequest(**action2)
        await process_service.execute_action(action2_obj)
        await db_session.commit()

        records = await process_service.get_process_records(process.process_id)
        assert len(records) == 2
