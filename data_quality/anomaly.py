from dataclasses import dataclass, field
from enum import Enum
from typing import Any, Dict, List, Optional, Set, Tuple
from datetime import datetime
import os
import json
import pandas as pd
import numpy as np
from .checker import CheckResult, RuleResult


class AnomalyLevel(Enum):
    INFO = "info"
    WARNING = "warning"
    ERROR = "error"
    CRITICAL = "critical"

    @property
    def weight(self) -> int:
        weights = {
            AnomalyLevel.INFO: 1,
            AnomalyLevel.WARNING: 2,
            AnomalyLevel.ERROR: 3,
            AnomalyLevel.CRITICAL: 4,
        }
        return weights[self]


class MarkStrategy(Enum):
    FLAG = "flag"
    REMOVE = "remove"
    ISOLATE = "isolate"
    REPAIR = "repair"


@dataclass
class AnomalyRecord:
    index: int
    column: Optional[str]
    rule_name: str
    rule_type: str
    level: AnomalyLevel
    value: Any = None
    message: str = ""
    marked_at: datetime = field(default_factory=datetime.now)
    metadata: Dict[str, Any] = field(default_factory=dict)

    def to_dict(self) -> Dict[str, Any]:
        return {
            "index": self.index,
            "column": self.column,
            "rule_name": self.rule_name,
            "rule_type": self.rule_type,
            "level": self.level.value,
            "value": str(self.value) if self.value is not None else None,
            "message": self.message,
            "marked_at": self.marked_at.isoformat(),
            "metadata": self.metadata,
        }

    @staticmethod
    def from_dict(data: Dict[str, Any]) -> "AnomalyRecord":
        return AnomalyRecord(
            index=data["index"],
            column=data.get("column"),
            rule_name=data["rule_name"],
            rule_type=data["rule_type"],
            level=AnomalyLevel(data["level"]),
            value=data.get("value"),
            message=data.get("message", ""),
            marked_at=datetime.fromisoformat(data["marked_at"]),
            metadata=data.get("metadata", {}),
        )


