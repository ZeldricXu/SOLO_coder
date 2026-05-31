from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from enum import Enum
from typing import Any, Callable, Dict, List, Optional, Union
from datetime import datetime, date
import re
import pandas as pd
import numpy as np


class RuleType(Enum):
    NULL_CHECK = "null_check"
    UNIQUENESS = "uniqueness"
    RANGE = "range"
    FORMAT = "format"
    PATTERN = "pattern"
    REFERENTIAL_INTEGRITY = "referential_integrity"
    BUSINESS = "business"


@dataclass
class RuleResult:
    rule_name: str
    rule_type: RuleType
    column: Optional[str]
    passed: bool
    total_count: int
    failed_count: int
    failed_indices: List[int] = field(default_factory=list)
    failed_values: List[Any] = field(default_factory=list)
    message: str = ""
    execution_time: float = 0.0
    metadata: Dict[str, Any] = field(default_factory=dict)

    @property
    def pass_rate(self) -> float:
        if self.total_count == 0:
            return 1.0
        return (self.total_count - self.failed_count) / self.total_count

    @property
    def score(self) -> float:
        return self.pass_rate * 100


class Rule(ABC):
    def __init__(
        self,
        name: str,
        column: Optional[str] = None,
        description: str = "",
        severity: str = "error",
        priority: int = 5,
        enabled: bool = True,
    ):
        self.name = name
        self.column = column
        self.description = description
        self.severity = severity
        self.priority = priority
        self.enabled = enabled
        self.created_at = datetime.now()

    @abstractmethod
    def validate(self, df: pd.DataFrame) -> RuleResult:
        pass

    def to_dict(self) -> Dict[str, Any]:
        return {
            "name": self.name,
            "type": self.rule_type.value,
            "column": self.column,
            "description": self.description,
            "severity": self.severity,
            "priority": self.priority,
            "enabled": self.enabled,
        }

    @property
    @abstractmethod
    def rule_type(self) -> RuleType:
        pass


class NullCheckRule(Rule):
    def __init__(
        self,
        name: str,
        column: str,
        description: str = "",
        severity: str = "error",
        priority: int = 5,
        enabled: bool = True,
        allow_empty_string: bool = False,
    ):
        super().__init__(name, column, description, severity, priority, enabled)
        self.allow_empty_string = allow_empty_string

    @property
    def rule_type(self) -> RuleType:
        return RuleType.NULL_CHECK

    def validate(self, df: pd.DataFrame) -> RuleResult:
        start_time = datetime.now().timestamp()
        total_count = len(df)

        if self.column not in df.columns:
            return RuleResult(
                rule_name=self.name,
                rule_type=self.rule_type,
                column=self.column,
                passed=False,
                total_count=total_count,
                failed_count=total_count,
                message=f"列 {self.column} 不存在",
                execution_time=datetime.now().timestamp() - start_time,
            )

        series = df[self.column]
        if self.allow_empty_string:
            mask = series.isna()
        else:
            mask = series.isna() | (series.astype(str).str.strip() == "")

        failed_indices = df[mask].index.tolist()
        failed_values = series[mask].tolist()
        failed_count = len(failed_indices)

        execution_time = datetime.now().timestamp() - start_time

        return RuleResult(
            rule_name=self.name,
            rule_type=self.rule_type,
            column=self.column,
            passed=failed_count == 0,
            total_count=total_count,
            failed_count=failed_count,
            failed_indices=failed_indices,
            failed_values=failed_values,
            message=f"发现 {failed_count} 个空值",
            execution_time=execution_time,
            metadata={"allow_empty_string": self.allow_empty_string},
        )


