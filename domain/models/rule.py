from enum import Enum
from datetime import datetime
from typing import Dict, Any, Optional, List, Union
from pydantic import BaseModel, Field


class RuleType(str, Enum):
    TELEMETRY = "telemetry"
    DEVICE_STATUS = "device_status"
    SCHEDULED = "scheduled"
    COMPOUND = "compound"
    CUSTOM = "custom"


class ConditionOperator(str, Enum):
    EQ = "eq"
    NE = "ne"
    GT = "gt"
    GTE = "gte"
    LT = "lt"
    LTE = "lte"
    IN = "in"
    NOT_IN = "not_in"
    CONTAINS = "contains"
    NOT_CONTAINS = "not_contains"
    BETWEEN = "between"
    AND = "and"
    OR = "or"


class ActionType(str, Enum):
    SET_PROPERTY = "set_property"
    SEND_COMMAND = "send_command"
    SEND_ALERT = "send_alert"
    FORWARD_DATA = "forward_data"
    TRIGGER_WORKFLOW = "trigger_workflow"
    HTTP_REQUEST = "http_request"
    MQTT_PUBLISH = "mqtt_publish"
    CUSTOM = "custom"


class RuleCondition(BaseModel):
    field: Optional[str] = None
    operator: ConditionOperator
    value: Optional[Any] = None
    conditions: Optional[List["RuleCondition"]] = None

    def evaluate(self, data: Dict[str, Any]) -> bool:
        if self.operator in [ConditionOperator.AND, ConditionOperator.OR]:
            if not self.conditions:
                return False
            results = [cond.evaluate(data) for cond in self.conditions]
            if self.operator == ConditionOperator.AND:
                return all(results)
            return any(results)

        if not self.field:
            return False

        actual_value = self._get_nested_value(data, self.field)

        if self.operator == ConditionOperator.EQ:
            return actual_value == self.value
        elif self.operator == ConditionOperator.NE:
            return actual_value != self.value
        elif self.operator == ConditionOperator.GT:
            return actual_value is not None and actual_value > self.value
        elif self.operator == ConditionOperator.GTE:
            return actual_value is not None and actual_value >= self.value
        elif self.operator == ConditionOperator.LT:
            return actual_value is not None and actual_value < self.value
        elif self.operator == ConditionOperator.LTE:
            return actual_value is not None and actual_value <= self.value
        elif self.operator == ConditionOperator.IN:
            return actual_value in (self.value or [])
        elif self.operator == ConditionOperator.NOT_IN:
            return actual_value not in (self.value or [])
        elif self.operator == ConditionOperator.CONTAINS:
            return isinstance(actual_value, str) and self.value in actual_value
        elif self.operator == ConditionOperator.NOT_CONTAINS:
            return isinstance(actual_value, str) and self.value not in actual_value
        elif self.operator == ConditionOperator.BETWEEN:
            if not isinstance(self.value, list) or len(self.value) != 2:
                return False
            return self.value[0] <= actual_value <= self.value[1]

        return False

    def _get_nested_value(self, data: Dict[str, Any], field: str) -> Any:
        keys = field.split(".")
        value = data
        for key in keys:
            if isinstance(value, dict) and key in value:
                value = value[key]
            else:
                return None
        return value


class RuleAction(BaseModel):
    action_type: ActionType
    parameters: Dict[str, Any] = Field(default_factory=dict)
    delay_seconds: int = 0
    repeat_count: int = 1
    repeat_interval: int = 0


class Rule(BaseModel):
    rule_id: str
    rule_name: str
    rule_type: RuleType
    description: Optional[str] = None

    condition: RuleCondition
    actions: List[RuleAction] = Field(default_factory=list)

    enabled: bool = True
    priority: int = 0
    trigger_limit: int = 0
    trigger_count: int = 0
    cooldown_period: int = 0
    last_triggered: Optional[datetime] = None

    device_ids: List[str] = Field(default_factory=list)
    device_tags: List[str] = Field(default_factory=list)

    metadata: Dict[str, Any] = Field(default_factory=dict)

    created_at: datetime = Field(default_factory=datetime.utcnow)
    updated_at: datetime = Field(default_factory=datetime.utcnow)

    def should_trigger(self, data: Dict[str, Any]) -> bool:
        if not self.enabled:
            return False

        if self.trigger_limit > 0 and self.trigger_count >= self.trigger_limit:
            return False

        if self.cooldown_period > 0 and self.last_triggered:
            elapsed = (datetime.utcnow() - self.last_triggered).total_seconds()
            if elapsed < self.cooldown_period:
                return False

        return self.condition.evaluate(data)

    def mark_triggered(self) -> None:
        self.trigger_count += 1
        self.last_triggered = datetime.utcnow()

    def applies_to_device(self, device_id: str, device_tags: List[str] = None) -> bool:
        if not self.device_ids and not self.device_tags:
            return True

        if device_id in self.device_ids:
            return True

        if device_tags and self.device_tags:
            return any(tag in self.device_tags for tag in device_tags)

        return False


RuleCondition.model_rebuild()
