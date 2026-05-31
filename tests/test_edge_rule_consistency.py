import pytest
import asyncio
from unittest.mock import patch, MagicMock, AsyncMock
from typing import Dict, Any, List

from modules.edge_rule_engine.engine import (
    ConditionEvaluator,
    ActionExecutor,
    EdgeRuleEngine,
)
from modules.edge_rule_engine.service import RuleService
from tests.builders import RuleBuilder


class TestConditionEvaluatorConsistency:
    @pytest.mark.parametrize(
        "field, operator, value, data, expected",
        [
            ("temperature", "gt", 30, {"temperature": 35}, True),
            ("temperature", "gt", 30, {"temperature": 25}, False),
            ("temperature", "gte", 30, {"temperature": 30}, True),
            ("temperature", "lt", 30, {"temperature": 25}, True),
            ("temperature", "lte", 30, {"temperature": 30}, True),
            ("temperature", "eq", 30, {"temperature": 30}, True),
            ("temperature", "ne", 30, {"temperature": 25}, True),
            ("status", "contains", "active", {"status": "is_active_now"}, True),
            ("role", "in", ["admin", "user"], {"role": "admin"}, True),
            ("name", "starts_with", "temp", {"name": "temperature_sensor"}, True),
            ("name", "ends_with", "sensor", {"name": "temperature_sensor"}, True),
            ("value", "is_empty", None, {"value": ""}, True),
            ("value", "is_not_empty", None, {"value": "data"}, True),
        ],
    )
    def test_basic_operators_consistency(
        self, field: str, operator: str, value: Any, data: Dict[str, Any], expected: bool
    ):
        condition = {"field": field, "operator": operator, "value": value}
        result = ConditionEvaluator.evaluate(condition, data)
        assert result == expected

    def test_nested_field_access_consistency(self):
        data = {"device": {"sensors": {"temperature": 35}}}
        condition = {"field": "device.sensors.temperature", "operator": "gt", "value": 30}
        result = ConditionEvaluator.evaluate(condition, data)
        assert result is True

    def test_unknown_operator_returns_false(self):
        condition = {"field": "value", "operator": "unknown_op", "value": 100}
        result = ConditionEvaluator.evaluate(condition, {"value": 50})
        assert result is False

    def test_nonexistent_field_returns_false(self):
        condition = {"field": "nonexistent", "operator": "eq", "value": "test"}
        result = ConditionEvaluator.evaluate(condition, {"existing": "data"})
        assert result is False

    def test_exception_in_operator_returns_false(self):
        condition = {"field": "value", "operator": "gt", "value": "not_a_number"}
        result = ConditionEvaluator.evaluate(condition, {"value": 100})
        assert result is False

    def test_evaluator_idempotency(self):
        condition = {"field": "temperature", "operator": "gt", "value": 30}
        data = {"temperature": 35}
        results = [ConditionEvaluator.evaluate(condition, data) for _ in range(100)]
        assert all(r is True for r in results)


class TestActionExecutorConsistency:
    @pytest.mark.asyncio
    async def test_register_and_execute_action(self):
        executor = ActionExecutor()
        mock_handler = AsyncMock(return_value={"status": "ok"})
        await executor.register_handler("test_action", mock_handler)

        action = {"action_type": "test_action", "parameters": {"key": "value"}}
        context = {"device_id": "dev_001"}

        result = await executor.execute(action, context)

        assert result["success"] is True
        assert result["action_type"] == "test_action"
        mock_handler.assert_called_once_with({"key": "value"}, context)

    @pytest.mark.asyncio
    async def test_unknown_action_type_returns_error(self):
        executor = ActionExecutor()
        action = {"action_type": "nonexistent", "parameters": {}}
        result = await executor.execute(action, {})

        assert result["success"] is False
        assert "Unknown action type" in result["error"]

    @pytest.mark.asyncio
    async def test_action_exception_handling(self):
        executor = ActionExecutor()
        async def failing_handler(params, context):
            raise ValueError("Test error")
        await executor.register_handler("failing", failing_handler)

        action = {"action_type": "failing", "parameters": {}}
        result = await executor.execute(action, {})

        assert result["success"] is False
        assert "Test error" in result["error"]

    @pytest.mark.asyncio
    async def test_sync_action_handler(self):
        executor = ActionExecutor()
        def sync_handler(params, context):
            return {"sync": True}
        await executor.register_handler("sync_action", sync_handler)

        action = {"action_type": "sync_action", "parameters": {}}
        result = await executor.execute(action, {})

        assert result["success"] is True
        assert result["result"] == {"sync": True}

    @pytest.mark.asyncio
    async def test_action_execution_idempotency(self):
        executor = ActionExecutor()
        call_count = 0
        async def handler(params, context):
            nonlocal call_count
            call_count += 1
            return {"call": call_count}
        await executor.register_handler("count_action", handler)

        action = {"action_type": "count_action", "parameters": {}}
        for i in range(5):
            result = await executor.execute(action, {})
            assert result["success"] is True
            assert result["result"]["call"] == i + 1
        assert call_count == 5


