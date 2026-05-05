import pandas as pd
import numpy as np
from scipy import stats
from typing import Dict, List, Any, Optional, Tuple
from dataclasses import dataclass, field
from enum import Enum

class TestMethod(str, Enum):
    CHI_SQUARE = "chi_square"
    FISHER_EXACT = "fisher_exact"
    T_TEST = "t_test"
    ANOVA = "anova"
    WILCOXON = "wilcoxon"
    MANN_WHITNEY_U = "mann_whitney_u"
    KRUSKAL_WALLIS = "kruskal_wallis"

@dataclass
class ExpectedFrequencyCheck:
    passed: bool
    expected_frequencies: List[List[float]]
    min_expected: float
    count_below_5: int
    count_below_1: int
    warnings: List[str] = field(default_factory=list)

from app.models import (
    SurveyData, Field, FieldType, 
    CrossAnalysisResult, CrossTableCell, SignificanceResult,
    survey_store, generate_id
)
from app.services.import_service import ImportService
from app.services.chart_service import ChartService

class CrossAnalysisService:
    def __init__(self, import_service: ImportService, chart_service: ChartService):
        self.import_service = import_service
        self.chart_service = chart_service
        self.min_expected_frequency = 5.0
        self.min_sample_per_group = 5
    
    def _check_expected_frequencies(
        self, 
        contingency_table: pd.DataFrame
    ) -> ExpectedFrequencyCheck:
        """
        检查交叉表的期望频数
        
        卡方检验的假设条件：
        1. 所有期望频数 >= 1
        2. 至少80%的期望频数 >= 5
        
        如果不满足，应使用Fisher精确检验
        """
        chi2, p_value, dof, expected = stats.chi2_contingency(contingency_table)
        
        expected_list = expected.tolist()
        min_expected = float(expected.min())
        count_below_5 = int((expected < 5).sum())
        count_below_1 = int((expected < 1).sum())
        
        total_cells = expected.size
        percent_below_5 = count_below_5 / total_cells * 100
        
        warnings = []
        passed = True
        
        if count_below_1 > 0:
            warnings.append(f"存在 {count_below_1} 个单元格的期望频数小于1，卡方检验可能不准确")
            passed = False
        
        if percent_below_5 > 20:
            warnings.append(f"超过20%的单元格（{count_below_5}/{total_cells}）期望频数小于5，建议使用Fisher精确检验")
            passed = False
        
        return ExpectedFrequencyCheck(
            passed=passed,
            expected_frequencies=expected_list,
            min_expected=min_expected,
            count_below_5=count_below_5,
            count_below_1=count_below_1,
            warnings=warnings
        )
    
    def _perform_fisher_exact(
        self, 
        contingency_table: pd.DataFrame
    ) -> Tuple[float, float, Dict[str, Any]]:
        """
        执行Fisher精确检验
        
        对于2x2表格，直接使用fisher_exact
        对于更大的表格，需要特殊处理
        """
        table_shape = contingency_table.shape
        
        details = {
            "table_shape": list(table_shape),
            "test_method": "fisher_exact"
        }
        
        if table_shape == (2, 2):
            try:
                table_array = contingency_table.values.astype(int)
                oddsratio, p_value = stats.fisher_exact(table_array)
                
                details["odds_ratio"] = round(float(oddsratio), 4)
                
                return float(oddsratio), float(p_value), details
            except Exception as e:
                return np.nan, np.nan, {"error": str(e), **details}
        else:
            try:
                table_array = contingency_table.values.astype(int)
                chi2, p_value = stats.chi2_contingency(table_array)[:2]
                
                details["fallback_to_chi2"] = True
                details["chi2_statistic"] = round(float(chi2), 4)
                
                return float(chi2), float(p_value), details
            except Exception as e:
                return np.nan, np.nan, {"error": str(e), **details}
    
    def get_series_for_field(self, df: pd.DataFrame, survey: SurveyData, field_id: str) -> pd.Series:
        field = None
        col_index = -1
        for i, f in enumerate(survey.fields):
            if f.field_id == field_id:
                field = f
                col_index = i
                break
        
        if col_index >= len(df.columns):
            col_index = -1
        
        if col_index == -1:
            col_name = field.field_name if field else field_id
        else:
            col_name = df.columns[col_index]
        
        if col_name not in df.columns:
            alt_col_name = None
            for col in df.columns:
                if field and (col == field.field_name or col.lower().replace(' ', '_') == field_id.replace('q_', '')):
                    alt_col_name = col
                    break
            if alt_col_name:
                col_name = alt_col_name
            else:
                col_name = df.columns[min(col_index if col_index >= 0 else 0, len(df.columns) - 1)]
        
        return df[col_name]
    
    def perform_cross_analysis(
        self, 
        survey_id: str, 
        variables: List[str], 
        analysis_type: str = "comparison"
    ) -> Optional[CrossAnalysisResult]:
        survey = survey_store.get_survey(survey_id)
        if not survey:
            return None
        
        if len(variables) < 2:
            return None
        
        try:
            df = self.import_service.parse_file(survey.file_path)
        except Exception as e:
            return None
        
        field_types = {}
        for field_id in variables:
            for f in survey.fields:
                if f.field_id == field_id:
                    field_types[field_id] = f.field_type
                    break
        
        series_dict = {}
        for var in variables:
            series_dict[var] = self.get_series_for_field(df, survey, var)
        
        valid_mask = pd.Series(True, index=df.index)
        for var, series in series_dict.items():
            if field_types.get(var) == FieldType.NUMERIC:
                numeric_series = pd.to_numeric(series, errors='coerce')
                valid_mask = valid_mask & ~numeric_series.isna()
            else:
                valid_mask = valid_mask & ~series.isna()
        
        valid_count = valid_mask.sum()
        if valid_count < 5:
            return None
        
        row_var = variables[0]
        col_var = variables[1]
        
        row_series = series_dict[row_var][valid_mask]
        col_series = series_dict[col_var][valid_mask]
        
        row_type = field_types.get(row_var, FieldType.TEXT)
        col_type = field_types.get(col_var, FieldType.TEXT)
        
        cross_table: List[CrossTableCell] = []
        significance: Optional[SignificanceResult] = None
        
        if col_type == FieldType.NUMERIC and row_type in [FieldType.SINGLE_CHOICE, FieldType.MULTIPLE_CHOICE]:
            col_series_numeric = pd.to_numeric(col_series, errors='coerce')
            cross_table, significance = self._categorical_numeric_cross(row_series, col_series_numeric)
        
        elif row_type == FieldType.NUMERIC and col_type in [FieldType.SINGLE_CHOICE, FieldType.MULTIPLE_CHOICE]:
            row_series_numeric = pd.to_numeric(row_series, errors='coerce')
            cross_table, significance = self._categorical_numeric_cross(col_series, row_series_numeric)
        
        else:
            cross_table, significance = self._categorical_categorical_cross(row_series, col_series)
        
        chart_config = self.chart_service.create_cross_chart_config(
            cross_table, 
            row_type, 
            col_type, 
            variables
        )
        
        result = CrossAnalysisResult(
            analysis_id=generate_id('cross'),
            survey_id=survey_id,
            variables=variables,
            cross_table=cross_table,
            significance=significance,
            chart_config=chart_config
        )
        
        survey_store.save_cross_analysis(result)
        
        return result
    
    def _categorical_categorical_cross(
        self, 
        row_series: pd.Series, 
        col_series: pd.Series
    ) -> Tuple[List[CrossTableCell], Optional[SignificanceResult]]:
        contingency_table = pd.crosstab(row_series, col_series)
        
        row_totals = contingency_table.sum(axis=1)
        col_totals = contingency_table.sum(axis=0)
        grand_total = contingency_table.sum().sum()
        
        cross_table: List[CrossTableCell] = []
        for row_name in contingency_table.index:
            row_values = {}
            row_count = int(row_totals[row_name])
            
            for col_name in contingency_table.columns:
                count = int(contingency_table.loc[row_name, col_name])
                percentage = (count / grand_total * 100) if grand_total > 0 else 0
                row_pct = (count / row_count * 100) if row_count > 0 else 0
                
                row_values[str(col_name)] = {
                    "count": count,
                    "percentage": round(percentage, 2),
                    "row_percentage": round(row_pct, 2)
                }
            
            row_values["_total"] = {
                "count": row_count,
                "percentage": round(row_count / grand_total * 100 if grand_total > 0 else 0, 2)
            }
            
            cross_table.append(CrossTableCell(
                row=str(row_name),
                col_values=row_values
            ))
        
        significance = None
        
        try:
            freq_check = self._check_expected_frequencies(contingency_table)
            
            if freq_check.passed:
                chi2, p_value, dof, expected = stats.chi2_contingency(contingency_table)
                
                significance = SignificanceResult(
                    test_type="chi_square",
                    p_value=round(float(p_value), 6),
                    significant=bool(p_value < 0.05),
                    details={
                        "chi2_statistic": round(float(chi2), 4),
                        "degrees_of_freedom": int(dof),
                        "expected_frequencies": freq_check.expected_frequencies,
                        "min_expected": round(freq_check.min_expected, 4),
                        "validated": True,
                        "warnings": freq_check.warnings
                    }
                )
            else:
                if contingency_table.shape == (2, 2):
                    stat, p_value, details = self._perform_fisher_exact(contingency_table)
                    
                    significance = SignificanceResult(
                        test_type="fisher_exact",
                        p_value=round(float(p_value), 6),
                        significant=bool(p_value < 0.05),
                        details={
                            **details,
                            "expected_frequencies": freq_check.expected_frequencies,
                            "min_expected": round(freq_check.min_expected, 4),
                            "count_below_5": freq_check.count_below_5,
                            "count_below_1": freq_check.count_below_1,
                            "switched_from_chi2": True,
                            "warnings": freq_check.warnings
                        }
                    )
                else:
                    chi2, p_value, dof, expected = stats.chi2_contingency(contingency_table)
                    
                    significance = SignificanceResult(
                        test_type="chi_square",
                        p_value=round(float(p_value), 6),
                        significant=bool(p_value < 0.05),
                        details={
                            "chi2_statistic": round(float(chi2), 4),
                            "degrees_of_freedom": int(dof),
                            "expected_frequencies": freq_check.expected_frequencies,
                            "min_expected": round(freq_check.min_expected, 4),
                            "count_below_5": freq_check.count_below_5,
                            "count_below_1": freq_check.count_below_1,
                            "validation_warning": True,
                            "warnings": freq_check.warnings
                        }
                    )
        except Exception as e:
            pass
        
        return cross_table, significance
    
    def _categorical_numeric_cross(
        self, 
        cat_series: pd.Series, 
        num_series: pd.Series
    ) -> Tuple[List[CrossTableCell], Optional[SignificanceResult]]:
        valid_mask = ~num_series.isna()
        cat_series_clean = cat_series[valid_mask]
        num_series_clean = num_series[valid_mask]
        
        groups = {}
        for cat_value in cat_series_clean.unique():
            group_data = num_series_clean[cat_series_clean == cat_value]
            if len(group_data) > 0:
                groups[str(cat_value)] = group_data
        
        cross_table: List[CrossTableCell] = []
        
        for group_name, group_data in groups.items():
            col_values = {
                "mean": round(float(group_data.mean()), 4),
                "std": round(float(group_data.std()), 4),
                "count": int(len(group_data)),
                "min": round(float(group_data.min()), 4),
                "max": round(float(group_data.max()), 4),
                "median": round(float(group_data.median()), 4)
            }
            
            cross_table.append(CrossTableCell(
                row=group_name,
                col_values=col_values
            ))
        
        significance = None
        group_values = list(groups.values())
        group_sizes = [len(g) for g in group_values]
        warnings = []
        
        small_sample_groups = [i for i, size in enumerate(group_sizes) if size < self.min_sample_per_group]
        if small_sample_groups:
            warnings.append(f"存在 {len(small_sample_groups)} 个组的样本量小于 {self.min_sample_per_group}，建议使用非参数检验")
        
        if len(group_values) == 2:
            g1, g2 = group_values[0], group_values[1]
            
            should_use_nonparametric = False
            if len(g1) < self.min_sample_per_group or len(g2) < self.min_sample_per_group:
                should_use_nonparametric = True
                warnings.append("样本量较小，使用Mann-Whitney U检验替代t检验")
            
            try:
                if should_use_nonparametric:
                    stat, p_value = stats.mannwhitneyu(g1, g2, alternative='two-sided')
                    
                    significance = SignificanceResult(
                        test_type="mann_whitney_u",
                        p_value=round(float(p_value), 6),
                        significant=bool(p_value < 0.05),
                        details={
                            "u_statistic": round(float(stat), 4),
                            "group_sizes": group_sizes,
                            "switched_from_t_test": True,
                            "warnings": warnings
                        }
                    )
                else:
                    stat, p_value = stats.ttest_ind(g1, g2, equal_var=False, nan_policy='omit')
                    
                    significance = SignificanceResult(
                        test_type="t_test",
                        p_value=round(float(p_value), 6),
                        significant=bool(p_value < 0.05),
                        details={
                            "t_statistic": round(float(stat), 4),
                            "group_sizes": group_sizes,
                            "warnings": warnings
                        }
                    )
            except Exception as e:
                pass
        
        elif len(group_values) > 2:
            should_use_nonparametric = False
            if any(size < self.min_sample_per_group for size in group_sizes):
                should_use_nonparametric = True
                warnings.append("存在小样本组，使用Kruskal-Wallis H检验替代ANOVA")
            
            try:
                if should_use_nonparametric:
                    stat, p_value = stats.kruskal(*group_values)
                    
                    significance = SignificanceResult(
                        test_type="kruskal_wallis",
                        p_value=round(float(p_value), 6),
                        significant=bool(p_value < 0.05),
                        details={
                            "h_statistic": round(float(stat), 4),
                            "group_sizes": group_sizes,
                            "switched_from_anova": True,
                            "warnings": warnings
                        }
                    )
                else:
                    stat, p_value = stats.f_oneway(*group_values)
                    
                    significance = SignificanceResult(
                        test_type="anova",
                        p_value=round(float(p_value), 6),
                        significant=bool(p_value < 0.05),
                        details={
                            "f_statistic": round(float(stat), 4),
                            "group_sizes": group_sizes,
                            "warnings": warnings
                        }
                    )
            except Exception as e:
                pass
        
        return cross_table, significance
    
    def get_analysis_result(self, analysis_id: str) -> Optional[Dict[str, Any]]:
        result = survey_store.get_cross_analysis(analysis_id)
        if result:
            return result.to_dict()
        return None
    
    def get_survey_analyses(self, survey_id: str) -> List[Dict[str, Any]]:
        analyses = survey_store.get_survey_analyses(survey_id)
        return [a.to_dict() for a in analyses]
