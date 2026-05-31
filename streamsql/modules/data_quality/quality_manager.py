from __future__ import annotations

import json
from dataclasses import dataclass, field
from typing import Any, Optional

from streamsql.core.context import ProcessingContext
from streamsql.core.events import Event, EventBus, EventType
from streamsql.core.models import generate_id
from streamsql.modules.data_quality.executor import ValidationExecutor, ValidationResult
from streamsql.modules.data_quality.rules import DataQualityRule, RuleFactory
from streamsql.modules.data_quality.scheduler import ValidationScheduler


@dataclass
class AnomalyMarker:
    marker_id: str = field(default_factory=lambda: generate_id("anom"))
    table: str = ""
    row_index: int = -1
    column: str = ""
    rule_id: str = ""
    rule_name: str = ""
    error_message: str = ""
    severity: str = "error"
    marked_at: float = field(default_factory=lambda: __import__("time").time())
    data_snapshot: dict[str, Any] = field(default_factory=dict)

    def to_dict(self) -> dict[str, Any]:
        return {
            "marker_id": self.marker_id,
            "table": self.table,
            "row_index": self.row_index,
            "column": self.column,
            "rule_id": self.rule_id,
            "rule_name": self.rule_name,
            "error_message": self.error_message,
            "severity": self.severity,
            "marked_at": self.marked_at,
        }