class TestEdgeRuleEngineConsistency:
    @pytest.fixture
    def engine(self) -> EdgeRuleEngine:
        engine = EdgeRuleEngine()
        return engine

    @pytest.mark.asyncio
    async def test_register_and_unregister_rule(self, engine: EdgeRuleEngine):
        await engine.initialize()
        rule = RuleBuilder().with_rule_id("test_rule_001").build()
        await engine.register_rule(rule)

        assert "test_rule_001" in engine._rules
        rules = await engine.get_registered_rules()
        assert rules[0]["rule_id"] == "test_rule_001"

        await engine.unregister_rule("test_rule_001")
        assert "test_rule_001" not in engine._rules

    @pytest.mark.asyncio
    async def test_register_duplicate_rule_overwrites(self, engine: EdgeRuleEngine):
        await engine.initialize()
        rule1 = RuleBuilder().with_rule_id("dup_rule").with_name("Rule 1").build()
        rule2 = RuleBuilder().with_rule_id("dup_rule").with_name("Rule 2").build()

        await engine.register_rule(rule1)
        await engine.register_rule(rule2)

        assert len(engine._rules) == 1
        assert engine._rules["dup_rule"]["name"] == "Rule 2"

    @pytest.mark.asyncio
    async def test_execute_nonexistent_rule(self, engine: EdgeRuleEngine):
        await engine.initialize()
        result = await engine.execute_rule("nonexistent", {"data": "test"})

        assert result["rule_id"] == "nonexistent"
        assert result["triggered"] is False
        assert result["success"] is False
        assert "Rule not found" in result["error"]

    @pytest.mark.asyncio
    async def test_execute_disabled_rule(self, engine: EdgeRuleEngine):
        await engine.initialize()
        rule = RuleBuilder().with_rule_id("disabled_rule").with_enabled(False).build()
        await engine.register_rule(rule)

        result = await engine.execute_rule("disabled_rule", {"data": "test"})

        assert result["triggered"] is False
        assert result["success"] is True

    @pytest.mark.asyncio
    async def test_rule_triggered_and_actions_executed(self, engine: EdgeRuleEngine):
        await engine.initialize()
        rule = (
            RuleBuilder()
            .with_rule_id("trigger_rule")
            .with_condition("temperature", "gt", 30)
            .with_action("log_message", {"message": "High temp"})
            .build()
        )
        await engine.register_rule(rule)

        result = await engine.execute_rule("trigger_rule", {"temperature": 35})

        assert result["rule_id"] == "trigger_rule"
        assert result["triggered"] is True
        assert len(result["actions_executed"]) == 1
        assert result["success"] is True
        assert "execution_time_ms" in result

    @pytest.mark.asyncio
    async def test_rule_not_triggered(self, engine: EdgeRuleEngine):
        await engine.initialize()
        rule = (
            RuleBuilder()
            .with_rule_id("no_trigger_rule")
            .with_condition("temperature", "gt", 30)
            .with_action("log_message", {"message": "High temp"})
            .build()
        )
        await engine.register_rule(rule)

        result = await engine.execute_rule("no_trigger_rule", {"temperature": 25})

        assert result["triggered"] is False
        assert len(result["actions_executed"]) == 0

    @pytest.mark.asyncio
    async def test_multiple_conditions_all_must_match(self, engine: EdgeRuleEngine):
        await engine.initialize()
        rule = (
            RuleBuilder()
            .with_rule_id("multi_cond_rule")
            .with_condition("temperature", "gt", 30)
            .with_condition("humidity", "lt", 50)
            .with_action("log_message", {})
            .build()
        )
        await engine.register_rule(rule)

        result1 = await engine.execute_rule(
            "multi_cond_rule", {"temperature": 35, "humidity": 40}
        )
        assert result1["triggered"] is True

        result2 = await engine.execute_rule(
            "multi_cond_rule", {"temperature": 35, "humidity": 60}
        )
        assert result2["triggered"] is False

    @pytest.mark.asyncio
    async def test_rule_priority_ordering(self, engine: EdgeRuleEngine):
        await engine.initialize()
        rule_low = RuleBuilder().with_rule_id("low_prio").with_priority(10).with_condition("value", "gt", 0).with_action("log_message", {}).build()
        rule_high = RuleBuilder().with_rule_id("high_prio").with_priority(100).with_condition("value", "gt", 0).with_action("log_message", {}).build()
        rule_medium = RuleBuilder().with_rule_id("med_prio").with_priority(50).with_condition("value", "gt", 0).with_action("log_message", {}).build()

        await engine.register_rule(rule_low)
        await engine.register_rule(rule_high)
        await engine.register_rule(rule_medium)

        results = await engine.process_data({"value": 10})
        rule_ids = [r["rule_id"] for r in results]

        assert rule_ids.index("high_prio") < rule_ids.index("med_prio")
        assert rule_ids.index("med_prio") < rule_ids.index("low_prio")

    @pytest.mark.asyncio
    async def test_concurrent_rule_execution_consistency(self, engine: EdgeRuleEngine):
        await engine.initialize()
        rule = (
            RuleBuilder()
            .with_rule_id("concurrent_rule")
            .with_condition("value", "gt", 0)
            .with_action("log_message", {})
            .build()
        )
        await engine.register_rule(rule)

        async def execute_once():
            return await engine.execute_rule("concurrent_rule", {"value": 10})

        tasks = [execute_once() for _ in range(20)]
        results = await asyncio.gather(*tasks)

        assert len(results) == 20
        for result in results:
            assert result["triggered"] is True
            assert result["success"] is True

    @pytest.mark.asyncio
    async def test_concurrent_register_and_execute_no_race_condition(self, engine: EdgeRuleEngine):
        await engine.initialize()
        
        async def register_rules():
            for i in range(10):
                rule = RuleBuilder().with_rule_id(f"concurrent_rule_{i}").with_condition("value", "gt", 0).with_action("log_message", {}).build()
                await engine.register_rule(rule)
                await asyncio.sleep(0.001)
        
        async def execute_rules():
            for i in range(10):
                await engine.execute_rule(f"concurrent_rule_{i}", {"value": 10})
                await asyncio.sleep(0.001)
        
        tasks = [register_rules(), execute_rules(), register_rules(), execute_rules()]
        await asyncio.gather(*tasks)
        
        rules = await engine.get_registered_rules()
        assert len(rules) >= 10

    @pytest.mark.asyncio
    async def test_concurrent_unregister_during_execution(self, engine: EdgeRuleEngine):
        await engine.initialize()
        for i in range(20):
            rule = RuleBuilder().with_rule_id(f"rule_{i}").with_condition("value", "gt", 0).with_action("log_message", {}).build()
            await engine.register_rule(rule)
        
        async def unregister_rules():
            for i in range(10):
                await engine.unregister_rule(f"rule_{i}")
                await asyncio.sleep(0.001)
        
        async def execute_rules():
            results = []
            for i in range(20):
                result = await engine.execute_rule(f"rule_{i}", {"value": 10})
                results.append(result)
                await asyncio.sleep(0.001)
            return results
        
        _, results = await asyncio.gather(unregister_rules(), execute_rules())
        
        assert len(results) == 20
        triggered_count = sum(1 for r in results if r["triggered"])
        assert triggered_count >= 10

    @pytest.mark.asyncio
    async def test_empty_conditions_always_trigger(self, engine: EdgeRuleEngine):
        await engine.initialize()
        rule = RuleBuilder().with_rule_id("empty_cond_rule").with_conditions([]).with_action("log_message", {}).build()
        await engine.register_rule(rule)

        result = await engine.execute_rule("empty_cond_rule", {})
        assert result["triggered"] is True

    @pytest.mark.asyncio
    async def test_action_failure_does_not_affect_other_actions(self, engine: EdgeRuleEngine):
        await engine.initialize()
        executor = ActionExecutor()
        async def good_handler(params, context):
            return {"status": "good"}
        async def bad_handler(params, context):
            raise ValueError("Bad handler")
        await executor.register_handler("good", good_handler)
        await executor.register_handler("bad", bad_handler)

        engine._action_executor = executor

        rule = (
            RuleBuilder()
            .with_rule_id("mixed_actions")
            .with_condition("value", "gt", 0)
            .with_actions([
                {"action_type": "good", "parameters": {}},
                {"action_type": "bad", "parameters": {}},
                {"action_type": "good", "parameters": {}},
            ])
            .build()
        )
        await engine.register_rule(rule)

        result = await engine.execute_rule("mixed_actions", {"value": 10})

        assert result["triggered"] is True
        assert len(result["actions_executed"]) == 3
        assert result["actions_executed"][0]["success"] is True
        assert result["actions_executed"][1]["success"] is False
        assert result["actions_executed"][2]["success"] is True
        assert result["success"] is False