class IsolationStore:
    def __init__(self, base_path: str = "./isolation"):
        self.base_path = base_path
        os.makedirs(base_path, exist_ok=True)

    def save(
        self,
        df: pd.DataFrame,
        anomalies: List[AnomalyRecord],
        dataset_name: str,
        include_clean: bool = False,
    ) -> Dict[str, str]:
        timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
        dataset_path = os.path.join(self.base_path, dataset_name)
        os.makedirs(dataset_path, exist_ok=True)

        anomaly_indices = list({a.index for a in anomalies})
        anomaly_df = df.loc[anomaly_indices].copy()
        anomaly_df["_anomaly_level"] = [
            max(
                (a.level.value for a in anomalies if a.index == idx),
                default="info",
            )
            for idx in anomaly_df.index
        ]
        anomaly_df["_anomaly_rules"] = [
            ";".join(sorted({a.rule_name for a in anomalies if a.index == idx}))
            for idx in anomaly_df.index
        ]
        anomaly_df["_marked_at"] = datetime.now()

        anomaly_file = os.path.join(dataset_path, f"anomalies_{timestamp}.csv")
        anomaly_df.to_csv(anomaly_file, index=True, encoding="utf-8")

        anomalies_file = os.path.join(dataset_path, f"anomalies_{timestamp}.json")
        with open(anomalies_file, "w", encoding="utf-8") as f:
            json.dump([a.to_dict() for a in anomalies], f, ensure_ascii=False, indent=2)

        result = {
            "anomaly_data": anomaly_file,
            "anomaly_records": anomalies_file,
            "anomaly_count": len(anomaly_indices),
            "total_count": len(df),
        }

        if include_clean:
            clean_indices = list(set(df.index) - set(anomaly_indices))
            clean_df = df.loc[clean_indices] if clean_indices else pd.DataFrame()
            clean_file = os.path.join(dataset_path, f"clean_{timestamp}.csv")
            clean_df.to_csv(clean_file, index=True, encoding="utf-8")
            result["clean_data"] = clean_file
            result["clean_count"] = len(clean_df)

        return result

    def load(
        self,
        dataset_name: str,
        timestamp: Optional[str] = None,
    ) -> Tuple[pd.DataFrame, List[AnomalyRecord]]:
        dataset_path = os.path.join(self.base_path, dataset_name)
        if not os.path.exists(dataset_path):
            raise FileNotFoundError(f"数据集不存在: {dataset_name}")

        if timestamp:
            anomaly_file = os.path.join(dataset_path, f"anomalies_{timestamp}.csv")
            records_file = os.path.join(dataset_path, f"anomalies_{timestamp}.json")
        else:
            files = sorted(
                [f for f in os.listdir(dataset_path) if f.startswith("anomalies_") and f.endswith(".csv")],
                reverse=True,
            )
            if not files:
                raise FileNotFoundError(f"没有找到异常数据文件")
            anomaly_file = os.path.join(dataset_path, files[0])
            records_file = os.path.join(dataset_path, files[0].replace(".csv", ".json"))

        if not os.path.exists(anomaly_file):
            raise FileNotFoundError(f"异常数据文件不存在: {anomaly_file}")

        df = pd.read_csv(anomaly_file, index_col=0, encoding="utf-8")

        anomalies = []
        if os.path.exists(records_file):
            with open(records_file, "r", encoding="utf-8") as f:
                records_data = json.load(f)
                anomalies = [AnomalyRecord.from_dict(r) for r in records_data]

        return df, anomalies

    def list_datasets(self) -> List[str]:
        if not os.path.exists(self.base_path):
            return []
        return [d for d in os.listdir(self.base_path) if os.path.isdir(os.path.join(self.base_path, d))]

    def list_versions(self, dataset_name: str) -> List[str]:
        dataset_path = os.path.join(self.base_path, dataset_name)
        if not os.path.exists(dataset_path):
            return []
        files = sorted(
            [f for f in os.listdir(dataset_path) if f.startswith("anomalies_") and f.endswith(".csv")],
            reverse=True,
        )
        return [f.replace("anomalies_", "").replace(".csv", "") for f in files]

    def clear(self, dataset_name: Optional[str] = None, days: Optional[int] = None) -> int:
        import shutil

        deleted_count = 0

        if dataset_name:
            dataset_path = os.path.join(self.base_path, dataset_name)
            if not os.path.exists(dataset_path):
                return 0

            if days is not None:
                cutoff = datetime.now().timestamp() - days * 24 * 3600
                for filename in os.listdir(dataset_path):
                    filepath = os.path.join(dataset_path, filename)
                    if os.path.getmtime(filepath) < cutoff:
                        os.remove(filepath)
                        deleted_count += 1
            else:
                shutil.rmtree(dataset_path)
                deleted_count = len(os.listdir(dataset_path)) if os.path.exists(dataset_path) else 0
        else:
            if days is not None:
                cutoff = datetime.now().timestamp() - days * 24 * 3600
                for root, dirs, files in os.walk(self.base_path):
                    for filename in files:
                        filepath = os.path.join(root, filename)
                        if os.path.getmtime(filepath) < cutoff:
                            os.remove(filepath)
                            deleted_count += 1
            else:
                for dataset in self.list_datasets():
                    shutil.rmtree(os.path.join(self.base_path, dataset))
                    deleted_count += 1

        return deleted_count


