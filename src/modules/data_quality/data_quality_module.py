"""Data quality module for rule configuration, scheduled validation, and anomaly marking."""
from __future__ import annotations

import asyncio
from dataclasses import dataclass, field
from datetime import datetime
from typing import Any, Dict, List, Optional
from uuid import UUID, uuid4

from ...domain.errors.common import ValidationError
from ...domain.models.common import EventMessage, ProcessingResult, ProcessingStatus
from ...infrastructure.logging.structured_logger import LogManager
from ...infrastructure.config.settings import Settings
from ...infrastructure.config.default_config import get_default_settings
from .quality_rules import QualityRuleManager, QualityRule, RuleType, RuleSeverity
from .anomaly_detector import AnomalyDetector, AnomalyScore


class DataQualityModule:
    def __init__(self, settings: Optional[Settings] = None) -> None:
        self._settings = settings or get_default_settings()
        self._rule_manager = QualityRuleManager()
        self._anomaly_detector = AnomalyDetector()
        self._logger = LogManager().get_logger(__name__)
        self._scheduled_tasks: Dict[str, Any] = {}

    @property
    def rule_manager(self) -> QualityRuleManager:
        return self._rule_manager

    @property
    def anomaly_detector(self) -> AnomalyDetector:
        return self._anomaly_detector

    async def process_event(self, event: EventMessage) -> ProcessingResult:
        result = ProcessingResult(
            started_at=datetime.utcnow(),
            status=ProcessingStatus.PROCESSING,
        )

        try:
            event_type = event.event_type
            payload = event.payload

            if event_type == "quality.rule.create":
                create_result = self._handle_create_rule(payload)
                result.results = [create_result]
                result.status = ProcessingStatus.SUCCESS
                result.message = "Quality rule created successfully"

            elif event_type == "quality.rule.list":
                list_result = self._handle_list_rules(payload)
                result.results = [list_result]
                result.status = ProcessingStatus.SUCCESS
                result.message = "Quality rules retrieved"

            elif event_type == "quality.rule.update":
                update_result = self._handle_update_rule(payload)
                result.results = [update_result]
                result.status = ProcessingStatus.SUCCESS
                result.message = "Quality rule updated successfully"

            elif event_type == "quality.rule.delete":
                delete_result = self._handle_delete_rule(payload)
                result.results = [delete_result]
                result.status = ProcessingStatus.SUCCESS
                result.message = "Quality rule deleted successfully"

            elif event_type == "quality.validate":
                validate_result = self._handle_validate(payload)
                result.results = [validate_result]
                result.status = ProcessingStatus.SUCCESS
                result.message = "Quality validation completed"

            elif event_type == "quality.anomaly.fit":
                fit_result = self._handle_anomaly_fit(payload)
                result.results = [fit_result]
                result.status = ProcessingStatus.SUCCESS
                result.message = "Anomaly detector fitted successfully"

            elif event_type == "quality.anomaly.detect":
                detect_result = self._handle_anomaly_detect(payload)
                result.results = [detect_result]
                result.status = ProcessingStatus.SUCCESS
                result.message = "Anomaly detection completed"

            elif event_type == "quality.anomaly.mark":
                mark_result = self._handle_anomaly_mark(payload)
                result.results = [mark_result]
                result.status = ProcessingStatus.SUCCESS
                result.message = "Anomaly marking completed"

            elif event_type == "quality.summary":
                summary_result = self._handle_get_summary(payload)
                result.results = [summary_result]
                result.status = ProcessingStatus.SUCCESS
                result.message = "Quality summary retrieved"

            else:
                raise ValidationError(
                    message=f"Unknown event type: {event_type}",
                    suggestion="Check the event type and try again.",
                )

        except Exception as e:
            result.status = ProcessingStatus.FAILED
            result.message = f"Data quality event processing failed: {str(e)}"
            result.errors.append({"error": str(e)})

            self._logger.error(
                "Data quality event processing failed",
                event_type=event.event_type,
                error=str(e),
            )

        result.completed_at = datetime.utcnow()
        result.calculate_duration()

        return result

    def _handle_create_rule(self, payload: Dict[str, Any]) -> Dict[str, Any]:
        name = payload.get("name")
        rule_type = payload.get("rule_type")
        field_name = payload.get("field_name")
        table_name = payload.get("table_name")
        severity = payload.get("severity", RuleSeverity.MEDIUM)
        parameters = payload.get("parameters", {})
        description = payload.get("description", "")
        schedule = payload.get("schedule")

        if not name or not rule_type or not field_name or not table_name:
            raise ValidationError(
                message="Rule name, type, field name, and table name are required",
                suggestion="Provide all required fields in the payload.",
            )

        if isinstance(rule_type, str):
            rule_type = RuleType(rule_type)
        if isinstance(severity, str):
            severity = RuleSeverity(severity)

        rule = self._rule_manager.create_rule(
            name=name,
            rule_type=rule_type,
            field_name=field_name,
            table_name=table_name,
            severity=severity,
            parameters=parameters,
            description=description,
            schedule=schedule,
        )

        return {
            "rule_id": str(rule.id),
            "name": rule.name,
            "rule_type": rule.rule_type.value,
            "field_name": rule.field_name,
            "table_name": rule.table_name,
            "severity": rule.severity.value,
        }

    def _handle_list_rules(self, payload: Dict[str, Any]) -> Dict[str, Any]:
        table_name = payload.get("table_name")
        field_name = payload.get("field_name")
        rule_type = payload.get("rule_type")
        severity = payload.get("severity")
        enabled_only = payload.get("enabled_only", True)

        if isinstance(rule_type, str):
            rule_type = RuleType(rule_type)
        if isinstance(severity, str):
            severity = RuleSeverity(severity)

        rules = self._rule_manager.list_rules(
            table_name=table_name,
            field_name=field_name,
            rule_type=rule_type,
            severity=severity,
            enabled_only=enabled_only,
        )

        return {
            "total_rules": len(rules),
            "rules": [
                {
                    "id": str(rule.id),
                    "name": rule.name,
                    "rule_type": rule.rule_type.value,
                    "field_name": rule.field_name,
                    "table_name": rule.table_name,
                    "severity": rule.severity.value,
                    "enabled": rule.enabled,
                    "description": rule.description,
                    "schedule": rule.schedule,
                }
                for rule in rules
            ],
        }

    def _handle_update_rule(self, payload: Dict[str, Any]) -> Dict[str, Any]:
        rule_id = payload.get("rule_id")
        if not rule_id:
            raise ValidationError(
                message="Rule ID is required",
                suggestion="Provide rule_id in the payload.",
            )

        rule_uuid = UUID(rule_id)
        updates = {k: v for k, v in payload.items() if k != "rule_id"}

        if "rule_type" in updates and isinstance(updates["rule_type"], str):
            updates["rule_type"] = RuleType(updates["rule_type"])
        if "severity" in updates and isinstance(updates["severity"], str):
            updates["severity"] = RuleSeverity(updates["severity"])

        rule = self._rule_manager.update_rule(rule_uuid, **updates)
        if not rule:
            raise ValidationError(
                message=f"Rule not found: {rule_id}",
                suggestion="Check that the rule ID is correct.",
            )

        return {
            "rule_id": str(rule.id),
            "name": rule.name,
            "updated": True,
        }

    def _handle_delete_rule(self, payload: Dict[str, Any]) -> Dict[str, Any]:
        rule_id = payload.get("rule_id")
        if not rule_id:
            raise ValidationError(
                message="Rule ID is required",
                suggestion="Provide rule_id in the payload.",
            )

        rule_uuid = UUID(rule_id)
        success = self._rule_manager.delete_rule(rule_uuid)

        return {
            "rule_id": rule_id,
            "deleted": success,
        }

    def _handle_validate(self, payload: Dict[str, Any]) -> Dict[str, Any]:
        data = payload.get("data")
        table_name = payload.get("table_name")
        context = payload.get("context")

        if not data or not table_name:
            raise ValidationError(
                message="Data and table name are required",
                suggestion="Provide 'data' and 'table_name' in the payload.",
            )

        if isinstance(data, dict):
            data = [data]

        validation_result = self._rule_manager.validate_batch(data, table_name, context)

        self._logger.info(
            f"Quality validation completed for {table_name}",
            total_rows=validation_result["total_rows"],
            violations_count=validation_result["violations_count"],
        )

        return validation_result

    def _handle_anomaly_fit(self, payload: Dict[str, Any]) -> Dict[str, Any]:
        data = payload.get("data")
        numeric_fields = payload.get("numeric_fields")

        if not data:
            raise ValidationError(
                message="Data is required",
                suggestion="Provide 'data' in the payload.",
            )

        stats = self._anomaly_detector.fit(data, numeric_fields)

        return {
            "fitted_fields": list(stats.keys()),
            "field_stats": {
                name: {
                    "mean": s.mean,
                    "std_dev": s.std_dev,
                    "min": s.min,
                    "max": s.max,
                    "count": s.count,
                    "null_count": s.null_count,
                }
                for name, s in stats.items()
            },
        }

    def _handle_anomaly_detect(self, payload: Dict[str, Any]) -> Dict[str, Any]:
        data = payload.get("data")
        methods = payload.get("methods")
        fields = payload.get("fields")
        streaming = payload.get("streaming", False)
        field_name = payload.get("field_name")
        value = payload.get("value")

        if streaming:
            if field_name is None or value is None:
                raise ValidationError(
                    message="Field name and value are required for streaming detection",
                    suggestion="Provide 'field_name' and 'value' in the payload.",
                )

            anomaly = self._anomaly_detector.detect_streaming(value, field_name, methods)
            if anomaly:
                return {
                    "anomaly": {
                        "field_name": anomaly.field_name,
                        "score": anomaly.score,
                        "threshold": anomaly.threshold,
                        "is_anomaly": anomaly.is_anomaly,
                        "reason": anomaly.reason,
                        "value": anomaly.value,
                    }
                }
            return {"anomaly": None}

        if not data:
            raise ValidationError(
                message="Data is required for batch detection",
                suggestion="Provide 'data' in the payload.",
            )

        anomalies = self._anomaly_detector.detect(data, methods, fields)

        return {
            "total_anomalies": len([a for a in anomalies if a.is_anomaly]),
            "total_scored": len(anomalies),
            "anomalies": [
                {
                    "field_name": a.field_name,
                    "row_index": a.row_index,
                    "score": a.score,
                    "threshold": a.threshold,
                    "is_anomaly": a.is_anomaly,
                    "reason": a.reason,
                    "value": a.value,
                }
                for a in anomalies
            ],
        }

    def _handle_anomaly_mark(self, payload: Dict[str, Any]) -> Dict[str, Any]:
        data = payload.get("data")
        anomalies_data = payload.get("anomalies", [])
        mark_field = payload.get("mark_field", "_is_anomaly")
        anomaly_details_field = payload.get("anomaly_details_field", "_anomaly_details")

        if not data:
            raise ValidationError(
                message="Data is required",
                suggestion="Provide 'data' in the payload.",
            )

        anomalies = [
            AnomalyScore(
                field_name=a["field_name"],
                row_index=a["row_index"],
                score=a["score"],
                threshold=a["threshold"],
                is_anomaly=a["is_anomaly"],
                reason=a.get("reason", ""),
                value=a.get("value"),
            )
            for a in anomalies_data
        ]

        if not anomalies:
            anomalies = self._anomaly_detector.detect(data)

        marked_data = self._anomaly_detector.mark_anomalies(
            data, anomalies, mark_field, anomaly_details_field
        )

        return {
            "total_rows": len(marked_data),
            "anomalies_count": len([a for a in anomalies if a.is_anomaly]),
            "marked_data": marked_data,
        }

    def _handle_get_summary(self, payload: Dict[str, Any]) -> Dict[str, Any]:
        start_time = payload.get("start_time")
        end_time = payload.get("end_time")

        if isinstance(start_time, str):
            start_time = datetime.fromisoformat(start_time)
        if isinstance(end_time, str):
            end_time = datetime.fromisoformat(end_time)

        anomaly_summary = self._anomaly_detector.get_anomaly_summary(start_time, end_time)

        return {
            "total_rules": len(self._rule_manager.list_rules(enabled_only=False)),
            "enabled_rules": len(self._rule_manager.list_rules(enabled_only=True)),
            "anomaly_detection": anomaly_summary,
            "field_stats": self._anomaly_detector.get_field_stats(),
        }

    def create_rule(
        self,
        name: str,
        rule_type: RuleType,
        field_name: str,
        table_name: str,
        **kwargs: Any,
    ) -> Dict[str, Any]:
        event = EventMessage(
            event_type="quality.rule.create",
            payload={
                "name": name,
                "rule_type": rule_type.value if isinstance(rule_type, RuleType) else rule_type,
                "field_name": field_name,
                "table_name": table_name,
                **kwargs,
            },
            source="data_quality",
        )
        result = asyncio.run(self.process_event(event))
        return result.results[0] if result.results else {}

    def validate_data(
        self,
        data: Any,
        table_name: str,
        **kwargs: Any,
    ) -> Dict[str, Any]:
        event = EventMessage(
            event_type="quality.validate",
            payload={
                "data": data,
                "table_name": table_name,
                **kwargs,
            },
            source="data_quality",
        )
        result = asyncio.run(self.process_event(event))
        return result.results[0] if result.results else {}

    def fit_anomaly_detector(
        self,
        data: List[Dict[str, Any]],
        **kwargs: Any,
    ) -> Dict[str, Any]:
        event = EventMessage(
            event_type="quality.anomaly.fit",
            payload={
                "data": data,
                **kwargs,
            },
            source="data_quality",
        )
        result = asyncio.run(self.process_event(event))
        return result.results[0] if result.results else {}

    def detect_anomalies(
        self,
        data: List[Dict[str, Any]],
        **kwargs: Any,
    ) -> Dict[str, Any]:
        event = EventMessage(
            event_type="quality.anomaly.detect",
            payload={
                "data": data,
                **kwargs,
            },
            source="data_quality",
        )
        result = asyncio.run(self.process_event(event))
        return result.results[0] if result.results else {}

    def detect_anomaly_streaming(
        self,
        value: Any,
        field_name: str,
        **kwargs: Any,
    ) -> Dict[str, Any]:
        event = EventMessage(
            event_type="quality.anomaly.detect",
            payload={
                "streaming": True,
                "value": value,
                "field_name": field_name,
                **kwargs,
            },
            source="data_quality",
        )
        result = asyncio.run(self.process_event(event))
        return result.results[0] if result.results else {}

    def mark_anomalies(
        self,
        data: List[Dict[str, Any]],
        **kwargs: Any,
    ) -> Dict[str, Any]:
        event = EventMessage(
            event_type="quality.anomaly.mark",
            payload={
                "data": data,
                **kwargs,
            },
            source="data_quality",
        )
        result = asyncio.run(self.process_event(event))
        return result.results[0] if result.results else {}

    def get_quality_summary(self) -> Dict[str, Any]:
        event = EventMessage(
            event_type="quality.summary",
            payload={},
            source="data_quality",
        )
        result = asyncio.run(self.process_event(event))
        return result.results[0] if result.results else {}

    def export_rules(self) -> List[Dict[str, Any]]:
        return self._rule_manager.export_rules()

    def import_rules(self, rules_data: List[Dict[str, Any]]) -> int:
        return self._rule_manager.import_rules(rules_data)

    def get_anomaly_history(self, **kwargs: Any) -> List[Dict[str, Any]]:
        return self._anomaly_detector.get_anomaly_history(**kwargs)

    def list_rules(self, **kwargs: Any) -> Dict[str, Any]:
        event = EventMessage(
            event_type="quality.rule.list",
            payload=kwargs,
            source="data_quality",
        )
        result = asyncio.run(self.process_event(event))
        return result.results[0] if result.results else {}

    def create_null_check(
        self,
        table_name: str,
        field_name: str,
        **kwargs: Any,
    ) -> QualityRule:
        return self._rule_manager.create_null_check(table_name, field_name, **kwargs)

    def create_range_check(
        self,
        table_name: str,
        field_name: str,
        **kwargs: Any,
    ) -> QualityRule:
        return self._rule_manager.create_range_check(table_name, field_name, **kwargs)

    def create_unique_check(
        self,
        table_name: str,
        field_name: str,
        **kwargs: Any,
    ) -> QualityRule:
        return self._rule_manager.create_unique_check(table_name, field_name, **kwargs)

    def create_format_check(
        self,
        table_name: str,
        field_name: str,
        format_type: str,
        **kwargs: Any,
    ) -> QualityRule:
        return self._rule_manager.create_format_check(table_name, field_name, format_type, **kwargs)

    def create_regex_check(
        self,
        table_name: str,
        field_name: str,
        pattern: str,
        **kwargs: Any,
    ) -> QualityRule:
        return self._rule_manager.create_regex_check(table_name, field_name, pattern, **kwargs)
