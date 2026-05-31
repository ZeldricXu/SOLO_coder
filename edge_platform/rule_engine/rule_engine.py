import asyncio
import logging
import operator
import re
import uuid
from typing import Dict, List, Optional, Any, Callable
from dataclasses import dataclass, field
from datetime import datetime
from enum import Enum
import threading

from ..common.event_bus import EventBus, Event, event_bus
from ..common.config import config
from ..common.exceptions import RuleExecutionException, RuleEngineException

logger = logging.getLogger(__name__)


class RuleStatus(str, Enum):
    ACTIVE = "active"
    INACTIVE = "inactive"
    ERROR = "error"


class ConditionOperator(str, Enum):
    EQUALS = "=="
    NOT_EQUALS = "!="
    GREATER_THAN = ">"
    LESS_THAN = "<"
    GREATER_OR_EQUAL = ">="
    LESS_OR_EQUAL = "<="
    CONTAINS = "contains"
    NOT_CONTAINS = "not_contains"
    MATCHES = "matches"
    IN = "in"
    NOT_IN = "not_in"


class LogicalOperator(str, Enum):
    AND = "and"
    OR = "or"


class ActionType(str, Enum):
    SET_VALUE = "set_value"
    SEND_COMMAND = "send_command"
    TRIGGER_ALERT = "trigger_alert"
    CALL_WEBHOOK = "call_webhook"
    RUN_SCRIPT = "run_script"
    PUBLISH_EVENT = "publish_event"


@dataclass
class RuleCondition:
    field: str = ""
    operator: ConditionOperator = ConditionOperator.EQUALS
    value: Any = None
    logical_operator: LogicalOperator = LogicalOperator.AND


@dataclass
class RuleAction:
    action_type: ActionType = ActionType.SET_VALUE
    parameters: Dict[str, Any] = field(default_factory=dict)
    description: str = ""


@dataclass
class Rule:
    rule_id: str = field(default_factory=lambda: str(uuid.uuid4()))
    name: str = ""
    description: str = ""
    conditions: List[RuleCondition] = field(default_factory=list)
    actions: List[RuleAction] = field(default_factory=list)
    status: RuleStatus = RuleStatus.INACTIVE
    priority: int = 0
    trigger_count: int = 0
    last_triggered_at: Optional[datetime] = None
    cooldown_seconds: int = 0
    created_at: datetime = field(default_factory=datetime.now)
    updated_at: datetime = field(default_factory=datetime.now)
    tags: List[str] = field(default_factory=list)


class ConditionEvaluator:
    _operators = {
        ConditionOperator.EQUALS: operator.eq,
        ConditionOperator.NOT_EQUALS: operator.ne,
        ConditionOperator.GREATER_THAN: operator.gt,
        ConditionOperator.LESS_THAN: operator.lt,
        ConditionOperator.GREATER_OR_EQUAL: operator.ge,
        ConditionOperator.LESS_OR_EQUAL: operator.le,
    }

    @classmethod
    def evaluate(cls, condition: RuleCondition, data: Dict[str, Any]) -> bool:
        field_value = cls._get_nested_value(data, condition.field)

        if condition.operator in cls._operators:
            return cls._operators[condition.operator](field_value, condition.value)
        elif condition.operator == ConditionOperator.CONTAINS:
            return condition.value in str(field_value) if field_value else False
        elif condition.operator == ConditionOperator.NOT_CONTAINS:
            return condition.value not in str(field_value) if field_value else True
        elif condition.operator == ConditionOperator.MATCHES:
            return bool(re.match(str(condition.value), str(field_value))) if field_value else False
        elif condition.operator == ConditionOperator.IN:
            return field_value in (condition.value if isinstance(condition.value, list) else [])
        elif condition.operator == ConditionOperator.NOT_IN:
            return field_value not in (condition.value if isinstance(condition.value, list) else [])

        return False

    @classmethod
    def _get_nested_value(cls, data: Dict[str, Any], field_path: str) -> Any:
        keys = field_path.split(".")
        value = data
        for key in keys:
            if isinstance(value, dict) and key in value:
                value = value[key]
            else:
                return None
        return value


