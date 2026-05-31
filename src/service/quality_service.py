import logging
from datetime import datetime
from typing import Any, Dict, List, Optional

from src.domain.quality.rule_engine import QualityRuleEngine, QualityRule, RuleType, Strictness, RuleViolation
from src.domain.quality.validator import DataValidator, ValidationResult, ScheduledCheck
from src.domain.quality.anomaly_marker import AnomalyMarker, AnomalyReport
from src.infrastructure.config.settings import QualityConfig

logger = logging.getLogger(__name__)


class QualityService:
    def __init__(self, config: Optional[QualityConfig] = None):
        self._config = config or QualityConfig()
        self._rule_engine = QualityRuleEngine(self._config)
        self._validator = DataValidator(self._rule_engine, self._config)
        self._anomaly_marker = AnomalyMarker(self._config)

    def add_rule(
        self,
        rule_id: str,
        rule_name: str,
        rule_type: str,
        target_database: str,
        target_table: str,
        target_column: Optional[str] = None,
        strictness: str = "warning",
        params: Optional[Dict[str, Any]] = None,
        description: Optional[str] = None,
    ) -> Dict[str, Any]:
        rule = QualityRule(
            rule_id=rule_id,
            rule_name=rule_name,
            rule_type=RuleType(rule_type),
            target_database=target_database,
            target_table=target_table,
            target_column=target_column,
            strictness=Strictness(strictness),
            params=params or {},
            description=description,
        )
        self._rule_engine.add_rule(rule)
        return rule.to_dict()

    def remove_rule(self, rule_id: str) -> bool:
        self._rule_engine.remove_rule(rule_id)
        return True

    def get_rules(
        self,
        database: Optional[str] = None,
        table: Optional[str] = None,
    ) -> List[Dict[str, Any]]:
        rules = self._rule_engine.get_rules(database=database, table=table)
        return [r.to_dict() for r in rules]

    def validate_table(
        self,
        database_name: str,
        table_name: str,
        data: List[Dict[str, Any]],
    ) -> Dict[str, Any]:
        result = self._validator.validate_table(database_name, table_name, data)
        return result.to_dict()

    def validate_column(
        self,
        database_name: str,
        table_name: str,
        column_name: str,
        data: List[Dict[str, Any]],
    ) -> Dict[str, Any]:
        result = self._validator.validate_column(database_name, table_name, column_name, data)
        return result.to_dict()

    def detect_anomalies(
        self,
        database_name: str,
        table_name: str,
        data: List[Dict[str, Any]],
        columns: Optional[List[str]] = None,
        method: str = "zscore",
    ) -> Dict[str, Any]:
        report = self._anomaly_marker.detect_anomalies(
            database_name, table_name, data, columns, method,
        )
        return report.to_dict()

    def mark_anomalies(
        self,
        database_name: str,
        table_name: str,
        data: List[Dict[str, Any]],
        columns: Optional[List[str]] = None,
        method: str = "zscore",
        marker_column: str = "_is_anomaly",
    ) -> List[Dict[str, Any]]:
        report = self._anomaly_marker.detect_anomalies(
            database_name, table_name, data, columns, method,
        )
        return self._anomaly_marker.mark_data(data, report, marker_column)

    def compute_baseline(self, column: str, values: List[Any]) -> Dict[str, Any]:
        stats = self._anomaly_marker.compute_baseline(column, values)
        return stats

    def get_quality_score(self, database_name: str, table_name: str) -> float:
        return self._validator.get_quality_score(database_name, table_name)

    def get_quality_summary(self, database_name: Optional[str] = None) -> Dict[str, Any]:
        return self._validator.get_quality_summary(database_name)

    def get_validation_history(
        self,
        database_name: str,
        table_name: str,
        limit: int = 10,
    ) -> List[Dict[str, Any]]:
        history = self._validator.get_validation_history(database_name, table_name, limit)
        return [h.to_dict() for h in history]

    def add_scheduled_check(
        self,
        check_id: str,
        database_name: str,
        table_name: str,
        cron_expression: str,
    ) -> None:
        check = ScheduledCheck(
            check_id=check_id,
            database_name=database_name,
            table_name=table_name,
            cron_expression=cron_expression,
        )
        self._validator.add_scheduled_check(check)

    def run_scheduled_checks(self) -> List[Dict[str, Any]]:
        results = self._validator.run_scheduled_checks()
        return [r.to_dict() for r in results]

    def get_anomaly_history(
        self,
        database_name: str,
        table_name: str,
        limit: int = 10,
    ) -> List[Dict[str, Any]]:
        history = self._anomaly_marker.get_anomaly_history(database_name, table_name, limit)
        return [h.to_dict() for h in history]

    def set_anomaly_threshold(self, threshold: float) -> None:
        self._anomaly_marker.set_threshold(threshold)
