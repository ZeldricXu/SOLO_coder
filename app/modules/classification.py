from datetime import datetime
from typing import Any, Dict, List, Optional, Pattern, Callable
from dataclasses import dataclass, field
from collections import Counter, defaultdict
import re
from enum import Enum
import threading
import time
import uuid

from app.core.logger import logger
from app.core.models import DataCategory, SensitivityLevel, DataClassificationResult


class MetricType(str, Enum):
    COUNTER = "counter"
    GAUGE = "gauge"
    HISTOGRAM = "histogram"
    SUMMARY = "summary"


@dataclass
class MetricSample:
    value: float
    timestamp: datetime
    labels: Dict[str, str] = field(default_factory=dict)


@dataclass
class ClassificationMetric:
    name: str
    type: MetricType
    description: str
    samples: List[MetricSample] = field(default_factory=list)
    labels: List[str] = field(default_factory=list)

    def record(self, value: float, labels: Optional[Dict[str, str]] = None):
        self.samples.append(MetricSample(
            value=value,
            timestamp=datetime.utcnow(),
            labels=labels or {}
        ))

    def get_latest(self, labels: Optional[Dict[str, str]] = None) -> Optional[float]:
        for sample in reversed(self.samples):
            if labels is None or sample.labels == labels:
                return sample.value
        return None

    def get_count(self, labels: Optional[Dict[str, str]] = None) -> int:
        if labels is None:
            return len(self.samples)
        return sum(1 for s in self.samples if s.labels == labels)

    def get_sum(self, labels: Optional[Dict[str, str]] = None) -> float:
        if labels is None:
            return sum(s.value for s in self.samples)
        return sum(s.value for s in self.samples if s.labels == labels)

    def get_avg(self, labels: Optional[Dict[str, str]] = None) -> float:
        count = self.get_count(labels)
        if count == 0:
            return 0.0
        return self.get_sum(labels) / count

    def get_percentile(self, percentile: float, labels: Optional[Dict[str, str]] = None) -> float:
        values = [s.value for s in self.samples if labels is None or s.labels == labels]
        if not values:
            return 0.0
        values.sort()
        index = int(len(values) * percentile)
        return values[min(index, len(values) - 1)]


@dataclass
class ScanHistoryEntry:
    scan_id: str
    timestamp: datetime
    field_name: str
    value_preview: str
    category: DataCategory
    sensitivity: SensitivityLevel
    confidence: float
    matched_patterns: List[str]
    scan_duration_ms: float


@dataclass
class ClassificationRule:
    rule_id: str
    name: str
    category: DataCategory
    sensitivity: SensitivityLevel
    pattern: Pattern
    priority: int = 0
    sample_checks: List[str] = field(default_factory=list)
    match_count: int = 0
    last_matched_at: Optional[datetime] = None
    is_enabled: bool = True


@dataclass
class ClassificationPolicy:
    policy_id: str
    name: str
    sensitivity_level: SensitivityLevel
    actions: List[str]
    conditions: Dict[str, Any] = field(default_factory=dict)
    enabled: bool = True
    applied_count: int = 0
    last_applied_at: Optional[datetime] = None


