from typing import Optional, Dict, Any, List
from datetime import datetime, timedelta
import threading
import time
import uuid

from domain.models.rule import Rule, RuleAction, RuleCondition, RuleType
from domain.models.event import EventType

from infrastructure.persistence.repositories.rule_repository import RuleRepository
from infrastructure.messaging.event_bus import EventBus, get_event_bus
from infrastructure.logging.logger import get_logger

logger = get_logger(__name__)


class RuleEngineService:
    def __init__(
        self,
        rule_repo: RuleRepository,
        event_bus: Optional[EventBus] = None,
    ):
        self.rule_repo = rule_repo
        self.event_bus = event_bus or get_event_bus()

        self._rules: Dict[str, Rule] = {}
        self._action_handlers: Dict[str, callable] = {}

        self._load_rules()
        self._register_default_handlers()

    def _load_rules(self) -> None:
        rules = self.rule_repo.get_all(enabled_only=True)
        for rule in rules:
            self._rules[rule.rule_id] = rule
        logger.info(f"Loaded {len(self._rules)} rules")

    def _register_default_handlers(self) -> None:
        self._action_handlers = {
            "send_alert": self._handle_send_alert,
            "set_property": self._handle_set_property,
            "send_command": self._handle_send_command,
            "http_request": self._handle_http_request,
            "mqtt_publish": self._handle_mqtt_publish,
        }

    def create_rule(self, rule: Rule) -> Rule:
        self.rule_repo.create(rule)
        if rule.enabled:
            self._rules[rule.rule_id] = rule
        logger.info(f"Created rule: {rule.rule_id}")
        return rule

    def update_rule(self, rule_id: str, update_data: Dict[str, Any]) -> Optional[Rule]:
        updated = self.rule_repo.update(rule_id, update_data)
        if updated:
            if updated.enabled:
                self._rules[rule_id] = updated
            elif rule_id in self._rules:
                del self._rules[rule_id]
            logger.info(f"Updated rule: {rule_id}")
        return updated

    def delete_rule(self, rule_id: str) -> bool:
        success = self.rule_repo.delete(rule_id)
        if success and rule_id in self._rules:
            del self._rules[rule_id]
            logger.info(f"Deleted rule: {rule_id}")
        return success

    def get_rule(self, rule_id: str) -> Optional[Rule]:
        return self.rule_repo.get_by_id(rule_id)

    def list_rules(self, enabled_only: bool = False) -> List[Rule]:
        if enabled_only:
            return list(self._rules.values())
        return self.rule_repo.get_all()

    def enable_rule(self, rule_id: str) -> Optional[Rule]:
        return self.update_rule(rule_id, {"enabled": True})

    def disable_rule(self, rule_id: str) -> Optional[Rule]:
        return self.update_rule(rule_id, {"enabled": False})

    def evaluate_rules(self, data: Dict[str, Any], device_id: Optional[str] = None, device_tags: Optional[List[str]] = None) -> List[Dict[str, Any]]:
        triggered_actions = []

        for rule in self._rules.values():
            try:
                if not rule.enabled:
                    continue

                if not rule.applies_to_device(device_id or "", device_tags or []):
                    continue

                if rule.should_trigger(data):
                    actions = self._execute_rule(rule, data, device_id)
                    triggered_actions.extend(actions)
                    rule.mark_triggered()
                    self.rule_repo.update(rule.rule_id, rule.model_dump())

                    event = self.event_bus.create_event(
                        event_type=EventType.RULE_TRIGGERED,
                        device_id=device_id,
                        data={"rule_id": rule.rule_id, "rule_name": rule.rule_name},
                    )
                    self.event_bus.publish(event)

            except Exception as e:
                logger.error(f"Error evaluating rule {rule.rule_id}: {str(e)}")

        return triggered_actions

    def _execute_rule(self, rule: Rule, data: Dict[str, Any], device_id: Optional[str]) -> List[Dict[str, Any]]:
        results = []

        for action in rule.actions:
            try:
                result = self._execute_action(action, data, device_id, rule.rule_id)
                results.append({
                    "action_type": action.action_type,
                    "success": True,
                    "result": result,
                })

                event = self.event_bus.create_event(
                    event_type=EventType.RULE_ACTION_EXECUTED,
                    device_id=device_id,
                    data={"rule_id": rule.rule_id, "action_type": action.action_type},
                )
                self.event_bus.publish(event)

            except Exception as e:
                logger.error(f"Error executing action {action.action_type} for rule {rule.rule_id}: {str(e)}")
                results.append({
                    "action_type": action.action_type,
                    "success": False,
                    "error": str(e),
                })

                event = self.event_bus.create_event(
                    event_type=EventType.RULE_ACTION_FAILED,
                    device_id=device_id,
                    data={"rule_id": rule.rule_id, "action_type": action.action_type, "error": str(e)},
                )
                self.event_bus.publish(event)

        return results

    def _execute_action(self, action: RuleAction, data: Dict[str, Any], device_id: Optional[str], rule_id: str) -> Any:
        action_type = action.action_type.value
        handler = self._action_handlers.get(action_type)

        if handler:
            return handler(action.parameters, data, device_id)
        else:
            logger.warning(f"No handler for action type: {action_type}")
            return None

    def register_action_handler(self, action_type: str, handler: callable) -> None:
        self._action_handlers[action_type] = handler
        logger.info(f"Registered handler for action type: {action_type}")

    def _handle_send_alert(self, params: Dict[str, Any], data: Dict[str, Any], device_id: Optional[str]) -> None:
        alert_type = params.get("type", "info")
        message = params.get("message", "Rule triggered")
        severity = params.get("severity", "low")

        logger.warning(f"ALERT [{alert_type}] [{severity}] Device: {device_id} - {message}")
        return {"type": alert_type, "message": message, "severity": severity}

    def _handle_set_property(self, params: Dict[str, Any], data: Dict[str, Any], device_id: Optional[str]) -> None:
        property_name = params.get("property")
        property_value = params.get("value")

        logger.info(f"Setting property {property_name} = {property_value} for device {device_id}")
        return {"property": property_name, "value": property_value}

    def _handle_send_command(self, params: Dict[str, Any], data: Dict[str, Any], device_id: Optional[str]) -> None:
        command = params.get("command")
        parameters = params.get("parameters", {})

        logger.info(f"Sending command {command} to device {device_id} with params: {parameters}")
        return {"command": command, "parameters": parameters}

    def _handle_http_request(self, params: Dict[str, Any], data: Dict[str, Any], device_id: Optional[str]) -> Optional[Any]:
        try:
            import requests
            url = params.get("url")
            method = params.get("method", "POST")
            headers = params.get("headers", {})
            body = params.get("body", {})

            if isinstance(body, str):
                body = body.format(**data)

            response = requests.request(method, url, json=body, headers=headers, timeout=10)
            return {"status_code": response.status_code, "response": response.text[:500]}
        except Exception as e:
            logger.error(f"HTTP request failed: {str(e)}")
            return {"error": str(e)}

    def _handle_mqtt_publish(self, params: Dict[str, Any], data: Dict[str, Any], device_id: Optional[str]) -> None:
        topic = params.get("topic")
        payload = params.get("payload", {})
        qos = params.get("qos", 1)

        logger.info(f"Publishing to MQTT topic {topic}: {payload}")
        return {"topic": topic, "payload": payload, "qos": qos}

    def evaluate_telemetry_data(self, telemetry_data: Dict[str, Any], device_id: str, device_tags: Optional[List[str]] = None) -> List[Dict[str, Any]]:
        context = {
            "device_id": device_id,
            "telemetry": telemetry_data,
            "timestamp": datetime.utcnow().isoformat(),
        }
        return self.evaluate_rules(context, device_id, device_tags)

    def evaluate_device_status(self, device_id: str, old_status: str, new_status: str, device_tags: Optional[List[str]] = None) -> List[Dict[str, Any]]:
        context = {
            "device_id": device_id,
            "old_status": old_status,
            "new_status": new_status,
            "timestamp": datetime.utcnow().isoformat(),
        }
        return self.evaluate_rules(context, device_id, device_tags)

    def get_rule_stats(self) -> Dict[str, Any]:
        total = len(self._rules)
        triggered_count = sum(rule.trigger_count for rule in self._rules.values())

        return {
            "total_rules": total,
            "enabled_rules": total,
            "total_triggers": triggered_count,
        }

    def reset_rule_trigger_count(self, rule_id: str) -> bool:
        rule = self.rule_repo.get_by_id(rule_id)
        if rule:
            rule.trigger_count = 0
            rule.last_triggered = None
            self.rule_repo.update(rule_id, rule.model_dump())
            if rule_id in self._rules:
                self._rules[rule_id] = rule
            return True
        return False