class TestRuleServiceConsistency:
    @pytest.fixture
    def mock_db(self):
        db = AsyncMock()
        db.flush = AsyncMock()
        db.commit = AsyncMock()
        db.delete = AsyncMock()
        return db

    @pytest.fixture
    def rule_service(self, mock_db):
        service = RuleService(mock_db)
        return service

    @pytest.mark.asyncio
    async def test_create_rule_registers_in_engine(self, rule_service: RuleService, mock_db):
        from modules.edge_rule_engine.schemas import RuleCreate

        await rule_service._ensure_engine_initialized()
        rule_data = RuleCreate(
            name="Test Rule",
            trigger_type="data_ingestion",
            conditions=[{"field": "temp", "operator": "gt", "value": 30}],
            actions=[{"action_type": "log_message", "parameters": {}}],
            enabled=True,
            priority=10,
        )

        mock_rule = MagicMock()
        mock_rule.id = "rule_001"
        mock_rule.to_dict = MagicMock(return_value={"rule_id": "rule_001", "enabled": True})
        mock_db.add = MagicMock()
        mock_db.flush = AsyncMock()
        type(mock_db).execute = AsyncMock(return_value=MagicMock(scalar_one_or_none=MagicMock(return_value=None)))

        with patch.object(rule_service.repository, "create", return_value=mock_rule):
            result = await rule_service.create_rule(rule_data)

        assert "rule_001" in rule_service.engine._rules

    @pytest.mark.asyncio
    async def test_delete_rule_unregisters_from_engine(self, rule_service: RuleService, mock_db):
        await rule_service._ensure_engine_initialized()
        mock_rule = MagicMock()
        mock_rule.id = "rule_to_delete"

        with patch.object(rule_service.repository, "get_by_id", return_value=mock_rule):
            with patch.object(rule_service.repository, "delete", AsyncMock()):
                await rule_service.engine.register_rule({"rule_id": "rule_to_delete", "enabled": True})
                assert "rule_to_delete" in rule_service.engine._rules

                await rule_service.delete_rule("rule_to_delete")

                assert "rule_to_delete" not in rule_service.engine._rules

    @pytest.mark.asyncio
    async def test_get_nonexistent_rule_raises_error(self, rule_service: RuleService, mock_db):
        from core import NotFoundError

        await rule_service._ensure_engine_initialized()
        with patch.object(rule_service.repository, "get_by_id", return_value=None):
            with pytest.raises(NotFoundError):
                await rule_service.get_rule("nonexistent")

    @pytest.mark.asyncio
    async def test_update_rule_updates_engine(self, rule_service: RuleService, mock_db):
        from modules.edge_rule_engine.schemas import RuleUpdate

        await rule_service._ensure_engine_initialized()
        mock_rule = MagicMock()
        mock_rule.id = "rule_update"
        mock_rule.to_dict = MagicMock(return_value={"rule_id": "rule_update", "enabled": False})

        await rule_service.engine.register_rule({"rule_id": "rule_update", "enabled": True})

        with patch.object(rule_service.repository, "get_by_id", return_value=mock_rule):
            with patch.object(rule_service.repository, "update", return_value=mock_rule):
                update_data = RuleUpdate(enabled=False)
                await rule_service.update_rule("rule_update", update_data)

                updated_rule = rule_service.engine._rules["rule_update"]
                assert updated_rule["enabled"] is False


