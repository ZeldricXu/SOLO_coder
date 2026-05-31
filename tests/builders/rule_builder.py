from typing import Any, Dict, List, Optional
import uuid


class RuleBuilder:
    def __init__(self):
        self._rule_id: str = f"rule_{uuid.uuid4().hex[:8]}"
        self._name: str = "Test Rule"
        self._description: str = "Test rule description"
        self._enabled: bool = True
        self._priority: int = 0
        self._conditions: List[Dict[str, Any]] = []
        self._actions: List[Dict[str, Any]] = []
        self._trigger_type: str = "data_ingestion"
        self._edge_node_id: Optional[str] = None

    def with_rule_id(self, rule_id: str) -> "RuleBuilder":
        self._rule_id = rule_id
        return self

    def with_name(self, name: str) -> "RuleBuilder":
        self._name = name
        return self

    def with_description(self, description: str) -> "RuleBuilder":
        self._description = description
        return self

    def with_enabled(self, enabled: bool) -> "RuleBuilder":
        self._enabled = enabled
        return self

    def with_priority(self, priority: int) -> "RuleBuilder":
        self._priority = priority
        return self

    def with_condition(
        self, field: str, operator: str, value: Any
    ) -> "RuleBuilder":
        self._conditions.append(
            {"field": field, "operator": operator, "value": value}
        )
        return self

    def with_conditions(self, conditions: List[Dict[str, Any]]) -> "RuleBuilder":
        self._conditions = conditions
        return self

    def with_action(
        self, action_type: str, parameters: Optional[Dict[str, Any]] = None
    ) -> "RuleBuilder":
        self._actions.append(
            {"action_type": action_type, "parameters": parameters or {}}
        )
        return self

    def with_actions(self, actions: List[Dict[str, Any]]) -> "RuleBuilder":
        self._actions = actions
        return self

    def with_trigger_type(self, trigger_type: str) -> "RuleBuilder":
        self._trigger_type = trigger_type
        return self

    def with_edge_node_id(self, edge_node_id: str) -> "RuleBuilder":
        self._edge_node_id = edge_node_id
        return self

    def build(self) -> Dict[str, Any]:
        return {
            "rule_id": self._rule_id,
            "id": self._rule_id,
            "name": self._name,
            "description": self._description,
            "enabled": self._enabled,
            "priority": self._priority,
            "conditions": self._conditions,
            "actions": self._actions,
            "trigger_type": self._trigger_type,
            "edge_node_id": self._edge_node_id,
            "trigger_config": {},
        }

    def build_entity_dict(self) -> Dict[str, Any]:
        return {
            "rule_id": self._rule_id,
            "name": self._name,
            "description": self._description,
            "enabled": self._enabled,
            "priority": self._priority,
            "conditions": self._conditions,
            "actions": self._actions,
            "trigger_type": self._trigger_type,
            "edge_node_id": self._edge_node_id,
            "type": "edge_rule",
            "status": "active" if self._enabled else "inactive",
        }