class RuleEngine:
    def __init__(self, event_bus_instance: Optional[EventBus] = None):
        self._event_bus = event_bus_instance or event_bus
        self._rules: Dict[str, Rule] = {}
        self._action_handlers: Dict[ActionType, Callable] = {}
        self._max_rules = config.get("rule_engine.max_rules", 1000)
        self._execution_timeout = config.get("rule_engine.execution_timeout_seconds", 10)
        self._context_data: Dict[str, Any] = {}
        self._last_trigger_times: Dict[str, datetime] = {}
        self._lock = threading.RLock()
        self._register_default_handlers()

    def _register_default_handlers(self) -> None:
        self._action_handlers[ActionType.SET_VALUE] = self._handle_set_value
        self._action_handlers[ActionType.PUBLISH_EVENT] = self._handle_publish_event
        self._action_handlers[ActionType.TRIGGER_ALERT] = self._handle_trigger_alert

    def register_action_handler(self, action_type: ActionType, handler: Callable) -> None:
        self._action_handlers[action_type] = handler
        logger.info(f"Registered handler for action type: {action_type}")

    def create_rule(
        self,
        name: str,
        conditions: List[RuleCondition],
        actions: List[RuleAction],
        description: str = "",
        priority: int = 0,
        cooldown_seconds: int = 0,
        tags: Optional[List[str]] = None
    ) -> Rule:
        with self._lock:
            if len(self._rules) >= self._max_rules:
                raise RuleEngineException(f"Maximum rules limit ({self._max_rules}) reached")

        rule = Rule(
            name=name,
            description=description,
            conditions=conditions,
            actions=actions,
            priority=priority,
            cooldown_seconds=cooldown_seconds,
            tags=tags or []
        )

        with self._lock:
            self._rules[rule.rule_id] = rule

        self._event_bus.publish(Event(
            event_type="rule.created",
            source="rule_engine",
            payload={"rule_id": rule.rule_id, "name": name}
        ))

        return rule

    def get_rule(self, rule_id: str) -> Rule:
        rule = self._rules.get(rule_id)
        if not rule:
            raise RuleEngineException(f"Rule {rule_id} not found")
        return rule

    def update_rule(
        self,
        rule_id: str,
        name: Optional[str] = None,
        description: Optional[str] = None,
        conditions: Optional[List[RuleCondition]] = None,
        actions: Optional[List[RuleAction]] = None,
        status: Optional[RuleStatus] = None,
        priority: Optional[int] = None,
        cooldown_seconds: Optional[int] = None,
        tags: Optional[List[str]] = None
    ) -> Rule:
        rule = self.get_rule(rule_id)

        if name is not None:
            rule.name = name
        if description is not None:
            rule.description = description
        if conditions is not None:
            rule.conditions = conditions
        if actions is not None:
            rule.actions = actions
        if status is not None:
            rule.status = status
        if priority is not None:
            rule.priority = priority
        if cooldown_seconds is not None:
            rule.cooldown_seconds = cooldown_seconds
        if tags is not None:
            rule.tags = tags

        rule.updated_at = datetime.now()

        self._event_bus.publish(Event(
            event_type="rule.updated",
            source="rule_engine",
            payload={"rule_id": rule_id}
        ))

        return rule

    def delete_rule(self, rule_id: str) -> None:
        if rule_id not in self._rules:
            raise RuleEngineException(f"Rule {rule_id} not found")

        with self._lock:
            del self._rules[rule_id]

        self._event_bus.publish(Event(
            event_type="rule.deleted",
            source="rule_engine",
            payload={"rule_id": rule_id}
        ))

    def enable_rule(self, rule_id: str) -> Rule:
        return self.update_rule(rule_id, status=RuleStatus.ACTIVE)

    def disable_rule(self, rule_id: str) -> Rule:
        return self.update_rule(rule_id, status=RuleStatus.INACTIVE)

    def list_rules(
        self,
        status: Optional[RuleStatus] = None,
        tag: Optional[str] = None,
        limit: int = 100
    ) -> List[Rule]:
        with self._lock:
            rules = list(self._rules.values())

        if status:
            rules = [r for r in rules if r.status == status]
        if tag:
            rules = [r for r in rules if tag in r.tags]

        rules.sort(key=lambda r: (r.priority, r.created_at), reverse=True)
        return rules[:limit]

    def evaluate_conditions(self, rule: Rule, data: Dict[str, Any]) -> bool:
        if not rule.conditions:
            return True

        results = []
        for i, condition in enumerate(rule.conditions):
            result = ConditionEvaluator.evaluate(condition, data)
            results.append((condition.logical_operator, result))

        if not results:
            return True

        final_result = results[0][1]
        for i in range(1, len(results)):
            logical_op, result = results[i]
            if logical_op == LogicalOperator.AND:
                final_result = final_result and result
            else:
                final_result = final_result or result

        return final_result

    async def execute_actions(self, rule: Rule, data: Dict[str, Any]) -> List[Any]:
        results = []

        for action in rule.actions:
            handler = self._action_handlers.get(action.action_type)
            if handler:
                try:
                    if asyncio.iscoroutinefunction(handler):
                        result = await asyncio.wait_for(
                            handler(action.parameters, data),
                            timeout=self._execution_timeout
                        )
                    else:
                        result = handler(action.parameters, data)
                    results.append(result)
                except Exception as e:
                    logger.error(f"Error executing action {action.action_type}: {e}")
                    raise RuleExecutionException(
                        f"Action {action.action_type} failed: {e}"
                    )
            else:
                logger.warning(f"No handler for action type: {action.action_type}")

        return results

    async def process_event(self, event_data: Dict[str, Any]) -> List[Dict[str, Any]]:
        triggered_rules = []

        with self._lock:
            active_rules = [
                r for r in self._rules.values()
                if r.status == RuleStatus.ACTIVE
            ]
            active_rules.sort(key=lambda r: r.priority, reverse=True)

        for rule in active_rules:
            try:
                if self._check_cooldown(rule):
                    continue

                if self.evaluate_conditions(rule, event_data):
                    await self.execute_actions(rule, event_data)

                    rule.trigger_count += 1
                    rule.last_triggered_at = datetime.now()
                    self._last_trigger_times[rule.rule_id] = datetime.now()

                    triggered_rules.append({
                        "rule_id": rule.rule_id,
                        "rule_name": rule.name,
                        "triggered_at": rule.last_triggered_at
                    })

                    self._event_bus.publish(Event(
                        event_type="rule.triggered",
                        source="rule_engine",
                        payload={
                            "rule_id": rule.rule_id,
                            "rule_name": rule.name,
                            "data": event_data
                        }
                    ))

            except Exception as e:
                rule.status = RuleStatus.ERROR
                logger.error(f"Error processing rule {rule.rule_id}: {e}")

        return triggered_rules

    def _check_cooldown(self, rule: Rule) -> bool:
        if rule.cooldown_seconds <= 0:
            return False

        last_triggered = self._last_trigger_times.get(rule.rule_id)
        if not last_triggered:
            return False

        elapsed = (datetime.now() - last_triggered).total_seconds()
        return elapsed < rule.cooldown_seconds

    async def _handle_set_value(
        self,
        params: Dict[str, Any],
        context: Dict[str, Any]
    ) -> None:
        field = params.get("field")
        value = params.get("value")
        if field:
            self._context_data[field] = value
            logger.debug(f"Set context field {field} = {value}")

    async def _handle_publish_event(
        self,
        params: Dict[str, Any],
        context: Dict[str, Any]
    ) -> None:
        event_type = params.get("event_type", "custom_event")
        payload = params.get("payload", {})
        self._event_bus.publish(Event(
            event_type=event_type,
            source="rule_engine",
            payload=payload
        ))

    async def _handle_trigger_alert(
        self,
        params: Dict[str, Any],
        context: Dict[str, Any]
    ) -> None:
        alert_level = params.get("level", "warning")
        message = params.get("message", "")
        logger.info(f"Alert [{alert_level}]: {message}")

    def set_context(self, key: str, value: Any) -> None:
        self._context_data[key] = value

    def get_context(self, key: str, default: Any = None) -> Any:
        return self._context_data.get(key, default)

    def get_stats(self) -> Dict[str, Any]:
        with self._lock:
            total = len(self._rules)
            active = sum(1 for r in self._rules.values() if r.status == RuleStatus.ACTIVE)
            inactive = sum(1 for r in self._rules.values() if r.status == RuleStatus.INACTIVE)
            error = sum(1 for r in self._rules.values() if r.status == RuleStatus.ERROR)
            total_triggers = sum(r.trigger_count for r in self._rules.values())

        return {
            "total_rules": total,
            "active_rules": active,
            "inactive_rules": inactive,
            "error_rules": error,
            "total_triggers": total_triggers
        }