class DataQualityManager:
    def __init__(
        self,
        context: Optional[ProcessingContext] = None,
        fail_fast: bool = False,
    ):
        self.context = context or ProcessingContext(trace_id=generate_id("trace"))
        self.event_bus = EventBus()
        self.executor = ValidationExecutor(fail_fast=fail_fast)
        self.scheduler = ValidationScheduler()
        self._rules: dict[str, DataQualityRule] = {}
        self._table_rules: dict[str, list[str]] = {}
        self._anomalies: list[AnomalyMarker] = []
        self._validation_history: list[ValidationResult] = []

    def add_rule(self, rule: DataQualityRule, table_name: str = "") -> str:
        self._rules[rule.rule_id] = rule
        if table_name:
            if table_name not in self._table_rules:
                self._table_rules[table_name] = []
            if rule.rule_id not in self._table_rules[table_name]:
                self._table_rules[table_name].append(rule.rule_id)

        self.event_bus.emit(
            Event(
                EventType.RULE_CREATED,
                {"rule_id": rule.rule_id, "name": rule.name, "table": table_name},
            )
        )

        return rule.rule_id

    def create_rule(self, rule_type: str, **kwargs: Any) -> str:
        from streamsql.modules.data_quality.rules import RuleType

        rule = RuleFactory.create(RuleType(rule_type), **kwargs)
        return self.add_rule(rule, kwargs.get("table", ""))

    def remove_rule(self, rule_id: str) -> bool:
        if rule_id in self._rules:
            rule = self._rules[rule_id]
            del self._rules[rule_id]

            for table_name in self._table_rules:
                if rule_id in self._table_rules[table_name]:
                    self._table_rules[table_name].remove(rule_id)

            self.event_bus.emit(
                Event(EventType.RULE_DELETED, {"rule_id": rule_id, "name": rule.name})
            )
            return True
        return False

    def get_rule(self, rule_id: str) -> Optional[DataQualityRule]:
        return self._rules.get(rule_id)

    def list_rules(self, table_name: Optional[str] = None) -> list[DataQualityRule]:
        if table_name:
            rule_ids = self._table_rules.get(table_name, [])
            return [self._rules[rid] for rid in rule_ids if rid in self._rules]
        return list(self._rules.values())

    def validate(
        self,
        data: list[dict[str, Any]],
        table_name: str = "",
        rules: Optional[list[DataQualityRule]] = None,
    ) -> ValidationResult:
        self.event_bus.emit(
            Event(
                EventType.VALIDATION_STARTED,
                {"table": table_name, "rows": len(data)},
            )
        )

        if rules is None:
            rules = self.list_rules(table_name)

        result = self.executor.execute(data, rules, table_name)
        self._validation_history.append(result)

        if not result.passed:
            self._mark_anomalies(data, result, table_name)

            self.event_bus.emit(
                Event(
                    EventType.VALIDATION_FAILED,
                    {
                        "table": table_name,
                        "validation_id": result.validation_id,
                        "failed_rules": result.failed_rules,
                        "anomalies": len(result.anomaly_rows),
                    },
                )
            )
        else:
            self.event_bus.emit(
                Event(
                    EventType.VALIDATION_PASSED,
                    {
                        "table": table_name,
                        "validation_id": result.validation_id,
                        "rows": len(data),
                    },
                )
            )

        return result

    def _mark_anomalies(
        self,
        data: list[dict[str, Any]],
        result: ValidationResult,
        table_name: str,
    ) -> None:
        for rule_result in result.rule_results:
            if rule_result.passed:
                continue

            for error in rule_result.errors[:50]:
                row_idx = self.executor._extract_row_number(error)
                if row_idx is None or row_idx >= len(data):
                    continue

                rule = self._rules.get(rule_result.rule_id)
                marker = AnomalyMarker(
                    table=table_name,
                    row_index=row_idx,
                    column=rule.column if rule else "",
                    rule_id=rule_result.rule_id,
                    rule_name=rule_result.rule_name,
                    error_message=error,
                    severity=rule_result.severity,
                    data_snapshot=data[row_idx].copy() if row_idx < len(data) else {},
                )
                self._anomalies.append(marker)

                self.event_bus.emit(
                    Event(
                        EventType.ANOMALY_DETECTED,
                        {
                            "marker_id": marker.marker_id,
                            "table": table_name,
                            "row": row_idx,
                            "severity": rule_result.severity,
                        },
                    )
                )

    def get_anomalies(
        self,
        table_name: Optional[str] = None,
        severity: Optional[str] = None,
        limit: int = 100,
    ) -> list[AnomalyMarker]:
        anomalies = self._anomalies

        if table_name:
            anomalies = [a for a in anomalies if a.table == table_name]
        if severity:
            anomalies = [a for a in anomalies if a.severity == severity]

        return anomalies[:limit]

    def clear_anomalies(self, table_name: Optional[str] = None) -> int:
        if table_name:
            count = sum(1 for a in self._anomalies if a.table == table_name)
            self._anomalies = [a for a in self._anomalies if a.table != table_name]
        else:
            count = len(self._anomalies)
            self._anomalies = []
        return count

    def schedule_validation(
        self,
        name: str,
        interval_seconds: int,
        data_provider: callable,
        table_name: str = "",
    ) -> str:
        def task_func():
            data = data_provider()
            if data:
                self.validate(data, table_name)

        return self.scheduler.add_interval_task(name, interval_seconds, task_func)

    def start_scheduler(self) -> None:
        self.scheduler.start()

    def stop_scheduler(self) -> None:
        self.scheduler.stop()

    def get_validation_history(
        self,
        table_name: Optional[str] = None,
        limit: int = 100,
    ) -> list[ValidationResult]:
        history = self._validation_history
        if table_name:
            history = [h for h in history if h.table == table_name]
        return history[-limit:]

    def get_quality_report(self, table_name: Optional[str] = None) -> dict[str, Any]:
        history = self.get_validation_history(table_name)

        if not history:
            return {"total_validations": 0}

        total_validations = len(history)
        passed_validations = sum(1 for h in history if h.passed)
        pass_rate = passed_validations / total_validations if total_validations > 0 else 0.0

        total_anomalies = sum(len(h.anomaly_rows) for h in history)
        avg_execution_time = sum(h.execution_time_ms for h in history) / total_validations

        by_table: dict[str, Any] = {}
        for h in history:
            if h.table not in by_table:
                by_table[h.table] = {"validations": 0, "passed": 0, "anomalies": 0}
            by_table[h.table]["validations"] += 1
            if h.passed:
                by_table[h.table]["passed"] += 1
            by_table[h.table]["anomalies"] += len(h.anomaly_rows)

        return {
            "total_validations": total_validations,
            "passed_validations": passed_validations,
            "pass_rate": pass_rate,
            "total_anomalies": total_anomalies,
            "avg_execution_time_ms": avg_execution_time,
            "by_table": by_table,
            "active_rules": len(self._rules),
            "current_anomalies": len(self._anomalies),
        }

    def export_rules(self, path: str) -> None:
        rules_data = [r.to_dict() for r in self._rules.values()]
        with open(path, "w") as f:
            json.dump(rules_data, f, indent=2)

    def import_rules(self, path: str) -> int:
        with open(path, "r") as f:
            rules_data = json.load(f)

        count = 0
        for rule_data in rules_data:
            rule = RuleFactory.from_dict(rule_data)
            self.add_rule(rule, rule_data.get("table", ""))
            count += 1
        return count
