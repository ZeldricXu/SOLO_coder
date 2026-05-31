"""
Data Quality Module.
Implements quality rule configuration, scheduled execution, and anomaly marking.
"""

import asyncio
import re
from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from datetime import datetime, timedelta
from enum import Enum
from typing import Any, Callable, Dict, List, Optional, Pattern, Tuple, Union

from app.logging import get_logger


class QualityRuleType(str, Enum):
    NULL_CHECK = "null_check"
    UNIQUENESS = "uniqueness"
    RANGE_CHECK = "range_check"
    REGEX_MATCH = "regex_match"
    CUSTOM_EXPRESSION = "custom_expression"
    REFERENTIAL_INTEGRITY = "referential_integrity"
    FORMAT_CHECK = "format_check"
    DISTRIBUTION_CHECK = "distribution_check"


class QualitySeverity(str, Enum):
    CRITICAL = "critical"
    HIGH = "high"
    MEDIUM = "medium"
    LOW = "low"


class QualityStatus(str, Enum):
    PENDING = "pending"
    RUNNING = "running"
    PASSED = "passed"
    FAILED = "failed"
    ERROR = "error"


@dataclass
class QualityRule:
    rule_id: str
    name: str
    rule_type: QualityRuleType
    database: str
    table: str
    column: Optional[str] = None
    enabled: bool = True
    severity: QualitySeverity = QualitySeverity.HIGH
    description: str = ""
    parameters: Dict[str, Any] = field(default_factory=dict)
    schedule_cron: Optional[str] = None
    schedule_interval_seconds: Optional[int] = None
    created_at: datetime = field(default_factory=datetime.utcnow)
    updated_at: datetime = field(default_factory=datetime.utcnow)
    tags: Dict[str, str] = field(default_factory=dict)


@dataclass
class QualityResult:
    result_id: str
    rule_id: str
    status: QualityStatus
    total_rows: int = 0
    valid_rows: int = 0
    invalid_rows: int = 0
    sample_invalid_values: List[Any] = field(default_factory=list)
    message: str = ""
    started_at: Optional[datetime] = None
    completed_at: Optional[datetime] = None
    execution_time_ms: float = 0.0
    metadata: Dict[str, Any] = field(default_factory=dict)


@dataclass
class AnomalyRecord:
    anomaly_id: str
    rule_id: str
    table: str
    column: Optional[str]
    primary_key: Dict[str, Any]
    value: Any
    reason: str
    timestamp: datetime = field(default_factory=datetime.utcnow)
    marked_as_invalid: bool = True
    resolved: bool = False
    resolved_at: Optional[datetime] = None


class DataQualityChecker(ABC):
    @abstractmethod
    def check(self, rule: QualityRule, data: List[Dict[str, Any]]) -> QualityResult:
        pass


class NullChecker(DataQualityChecker):
    def check(self, rule: QualityRule, data: List[Dict[str, Any]]) -> QualityResult:
        column = rule.column
        if not column:
            return QualityResult(
                result_id=f"res_{rule.rule_id}_{int(datetime.utcnow().timestamp())}",
                rule_id=rule.rule_id,
                status=QualityStatus.ERROR,
                message="Column not specified for null check"
            )
        
        total = len(data)
        invalid_count = 0
        samples = []
        
        for row in data:
            value = row.get(column)
            if value is None or value == "" or (isinstance(value, str) and value.strip() == ""):
                invalid_count += 1
                if len(samples) < 10:
                    pk = {k: row.get(k) for k in row if "id" in k.lower()}
                    samples.append({"row": pk, "value": value})
        
        valid_count = total - invalid_count
        return QualityResult(
            result_id=f"res_{rule.rule_id}_{int(datetime.utcnow().timestamp())}",
            rule_id=rule.rule_id,
            status=QualityStatus.PASSED if invalid_count == 0 else QualityStatus.FAILED,
            total_rows=total,
            valid_rows=valid_count,
            invalid_rows=invalid_count,
            sample_invalid_values=samples,
            message=f"Null check: {invalid_count}/{total} invalid"
        )