class UniquenessRule(Rule):
    def __init__(
        self,
        name: str,
        columns: Union[str, List[str]],
        description: str = "",
        severity: str = "error",
        priority: int = 5,
        enabled: bool = True,
        ignore_nulls: bool = True,
    ):
        column = columns if isinstance(columns, str) else ",".join(columns)
        super().__init__(name, column, description, severity, priority, enabled)
        self.columns = [columns] if isinstance(columns, str) else columns
        self.ignore_nulls = ignore_nulls

    @property
    def rule_type(self) -> RuleType:
        return RuleType.UNIQUENESS

    def validate(self, df: pd.DataFrame) -> RuleResult:
        start_time = datetime.now().timestamp()
        total_count = len(df)

        for col in self.columns:
            if col not in df.columns:
                return RuleResult(
                    rule_name=self.name,
                    rule_type=self.rule_type,
                    column=self.column,
                    passed=False,
                    total_count=total_count,
                    failed_count=total_count,
                    message=f"列 {col} 不存在",
                    execution_time=datetime.now().timestamp() - start_time,
                )

        check_df = df[self.columns].copy()
        if self.ignore_nulls:
            mask = check_df.notna().all(axis=1)
            check_df = check_df[mask]

        duplicate_mask = check_df.duplicated(keep=False)
        failed_indices = check_df[duplicate_mask].index.tolist()
        failed_values = check_df[duplicate_mask].values.tolist()
        failed_count = len(failed_indices)

        execution_time = datetime.now().timestamp() - start_time

        return RuleResult(
            rule_name=self.name,
            rule_type=self.rule_type,
            column=self.column,
            passed=failed_count == 0,
            total_count=total_count,
            failed_count=failed_count,
            failed_indices=failed_indices,
            failed_values=failed_values,
            message=f"发现 {failed_count} 条重复记录",
            execution_time=execution_time,
            metadata={"columns": self.columns, "ignore_nulls": self.ignore_nulls},
        )


class RangeRule(Rule):
    def __init__(
        self,
        name: str,
        column: str,
        min_value: Optional[Union[int, float, datetime, date]] = None,
        max_value: Optional[Union[int, float, datetime, date]] = None,
        inclusive_min: bool = True,
        inclusive_max: bool = True,
        description: str = "",
        severity: str = "error",
        priority: int = 5,
        enabled: bool = True,
    ):
        super().__init__(name, column, description, severity, priority, enabled)
        self.min_value = min_value
        self.max_value = max_value
        self.inclusive_min = inclusive_min
        self.inclusive_max = inclusive_max

    @property
    def rule_type(self) -> RuleType:
        return RuleType.RANGE

    def validate(self, df: pd.DataFrame) -> RuleResult:
        start_time = datetime.now().timestamp()
        total_count = len(df)

        if self.column not in df.columns:
            return RuleResult(
                rule_name=self.name,
                rule_type=self.rule_type,
                column=self.column,
                passed=False,
                total_count=total_count,
                failed_count=total_count,
                message=f"列 {self.column} 不存在",
                execution_time=datetime.now().timestamp() - start_time,
            )

        series = df[self.column]
        mask = pd.Series(False, index=df.index)

        if self.min_value is not None:
            if self.inclusive_min:
                min_mask = series < self.min_value
            else:
                min_mask = series <= self.min_value
            mask = mask | min_mask

        if self.max_value is not None:
            if self.inclusive_max:
                max_mask = series > self.max_value
            else:
                max_mask = series >= self.max_value
            mask = mask | max_mask

        mask = mask & series.notna()

        failed_indices = df[mask].index.tolist()
        failed_values = series[mask].tolist()
        failed_count = len(failed_indices)

        execution_time = datetime.now().timestamp() - start_time

        return RuleResult(
            rule_name=self.name,
            rule_type=self.rule_type,
            column=self.column,
            passed=failed_count == 0,
            total_count=total_count,
            failed_count=failed_count,
            failed_indices=failed_indices,
            failed_values=failed_values,
            message=f"发现 {failed_count} 条超出范围的记录",
            execution_time=execution_time,
            metadata={
                "min_value": self.min_value,
                "max_value": self.max_value,
                "inclusive_min": self.inclusive_min,
                "inclusive_max": self.inclusive_max,
            },
        )