class TestConditionEvaluatorEdgeCases:
    def test_get_nested_value_with_none(self):
        result = ConditionEvaluator._get_nested_value({}, "nonexistent")
        assert result is None

    def test_get_nested_value_with_non_dict(self):
        result = ConditionEvaluator._get_nested_value({"key": "string"}, "key.nested")
        assert result is None

    def test_evaluate_with_missing_field(self):
        condition = {"operator": "gt", "value": 10}
        result = ConditionEvaluator.evaluate(condition, {"value": 20})
        assert result is False

    def test_evaluate_with_missing_operator(self):
        condition = {"field": "value", "value": 10}
        result = ConditionEvaluator.evaluate(condition, {"value": 20})
        assert result is False


class TestActionExecutorEdgeCases:
    @pytest.mark.asyncio
    async def test_execute_with_missing_action_type(self):
        executor = ActionExecutor()
        action = {"parameters": {}}
        result = await executor.execute(action, {})
        assert result["success"] is False
        assert "Unknown action type" in result["error"]

    @pytest.mark.asyncio
    async def test_execute_with_sync_handler_returning_value(self):
        executor = ActionExecutor()
        def sync_handler(params, context):
            return {"result": "sync_value"}
        await executor.register_handler("sync_test", sync_handler)

        result = await executor.execute({"action_type": "sync_test", "parameters": {}}, {})
        assert result["success"] is True
        assert result["result"] == {"result": "sync_value"}