class ClassificationMetricsRegistry:
    def __init__(self):
        self._metrics: Dict[str, ClassificationMetric] = {}
        self._lock = threading.Lock()
        self._max_samples_per_metric = 10000
        self._init_default_metrics()

    def _init_default_metrics(self):
        self._metrics = {
            "classification_total": ClassificationMetric(
                name="classification_total",
                type=MetricType.COUNTER,
                description="Total number of classifications performed",
                labels=["category", "sensitivity"]
            ),
            "classification_duration_ms": ClassificationMetric(
                name="classification_duration_ms",
                type=MetricType.HISTOGRAM,
                description="Duration of classification operations in milliseconds",
                labels=["operation"]
            ),
            "rule_matches_total": ClassificationMetric(
                name="rule_matches_total",
                type=MetricType.COUNTER,
                description="Total number of rule matches",
                labels=["rule_id", "rule_name", "category"]
            ),
            "policy_applications_total": ClassificationMetric(
                name="policy_applications_total",
                type=MetricType.COUNTER,
                description="Total number of policy applications",
                labels=["policy_id", "sensitivity_level"]
            ),
            "sensitive_fields_detected": ClassificationMetric(
                name="sensitive_fields_detected",
                type=MetricType.GAUGE,
                description="Number of sensitive fields detected",
                labels=["category", "sensitivity"]
            ),
            "scanner_confidence_distribution": ClassificationMetric(
                name="scanner_confidence_distribution",
                type=MetricType.HISTOGRAM,
                description="Distribution of classification confidence scores"
            ),
            "dataset_scan_records": ClassificationMetric(
                name="dataset_scan_records",
                type=MetricType.COUNTER,
                description="Number of records scanned per dataset scan"
            ),
            "high_risk_alerts": ClassificationMetric(
                name="high_risk_alerts",
                type=MetricType.COUNTER,
                description="Number of high risk classifications detected",
                labels=["risk_level"]
            )
        }

    def record_classification(self, category: DataCategory, sensitivity: SensitivityLevel,
                               duration_ms: float, confidence: float):
        with self._lock:
            self._metrics["classification_total"].record(
                1, {"category": category, "sensitivity": sensitivity}
            )
            self._metrics["classification_duration_ms"].record(
                duration_ms, {"operation": "scan_value"}
            )
            self._metrics["scanner_confidence_distribution"].record(confidence)

            sensitivity_order = list(SensitivityLevel)
            if sensitivity_order.index(sensitivity) >= sensitivity_order.index(SensitivityLevel.CONFIDENTIAL):
                risk_level = "high" if sensitivity == SensitivityLevel.CONFIDENTIAL else "critical"
                self._metrics["high_risk_alerts"].record(
                    1, {"risk_level": risk_level}
                )

    def record_rule_match(self, rule_id: str, rule_name: str, category: DataCategory):
        with self._lock:
            self._metrics["rule_matches_total"].record(
                1, {"rule_id": rule_id, "rule_name": rule_name, "category": category}
            )

    def record_policy_application(self, policy_id: str, sensitivity_level: SensitivityLevel):
        with self._lock:
            self._metrics["policy_applications_total"].record(
                1, {"policy_id": policy_id, "sensitivity_level": sensitivity_level}
            )

    def record_dataset_scan(self, record_count: int):
        with self._lock:
            self._metrics["dataset_scan_records"].record(float(record_count))

    def update_sensitive_fields_gauge(self, counts: Dict[DataCategory, Dict[SensitivityLevel, int]]):
        with self._lock:
            for category, sensitivities in counts.items():
                for sensitivity, count in sensitivities.items():
                    self._metrics["sensitive_fields_detected"].record(
                        float(count), {"category": category, "sensitivity": sensitivity}
                    )

    def get_metric(self, name: str) -> Optional[ClassificationMetric]:
        return self._metrics.get(name)

    def get_all_metrics(self) -> Dict[str, Dict[str, Any]]:
        with self._lock:
            result = {}
            for name, metric in self._metrics.items():
                result[name] = {
                    "name": metric.name,
                    "type": metric.type,
                    "description": metric.description,
                    "labels": metric.labels,
                    "sample_count": len(metric.samples),
                    "latest": metric.get_latest(),
                    "total": metric.get_sum() if metric.type in [MetricType.COUNTER, MetricType.HISTOGRAM] else metric.get_latest(),
                    "average": metric.get_avg() if metric.type in [MetricType.HISTOGRAM, MetricType.SUMMARY] else None,
                    "p50": metric.get_percentile(0.5) if metric.type == MetricType.HISTOGRAM else None,
                    "p95": metric.get_percentile(0.95) if metric.type == MetricType.HISTOGRAM else None,
                    "p99": metric.get_percentile(0.99) if metric.type == MetricType.HISTOGRAM else None
                }
            return result

    def export_prometheus_format(self) -> str:
        with self._lock:
            lines = []
            for name, metric in self._metrics.items():
                lines.append(f"# HELP {name} {metric.description}")
                lines.append(f"# TYPE {name} {metric.type}")

                label_counts: Dict[tuple, int] = defaultdict(int)
                label_values: Dict[tuple, List[float]] = defaultdict(list)

                for sample in metric.samples:
                    label_key = tuple(sorted(sample.labels.items()))
                    label_counts[label_key] += 1
                    label_values[label_key].append(sample.value)

                for label_key, values in label_values.items():
                    label_str = ""
                    if label_key:
                        label_parts = [f'{k}="{v}"' for k, v in label_key]
                        label_str = f"{{{','.join(label_parts)}}}"

                    if metric.type in [MetricType.COUNTER, MetricType.GAUGE]:
                        total = sum(values)
                        lines.append(f"{name}{label_str} {total}")
                    elif metric.type == MetricType.HISTOGRAM:
                        values.sort()
                        n = len(values)
                        if n > 0:
                            lines.append(f'{name}_sum{label_str} {sum(values)}')
                            lines.append(f'{name}_count{label_str} {n}')
                            for p in [0.5, 0.75, 0.9, 0.95, 0.99]:
                                idx = int(n * p)
                                lines.append(f'{name}{{quantile="{p}"}}{label_str} {values[min(idx, n-1)]}')

            return "\n".join(lines)

    def reset_metric(self, name: str) -> bool:
        with self._lock:
            if name in self._metrics:
                self._metrics[name].samples.clear()
                return True
            return False

    def reset_all(self):
        with self._lock:
            for metric in self._metrics.values():
                metric.samples.clear()