class FormatRule(Rule):
    def __init__(
        self,
        name: str,
        column: str,
        data_type: str,
        description: str = "",
        severity: str = "error",
        priority: int = 5,
        enabled: bool = True,
    ):
        super().__init__(name, column, description, severity, priority, enabled)
        self.data_type = data_type.lower()

    @property
    def rule_type(self) -> RuleType:
        return RuleType.FORMAT

    def _check_type(self, value: Any) -> bool:
        if pd.isna(value):
            return True

        try:
            if self.data_type == "int":
                int(value)
            elif self.data_type == "float":
                float(value)
            elif self.data_type == "datetime":
                pd.to_datetime(value)
            elif self.data_type == "date":
                pd.to_datetime(value).date()
            elif self.data_type == "bool":
                if isinstance(value, bool):
                    return True
                str_val = str(value).lower()
                return str_val in ["true", "false", "1", "0", "yes", "no"]
            elif self.data_type == "email":
                email_pattern = r"^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}$"
                return bool(re.match(email_pattern, str(value)))
            elif self.data_type == "phone":
                phone_pattern = r"^1[3-9]\d{9}$|^0\d{2,3}-?\d{7,8}$"
                return bool(re.match(phone_pattern, str(value)))
            elif self.data_type == "url":
                url_pattern = r"^https?://[\w\-]+(\.[\w\-]+)+[/#?]?.*$"
                return bool(re.match(url_pattern, str(value)))
            else:
                raise ValueError(f"不支持的数据类型: {self.data_type}")
            return True
        except (ValueError, TypeError):
            return False

    def validate(self, df: pd.DataFrame) -> RuleResult:
        start_time = datetime.now().timestamp()
        total_count = len(df)

        if self.column not in df.columns:
            return RuleResult(
                rule_name=self.name,
                rule_type=self.rule_type,
                column=self.column,
                passed=False,
                total_count=total_count,
                failed_count=total_count,
                message=f"列 {self.column} 不存在",
                execution_time=datetime.now().timestamp() - start_time,
            )

        series = df[self.column]
        mask = ~series.apply(self._check_type)

        failed_indices = df[mask].index.tolist()
        failed_values = series[mask].tolist()
        failed_count = len(failed_indices)

        execution_time = datetime.now().timestamp() - start_time

        return RuleResult(
            rule_name=self.name,
            rule_type=self.rule_type,
            column=self.column,
            passed=failed_count == 0,
            total_count=total_count,
            failed_count=failed_count,
            failed_indices=failed_indices,
            failed_values=failed_values,
            message=f"发现 {failed_count} 条格式错误的记录",
            execution_time=execution_time,
            metadata={"data_type": self.data_type},
        )


class PatternRule(Rule):
    def __init__(
        self,
        name: str,
        column: str,
        pattern: str,
        description: str = "",
        severity: str = "error",
        priority: int = 5,
        enabled: bool = True,
        case_sensitive: bool = False,
    ):
        super().__init__(name, column, description, severity, priority, enabled)
        self.pattern = pattern
        self.case_sensitive = case_sensitive
        flags = 0 if case_sensitive else re.IGNORECASE
        self._regex = re.compile(pattern, flags)

    @property
    def rule_type(self) -> RuleType:
        return RuleType.PATTERN

    def validate(self, df: pd.DataFrame) -> RuleResult:
        start_time = datetime.now().timestamp()
        total_count = len(df)

        if self.column not in df.columns:
            return RuleResult(
                rule_name=self.name,
                rule_type=self.rule_type,
                column=self.column,
                passed=False,
                total_count=total_count,
                failed_count=total_count,
                message=f"列 {self.column} 不存在",
                execution_time=datetime.now().timestamp() - start_time,
            )

        series = df[self.column].astype(str)
        mask = ~series.apply(lambda x: pd.isna(x) or bool(self._regex.match(x)))

        failed_indices = df[mask].index.tolist()
        failed_values = series[mask].tolist()
        failed_count = len(failed_indices)

        execution_time = datetime.now().timestamp() - start_time

        return RuleResult(
            rule_name=self.name,
            rule_type=self.rule_type,
            column=self.column,
            passed=failed_count == 0,
            total_count=total_count,
            failed_count=failed_count,
            failed_indices=failed_indices,
            failed_values=failed_values,
            message=f"发现 {failed_count} 条不匹配模式的记录",
            execution_time=execution_time,
            metadata={"pattern": self.pattern, "case_sensitive": self.case_sensitive},
        )