class AnomalyMarker:
    def __init__(
        self,
        default_strategy: MarkStrategy = MarkStrategy.FLAG,
        isolation_store: Optional[IsolationStore] = None,
        level_thresholds: Optional[Dict[str, AnomalyLevel]] = None,
    ):
        self.default_strategy = default_strategy
        self.isolation_store = isolation_store or IsolationStore()
        self.level_thresholds = level_thresholds or {
            "null_check": AnomalyLevel.ERROR,
            "uniqueness": AnomalyLevel.ERROR,
            "range": AnomalyLevel.WARNING,
            "format": AnomalyLevel.WARNING,
            "pattern": AnomalyLevel.WARNING,
            "referential_integrity": AnomalyLevel.CRITICAL,
            "business": AnomalyLevel.ERROR,
        }

    def determine_level(
        self,
        rule_result: RuleResult,
        severity: Optional[str] = None,
    ) -> AnomalyLevel:
        if severity:
            severity_map = {
                "info": AnomalyLevel.INFO,
                "warning": AnomalyLevel.WARNING,
                "error": AnomalyLevel.ERROR,
                "critical": AnomalyLevel.CRITICAL,
            }
            return severity_map.get(severity.lower(), AnomalyLevel.ERROR)

        return self.level_thresholds.get(rule_result.rule_type.value, AnomalyLevel.ERROR)

    def mark(
        self,
        df: pd.DataFrame,
        check_result: CheckResult,
        strategy: Optional[MarkStrategy] = None,
        min_level: AnomalyLevel = AnomalyLevel.INFO,
        strategies: Optional[Dict[str, MarkStrategy]] = None,
    ) -> Tuple[pd.DataFrame, List[AnomalyRecord]]:
        strategy = strategy or self.default_strategy
        strategies = strategies or {}

        anomalies = self._extract_anomalies(df, check_result, min_level)

        if strategy == MarkStrategy.FLAG:
            result_df = self._mark_flag(df, anomalies)
        elif strategy == MarkStrategy.REMOVE:
            result_df = self._mark_remove(df, anomalies)
        elif strategy == MarkStrategy.ISOLATE:
            result_df = self._mark_isolate(df, anomalies, strategies.get("dataset_name"))
        elif strategy == MarkStrategy.REPAIR:
            result_df = self._mark_repair(df, anomalies, strategies.get("repair_rules", {}))
        else:
            raise ValueError(f"不支持的标记策略: {strategy}")

        return result_df, anomalies

    def _extract_anomalies(
        self,
        df: pd.DataFrame,
        check_result: CheckResult,
        min_level: AnomalyLevel,
    ) -> List[AnomalyRecord]:
        anomalies = []
        rules_map = check_result.metadata.get("rules", {})

        for result in check_result.rule_results:
            if result.passed:
                continue

            rule = rules_map.get(result.rule_name)
            severity = getattr(rule, "severity", None) if rule else None
            level = self.determine_level(result, severity)

            if level.weight < min_level.weight:
                continue

            for i, idx in enumerate(result.failed_indices):
                value = result.failed_values[i] if i < len(result.failed_values) else None
                anomalies.append(
                    AnomalyRecord(
                        index=idx,
                        column=result.column,
                        rule_name=result.rule_name,
                        rule_type=result.rule_type.value,
                        level=level,
                        value=value,
                        message=result.message,
                        metadata={
                            "pass_rate": result.pass_rate,
                            "score": result.score,
                        },
                    )
                )

        return anomalies

    def _mark_flag(
        self,
        df: pd.DataFrame,
        anomalies: List[AnomalyRecord],
    ) -> pd.DataFrame:
        result_df = df.copy()

        if anomalies:
            anomaly_indices = list({a.index for a in anomalies})
            result_df["_anomaly"] = result_df.index.isin(anomaly_indices)
            result_df["_anomaly_level"] = result_df.index.map(
                lambda idx: max(
                    (a.level.value for a in anomalies if a.index == idx),
                    default=None,
                )
            )
            result_df["_anomaly_rules"] = result_df.index.map(
                lambda idx: ";".join(sorted({a.rule_name for a in anomalies if a.index == idx}))
                if idx in {a.index for a in anomalies}
                else None
            )
            result_df["_anomaly_count"] = result_df.index.map(
                lambda idx: sum(1 for a in anomalies if a.index == idx)
            )
        else:
            result_df["_anomaly"] = False
            result_df["_anomaly_level"] = None
            result_df["_anomaly_rules"] = None
            result_df["_anomaly_count"] = 0

        return result_df

    def _mark_remove(
        self,
        df: pd.DataFrame,
        anomalies: List[AnomalyRecord],
    ) -> pd.DataFrame:
        anomaly_indices = list({a.index for a in anomalies})
        return df.drop(index=anomaly_indices)

    def _mark_isolate(
        self,
        df: pd.DataFrame,
        anomalies: List[AnomalyRecord],
        dataset_name: Optional[str] = None,
    ) -> pd.DataFrame:
        if not dataset_name:
            dataset_name = f"dataset_{datetime.now().strftime('%Y%m%d')}"

        self.isolation_store.save(
            df=df,
            anomalies=anomalies,
            dataset_name=dataset_name,
            include_clean=True,
        )

        anomaly_indices = list({a.index for a in anomalies})
        return df.drop(index=anomaly_indices)

    def _mark_repair(
        self,
        df: pd.DataFrame,
        anomalies: List[AnomalyRecord],
        repair_rules: Dict[str, Any],
    ) -> pd.DataFrame:
        result_df = df.copy()

        for anomaly in anomalies:
            if anomaly.column and anomaly.column in repair_rules:
                repair_rule = repair_rules[anomaly.column]
                repair_type = repair_rule.get("type", "default")
                repair_value = repair_rule.get("value")

                if repair_type == "default":
                    result_df.loc[anomaly.index, anomaly.column] = repair_value
                elif repair_type == "mean":
                    mean_val = result_df[anomaly.column].mean()
                    result_df.loc[anomaly.index, anomaly.column] = mean_val
                elif repair_type == "median":
                    median_val = result_df[anomaly.column].median()
                    result_df.loc[anomaly.index, anomaly.column] = median_val
                elif repair_type == "mode":
                    mode_val = result_df[anomaly.column].mode().iloc[0]
                    result_df.loc[anomaly.index, anomaly.column] = mode_val
                elif repair_type == "ffill":
                    result_df[anomaly.column] = result_df[anomaly.column].ffill()
                elif repair_type == "bfill":
                    result_df[anomaly.column] = result_df[anomaly.column].bfill()
                elif repair_type == "interpolate":
                    result_df[anomaly.column] = result_df[anomaly.column].interpolate()

        result_df = self._mark_flag(result_df, anomalies)
        result_df["_repaired"] = result_df.index.isin({a.index for a in anomalies})

        return result_df

    def analyze_anomalies(
        self,
        anomalies: List[AnomalyRecord],
    ) -> Dict[str, Any]:
        if not anomalies:
            return {
                "total_count": 0,
                "by_level": {},
                "by_rule": {},
                "by_column": {},
                "by_type": {},
            }

        by_level: Dict[str, int] = {}
        by_rule: Dict[str, int] = {}
        by_column: Dict[str, int] = {}
        by_type: Dict[str, int] = {}

        indices: Set[int] = set()

        for anomaly in anomalies:
            indices.add(anomaly.index)

            level_key = anomaly.level.value
            by_level[level_key] = by_level.get(level_key, 0) + 1

            by_rule[anomaly.rule_name] = by_rule.get(anomaly.rule_name, 0) + 1

            column_key = anomaly.column or "unknown"
            by_column[column_key] = by_column.get(column_key, 0) + 1

            by_type[anomaly.rule_type] = by_type.get(anomaly.rule_type, 0) + 1

        max_level = max(anomalies, key=lambda a: a.level.weight).level.value if anomalies else None

        return {
            "total_count": len(anomalies),
            "affected_rows": len(indices),
            "max_level": max_level,
            "by_level": by_level,
            "by_rule": by_rule,
            "by_column": by_column,
            "by_type": by_type,
        }

    def export_anomalies(
        self,
        anomalies: List[AnomalyRecord],
        output_path: str,
        format: str = "json",
    ) -> None:
        if format == "json":
            with open(output_path, "w", encoding="utf-8") as f:
                json.dump([a.to_dict() for a in anomalies], f, ensure_ascii=False, indent=2)
        elif format == "csv":
            df = pd.DataFrame([a.to_dict() for a in anomalies])
            df.to_csv(output_path, index=False, encoding="utf-8")
        else:
            raise ValueError(f"不支持的导出格式: {format}")
