from dataclasses import dataclass, field
from enum import Enum
from typing import Any, Callable, Dict, List, Optional, Set, Union
from datetime import datetime
import pandas as pd
import numpy as np
from .rules import Rule, RuleResult, RuleType
from .config import ConfigManager


class CheckMode(Enum):
    SINGLE = "single"
    BATCH = "batch"
    INCREMENTAL = "incremental"


@dataclass
class CheckResult:
    rule_results: List[RuleResult] = field(default_factory=list)
    data_hash: str = ""
    check_time: datetime = field(default_factory=datetime.now)
    mode: CheckMode = CheckMode.BATCH
    metadata: Dict[str, Any] = field(default_factory=dict)

    @property
    def total_rules(self) -> int:
        return len(self.rule_results)

    @property
    def passed_rules(self) -> int:
        return sum(1 for r in self.rule_results if r.passed)

    @property
    def failed_rules(self) -> int:
        return sum(1 for r in self.rule_results if not r.passed)

    @property
    def total_records(self) -> int:
        if not self.rule_results:
            return 0
        return self.rule_results[0].total_count

    @property
    def overall_score(self) -> float:
        if not self.rule_results:
            return 100.0

        weighted_scores = []
        for result in self.rule_results:
            rule = self._get_rule_by_name(result.rule_name)
            weight = getattr(rule, "priority", 5)
            weighted_scores.append(result.score * weight)

        total_weight = sum(getattr(self._get_rule_by_name(r.rule_name), "priority", 5) for r in self.rule_results)
        if total_weight == 0:
            return sum(r.score for r in self.rule_results) / len(self.rule_results)

        return sum(weighted_scores) / total_weight

    @property
    def pass_rate(self) -> float:
        if self.total_rules == 0:
            return 1.0
        return self.passed_rules / self.total_rules

    def _get_rule_by_name(self, name: str) -> Optional[Rule]:
        for key, value in self.metadata.get("rules", {}).items():
            if key == name:
                return value
        return None

    def get_failed_results(self) -> List[RuleResult]:
        return [r for r in self.rule_results if not r.passed]

    def get_results_by_type(self, rule_type: RuleType) -> List[RuleResult]:
        return [r for r in self.rule_results if r.rule_type == rule_type]

    def get_result_by_name(self, name: str) -> Optional[RuleResult]:
        for result in self.rule_results:
            if result.rule_name == name:
                return result
        return None

    def get_all_failed_indices(self) -> Set[int]:
        indices = set()
        for result in self.rule_results:
            indices.update(result.failed_indices)
        return indices

    def get_quality_level(self) -> str:
        score = self.overall_score
        if score >= 95:
            return "优秀"
        elif score >= 85:
            return "良好"
        elif score >= 70:
            return "中等"
        elif score >= 60:
            return "及格"
        else:
            return "较差"

    def to_dict(self) -> Dict[str, Any]:
        return {
            "check_time": self.check_time.isoformat(),
            "mode": self.mode.value,
            "data_hash": self.data_hash,
            "total_rules": self.total_rules,
            "passed_rules": self.passed_rules,
            "failed_rules": self.failed_rules,
            "total_records": self.total_records,
            "overall_score": round(self.overall_score, 2),
            "pass_rate": round(self.pass_rate, 4),
            "quality_level": self.get_quality_level(),
            "results": [
                {
                    "rule_name": r.rule_name,
                    "rule_type": r.rule_type.value,
                    "column": r.column,
                    "passed": r.passed,
                    "total_count": r.total_count,
                    "failed_count": r.failed_count,
                    "pass_rate": round(r.pass_rate, 4),
                    "score": round(r.score, 2),
                    "message": r.message,
                    "execution_time": round(r.execution_time, 4),
                    "metadata": r.metadata,
                }
                for r in self.rule_results
            ],
            "metadata": {k: v for k, v in self.metadata.items() if k != "rules"},
        }


@dataclass
class CheckReport:
    check_result: CheckResult
    summary: Dict[str, Any] = field(default_factory=dict)
    recommendations: List[str] = field(default_factory=list)

    def to_dict(self) -> Dict[str, Any]:
        return {
            "summary": self.summary,
            "recommendations": self.recommendations,
            "check_result": self.check_result.to_dict(),
        }