class UniquenessChecker(DataQualityChecker):
    def check(self, rule: QualityRule, data: List[Dict[str, Any]]) -> QualityResult:
        column = rule.column
        if not column:
            return QualityResult(
                result_id=f"res_{rule.rule_id}_{int(datetime.utcnow().timestamp())}",
                rule_id=rule.rule_id,
                status=QualityStatus.ERROR,
                message="Column not specified for uniqueness check"
            )
        
        values = []
        for row in data:
            val = row.get(column)
            if val is not None:
                values.append(val)
        
        total = len(values)
        unique_count = len(set(values))
        duplicate_count = total - unique_count
        
        seen = set()
        duplicates = []
        for row in data:
            val = row.get(column)
            if val in seen and val not in duplicates and len(duplicates) < 10:
                duplicates.append(val)
            seen.add(val)
        
        return QualityResult(
            result_id=f"res_{rule.rule_id}_{int(datetime.utcnow().timestamp())}",
            rule_id=rule.rule_id,
            status=QualityStatus.PASSED if duplicate_count == 0 else QualityStatus.FAILED,
            total_rows=total,
            valid_rows=unique_count,
            invalid_rows=duplicate_count,
            sample_invalid_values=duplicates,
            message=f"Uniqueness check: {duplicate_count}/{total} duplicates"
        )


class RangeChecker(DataQualityChecker):
    def check(self, rule: QualityRule, data: List[Dict[str, Any]]) -> QualityResult:
        column = rule.column
        if not column:
            return QualityResult(
                result_id=f"res_{rule.rule_id}_{int(datetime.utcnow().timestamp())}",
                rule_id=rule.rule_id,
                status=QualityStatus.ERROR,
                message="Column not specified for range check"
            )
        
        min_val = rule.parameters.get("min")
        max_val = rule.parameters.get("max")
        inclusive = rule.parameters.get("inclusive", True)
        
        total = 0
        invalid = 0
        samples = []
        
        for row in data:
            value = row.get(column)
            if value is None:
                continue
            
            try:
                num_val = float(value)
                total += 1
                
                is_valid = True
                if min_val is not None:
                    if inclusive:
                        if num_val < min_val:
                            is_valid = False
                    else:
                        if num_val <= min_val:
                            is_valid = False
                
                if max_val is not None:
                    if inclusive:
                        if num_val > max_val:
                            is_valid = False
                    else:
                        if num_val >= max_val:
                            is_valid = False
                
                if not is_valid:
                    invalid += 1
                    if len(samples) < 10:
                        pk = {k: row.get(k) for k in row if "id" in k.lower()}
                        samples.append({"row": pk, "value": value})
                        
            except (TypeError, ValueError):
                total += 1
                invalid += 1
        
        valid = total - invalid
        return QualityResult(
            result_id=f"res_{rule.rule_id}_{int(datetime.utcnow().timestamp())}",
            rule_id=rule.rule_id,
            status=QualityStatus.PASSED if invalid == 0 else QualityStatus.FAILED,
            total_rows=total,
            valid_rows=valid,
            invalid_rows=invalid,
            sample_invalid_values=samples,
            message=f"Range check: {invalid}/{total} outside [{min_val}, {max_val}]"
        )


class RegexChecker(DataQualityChecker):
    def __init__(self):
        self._cache: Dict[str, Pattern] = {}
    
    def _get_pattern(self, pattern: str) -> Pattern:
        if pattern not in self._cache:
            self._cache[pattern] = re.compile(pattern)
        return self._cache[pattern]
    
    def check(self, rule: QualityRule, data: List[Dict[str, Any]]) -> QualityResult:
        column = rule.column
        pattern_str = rule.parameters.get("pattern")
        
        if not column or not pattern_str:
            return QualityResult(
                result_id=f"res_{rule.rule_id}_{int(datetime.utcnow().timestamp())}",
                rule_id=rule.rule_id,
                status=QualityStatus.ERROR,
                message="Column or pattern not specified for regex check"
            )
        
        try:
            pattern = self._get_pattern(pattern_str)
        except re.error as e:
            return QualityResult(
                result_id=f"res_{rule.rule_id}_{int(datetime.utcnow().timestamp())}",
                rule_id=rule.rule_id,
                status=QualityStatus.ERROR,
                message=f"Invalid regex pattern: {str(e)}"
            )
        
        total = 0
        invalid = 0
        samples = []
        
        for row in data:
            value = row.get(column)
            if value is None:
                continue
            
            total += 1
            str_val = str(value)
            if not pattern.match(str_val):
                invalid += 1
                if len(samples) < 10:
                    pk = {k: row.get(k) for k in row if "id" in k.lower()}
                    samples.append({"row": pk, "value": str_val})
        
        valid = total - invalid
        return QualityResult(
            result_id=f"res_{rule.rule_id}_{int(datetime.utcnow().timestamp())}",
            rule_id=rule.rule_id,
            status=QualityStatus.PASSED if invalid == 0 else QualityStatus.FAILED,
            total_rows=total,
            valid_rows=valid,
            invalid_rows=invalid,
            sample_invalid_values=samples,
            message=f"Regex check: {invalid}/{total} don't match pattern"
        )


