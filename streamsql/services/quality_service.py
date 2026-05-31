from __future__ import annotations

from typing import Any, Optional

from streamsql.core.config import ConfigManager
from streamsql.core.context import ProcessingContext
from streamsql.core.events import EventBus
from streamsql.modules.data_quality.quality_manager import DataQualityManager
from streamsql.modules.data_quality.rules import RuleFactory, RuleType, SeverityLevel


class QualityService:
    def __init__(self, config_manager: Optional[ConfigManager] = None):
        self.config_manager = config_manager or ConfigManager()
        self.event_bus = EventBus()
        self.manager = DataQualityManager()

    def create_rule(
        self,
        rule_type: str,
        name: str,
        column: str,
        table: str,
        parameters: Optional[dict[str, Any]] = None,
        severity: str = "error",
    ) -> dict[str, Any]:
        params = parameters or {}
        rule = RuleFactory.create(
            RuleType(rule_type),
            name=name,
            column=column,
            table=table,
            parameters=params,
            severity=SeverityLevel(severity),
            **params,
        )
        rule_id = self.manager.add_rule(rule, table)

        return {
            "rule_id": rule_id,
            "name": name,
            "type": rule_type,
            "column": column,
            "table": table,
            "severity": severity,
        }

    def add_rule(self, rule: Any, table_name: str = "") -> str:
        return self.manager.add_rule(rule, table_name)

    def get_rule(self, rule_id: str) -> Optional[dict[str, Any]]:
        rule = self.manager.get_rule(rule_id)
        return rule.to_dict() if rule else None

    def list_rules(self, table_name: Optional[str] = None) -> list[dict[str, Any]]:
        rules = self.manager.list_rules(table_name)
        return [r.to_dict() for r in rules]

    def delete_rule(self, rule_id: str) -> bool:
        return self.manager.remove_rule(rule_id)

    def validate(
        self,
        data: list[dict[str, Any]],
        table_name: str = "",
        rule_ids: Optional[list[str]] = None,
    ) -> dict[str, Any]:
        rules = None
        if rule_ids:
            rules = [self.manager.get_rule(rid) for rid in rule_ids if self.manager.get_rule(rid)]

        result = self.manager.validate(data, table_name, rules)
        return result.to_dict()

    def validate_row(
        self,
        row: dict[str, Any],
        table_name: str = "",
    ) -> dict[str, Any]:
        rules = self.manager.list_rules(table_name)
        passed, errors = self.manager.executor.validate_row(row, rules)
        return {"passed": passed, "errors": errors}

    def get_anomalies(
        self,
        table_name: Optional[str] = None,
        severity: Optional[str] = None,
        limit: int = 100,
    ) -> list[dict[str, Any]]:
        anomalies = self.manager.get_anomalies(table_name, severity, limit)
        return [a.to_dict() for a in anomalies]

    def clear_anomalies(self, table_name: Optional[str] = None) -> int:
        return self.manager.clear_anomalies(table_name)

    def get_validation_history(
        self,
        table_name: Optional[str] = None,
        limit: int = 100,
    ) -> list[dict[str, Any]]:
        history = self.manager.get_validation_history(table_name, limit)
        return [h.to_dict() for h in history]

    def get_quality_report(
        self,
        table_name: Optional[str] = None,
    ) -> dict[str, Any]:
        return self.manager.get_quality_report(table_name)

    def schedule_validation(
        self,
        name: str,
        interval_seconds: int,
        data_provider: Any,
        table_name: str = "",
    ) -> str:
        return self.manager.schedule_validation(name, interval_seconds, data_provider, table_name)

    def start_scheduler(self) -> None:
        self.manager.start_scheduler()

    def stop_scheduler(self) -> None:
        self.manager.stop_scheduler()

    def get_scheduler_status(self) -> dict[str, Any]:
        return self.manager.scheduler.get_status_summary()

    def export_rules(self, path: str) -> None:
        self.manager.export_rules(path)

    def import_rules(self, path: str) -> int:
        return self.manager.import_rules(path)

    def get_available_rule_types(self) -> list[str]:
        return [t.value for t in RuleType]

    def get_available_severities(self) -> list[str]:
        return [s.value for s in SeverityLevel]