class DataQualityChecker:
    def __init__(
        self,
        config_manager: Optional[ConfigManager] = None,
        fail_on_error: bool = False,
        max_failed_records: int = 1000,
    ):
        self.config_manager = config_manager or ConfigManager()
        self.fail_on_error = fail_on_error
        self.max_failed_records = max_failed_records
        self.check_history: List[CheckResult] = []

    def add_rule(self, rule: Rule, group_name: str = "default") -> None:
        self.config_manager.add_rule(rule, group_name)

    def add_rules(self, rules: List[Rule], group_name: str = "default") -> None:
        for rule in rules:
            self.add_rule(rule, group_name)

    def check_single(
        self,
        df: pd.DataFrame,
        rule: Rule,
        include_failed_data: bool = False,
    ) -> RuleResult:
        if not rule.enabled:
            return RuleResult(
                rule_name=rule.name,
                rule_type=rule.rule_type,
                column=rule.column,
                passed=True,
                total_count=len(df),
                failed_count=0,
                message="规则已禁用",
                metadata={"disabled": True},
            )

        result = rule.validate(df)

        if len(result.failed_indices) > self.max_failed_records:
            result.failed_indices = result.failed_indices[: self.max_failed_records]
            result.failed_values = result.failed_values[: self.max_failed_records]
            result.message += f" (仅显示前{self.max_failed_records}条)"

        if include_failed_data and result.failed_indices:
            result.metadata["failed_data"] = df.loc[result.failed_indices].to_dict("records")

        if not result.passed and self.fail_on_error:
            raise ValueError(f"规则 {rule.name} 校验失败: {result.message}")

        return result

    def check_batch(
        self,
        df: pd.DataFrame,
        rules: Optional[List[Rule]] = None,
        include_failed_data: bool = False,
    ) -> CheckResult:
        check_rules = rules or self.config_manager.get_enabled_rules()
        check_rules = sorted(check_rules, key=lambda r: r.priority)

        rule_results = []
        check_metadata = {
            "rules": {r.name: r for r in check_rules},
            "row_count": len(df),
            "column_count": len(df.columns),
        }

        data_hash = self._calculate_hash(df)

        for rule in check_rules:
            try:
                result = self.check_single(df, rule, include_failed_data)
                rule_results.append(result)
            except Exception as e:
                rule_results.append(
                    RuleResult(
                        rule_name=rule.name,
                        rule_type=rule.rule_type,
                        column=rule.column,
                        passed=False,
                        total_count=len(df),
                        failed_count=len(df),
                        message=f"执行出错: {str(e)}",
                        metadata={"error": str(e)},
                    )
                )
                if self.fail_on_error:
                    raise

        check_result = CheckResult(
            rule_results=rule_results,
            data_hash=data_hash,
            check_time=datetime.now(),
            mode=CheckMode.BATCH,
            metadata=check_metadata,
        )

        self.check_history.append(check_result)
        return check_result

    def check_incremental(
        self,
        new_df: pd.DataFrame,
        old_df: Optional[pd.DataFrame] = None,
        rules: Optional[List[Rule]] = None,
        include_failed_data: bool = False,
        id_column: Optional[str] = None,
    ) -> CheckResult:
        check_rules = rules or self.config_manager.get_enabled_rules()

        if old_df is not None and id_column:
            old_ids = set(old_df[id_column].dropna().unique())
            incremental_df = new_df[~new_df[id_column].isin(old_ids)]
        else:
            incremental_df = new_df

        check_result = self.check_batch(
            incremental_df,
            rules=check_rules,
            include_failed_data=include_failed_data,
        )

        check_result.mode = CheckMode.INCREMENTAL
        check_result.metadata["incremental_count"] = len(incremental_df)
        check_result.metadata["original_count"] = len(new_df)
        if old_df is not None:
            check_result.metadata["old_count"] = len(old_df)

        return check_result

    def check_by_group(
        self,
        df: pd.DataFrame,
        group_name: str,
        include_failed_data: bool = False,
    ) -> CheckResult:
        group = self.config_manager.get_rule_group(group_name)
        if not group:
            raise ValueError(f"规则组不存在: {group_name}")

        if not group.enabled:
            return CheckResult(
                rule_results=[],
                data_hash=self._calculate_hash(df),
                check_time=datetime.now(),
                mode=CheckMode.BATCH,
                metadata={"group_disabled": True, "group_name": group_name},
            )

        return self.check_batch(
            df,
            rules=group.get_enabled_rules(),
            include_failed_data=include_failed_data,
        )

    def check_by_type(
        self,
        df: pd.DataFrame,
        rule_type: RuleType,
        include_failed_data: bool = False,
    ) -> CheckResult:
        rules = self.config_manager.get_rules_by_type(rule_type)
        return self.check_batch(
            df,
            rules=rules,
            include_failed_data=include_failed_data,
        )

    def check_by_column(
        self,
        df: pd.DataFrame,
        column: str,
        include_failed_data: bool = False,
    ) -> CheckResult:
        rules = self.config_manager.get_rules_by_column(column)
        return self.check_batch(
            df,
            rules=rules,
            include_failed_data=include_failed_data,
        )

    def generate_report(self, check_result: CheckResult) -> CheckReport:
        summary = {
            "总体得分": round(check_result.overall_score, 2),
            "质量等级": check_result.get_quality_level(),
            "检查时间": check_result.check_time.strftime("%Y-%m-%d %H:%M:%S"),
            "检查模式": check_result.mode.value,
            "数据记录数": check_result.total_records,
            "规则总数": check_result.total_rules,
            "通过规则数": check_result.passed_rules,
            "失败规则数": check_result.failed_rules,
            "规则通过率": f"{round(check_result.pass_rate * 100, 2)}%",
        }

        recommendations = []

        for result in check_result.get_failed_results():
            rule = check_result.metadata.get("rules", {}).get(result.rule_name)
            severity = getattr(rule, "severity", "error") if rule else "error"

            if severity == "error":
                recommendations.append(
                    f"【严重】{result.rule_name}: {result.message}，影响{result.failed_count}条记录"
                )
            else:
                recommendations.append(
                    f"【警告】{result.rule_name}: {result.message}，影响{result.failed_count}条记录"
                )

        if check_result.overall_score < 60:
            recommendations.append(
                "数据质量严重不达标，建议暂停数据处理流程，进行全面数据清洗"
            )
        elif check_result.overall_score < 80:
            recommendations.append(
                "数据质量一般，建议定期检查并制定数据质量提升计划"
            )

        if not recommendations:
            recommendations.append("数据质量良好，继续保持当前的数据管理策略")

        return CheckReport(
            check_result=check_result,
            summary=summary,
            recommendations=recommendations,
        )

    def get_clean_data(
        self,
        df: pd.DataFrame,
        check_result: Optional[CheckResult] = None,
        strategy: str = "remove",
    ) -> pd.DataFrame:
        if check_result is None:
            check_result = self.check_batch(df)

        failed_indices = check_result.get_all_failed_indices()

        if strategy == "remove":
            return df.drop(index=failed_indices)
        elif strategy == "flag":
            result_df = df.copy()
            result_df["_quality_failed"] = result_df.index.isin(failed_indices)
            result_df["_quality_issues"] = result_df.index.map(
                lambda idx: self._get_failed_rules_for_index(idx, check_result)
            )
            return result_df
        else:
            raise ValueError(f"不支持的清洗策略: {strategy}")

    def _get_failed_rules_for_index(
        self,
        index: int,
        check_result: CheckResult,
    ) -> str:
        failed_rules = []
        for result in check_result.rule_results:
            if index in result.failed_indices:
                failed_rules.append(result.rule_name)
        return ";".join(failed_rules)

    def get_history(self, limit: Optional[int] = None) -> List[CheckResult]:
        history = sorted(self.check_history, key=lambda r: r.check_time, reverse=True)
        if limit:
            return history[:limit]
        return history

    def compare_checks(
        self,
        check1: CheckResult,
        check2: CheckResult,
    ) -> Dict[str, Any]:
        return {
            "时间范围": [check1.check_time.isoformat(), check2.check_time.isoformat()],
            "总体得分变化": round(check2.overall_score - check1.overall_score, 2),
            "通过率变化": round(check2.pass_rate - check1.pass_rate, 4),
            "规则变化": {
                "新增失败": [
                    r.rule_name
                    for r in check2.get_failed_results()
                    if check1.get_result_by_name(r.rule_name)?.passed
                ],
                "恢复通过": [
                    r.rule_name
                    for r in check1.get_failed_results()
                    if check2.get_result_by_name(r.rule_name)?.passed
                ],
            },
            "详细对比": [
                {
                    "rule_name": r.rule_name,
                    "old_score": round(check1.get_result_by_name(r.rule_name).score, 2)
                    if check1.get_result_by_name(r.rule_name)
                    else 0,
                    "new_score": round(r.score, 2),
                    "score_change": round(
                        r.score - (check1.get_result_by_name(r.rule_name).score if check1.get_result_by_name(r.rule_name) else 0),
                        2,
                    ),
                }
                for r in check2.rule_results
            ],
        }

    @staticmethod
    def _calculate_hash(df: pd.DataFrame) -> str:
        try:
            import hashlib
            df_json = df.head(1000).to_json()
            return hashlib.md5(df_json.encode()).hexdigest()
        except Exception:
            return ""