class CustomExpressionChecker(DataQualityChecker):
    def check(self, rule: QualityRule, data: List[Dict[str, Any]]) -> QualityResult:
        expression = rule.parameters.get("expression")
        if not expression:
            return QualityResult(
                result_id=f"res_{rule.rule_id}_{int(datetime.utcnow().timestamp())}",
                rule_id=rule.rule_id,
                status=QualityStatus.ERROR,
                message="Expression not specified for custom check"
            )
        
        total = 0
        invalid = 0
        samples = []
        
        for i, row in enumerate(data):
            total += 1
            try:
                result = eval(expression, {"row": row, "i": i})
                if not bool(result):
                    invalid += 1
                    if len(samples) < 10:
                        pk = {k: row.get(k) for k in row if "id" in k.lower()}
                        samples.append({"row": pk, "row_data": row})
            except Exception as e:
                invalid += 1
        
        valid = total - invalid
        return QualityResult(
            result_id=f"res_{rule.rule_id}_{int(datetime.utcnow().timestamp())}",
            rule_id=rule.rule_id,
            status=QualityStatus.PASSED if invalid == 0 else QualityStatus.FAILED,
            total_rows=total,
            valid_rows=valid,
            invalid_rows=invalid,
            sample_invalid_values=samples,
            message=f"Custom expression check: {invalid}/{total} failed"
        )


class QualityRuleRegistry:
    def __init__(self):
        self._rules: Dict[str, QualityRule] = {}
        self._checkers: Dict[QualityRuleType, DataQualityChecker] = {
            QualityRuleType.NULL_CHECK: NullChecker(),
            QualityRuleType.UNIQUENESS: UniquenessChecker(),
            QualityRuleType.RANGE_CHECK: RangeChecker(),
            QualityRuleType.REGEX_MATCH: RegexChecker(),
            QualityRuleType.CUSTOM_EXPRESSION: CustomExpressionChecker()
        }
    
    def add_rule(self, rule: QualityRule):
        self._rules[rule.rule_id] = rule
    
    def get_rule(self, rule_id: str) -> Optional[QualityRule]:
        return self._rules.get(rule_id)
    
    def list_rules(self, enabled_only: bool = False) -> List[QualityRule]:
        rules = list(self._rules.values())
        if enabled_only:
            rules = [r for r in rules if r.enabled]
        return rules
    
    def remove_rule(self, rule_id: str):
        if rule_id in self._rules:
            del self._rules[rule_id]
    
    def get_checker(self, rule_type: QualityRuleType) -> Optional[DataQualityChecker]:
        return self._checkers.get(rule_type)
    
    def register_checker(self, rule_type: QualityRuleType, checker: DataQualityChecker):
        self._checkers[rule_type] = checker


