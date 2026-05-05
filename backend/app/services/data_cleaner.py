import pandas as pd
import numpy as np
from typing import Dict, List, Any, Optional, Tuple, Callable
from dataclasses import dataclass
from enum import Enum
import re
from datetime import datetime

class NullTreatment(str, Enum):
    DROP = "drop"
    FILL = "fill"
    FILL_MEAN = "fill_mean"
    FILL_MEDIAN = "fill_median"
    FILL_MODE = "fill_mode"
    FILL_FORWARD = "fill_forward"
    FILL_BACKWARD = "fill_backward"
    MARK = "mark"
    KEEP = "keep"

class OutlierMethod(str, Enum):
    Z_SCORE = "z_score"
    IQR = "iqr"
    PERCENTILE = "percentile"
    NONE = "none"

class TextNormalization(str, Enum):
    LOWER = "lower"
    UPPER = "upper"
    TRIM = "trim"
    REMOVE_WHITESPACE = "remove_whitespace"
    REMOVE_SPECIAL = "remove_special"
    NONE = "none"

@dataclass
class FieldCleaningConfig:
    field_id: str
    field_name: str
    null_treatment: NullTreatment = NullTreatment.KEEP
    fill_value: Optional[Any] = None
    outlier_method: OutlierMethod = OutlierMethod.NONE
    outlier_threshold: float = 3.0
    outlier_action: str = "mark"
    lower_percentile: float = 1.0
    upper_percentile: float = 99.0
    text_normalization: List[TextNormalization] = None
    date_format: Optional[str] = None
    numeric_decimals: Optional[int] = None
    custom_rules: List[Dict[str, Any]] = None
    
    def __post_init__(self):
        if self.text_normalization is None:
            self.text_normalization = []
        if self.custom_rules is None:
            self.custom_rules = []

@dataclass
class CleaningResult:
    total_rows: int
    cleaned_rows: int
    dropped_rows: int
    null_count: int
    outlier_count: int
    field_stats: Dict[str, Dict[str, Any]]
    warnings: List[str]
    errors: List[str]
    
    def to_dict(self) -> Dict[str, Any]:
        return {
            "total_rows": self.total_rows,
            "cleaned_rows": self.cleaned_rows,
            "dropped_rows": self.dropped_rows,
            "null_count": self.null_count,
            "outlier_count": self.outlier_count,
            "field_stats": self.field_stats,
            "warnings": self.warnings,
            "errors": self.errors
        }