class ClassificationHealthChecker:
    def __init__(self, metrics: ClassificationMetricsRegistry):
        self._metrics = metrics
        self._checks: Dict[str, Callable[[], Dict[str, Any]]] = {}
        self._init_default_checks()

    def _init_default_checks(self):
        self._checks = {
            "rule_coverage": self._check_rule_coverage,
            "classification_latency": self._check_classification_latency,
            "high_risk_detection": self._check_high_risk_detection,
            "policy_application_rate": self._check_policy_application_rate
        }

    def _check_rule_coverage(self) -> Dict[str, Any]:
        rule_matches = self._metrics.get_metric("rule_matches_total")
        if not rule_matches:
            return {"status": "unknown", "message": "No rule match data available"}

        total_matches = rule_matches.get_count()
        if total_matches == 0:
            return {"status": "warning", "message": "No rules have been matched yet"}

        return {
            "status": "healthy",
            "message": f"Total rule matches: {total_matches}",
            "details": {"total_matches": total_matches}
        }

    def _check_classification_latency(self) -> Dict[str, Any]:
        latency = self._metrics.get_metric("classification_duration_ms")
        if not latency:
            return {"status": "unknown", "message": "No latency data available"}

        p99 = latency.get_percentile(0.99)
        avg = latency.get_avg()

        if p99 > 100:
            return {
                "status": "warning",
                "message": f"High classification latency: p99={p99:.2f}ms",
                "details": {"avg": avg, "p99": p99}
            }

        return {
            "status": "healthy",
            "message": f"Classification latency healthy: avg={avg:.2f}ms, p99={p99:.2f}ms",
            "details": {"avg": avg, "p99": p99}
        }

    def _check_high_risk_detection(self) -> Dict[str, Any]:
        alerts = self._metrics.get_metric("high_risk_alerts")
        if not alerts:
            return {"status": "unknown", "message": "No alert data available"}

        critical_count = alerts.get_count({"risk_level": "critical"})
        high_count = alerts.get_count({"risk_level": "high"})

        return {
            "status": "healthy",
            "message": f"High risk detection active: {critical_count} critical, {high_count} high",
            "details": {
                "critical_alerts": critical_count,
                "high_alerts": high_count
            }
        }

    def _check_policy_application_rate(self) -> Dict[str, Any]:
        policies = self._metrics.get_metric("policy_applications_total")
        if not policies:
            return {"status": "unknown", "message": "No policy application data available"}

        total_applications = policies.get_count()
        return {
            "status": "healthy",
            "message": f"Total policy applications: {total_applications}",
            "details": {"total_applications": total_applications}
        }

    def run_all_checks(self) -> Dict[str, Any]:
        results = {}
        for check_name, check_func in self._checks.items():
            try:
                results[check_name] = check_func()
            except Exception as e:
                results[check_name] = {"status": "error", "message": str(e)}

        overall_status = "healthy"
        for check_result in results.values():
            if check_result.get("status") == "error":
                overall_status = "degraded"
                break
            elif check_result.get("status") == "warning":
                overall_status = "warning"

        return {
            "overall_status": overall_status,
            "timestamp": datetime.utcnow().isoformat(),
            "checks": results
        }

    def register_check(self, name: str, check_func: Callable[[], Dict[str, Any]]):
        self._checks[name] = check_func

    def unregister_check(self, name: str) -> bool:
        if name in self._checks:
            del self._checks[name]
            return True
        return False