class AnomalyStore:
    def __init__(self):
        self._anomalies: Dict[str, AnomalyRecord] = {}
    
    def add(self, anomaly: AnomalyRecord):
        self._anomalies[anomaly.anomaly_id] = anomaly
    
    def list_by_rule(self, rule_id: str) -> List[AnomalyRecord]:
        return [a for a in self._anomalies.values() if a.rule_id == rule_id]
    
    def list_by_table(self, table: str) -> List[AnomalyRecord]:
        return [a for a in self._anomalies.values() if a.table == table]
    
    def list_unresolved(self) -> List[AnomalyRecord]:
        return [a for a in self._anomalies.values() if not a.resolved]
    
    def resolve(self, anomaly_id: str) -> bool:
        if anomaly_id in self._anomalies:
            self._anomalies[anomaly_id].resolved = True
            self._anomalies[anomaly_id].resolved_at = datetime.utcnow()
            return True
        return False
    
    def get_stats(self) -> Dict[str, Any]:
        total = len(self._anomalies)
        unresolved = len(self.list_unresolved())
        return {
            "total": total,
            "unresolved": unresolved,
            "resolved": total - unresolved
        }


class DataQualityService:
    def __init__(self):
        self._registry = QualityRuleRegistry()
        self._anomaly_store = AnomalyStore()
        self._results: Dict[str, List[QualityResult]] = {}
        self._running = False
        self._scheduler_task: Optional[asyncio.Task] = None
        self._logger = get_logger("data_quality_service")
    
    @property
    def rules(self) -> QualityRuleRegistry:
        return self._registry
    
    @property
    def anomalies(self) -> AnomalyStore:
        return self._anomaly_store
    
    async def execute_rule(
        self,
        rule_id: str,
        data: List[Dict[str, Any]]
    ) -> Optional[QualityResult]:
        rule = self._registry.get_rule(rule_id)
        if not rule or not rule.enabled:
            return None
        
        checker = self._registry.get_checker(rule.rule_type)
        if not checker:
            self._logger.error(
                "No checker found for rule type",
                rule_type=rule.rule_type.value
            )
            return None
        
        result = checker.check(rule, data)
        result.started_at = datetime.utcnow()
        result.completed_at = datetime.utcnow()
        result.execution_time_ms = 0.0
        
        if rule_id not in self._results:
            self._results[rule_id] = []
        self._results[rule_id].append(result)
        
        if result.status == QualityStatus.FAILED:
            self._mark_anomalies(rule, result, data)
            self._logger.warning(
                "Quality check failed",
                rule_id=rule.rule_id,
                invalid_count=result.invalid_rows
            )
        
        return result
    
    def _mark_anomalies(
        self,
        rule: QualityRule,
        result: QualityResult,
        data: List[Dict[str, Any]]
    ):
        for sample in result.sample_invalid_values:
            anomaly = AnomalyRecord(
                anomaly_id=f"anom_{rule.rule_id}_{len(self._anomaly_store._anomalies)}",
                rule_id=rule.rule_id,
                table=rule.table,
                column=rule.column,
                primary_key=sample.get("row", {}),
                value=sample.get("value", sample),
                reason=result.message,
                timestamp=datetime.utcnow()
            )
            self._anomaly_store.add(anomaly)
    
    async def execute_all(
        self,
        data_sources: Dict[str, List[Dict[str, Any]]]
    ) -> List[QualityResult]:
        results = []
        for rule in self._registry.list_rules(enabled_only=True):
            table_key = f"{rule.database}.{rule.table}"
            data = data_sources.get(table_key, [])
            if data:
                result = await self.execute_rule(rule.rule_id, data)
                if result:
                    results.append(result)
        return results
    
    def get_results(self, rule_id: str, limit: int = 100) -> List[QualityResult]:
        return self._results.get(rule_id, [])[-limit:]
    
    def get_stats(self) -> Dict[str, Any]:
        total_rules = len(self._registry.list_rules())
        enabled_rules = len(self._registry.list_rules(enabled_only=True))
        anomaly_stats = self._anomaly_store.get_stats()
        
        passed = 0
        failed = 0
        for rule_results in self._results.values():
            if rule_results:
                latest = rule_results[-1]
                if latest.status == QualityStatus.PASSED:
                    passed += 1
                elif latest.status == QualityStatus.FAILED:
                    failed += 1
        
        return {
            "total_rules": total_rules,
            "enabled_rules": enabled_rules,
            "passed_checks": passed,
            "failed_checks": failed,
            "anomalies": anomaly_stats
        }