class DataCleaner:
    """
    配置驱动的数据清洗模块
    支持空值处理、异常值检测、格式标准化等功能
    """
    
    def __init__(self, config: Optional[Dict[str, Any]] = None):
        self.config = config or {}
        self.field_configs: Dict[str, FieldCleaningConfig] = {}
        self._init_field_configs()
    
    def _init_field_configs(self):
        """从配置初始化字段清洗规则"""
        field_configs = self.config.get("field_configs", [])
        for fc in field_configs:
            field_config = FieldCleaningConfig(
                field_id=fc.get("field_id"),
                field_name=fc.get("field_name"),
                null_treatment=NullTreatment(fc.get("null_treatment", "keep")),
                fill_value=fc.get("fill_value"),
                outlier_method=OutlierMethod(fc.get("outlier_method", "none")),
                outlier_threshold=fc.get("outlier_threshold", 3.0),
                outlier_action=fc.get("outlier_action", "mark"),
                lower_percentile=fc.get("lower_percentile", 1.0),
                upper_percentile=fc.get("upper_percentile", 99.0),
                text_normalization=[TextNormalization(t) for t in fc.get("text_normalization", [])],
                date_format=fc.get("date_format"),
                numeric_decimals=fc.get("numeric_decimals"),
                custom_rules=fc.get("custom_rules", [])
            )
            self.field_configs[field_config.field_id] = field_config
    
    def _detect_nulls(self, series: pd.Series) -> pd.Series:
        """检测空值（支持多种空值表示）"""
        null_mask = series.isna()
        
        null_indicators = ['', 'NA', 'N/A', 'null', 'NULL', 'NaN', 'nan', 'None']
        if series.dtype == object:
            for indicator in null_indicators:
                null_mask = null_mask | (series.astype(str).str.strip() == indicator)
        
        return null_mask
    
    def _handle_nulls(
        self, 
        df: pd.DataFrame, 
        field_config: FieldCleaningConfig,
        source_column: str
    ) -> Tuple[pd.DataFrame, int, List[str]]:
        """处理空值"""
        warnings = []
        null_count = 0
        
        if source_column not in df.columns:
            return df, 0, warnings
        
        series = df[source_column]
        null_mask = self._detect_nulls(series)
        null_count = null_mask.sum()
        
        if null_count == 0:
            return df, 0, warnings
        
        if field_config.null_treatment == NullTreatment.DROP:
            df = df[~null_mask].copy()
            warnings.append(f"字段 {field_config.field_name} 删除了 {null_count} 条空值记录")
        
        elif field_config.null_treatment == NullTreatment.FILL:
            fill_value = field_config.fill_value
            if fill_value is not None:
                if source_column in df.columns:
                    df.loc[null_mask, source_column] = fill_value
                warnings.append(f"字段 {field_config.field_name} 使用值 '{fill_value}' 填充了 {null_count} 个空值")
            else:
                warnings.append(f"字段 {field_config.field_name} 未指定填充值，空值保持原样")
        
        elif field_config.null_treatment == NullTreatment.FILL_MEAN:
            try:
                numeric_series = pd.to_numeric(series, errors='coerce')
                mean_value = numeric_series.mean()
                if not np.isnan(mean_value):
                    df.loc[null_mask, source_column] = mean_value
                    warnings.append(f"字段 {field_config.field_name} 使用均值 {mean_value:.4f} 填充了 {null_count} 个空值")
            except Exception as e:
                warnings.append(f"字段 {field_config.field_name} 无法使用均值填充: {str(e)}")
        
        elif field_config.null_treatment == NullTreatment.FILL_MEDIAN:
            try:
                numeric_series = pd.to_numeric(series, errors='coerce')
                median_value = numeric_series.median()
                if not np.isnan(median_value):
                    df.loc[null_mask, source_column] = median_value
                    warnings.append(f"字段 {field_config.field_name} 使用中位数 {median_value} 填充了 {null_count} 个空值")
            except Exception as e:
                warnings.append(f"字段 {field_config.field_name} 无法使用中位数填充: {str(e)}")
        
        elif field_config.null_treatment == NullTreatment.FILL_MODE:
            try:
                mode_value = series.mode().iloc[0] if not series.mode().empty else None
                if mode_value is not None:
                    df.loc[null_mask, source_column] = mode_value
                    warnings.append(f"字段 {field_config.field_name} 使用众数 '{mode_value}' 填充了 {null_count} 个空值")
            except Exception as e:
                warnings.append(f"字段 {field_config.field_name} 无法使用众数填充: {str(e)}")
        
        elif field_config.null_treatment == NullTreatment.FILL_FORWARD:
            df[source_column] = df[source_column].ffill()
            warnings.append(f"字段 {field_config.field_name} 使用前向填充处理了 {null_count} 个空值")
        
        elif field_config.null_treatment == NullTreatment.FILL_BACKWARD:
            df[source_column] = df[source_column].bfill()
            warnings.append(f"字段 {field_config.field_name} 使用后向填充处理了 {null_count} 个空值")
        
        elif field_config.null_treatment == NullTreatment.MARK:
            mark_column = f"{source_column}_is_null"
            df[mark_column] = null_mask.astype(int)
            warnings.append(f"字段 {field_config.field_name} 添加了空值标记列 {mark_column}")
        
        return df, null_count, warnings
    
    def _detect_outliers_zscore(self, series: pd.Series, threshold: float) -> pd.Series:
        """使用Z-score方法检测异常值"""
        series_clean = pd.to_numeric(series, errors='coerce').dropna()
        if len(series_clean) == 0:
            return pd.Series([False] * len(series), index=series.index)
        
        mean = series_clean.mean()
        std = series_clean.std()
        
        if std == 0:
            return pd.Series([False] * len(series), index=series.index)
        
        z_scores = (series_clean - mean) / std
        outlier_mask = abs(z_scores) > threshold
        
        full_mask = pd.Series([False] * len(series), index=series.index)
        full_mask.loc[series_clean.index] = outlier_mask
        
        return full_mask
    
    def _detect_outliers_iqr(self, series: pd.Series) -> pd.Series:
        """使用IQR方法检测异常值"""
        series_clean = pd.to_numeric(series, errors='coerce').dropna()
        if len(series_clean) == 0:
            return pd.Series([False] * len(series), index=series.index)
        
        q1 = series_clean.quantile(0.25)
        q3 = series_clean.quantile(0.75)
        iqr = q3 - q1
        
        lower_bound = q1 - 1.5 * iqr
        upper_bound = q3 + 1.5 * iqr
        
        outlier_mask = (series_clean < lower_bound) | (series_clean > upper_bound)
        
        full_mask = pd.Series([False] * len(series), index=series.index)
        full_mask.loc[series_clean.index] = outlier_mask
        
        return full_mask
    
    def _detect_outliers_percentile(
        self, 
        series: pd.Series, 
        lower_percentile: float,
        upper_percentile: float
    ) -> pd.Series:
        """使用百分位数方法检测异常值"""
        series_clean = pd.to_numeric(series, errors='coerce').dropna()
        if len(series_clean) == 0:
            return pd.Series([False] * len(series), index=series.index)
        
        lower_bound = series_clean.quantile(lower_percentile / 100)
        upper_bound = series_clean.quantile(upper_percentile / 100)
        
        outlier_mask = (series_clean < lower_bound) | (series_clean > upper_bound)
        
        full_mask = pd.Series([False] * len(series), index=series.index)
        full_mask.loc[series_clean.index] = outlier_mask
        
        return full_mask
    
    def _handle_outliers(
        self,
        df: pd.DataFrame,
        field_config: FieldCleaningConfig,
        source_column: str
    ) -> Tuple[pd.DataFrame, int, List[str]]:
        """处理异常值"""
        warnings = []
        outlier_count = 0
        
        if source_column not in df.columns:
            return df, 0, warnings
        
        if field_config.outlier_method == OutlierMethod.NONE:
            return df, 0, warnings
        
        series = df[source_column]
        
        if field_config.outlier_method == OutlierMethod.Z_SCORE:
            outlier_mask = self._detect_outliers_zscore(series, field_config.outlier_threshold)
        elif field_config.outlier_method == OutlierMethod.IQR:
            outlier_mask = self._detect_outliers_iqr(series)
        elif field_config.outlier_method == OutlierMethod.PERCENTILE:
            outlier_mask = self._detect_outliers_percentile(
                series, 
                field_config.lower_percentile,
                field_config.upper_percentile
            )
        else:
            return df, 0, warnings
        
        outlier_count = outlier_mask.sum()
        
        if outlier_count == 0:
            return df, 0, warnings
        
        if field_config.outlier_action == "mark":
            mark_column = f"{source_column}_is_outlier"
            df[mark_column] = outlier_mask.astype(int)
            warnings.append(f"字段 {field_config.field_name} 检测到 {outlier_count} 个异常值，已添加标记列 {mark_column}")
        
        elif field_config.outlier_action == "drop":
            df = df[~outlier_mask].copy()
            warnings.append(f"字段 {field_config.field_name} 检测到 {outlier_count} 个异常值，已删除相关记录")
        
        elif field_config.outlier_action == "cap":
            series_clean = pd.to_numeric(series, errors='coerce')
            non_outliers = series_clean[~outlier_mask]
            if len(non_outliers) > 0:
                lower_cap = non_outliers.min()
                upper_cap = non_outliers.max()
                
                def cap_value(x):
                    try:
                        x_num = float(x)
                        if x_num < lower_cap:
                            return lower_cap
                        elif x_num > upper_cap:
                            return upper_cap
                        return x_num
                    except (ValueError, TypeError):
                        return x
                
                df[source_column] = df[source_column].apply(cap_value)
                warnings.append(f"字段 {field_config.field_name} 检测到 {outlier_count} 个异常值，已进行缩尾处理")
        
        return df, outlier_count, warnings
    
    def _normalize_text(
        self,
        df: pd.DataFrame,
        field_config: FieldCleaningConfig,
        source_column: str
    ) -> Tuple[pd.DataFrame, List[str]]:
        """文本标准化处理"""
        warnings = []
        
        if source_column not in df.columns:
            return df, warnings
        
        if not field_config.text_normalization:
            return df, warnings
        
        series = df[source_column].astype(str)
        
        for normalization in field_config.text_normalization:
            if normalization == TextNormalization.LOWER:
                series = series.str.lower()
            elif normalization == TextNormalization.UPPER:
                series = series.str.upper()
            elif normalization == TextNormalization.TRIM:
                series = series.str.strip()
            elif normalization == TextNormalization.REMOVE_WHITESPACE:
                series = series.str.replace(r'\s+', '', regex=True)
            elif normalization == TextNormalization.REMOVE_SPECIAL:
                series = series.str.replace(r'[^a-zA-Z0-9\u4e00-\u9fa5\s]', '', regex=True)
        
        df[source_column] = series
        warnings.append(f"字段 {field_config.field_name} 已应用文本标准化: {', '.join([n.value for n in field_config.text_normalization])}")
        
        return df, warnings
    
    def _standardize_date(
        self,
        df: pd.DataFrame,
        field_config: FieldCleaningConfig,
        source_column: str
    ) -> Tuple[pd.DataFrame, List[str]]:
        """日期格式标准化"""
        warnings = []
        
        if source_column not in df.columns:
            return df, warnings
        
        if not field_config.date_format:
            return df, warnings
        
        series = df[source_column]
        
        try:
            parsed_dates = pd.to_datetime(series, errors='coerce')
            df[source_column] = parsed_dates.dt.strftime(field_config.date_format)
            warnings.append(f"字段 {field_config.field_name} 已标准化为日期格式: {field_config.date_format}")
        except Exception as e:
            warnings.append(f"字段 {field_config.field_name} 日期格式标准化失败: {str(e)}")
        
        return df, warnings
    
    def _standardize_numeric(
        self,
        df: pd.DataFrame,
        field_config: FieldCleaningConfig,
        source_column: str
    ) -> Tuple[pd.DataFrame, List[str]]:
        """数值标准化（小数位数）"""
        warnings = []
        
        if source_column not in df.columns:
            return df, warnings
        
        if field_config.numeric_decimals is None:
            return df, warnings
        
        try:
            series = pd.to_numeric(df[source_column], errors='coerce')
            df[source_column] = series.round(field_config.numeric_decimals)
            warnings.append(f"字段 {field_config.field_name} 已标准化为 {field_config.numeric_decimals} 位小数")
        except Exception as e:
            warnings.append(f"字段 {field_config.field_name} 数值标准化失败: {str(e)}")
        
        return df, warnings
    
    def _apply_custom_rules(
        self,
        df: pd.DataFrame,
        field_config: FieldCleaningConfig,
        source_column: str
    ) -> Tuple[pd.DataFrame, List[str]]:
        """应用自定义清洗规则"""
        warnings = []
        
        if source_column not in df.columns:
            return df, warnings
        
        if not field_config.custom_rules:
            return df, warnings
        
        for rule in field_config.custom_rules:
            rule_type = rule.get("type")
            params = rule.get("params", {})
            
            if rule_type == "replace":
                old_value = params.get("old_value")
                new_value = params.get("new_value")
                if old_value is not None and new_value is not None:
                    df[source_column] = df[source_column].replace(old_value, new_value)
                    warnings.append(f"字段 {field_config.field_name} 应用替换规则: '{old_value}' -> '{new_value}'")
            
            elif rule_type == "regex_replace":
                pattern = params.get("pattern")
                replacement = params.get("replacement", "")
                if pattern:
                    df[source_column] = df[source_column].astype(str).str.replace(
                        pattern, replacement, regex=True
                    )
                    warnings.append(f"字段 {field_config.field_name} 应用正则替换规则: {pattern}")
            
            elif rule_type == "map_values":
                mapping = params.get("mapping", {})
                if mapping:
                    df[source_column] = df[source_column].map(mapping).fillna(df[source_column])
                    warnings.append(f"字段 {field_config.field_name} 应用值映射规则")
        
        return df, warnings
    
    def clean(
        self, 
        df: pd.DataFrame,
        field_mappings: Optional[List[Dict[str, Any]]] = None
    ) -> Tuple[pd.DataFrame, CleaningResult]:
        """
        执行完整的数据清洗流程
        
        Args:
            df: 原始DataFrame
            field_mappings: 字段映射列表，包含field_id和source_column
            
        Returns:
            Tuple[清洗后的DataFrame, 清洗结果统计]
        """
        total_rows = len(df)
        all_warnings: List[str] = []
        all_errors: List[str] = []
        field_stats: Dict[str, Dict[str, Any]] = {}
        total_null_count = 0
        total_outlier_count = 0
        
        df_cleaned = df.copy()
        
        if field_mappings:
            for mapping in field_mappings:
                field_id = mapping.get("field_id")
                source_column = mapping.get("source_column", field_id)
                
                if field_id not in self.field_configs:
                    field_config = FieldCleaningConfig(
                        field_id=field_id,
                        field_name=mapping.get("field_name", field_id)
                    )
                else:
                    field_config = self.field_configs[field_id]
                
                field_stat = {
                    "field_id": field_id,
                    "field_name": field_config.field_name,
                    "original_nulls": 0,
                    "handled_nulls": 0,
                    "outliers_detected": 0,
                    "warnings": []
                }
                
                df_cleaned, null_count, null_warnings = self._handle_nulls(
                    df_cleaned, field_config, source_column
                )
                total_null_count += null_count
                all_warnings.extend(null_warnings)
                field_stat["original_nulls"] = null_count
                field_stat["handled_nulls"] = null_count
                
                df_cleaned, outlier_count, outlier_warnings = self._handle_outliers(
                    df_cleaned, field_config, source_column
                )
                total_outlier_count += outlier_count
                all_warnings.extend(outlier_warnings)
                field_stat["outliers_detected"] = outlier_count
                
                df_cleaned, text_warnings = self._normalize_text(
                    df_cleaned, field_config, source_column
                )
                all_warnings.extend(text_warnings)
                
                df_cleaned, date_warnings = self._standardize_date(
                    df_cleaned, field_config, source_column
                )
                all_warnings.extend(date_warnings)
                
                df_cleaned, numeric_warnings = self._standardize_numeric(
                    df_cleaned, field_config, source_column
                )
                all_warnings.extend(numeric_warnings)
                
                df_cleaned, custom_warnings = self._apply_custom_rules(
                    df_cleaned, field_config, source_column
                )
                all_warnings.extend(custom_warnings)
                
                field_stats[field_id] = field_stat
        
        else:
            all_warnings.append("未提供字段映射，跳过字段级清洗")
        
        dropped_rows = total_rows - len(df_cleaned)
        
        result = CleaningResult(
            total_rows=total_rows,
            cleaned_rows=len(df_cleaned),
            dropped_rows=dropped_rows,
            null_count=total_null_count,
            outlier_count=total_outlier_count,
            field_stats=field_stats,
            warnings=list(set(all_warnings)),
            errors=all_errors
        )
        
        return df_cleaned, result
    
    @classmethod
    def get_default_config(cls) -> Dict[str, Any]:
        """获取默认清洗配置"""
        return {
            "field_configs": [
                {
                    "field_id": "*",
                    "field_name": "默认规则",
                    "null_treatment": "keep",
                    "outlier_method": "none",
                    "text_normalization": []
                }
            ],
            "global_settings": {
                "drop_duplicates": False,
                "reset_index": True
            }
        }
    
    @classmethod
    def create_config(
        cls,
        field_configs: List[Dict[str, Any]],
        global_settings: Optional[Dict[str, Any]] = None
    ) -> Dict[str, Any]:
        """创建清洗配置"""
        return {
            "field_configs": field_configs,
            "global_settings": global_settings or {
                "drop_duplicates": False,
                "reset_index": True
            }
        }