class ClassificationTelemetry:
    def __init__(self):
        self._metrics_registry = ClassificationMetricsRegistry()
        self._health_checker = ClassificationHealthChecker(self._metrics_registry)
        self._scan_history: List[ScanHistoryEntry] = []
        self._max_history_size = 10000
        self._lock = threading.Lock()

    @property
    def metrics(self) -> ClassificationMetricsRegistry:
        return self._metrics_registry

    @property
    def health_checker(self) -> ClassificationHealthChecker:
        return self._health_checker

    def record_scan(self, entry: ScanHistoryEntry):
        with self._lock:
            self._scan_history.append(entry)
            if len(self._scan_history) > self._max_history_size:
                self._scan_history = self._scan_history[-self._max_history_size:]

    def get_scan_history(self, limit: int = 100,
                         category: Optional[DataCategory] = None,
                         sensitivity: Optional[SensitivityLevel] = None,
                         min_confidence: Optional[float] = None) -> List[Dict[str, Any]]:
        with self._lock:
            filtered = self._scan_history

            if category:
                filtered = [e for e in filtered if e.category == category]
            if sensitivity:
                filtered = [e for e in filtered if e.sensitivity == sensitivity]
            if min_confidence is not None:
                filtered = [e for e in filtered if e.confidence >= min_confidence]

            return [
                {
                    "scan_id": e.scan_id,
                    "timestamp": e.timestamp.isoformat(),
                    "field_name": e.field_name,
                    "value_preview": e.value_preview,
                    "category": e.category,
                    "sensitivity": e.sensitivity,
                    "confidence": e.confidence,
                    "matched_patterns": e.matched_patterns,
                    "scan_duration_ms": e.scan_duration_ms
                }
                for e in filtered[-limit:]
            ]

    def get_scan_statistics(self) -> Dict[str, Any]:
        with self._lock:
            if not self._scan_history:
                return {"total_scans": 0}

            categories = Counter(e.category for e in self._scan_history)
            sensitivities = Counter(e.sensitivity for e in self._scan_history)
            avg_duration = sum(e.scan_duration_ms for e in self._scan_history) / len(self._scan_history)
            avg_confidence = sum(e.confidence for e in self._scan_history) / len(self._scan_history)

            return {
                "total_scans": len(self._scan_history),
                "by_category": dict(categories),
                "by_sensitivity": dict(sensitivities),
                "avg_duration_ms": avg_duration,
                "avg_confidence": avg_confidence,
                "first_scan_at": self._scan_history[0].timestamp.isoformat(),
                "last_scan_at": self._scan_history[-1].timestamp.isoformat()
            }

    def get_observability_summary(self) -> Dict[str, Any]:
        return {
            "metrics": self._metrics_registry.get_all_metrics(),
            "health": self._health_checker.run_all_checks(),
            "statistics": self.get_scan_statistics()
        }

    def export_metrics(self, format_type: str = "json") -> Any:
        if format_type == "prometheus":
            return self._metrics_registry.export_prometheus_format()
        return self._metrics_registry.get_all_metrics()