class TestEdgeRuleEngineEdgeCases:
    @pytest.fixture
    def engine(self) -> EdgeRuleEngine:
        engine = EdgeRuleEngine()
        return engine

    @pytest.mark.asyncio
    async def test_register_rule_without_id(self, engine: EdgeRuleEngine):
        await engine.initialize()
        rule = {"name": "No ID Rule", "enabled": True}
        await engine.register_rule(rule)
        assert len(engine._rules) == 0

    @pytest.mark.asyncio
    async def test_unregister_nonexistent_rule(self, engine: EdgeRuleEngine):
        await engine.initialize()
        await engine.unregister_rule("nonexistent")

    @pytest.mark.asyncio
    async def test_process_data_with_no_enabled_rules(self, engine: EdgeRuleEngine):
        await engine.initialize()
        rule = RuleBuilder().with_rule_id("disabled_rule").with_enabled(False).build()
        await engine.register_rule(rule)

        results = await engine.process_data({"value": 10})
        assert len(results) == 0

    @pytest.mark.asyncio
    async def test_process_data_with_no_rules(self, engine: EdgeRuleEngine):
        await engine.initialize()
        results = await engine.process_data({"value": 10})
        assert len(results) == 0

    @pytest.mark.asyncio
    async def test_execute_rule_with_none_context(self, engine: EdgeRuleEngine):
        await engine.initialize()
        rule = (
            RuleBuilder()
            .with_rule_id("context_test")
            .with_condition("value", "gt", 0)
            .with_action("log_message", {})
            .build()
        )
        await engine.register_rule(rule)

        result = await engine.execute_rule("context_test", {"value": 10}, None)
        assert result["triggered"] is True

    @pytest.mark.asyncio
    async def test_execute_rule_with_custom_context(self, engine: EdgeRuleEngine):
        await engine.initialize()
        rule = (
            RuleBuilder()
            .with_rule_id("custom_context")
            .with_condition("value", "gt", 0)
            .with_action("log_message", {})
            .build()
        )
        await engine.register_rule(rule)

        context = {"trace_id": "trace_123", "device_id": "dev_001"}
        result = await engine.execute_rule("custom_context", {"value": 10}, context)
        assert result["triggered"] is True

    @pytest.mark.asyncio
    async def test_get_registered_rules_empty(self, engine: EdgeRuleEngine):
        await engine.initialize()
        rules = await engine.get_registered_rules()
        assert isinstance(rules, list)
        assert len(rules) == 0

    @pytest.mark.asyncio
    async def test_get_registered_rules(self, engine: EdgeRuleEngine):
        await engine.initialize()
        rule1 = RuleBuilder().with_rule_id("rule1").build()
        rule2 = RuleBuilder().with_rule_id("rule2").build()
        await engine.register_rule(rule1)
        await engine.register_rule(rule2)

        rules = await engine.get_registered_rules()
        assert len(rules) == 2
        rule_ids = [r["rule_id"] for r in rules]
        assert "rule1" in rule_ids
        assert "rule2" in rule_ids
