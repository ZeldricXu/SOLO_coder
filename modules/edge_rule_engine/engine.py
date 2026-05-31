import asyncio
import time
import operator
from datetime import datetime
from typing import Any, Callable, Dict, List, Optional, Tuple
from core import emit_event, EventTypes


class ConditionEvaluator:
    OPERATORS = {
        "eq": operator.eq,
        "ne": operator.ne,
        "gt": operator.gt,
        "lt": operator.lt,
        "gte": operator.ge,
        "lte": operator.le,
        "contains": lambda a, b: b in a,
        "not_contains": lambda a, b: b not in a,
        "in": lambda a, b: a in b,
        "not_in": lambda a, b: a not in b,
        "starts_with": lambda a, b: isinstance(a, str) and a.startswith(b),
        "ends_with": lambda a, b: isinstance(a, str) and a.endswith(b),
        "is_empty": lambda a, _: not a,
        "is_not_empty": lambda a, _: bool(a),
    }

    @classmethod
    def evaluate(cls, condition: Dict[str, Any], data: Dict[str, Any]) -> bool:
        field = condition.get("field")
        op = condition.get("operator")
        expected_value = condition.get("value")

        if field is None or op is None:
            return False

        actual_value = cls._get_nested_value(data, field)
        operator_func = cls.OPERATORS.get(op)

        if operator_func is None:
            return False

        try:
            return operator_func(actual_value, expected_value)
        except Exception:
            return False

    @staticmethod
    def _get_nested_value(data: Dict[str, Any], path: str) -> Any:
        keys = path.split(".")
        value = data
        for key in keys:
            if isinstance(value, dict) and key in value:
                value = value[key]
            else:
                return None
        return value


class ActionExecutor:
    def __init__(self):
        self._action_handlers: Dict[str, Callable] = {}
        self._lock = asyncio.Lock()

    async def register_handler(self, action_type: str, handler: Callable) -> None:
        async with self._lock:
            self._action_handlers[action_type] = handler

    async def execute(self, action: Dict[str, Any], context: Dict[str, Any]) -> Dict[str, Any]:
        action_type = action.get("action_type")
        parameters = action.get("parameters", {})

        async with self._lock:
            handler = self._action_handlers.get(action_type)

        if handler is None:
            return {
                "action_type": action_type,
                "success": False,
                "error": f"Unknown action type: {action_type}",
            }

        try:
            if asyncio.iscoroutinefunction(handler):
                result = await handler(parameters, context)
            else:
                result = handler(parameters, context)

            return {
                "action_type": action_type,
                "success": True,
                "result": result,
            }
        except Exception as e:
            return {
                "action_type": action_type,
                "success": False,
                "error": str(e),
            }


class EdgeRuleEngine:
    def __init__(self):
        self._rules: Dict[str, Dict[str, Any]] = {}
        self._action_executor = ActionExecutor()
        self._condition_evaluator = ConditionEvaluator()
        self._lock = asyncio.Lock()
        self._initialized = False

    async def initialize(self) -> None:
        if not self._initialized:
            await self._register_default_actions()
            self._initialized = True

    async def _register_default_actions(self) -> None:
        async def send_alert(params: Dict, context: Dict) -> Dict:
            emit_event(
                EventTypes.ALERT_TRIGGERED,
                "edge_rule_engine",
                {"alert": params, "context": context},
            )
            return {"alert_sent": True}

        async def set_device_state(params: Dict, context: Dict) -> Dict:
            device_id = params.get("device_id") or context.get("device_id")
            state = params.get("state")
            return {"device_id": device_id, "state_set": state}

        async def log_message(params: Dict, context: Dict) -> Dict:
            message = params.get("message", "")
            print(f"[Rule Log] {message}")
            return {"logged": True}

        await self._action_executor.register_handler("send_alert", send_alert)
        await self._action_executor.register_handler("set_device_state", set_device_state)
        await self._action_executor.register_handler("log_message", log_message)

    async def register_rule(self, rule: Dict[str, Any]) -> None:
        rule_id = rule.get("rule_id") or rule.get("id")
        if rule_id:
            async with self._lock:
                self._rules[rule_id] = rule

    async def unregister_rule(self, rule_id: str) -> None:
        async with self._lock:
            if rule_id in self._rules:
                del self._rules[rule_id]

    def evaluate_conditions(self, conditions: List[Dict], data: Dict) -> bool:
        if not conditions:
            return True

        for condition in conditions:
            if not self._condition_evaluator.evaluate(condition, data):
                return False

        return True

    async def execute_rule(
        self,
        rule_id: str,
        input_data: Dict[str, Any],
        context: Optional[Dict[str, Any]] = None,
    ) -> Dict[str, Any]:
        start_time = time.time()
        context = context or {}

        async with self._lock:
            rule = self._rules.get(rule_id)

        if rule is None:
            return {
                "rule_id": rule_id,
                "triggered": False,
                "actions_executed": [],
                "success": False,
                "error": f"Rule not found: {rule_id}",
                "execution_time_ms": (time.time() - start_time) * 1000,
                "timestamp": datetime.utcnow(),
            }

        if not rule.get("enabled", True):
            return {
                "rule_id": rule_id,
                "triggered": False,
                "actions_executed": [],
                "success": True,
                "error": None,
                "execution_time_ms": (time.time() - start_time) * 1000,
                "timestamp": datetime.utcnow(),
            }

        conditions = rule.get("conditions", [])
        triggered = self.evaluate_conditions(conditions, input_data)

        if not triggered:
            return {
                "rule_id": rule_id,
                "triggered": False,
                "actions_executed": [],
                "success": True,
                "error": None,
                "execution_time_ms": (time.time() - start_time) * 1000,
                "timestamp": datetime.utcnow(),
            }

        actions = rule.get("actions", [])
        execution_context = {**context, "input_data": input_data, "rule": rule}

        actions_executed = []
        for action in actions:
            result = await self._action_executor.execute(action, execution_context)
            actions_executed.append(result)

        emit_event(
            EventTypes.RULE_EXECUTED,
            "edge_rule_engine",
            {
                "rule_id": rule_id,
                "triggered": True,
                "actions_count": len(actions_executed),
            },
            context.get("trace_id"),
        )

        return {
            "rule_id": rule_id,
            "triggered": True,
            "actions_executed": actions_executed,
            "success": all(a.get("success", False) for a in actions_executed),
            "error": None,
            "execution_time_ms": (time.time() - start_time) * 1000,
            "timestamp": datetime.utcnow(),
        }

    async def process_data(
        self,
        input_data: Dict[str, Any],
        context: Optional[Dict[str, Any]] = None,
    ) -> List[Dict[str, Any]]:
        results = []

        async with self._lock:
            enabled_rules = [
                rule for rule in self._rules.values()
                if rule.get("enabled", True)
            ]

        sorted_rules = sorted(
            enabled_rules,
            key=lambda r: r.get("priority", 0),
            reverse=True,
        )

        for rule in sorted_rules:
            rule_id = rule.get("rule_id") or rule.get("id")
            result = await self.execute_rule(rule_id, input_data, context)
            if result.get("triggered"):
                results.append(result)

        return results

    async def get_registered_rules(self) -> List[Dict[str, Any]]:
        async with self._lock:
            return list(self._rules.values())