class SensitiveDataScanner:
    def __init__(self, telemetry: Optional[ClassificationTelemetry] = None):
        self._rules: List[ClassificationRule] = []
        self._category_keywords: Dict[DataCategory, List[str]] = {
            DataCategory.PII: ["name", "email", "phone", "ssn", "id", "passport", "address", "user", "username"],
            DataCategory.FINANCIAL: ["credit", "card", "bank", "account", "payment", "amount", "salary", "income", "iban"],
            DataCategory.HEALTH: ["health", "medical", "patient", "diagnosis", "disease", "drug", "prescription", "blood"],
            DataCategory.LOCATION: ["location", "address", "gps", "coordinate", "latitude", "longitude", "city", "zip"]
        }
        self._telemetry = telemetry or ClassificationTelemetry()
        self._init_default_rules()

    def _init_default_rules(self):
        default_patterns = [
            ("email", r"[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}", DataCategory.PII, SensitivityLevel.CONFIDENTIAL),
            ("phone_cn", r"1[3-9]\d{9}", DataCategory.PII, SensitivityLevel.CONFIDENTIAL),
            ("phone_us", r"\+?1?[-. (]*\d{3}[-. )]*\d{3}[-. ]*\d{4}", DataCategory.PII, SensitivityLevel.CONFIDENTIAL),
            ("ssn_us", r"\b\d{3}[- ]?\d{2}[- ]?\d{4}\b", DataCategory.PII, SensitivityLevel.RESTRICTED),
            ("id_card_cn", r"\b[1-9]\d{5}(19|20)\d{2}(0[1-9]|1[0-2])(0[1-9]|[12]\d|3[01])\d{3}[\dXx]\b", DataCategory.PII, SensitivityLevel.RESTRICTED),
            ("passport", r"\b[A-Z]{1,2}\d{6,9}\b", DataCategory.PII, SensitivityLevel.CONFIDENTIAL),
            ("credit_card", r"\b(?:\d[ -]*?){13,16}\b", DataCategory.FINANCIAL, SensitivityLevel.RESTRICTED),
            ("iban", r"\b[A-Z]{2}\d{2}[A-Z0-9]{4}\d{7}(?:[A-Z0-9]?){0,16}\b", DataCategory.FINANCIAL, SensitivityLevel.RESTRICTED),
            ("amount", r"[¥$€£]\s?\d+(?:[.,]\d{2})?", DataCategory.FINANCIAL, SensitivityLevel.INTERNAL),
            ("medical_code", r"\b[ICD]-?\d{3,4}(?:\.\d{1,2})?\b", DataCategory.HEALTH, SensitivityLevel.CONFIDENTIAL),
            ("patient_id", r"\bPAT[_\-]?\d{5,10}\b", DataCategory.HEALTH, SensitivityLevel.CONFIDENTIAL),
            ("latitude", r"-?\d{1,3}\.\d{4,}", DataCategory.LOCATION, SensitivityLevel.INTERNAL),
            ("longitude", r"-?\d{1,3}\.\d{4,}", DataCategory.LOCATION, SensitivityLevel.INTERNAL),
            ("gps_coords", r"-?\d{1,3}\.\d{4,},\s*-?\d{1,3}\.\d{4,}", DataCategory.LOCATION, SensitivityLevel.CONFIDENTIAL),
            ("ip_address", r"\b\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}\b", DataCategory.LOCATION, SensitivityLevel.INTERNAL),
        ]

        for name, pattern, category, sensitivity in default_patterns:
            rule = ClassificationRule(
                rule_id=f"rule_{name}",
                name=name,
                category=category,
                sensitivity=sensitivity,
                pattern=re.compile(pattern, re.IGNORECASE),
                priority=1
            )
            self._rules.append(rule)

    def add_rule(self, rule: ClassificationRule):
        self._rules.append(rule)
        self._rules.sort(key=lambda r: r.priority, reverse=True)
        logger.info(f"Added classification rule: {rule.rule_id}")

    def remove_rule(self, rule_id: str):
        self._rules = [r for r in self._rules if r.rule_id != rule_id]
        logger.info(f"Removed classification rule: {rule_id}")

    def enable_rule(self, rule_id: str) -> bool:
        for rule in self._rules:
            if rule.rule_id == rule_id:
                rule.is_enabled = True
                logger.info(f"Enabled rule: {rule_id}")
                return True
        return False

    def disable_rule(self, rule_id: str) -> bool:
        for rule in self._rules:
            if rule.rule_id == rule_id:
                rule.is_enabled = False
                logger.info(f"Disabled rule: {rule_id}")
                return True
        return False

    def list_rules(self, include_stats: bool = True) -> List[Dict[str, Any]]:
        rules = sorted(self._rules, key=lambda r: r.priority, reverse=True)
        result = []
        for rule in rules:
            rule_dict = {
                "rule_id": rule.rule_id,
                "name": rule.name,
                "category": rule.category,
                "sensitivity": rule.sensitivity,
                "priority": rule.priority,
                "is_enabled": rule.is_enabled
            }
            if include_stats:
                rule_dict["match_count"] = rule.match_count
                rule_dict["last_matched_at"] = rule.last_matched_at.isoformat() if rule.last_matched_at else None
            result.append(rule_dict)
        return result

    def scan_value(self, field_name: str, value: Any) -> DataClassificationResult:
        start_time = time.time()

        if value is None:
            result = DataClassificationResult(
                field_name=field_name,
                category=DataCategory.GENERAL,
                sensitivity=SensitivityLevel.PUBLIC,
                confidence=0.0,
                matched_patterns=[]
            )
            duration_ms = (time.time() - start_time) * 1000
            self._telemetry.metrics.record_classification(
                DataCategory.GENERAL, SensitivityLevel.PUBLIC, duration_ms, 0.0
            )
            return result

        str_value = str(value)
        matched_rules: List[ClassificationRule] = []
        matched_patterns: List[str] = []

        for rule in self._rules:
            if not rule.is_enabled:
                continue

            if rule.pattern.search(str_value):
                matched_rules.append(rule)
                matched_patterns.append(rule.name)
                rule.match_count += 1
                rule.last_matched_at = datetime.utcnow()
                self._telemetry.metrics.record_rule_match(
                    rule.rule_id, rule.name, rule.category
                )

        category = DataCategory.GENERAL
        sensitivity = SensitivityLevel.PUBLIC
        confidence = 0.0

        if matched_rules:
            matched_rules.sort(key=lambda r: r.priority, reverse=True)
            best_rule = matched_rules[0]
            category = best_rule.category
            sensitivity = max((r.sensitivity for r in matched_rules), key=lambda s: list(SensitivityLevel).index(s))
            confidence = min(1.0, 0.5 + 0.1 * len(matched_rules))

        field_name_lower = field_name.lower()
        for cat, keywords in self._category_keywords.items():
            if any(kw in field_name_lower for kw in keywords):
                if category == DataCategory.GENERAL:
                    category = cat
                    sensitivity = max(sensitivity, SensitivityLevel.INTERNAL)
                    confidence = max(confidence, 0.3)
                break

        result = DataClassificationResult(
            field_name=field_name,
            category=category,
            sensitivity=sensitivity,
            confidence=confidence,
            matched_patterns=matched_patterns
        )

        duration_ms = (time.time() - start_time) * 1000
        self._telemetry.metrics.record_classification(category, sensitivity, duration_ms, confidence)

        value_preview = str_value[:50] + "..." if len(str_value) > 50 else str_value
        self._telemetry.record_scan(ScanHistoryEntry(
            scan_id=f"scan_{uuid.uuid4().hex[:8]}",
            timestamp=datetime.utcnow(),
            field_name=field_name,
            value_preview=value_preview,
            category=category,
            sensitivity=sensitivity,
            confidence=confidence,
            matched_patterns=matched_patterns,
            scan_duration_ms=duration_ms
        ))

        return result

    def scan_record(self, record: Dict[str, Any]) -> Dict[str, DataClassificationResult]:
        results = {}
        for field_name, value in record.items():
            results[field_name] = self.scan_value(field_name, value)
        return results

    def scan_dataset(self, dataset: List[Dict[str, Any]],
                      sample_rate: float = 1.0,
                      record_progress: bool = True) -> Dict[str, DataClassificationResult]:
        if not dataset:
            return {}

        self._telemetry.metrics.record_dataset_scan(len(dataset))

        field_results: Dict[str, List[DataClassificationResult]] = {}
        sample_size = int(len(dataset) * sample_rate)
        sampled = dataset[:sample_size] if sample_size > 0 else dataset

        for record in sampled:
            for field_name, value in record.items():
                result = self.scan_value(field_name, value)
                if field_name not in field_results:
                    field_results[field_name] = []
                field_results[field_name].append(result)

        final_results: Dict[str, DataClassificationResult] = {}
        sensitive_counts: Dict[DataCategory, Dict[SensitivityLevel, int]] = defaultdict(
            lambda: defaultdict(int)
        )

        for field_name, results in field_results.items():
            categories: Dict[DataCategory, int] = {}
            sensitivities: Dict[SensitivityLevel, int] = {}
            all_patterns = set()

            for r in results:
                categories[r.category] = categories.get(r.category, 0) + 1
                sensitivities[r.sensitivity] = sensitivities.get(r.sensitivity, 0) + 1
                all_patterns.update(r.matched_patterns)

                if list(SensitivityLevel).index(r.sensitivity) >= list(SensitivityLevel).index(SensitivityLevel.INTERNAL):
                    sensitive_counts[r.category][r.sensitivity] += 1

            final_category = max(categories.items(), key=lambda x: x[1])[0]
            final_sensitivity = max(sensitivities.items(), key=lambda x: x[1])[0]
            avg_confidence = sum(r.confidence for r in results) / len(results)

            final_results[field_name] = DataClassificationResult(
                field_name=field_name,
                category=final_category,
                sensitivity=final_sensitivity,
                confidence=avg_confidence,
                matched_patterns=list(all_patterns)
            )

        self._telemetry.metrics.update_sensitive_fields_gauge(dict(sensitive_counts))

        return final_results

    def get_telemetry(self) -> ClassificationTelemetry:
        return self._telemetry