class ReferentialIntegrityRule(Rule):
    def __init__(
        self,
        name: str,
        column: str,
        reference_df: pd.DataFrame,
        reference_column: Optional[str] = None,
        description: str = "",
        severity: str = "error",
        priority: int = 5,
        enabled: bool = True,
        ignore_nulls: bool = True,
    ):
        super().__init__(name, column, description, severity, priority, enabled)
        self.reference_df = reference_df
        self.reference_column = reference_column or column
        self.ignore_nulls = ignore_nulls

    @property
    def rule_type(self) -> RuleType:
        return RuleType.REFERENTIAL_INTEGRITY

    def validate(self, df: pd.DataFrame) -> RuleResult:
        start_time = datetime.now().timestamp()
        total_count = len(df)

        if self.column not in df.columns:
            return RuleResult(
                rule_name=self.name,
                rule_type=self.rule_type,
                column=self.column,
                passed=False,
                total_count=total_count,
                failed_count=total_count,
                message=f"列 {self.column} 不存在",
                execution_time=datetime.now().timestamp() - start_time,
            )

        if self.reference_column not in self.reference_df.columns:
            return RuleResult(
                rule_name=self.name,
                rule_type=self.rule_type,
                column=self.column,
                passed=False,
                total_count=total_count,
                failed_count=total_count,
                message=f"参考列 {self.reference_column} 不存在",
                execution_time=datetime.now().timestamp() - start_time,
            )

        series = df[self.column]
        reference_values = set(self.reference_df[self.reference_column].dropna().unique())

        mask = ~series.isin(reference_values)
        if self.ignore_nulls:
            mask = mask & series.notna()

        failed_indices = df[mask].index.tolist()
        failed_values = series[mask].tolist()
        failed_count = len(failed_indices)

        execution_time = datetime.now().timestamp() - start_time

        return RuleResult(
            rule_name=self.name,
            rule_type=self.rule_type,
            column=self.column,
            passed=failed_count == 0,
            total_count=total_count,
            failed_count=failed_count,
            failed_indices=failed_indices,
            failed_values=failed_values,
            message=f"发现 {failed_count} 条违反引用完整性的记录",
            execution_time=execution_time,
            metadata={
                "reference_column": self.reference_column,
                "reference_count": len(reference_values),
                "ignore_nulls": self.ignore_nulls,
            },
        )


class BusinessRule(Rule):
    def __init__(
        self,
        name: str,
        check_func: Callable[[pd.DataFrame], pd.Series],
        column: Optional[str] = None,
        description: str = "",
        severity: str = "error",
        priority: int = 5,
        enabled: bool = True,
    ):
        super().__init__(name, column, description, severity, priority, enabled)
        self.check_func = check_func

    @property
    def rule_type(self) -> RuleType:
        return RuleType.BUSINESS

    def validate(self, df: pd.DataFrame) -> RuleResult:
        start_time = datetime.now().timestamp()
        total_count = len(df)

        try:
            result = self.check_func(df)
            if isinstance(result, pd.Series):
                mask = ~result.astype(bool)
            else:
                mask = pd.Series([not result] * total_count, index=df.index)

            failed_indices = df[mask].index.tolist()
            failed_count = len(failed_indices)
            failed_values = []
            if self.column and self.column in df.columns:
                failed_values = df.loc[failed_indices, self.column].tolist()

            execution_time = datetime.now().timestamp() - start_time

            return RuleResult(
                rule_name=self.name,
                rule_type=self.rule_type,
                column=self.column,
                passed=failed_count == 0,
                total_count=total_count,
                failed_count=failed_count,
                failed_indices=failed_indices,
                failed_values=failed_values,
                message=f"发现 {failed_count} 条违反业务规则的记录",
                execution_time=execution_time,
                metadata={"description": self.description},
            )
        except Exception as e:
            execution_time = datetime.now().timestamp() - start_time
            return RuleResult(
                rule_name=self.name,
                rule_type=self.rule_type,
                column=self.column,
                passed=False,
                total_count=total_count,
                failed_count=total_count,
                message=f"业务规则执行出错: {str(e)}",
                execution_time=execution_time,
                metadata={"error": str(e)},
            )


class RuleFactory:
    @staticmethod
    def create(rule_type: Union[RuleType, str], **kwargs) -> Rule:
        if isinstance(rule_type, str):
            rule_type = RuleType(rule_type)

        rule_classes = {
            RuleType.NULL_CHECK: NullCheckRule,
            RuleType.UNIQUENESS: UniquenessRule,
            RuleType.RANGE: RangeRule,
            RuleType.FORMAT: FormatRule,
            RuleType.PATTERN: PatternRule,
            RuleType.REFERENTIAL_INTEGRITY: ReferentialIntegrityRule,
            RuleType.BUSINESS: BusinessRule,
        }

        rule_class = rule_classes.get(rule_type)
        if not rule_class:
            raise ValueError(f"不支持的规则类型: {rule_type}")

        return rule_class(**kwargs)