class PolicyEngine:
    def __init__(self, telemetry: Optional[ClassificationTelemetry] = None):
        self._policies: List[ClassificationPolicy] = []
        self._telemetry = telemetry or ClassificationTelemetry()
        self._init_default_policies()

    def _init_default_policies(self):
        default_policies = [
            ("restricted_data_mask", SensitivityLevel.RESTRICTED, ["mask", "encrypt", "audit_access"]),
            ("confidential_data_anonymize", SensitivityLevel.CONFIDENTIAL, ["anonymize", "access_control"]),
            ("internal_data_log", SensitivityLevel.INTERNAL, ["log_access", "monitor"]),
        ]
        for name, level, actions in default_policies:
            self._policies.append(ClassificationPolicy(
                policy_id=f"policy_{name}",
                name=name,
                sensitivity_level=level,
                actions=actions,
                enabled=True
            ))

    def add_policy(self, policy: ClassificationPolicy):
        self._policies.append(policy)
        logger.info(f"Added policy: {policy.policy_id}")

    def remove_policy(self, policy_id: str):
        self._policies = [p for p in self._policies if p.policy_id != policy_id]
        logger.info(f"Removed policy: {policy_id}")

    def list_policies(self, include_stats: bool = True) -> List[Dict[str, Any]]:
        result = []
        for policy in self._policies:
            policy_dict = {
                "policy_id": policy.policy_id,
                "name": policy.name,
                "sensitivity_level": policy.sensitivity_level,
                "actions": policy.actions,
                "enabled": policy.enabled
            }
            if include_stats:
                policy_dict["applied_count"] = policy.applied_count
                policy_dict["last_applied_at"] = policy.last_applied_at.isoformat() if policy.last_applied_at else None
            result.append(policy_dict)
        return result

    def enable_policy(self, policy_id: str) -> bool:
        for policy in self._policies:
            if policy.policy_id == policy_id:
                policy.enabled = True
                logger.info(f"Enabled policy: {policy_id}")
                return True
        return False

    def disable_policy(self, policy_id: str) -> bool:
        for policy in self._policies:
            if policy.policy_id == policy_id:
                policy.enabled = False
                logger.info(f"Disabled policy: {policy_id}")
                return True
        return False

    def get_applicable_actions(self, result: DataClassificationResult) -> List[str]:
        applicable_actions = []
        sensitivity_order = list(SensitivityLevel)

        for policy in self._policies:
            if not policy.enabled:
                continue

            should_apply = False
            if result.sensitivity == policy.sensitivity_level:
                should_apply = True
            elif (sensitivity_order.index(result.sensitivity) >
                  sensitivity_order.index(policy.sensitivity_level)):
                should_apply = True

            if should_apply:
                applicable_actions.extend(policy.actions)
                policy.applied_count += 1
                policy.last_applied_at = datetime.utcnow()
                self._telemetry.metrics.record_policy_application(
                    policy.policy_id, policy.sensitivity_level
                )

        return list(dict.fromkeys(applicable_actions))

    def evaluate_policy(self, result: DataClassificationResult) -> Dict[str, Any]:
        actions = self.get_applicable_actions(result)
        return {
            "classification": result.model_dump(),
            "required_actions": actions,
            "risk_level": self._calculate_risk_level(result),
            "evaluation_timestamp": datetime.utcnow().isoformat()
        }

    def _calculate_risk_level(self, result: DataClassificationResult) -> str:
        sensitivity_risk = {
            SensitivityLevel.PUBLIC: "low",
            SensitivityLevel.INTERNAL: "medium",
            SensitivityLevel.CONFIDENTIAL: "high",
            SensitivityLevel.RESTRICTED: "critical"
        }
        return sensitivity_risk.get(result.sensitivity, "unknown")

    def get_telemetry(self) -> ClassificationTelemetry:
        return self._telemetry


class DataClassificationModule:
    def __init__(self):
        self._telemetry = ClassificationTelemetry()
        self._scanner = SensitiveDataScanner(self._telemetry)
        self._policy_engine = PolicyEngine(self._telemetry)
        logger.info("DataClassificationModule initialized")

    @property
    def scanner(self) -> SensitiveDataScanner:
        return self._scanner

    @property
    def policy_engine(self) -> PolicyEngine:
        return self._policy_engine

    @property
    def telemetry(self) -> ClassificationTelemetry:
        return self._telemetry

    def classify_and_evaluate(self, field_name: str, value: Any) -> Dict[str, Any]:
        result = self._scanner.scan_value(field_name, value)
        return self._policy_engine.evaluate_policy(result)

    def classify_record(self, record: Dict[str, Any]) -> Dict[str, Dict[str, Any]]:
        scan_results = self._scanner.scan_record(record)
        return {
            field: self._policy_engine.evaluate_policy(result)
            for field, result in scan_results.items()
        }

    def classify_dataset(self, dataset: List[Dict[str, Any]],
                          sample_rate: float = 1.0) -> Dict[str, Dict[str, Any]]:
        scan_results = self._scanner.scan_dataset(dataset, sample_rate)
        return {
            field: self._policy_engine.evaluate_policy(result)
            for field, result in scan_results.items()
        }

    def get_data_summary(self, dataset: List[Dict[str, Any]]) -> Dict[str, Any]:
        classifications = self.classify_dataset(dataset)
        summary = {
            "total_fields": len(classifications),
            "categories": {},
            "sensitivity_levels": {},
            "high_risk_fields": []
        }

        for field, evaluation in classifications.items():
            cat = evaluation["classification"]["category"]
            sens = evaluation["classification"]["sensitivity"]
            risk = evaluation["risk_level"]

            summary["categories"][cat] = summary["categories"].get(cat, 0) + 1
            summary["sensitivity_levels"][sens] = summary["sensitivity_levels"].get(sens, 0) + 1

            if risk in ["high", "critical"]:
                summary["high_risk_fields"].append({
                    "field": field,
                    "risk_level": risk,
                    "required_actions": evaluation["required_actions"]
                })

        return summary

    def get_observability_metrics(self, format_type: str = "json") -> Any:
        return self._telemetry.export_metrics(format_type)

    def get_health_status(self) -> Dict[str, Any]:
        return self._telemetry.health_checker.run_all_checks()

    def get_scan_history(self, limit: int = 100,
                         category: Optional[DataCategory] = None,
                         sensitivity: Optional[SensitivityLevel] = None,
                         min_confidence: Optional[float] = None) -> List[Dict[str, Any]]:
        return self._telemetry.get_scan_history(limit, category, sensitivity, min_confidence)

    def get_scan_statistics(self) -> Dict[str, Any]:
        return self._telemetry.get_scan_statistics()

    def get_observability_summary(self) -> Dict[str, Any]:
        return self._telemetry.get_observability_summary()

    def list_rules_with_stats(self) -> List[Dict[str, Any]]:
        return self._scanner.list_rules(include_stats=True)

    def list_policies_with_stats(self) -> List[Dict[str, Any]]:
        return self._policy_engine.list_policies(include_stats=True)


classification_module = DataClassificationModule()
