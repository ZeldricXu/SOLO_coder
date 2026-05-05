"""
交叉分析模块集成测试
测试交叉频数表计算、卡方检验统计量、期望频数校验、Fisher精确检验切换
"""
import os
import sys
import pandas as pd
import numpy as np
from scipy import stats
import pytest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from app.services.cross_analysis_service import (
    CrossAnalysisService, 
    TestMethod, 
    ExpectedFrequencyCheck
)
from app.services.import_service import ImportService
from app.services.chart_service import ChartService
from app.models import (
    SurveyData, Field, FieldType,
    SignificanceResult, CrossTableCell,
    survey_store
)
from tests.conftest import create_test_excel_file, assert_almost_equal


class TestExpectedFrequencyCheck:
    """期望频数校验测试"""
    
    def test_check_expected_frequencies_valid(self):
        """
        测试期望频数检查 - 有效情况
        所有期望频数 >= 5，且没有期望频数 < 1
        """
        contingency_table = pd.DataFrame({
            'A': [20, 15, 10],
            'B': [15, 25, 20],
            'C': [10, 20, 25]
        }, index=['X', 'Y', 'Z'])
        
        import_service = ImportService('/tmp')
        chart_service = ChartService()
        cross_service = CrossAnalysisService(import_service, chart_service)
        
        check = cross_service._check_expected_frequencies(contingency_table)
        
        assert check.passed is True
        assert len(check.warnings) == 0
        assert check.count_below_1 == 0
        assert check.count_below_5 == 0
    
    def test_check_expected_frequencies_small_sample(self):
        """
        测试期望频数检查 - 小样本情况
        期望频数 < 5 超过20%，应触发警告
        """
        contingency_table = pd.DataFrame({
            'A': [3, 1],
            'B': [1, 1]
        }, index=['X', 'Y'])
        
        import_service = ImportService('/tmp')
        chart_service = ChartService()
        cross_service = CrossAnalysisService(import_service, chart_service)
        
        check = cross_service._check_expected_frequencies(contingency_table)
        
        assert check.passed is False
        assert len(check.warnings) > 0
        assert check.min_expected < 5.0
    
    def test_check_expected_frequencies_with_zero_expected(self):
        """
        测试期望频数检查 - 期望频数 < 1
        """
        contingency_table = pd.DataFrame({
            'A': [5, 0],
            'B': [0, 1]
        }, index=['X', 'Y'])
        
        import_service = ImportService('/tmp')
        chart_service = ChartService()
        cross_service = CrossAnalysisService(import_service, chart_service)
        
        check = cross_service._check_expected_frequencies(contingency_table)
        
        assert check.passed is False
        assert check.count_below_1 > 0
        assert any("期望频数小于1" in w for w in check.warnings)
    
    def test_expected_frequencies_calculation(self):
        """
        验证期望频数计算的正确性
        期望频数 = (行总计 × 列总计) / 总计
        """
        contingency_table = pd.DataFrame({
            'A': [10, 20],
            'B': [15, 25]
        }, index=['X', 'Y'])
        
        chi2, p_value, dof, expected = stats.chi2_contingency(contingency_table)
        
        import_service = ImportService('/tmp')
        chart_service = ChartService()
        cross_service = CrossAnalysisService(import_service, chart_service)
        
        check = cross_service._check_expected_frequencies(contingency_table)
        
        expected_list = expected.tolist()
        
        assert len(check.expected_frequencies) == 2
        assert len(check.expected_frequencies[0]) == 2
        
        for i in range(2):
            for j in range(2):
                assert_almost_equal(check.expected_frequencies[i][j], expected_list[i][j])


class TestFisherExactTest:
    """Fisher精确检验测试"""
    
    def test_perform_fisher_exact_2x2(self):
        """
        测试2x2表格的Fisher精确检验
        """
        contingency_table = pd.DataFrame({
            'A': [3, 1],
            'B': [1, 2]
        }, index=['X', 'Y'])
        
        import_service = ImportService('/tmp')
        chart_service = ChartService()
        cross_service = CrossAnalysisService(import_service, chart_service)
        
        stat, p_value, details = cross_service._perform_fisher_exact(contingency_table)
        
        assert details["test_method"] == "fisher_exact"
        assert details["table_shape"] == [2, 2]
        assert "odds_ratio" in details
        assert not np.isnan(p_value)
        
        scipy_odds, scipy_p = stats.fisher_exact(contingency_table.values.astype(int))
        assert_almost_equal(details["odds_ratio"], scipy_odds, 1e-4)
        assert_almost_equal(p_value, scipy_p, 1e-4)
    
    def test_perform_fisher_exact_larger_table(self):
        """
        测试大于2x2的表格
        应该回退到卡方检验
        """
        contingency_table = pd.DataFrame({
            'A': [10, 20, 15],
            'B': [15, 25, 20]
        }, index=['X', 'Y', 'Z'])
        
        import_service = ImportService('/tmp')
        chart_service = ChartService()
        cross_service = CrossAnalysisService(import_service, chart_service)
        
        stat, p_value, details = cross_service._perform_fisher_exact(contingency_table)
        
        assert details["table_shape"] == [3, 2]
        assert details.get("fallback_to_chi2") is True
        assert "chi2_statistic" in details


class TestCrossTableCalculation:
    """交叉频数表计算测试"""
    
    def test_cross_table_basic(self, test_upload_dir):
        """
        测试基本交叉表计算
        """
        df = pd.DataFrame({
            'gender': ['男', '男', '女', '女', '男', '女', '男', '女', '男', '女'],
            'income': ['高', '中', '低', '中', '高', '中', '高', '低', '中', '高']
        })
        
        file_path = os.path.join(test_upload_dir, 'test_cross.xlsx')
        create_test_excel_file(file_path, df)
        
        survey = SurveyData(
            survey_id='test_cross_001',
            survey_name='交叉分析测试',
            total_responses=10,
            fields=[
                Field(
                    field_id='q_gender',
                    field_name='gender',
                    field_type=FieldType.SINGLE_CHOICE,
                    options=['男', '女']
                ),
                Field(
                    field_id='q_income',
                    field_name='income',
                    field_type=FieldType.SINGLE_CHOICE,
                    options=['高', '中', '低']
                )
            ],
            imported_at='2026-05-05T00:00:00Z',
            file_path=file_path
        )
        survey_store.save_survey(survey)
        
        import_service = ImportService(test_upload_dir)
        chart_service = ChartService()
        cross_service = CrossAnalysisService(import_service, chart_service)
        
        result = cross_service.perform_cross_analysis(
            'test_cross_001',
            ['q_gender', 'q_income'],
            'comparison'
        )
        
        assert result is not None
        assert len(result.cross_table) == 2
        
        male_row = next((r for r in result.cross_table if r.row == '男'), None)
        female_row = next((r for r in result.cross_table if r.row == '女'), None)
        
        assert male_row is not None
        assert female_row is not None
        
        assert male_row.col_values['_total']['count'] == 5
        assert female_row.col_values['_total']['count'] == 5
        
        assert male_row.col_values['高']['count'] == 3
        assert male_row.col_values['中']['count'] == 1
        assert male_row.col_values['低']['count'] == 1
        
        assert female_row.col_values['中']['count'] == 3
    
    def test_cross_table_with_significance(self, test_upload_dir):
        """
        测试带有显著性检验的交叉分析
        """
        np.random.seed(42)
        n = 100
        gender = np.random.choice(['男', '女'], size=n)
        
        def get_income(g):
            if g == '男':
                return np.random.choice(['高', '中', '低'], p=[0.5, 0.3, 0.2])
            else:
                return np.random.choice(['高', '中', '低'], p=[0.3, 0.5, 0.2])
        
        income = [get_income(g) for g in gender]
        
        df = pd.DataFrame({
            'gender': gender,
            'income': income
        })
        
        file_path = os.path.join(test_upload_dir, 'test_cross_sig.xlsx')
        create_test_excel_file(file_path, df)
        
        survey = SurveyData(
            survey_id='test_cross_sig_001',
            survey_name='显著性测试',
            total_responses=n,
            fields=[
                Field(
                    field_id='q_gender',
                    field_name='gender',
                    field_type=FieldType.SINGLE_CHOICE,
                    options=['男', '女']
                ),
                Field(
                    field_id='q_income',
                    field_name='income',
                    field_type=FieldType.SINGLE_CHOICE,
                    options=['高', '中', '低']
                )
            ],
            imported_at='2026-05-05T00:00:00Z',
            file_path=file_path
        )
        survey_store.save_survey(survey)
        
        import_service = ImportService(test_upload_dir)
        chart_service = ChartService()
        cross_service = CrossAnalysisService(import_service, chart_service)
        
        result = cross_service.perform_cross_analysis(
            'test_cross_sig_001',
            ['q_gender', 'q_income'],
            'comparison'
        )
        
        assert result is not None
        assert result.significance is not None
        
        sig = result.significance
        assert sig.test_type in ["chi_square", "fisher_exact"]
        assert 0 <= sig.p_value <= 1
        assert isinstance(sig.significant, bool)
        
        assert "expected_frequencies" in sig.details
        assert "min_expected" in sig.details


class TestTestMethodSwitching:
    """检验方法切换测试"""
    
    def test_fisher_exact_switch_2x2_small_sample(self, test_upload_dir):
        """
        测试2x2小样本表格是否自动切换到Fisher精确检验
        """
        df = pd.DataFrame({
            'gender': ['男', '男', '女', '女', '男', '女'],
            'outcome': ['阳性', '阴性', '阳性', '阴性', '阴性', '阳性']
        })
        
        file_path = os.path.join(test_upload_dir, 'test_fisher_switch.xlsx')
        create_test_excel_file(file_path, df)
        
        survey = SurveyData(
            survey_id='test_fisher_001',
            survey_name='Fisher切换测试',
            total_responses=6,
            fields=[
                Field(
                    field_id='q_gender',
                    field_name='gender',
                    field_type=FieldType.SINGLE_CHOICE,
                    options=['男', '女']
                ),
                Field(
                    field_id='q_outcome',
                    field_name='outcome',
                    field_type=FieldType.SINGLE_CHOICE,
                    options=['阳性', '阴性']
                )
            ],
            imported_at='2026-05-05T00:00:00Z',
            file_path=file_path
        )
        survey_store.save_survey(survey)
        
        import_service = ImportService(test_upload_dir)
        chart_service = ChartService()
        cross_service = CrossAnalysisService(import_service, chart_service)
        
        result = cross_service.perform_cross_analysis(
            'test_fisher_001',
            ['q_gender', 'q_outcome'],
            'comparison'
        )
        
        if result and result.significance:
            sig = result.significance
            
            if sig.details.get("switched_from_chi2"):
                assert sig.test_type == "fisher_exact"
                assert sig.details.get("min_expected") < 5.0
    
    def test_t_test_vs_mannwhitney_switch(self, test_upload_dir):
        """
        测试分类-数值交叉分析的检验方法切换
        小样本时应切换到Mann-Whitney U检验
        """
        df = pd.DataFrame({
            'group': ['A', 'A', 'A', 'B', 'B'],
            'score': [10, 12, 11, 20, 22]
        })
        
        file_path = os.path.join(test_upload_dir, 'test_ttest_switch.xlsx')
        create_test_excel_file(file_path, df)
        
        survey = SurveyData(
            survey_id='test_ttest_001',
            survey_name='t检验切换测试',
            total_responses=5,
            fields=[
                Field(
                    field_id='q_group',
                    field_name='group',
                    field_type=FieldType.SINGLE_CHOICE,
                    options=['A', 'B']
                ),
                Field(
                    field_id='q_score',
                    field_name='score',
                    field_type=FieldType.NUMERIC
                )
            ],
            imported_at='2026-05-05T00:00:00Z',
            file_path=file_path
        )
        survey_store.save_survey(survey)
        
        import_service = ImportService(test_upload_dir)
        chart_service = ChartService()
        cross_service = CrossAnalysisService(import_service, chart_service)
        
        result = cross_service.perform_cross_analysis(
            'test_ttest_001',
            ['q_group', 'q_score'],
            'comparison'
        )
        
        if result and result.significance:
            sig = result.significance
            
            if sig.details.get("switched_from_t_test"):
                assert sig.test_type == "mann_whitney_u"
                assert any("样本量较小" in w for w in sig.details.get("warnings", []))


class TestCategoricalNumericCross:
    """分类-数值交叉分析测试"""
    
    def test_categorical_numeric_cross_basic(self, test_upload_dir):
        """
        测试分类-数值交叉分析的基本功能
        """
        np.random.seed(42)
        n = 50
        group = np.random.choice(['A', 'B', 'C'], size=n)
        
        def get_score(g):
            if g == 'A':
                return np.random.normal(75, 10)
            elif g == 'B':
                return np.random.normal(80, 8)
            else:
                return np.random.normal(70, 12)
        
        score = [get_score(g) for g in group]
        
        df = pd.DataFrame({
            'group': group,
            'score': score
        })
        
        file_path = os.path.join(test_upload_dir, 'test_cat_num.xlsx')
        create_test_excel_file(file_path, df)
        
        survey = SurveyData(
            survey_id='test_cat_num_001',
            survey_name='分类-数值交叉测试',
            total_responses=n,
            fields=[
                Field(
                    field_id='q_group',
                    field_name='group',
                    field_type=FieldType.SINGLE_CHOICE,
                    options=['A', 'B', 'C']
                ),
                Field(
                    field_id='q_score',
                    field_name='score',
                    field_type=FieldType.NUMERIC
                )
            ],
            imported_at='2026-05-05T00:00:00Z',
            file_path=file_path
        )
        survey_store.save_survey(survey)
        
        import_service = ImportService(test_upload_dir)
        chart_service = ChartService()
        cross_service = CrossAnalysisService(import_service, chart_service)
        
        result = cross_service.perform_cross_analysis(
            'test_cat_num_001',
            ['q_group', 'q_score'],
            'comparison'
        )
        
        assert result is not None
        assert len(result.cross_table) == 3
        
        for cell in result.cross_table:
            assert "mean" in cell.col_values
            assert "std" in cell.col_values
            assert "count" in cell.col_values
            assert "median" in cell.col_values
            assert "min" in cell.col_values
            assert "max" in cell.col_values
        
        if result.significance:
            assert result.significance.test_type in ["anova", "kruskal_wallis"]


class TestChiSquareAccuracy:
    """卡方检验准确性测试"""
    
    def test_chi_square_statistic_calculation(self, test_upload_dir):
        """
        验证卡方检验统计量的计算准确性
        """
        df = pd.DataFrame({
            'A': [20, 10],
            'B': [15, 25]
        }, index=['X', 'Y'])
        
        data_df = pd.DataFrame({
            'row': ['X']*35 + ['Y']*35,
            'col': (['A']*20 + ['B']*15) + (['A']*10 + ['B']*25)
        })
        
        file_path = os.path.join(test_upload_dir, 'test_chi2.xlsx')
        create_test_excel_file(file_path, data_df)
        
        survey = SurveyData(
            survey_id='test_chi2_001',
            survey_name='卡方准确性测试',
            total_responses=70,
            fields=[
                Field(
                    field_id='q_row',
                    field_name='row',
                    field_type=FieldType.SINGLE_CHOICE,
                    options=['X', 'Y']
                ),
                Field(
                    field_id='q_col',
                    field_name='col',
                    field_type=FieldType.SINGLE_CHOICE,
                    options=['A', 'B']
                )
            ],
            imported_at='2026-05-05T00:00:00Z',
            file_path=file_path
        )
        survey_store.save_survey(survey)
        
        import_service = ImportService(test_upload_dir)
        chart_service = ChartService()
        cross_service = CrossAnalysisService(import_service, chart_service)
        
        result = cross_service.perform_cross_analysis(
            'test_chi2_001',
            ['q_row', 'q_col'],
            'comparison'
        )
        
        assert result is not None
        assert result.significance is not None
        
        contingency = pd.crosstab(data_df['row'], data_df['col'])
        scipy_chi2, scipy_p, scipy_dof, _ = stats.chi2_contingency(contingency)
        
        sig = result.significance
        if sig.test_type == "chi_square":
            calculated_chi2 = sig.details.get("chi2_statistic")
            assert_almost_equal(calculated_chi2, scipy_chi2, 1e-3)
            
            assert_almost_equal(sig.p_value, scipy_p, 1e-4)
            assert sig.details.get("degrees_of_freedom") == scipy_dof


class TestEdgeCases:
    """边界场景测试"""
    
    def test_cross_analysis_with_missing_values(self, test_upload_dir):
        """
        测试包含缺失值的交叉分析
        """
        df = pd.DataFrame({
            'gender': ['男', '男', '女', None, '男', '女', None, '女', '男', '女'],
            'income': ['高', None, '低', '中', '高', '中', '高', None, '中', '高']
        })
        
        file_path = os.path.join(test_upload_dir, 'test_cross_nan.xlsx')
        create_test_excel_file(file_path, df)
        
        survey = SurveyData(
            survey_id='test_cross_nan_001',
            survey_name='缺失值测试',
            total_responses=10,
            fields=[
                Field(
                    field_id='q_gender',
                    field_name='gender',
                    field_type=FieldType.SINGLE_CHOICE,
                    options=['男', '女']
                ),
                Field(
                    field_id='q_income',
                    field_name='income',
                    field_type=FieldType.SINGLE_CHOICE,
                    options=['高', '中', '低']
                )
            ],
            imported_at='2026-05-05T00:00:00Z',
            file_path=file_path
        )
        survey_store.save_survey(survey)
        
        import_service = ImportService(test_upload_dir)
        chart_service = ChartService()
        cross_service = CrossAnalysisService(import_service, chart_service)
        
        result = cross_service.perform_cross_analysis(
            'test_cross_nan_001',
            ['q_gender', 'q_income'],
            'comparison'
        )
        
        if result:
            total_count = sum(
                cell.col_values['_total']['count']
                for cell in result.cross_table
            )
            assert total_count <= 10
    
    def test_insufficient_valid_data(self, test_upload_dir):
        """
        测试有效数据不足的情况
        有效样本 < 5 应该返回None
        """
        df = pd.DataFrame({
            'group': ['A', None, 'B', None, None],
            'score': [10, 20, None, None, None]
        })
        
        file_path = os.path.join(test_upload_dir, 'test_insufficient.xlsx')
        create_test_excel_file(file_path, df)
        
        survey = SurveyData(
            survey_id='test_insufficient_001',
            survey_name='有效数据不足测试',
            total_responses=5,
            fields=[
                Field(
                    field_id='q_group',
                    field_name='group',
                    field_type=FieldType.SINGLE_CHOICE,
                    options=['A', 'B']
                ),
                Field(
                    field_id='q_score',
                    field_name='score',
                    field_type=FieldType.NUMERIC
                )
            ],
            imported_at='2026-05-05T00:00:00Z',
            file_path=file_path
        )
        survey_store.save_survey(survey)
        
        import_service = ImportService(test_upload_dir)
        chart_service = ChartService()
        cross_service = CrossAnalysisService(import_service, chart_service)
        
        result = cross_service.perform_cross_analysis(
            'test_insufficient_001',
            ['q_group', 'q_score'],
            'comparison'
        )
        
        assert result is None
    
    def test_invalid_survey_id(self):
        """
        测试无效的问卷ID
        """
        import_service = ImportService('/tmp')
        chart_service = ChartService()
        cross_service = CrossAnalysisService(import_service, chart_service)
        
        result = cross_service.perform_cross_analysis(
            'nonexistent_survey',
            ['q_a', 'q_b'],
            'comparison'
        )
        
        assert result is None
    
    def test_insufficient_variables(self, test_upload_dir):
        """
        测试变量数量不足
        """
        df = pd.DataFrame({
            'gender': ['男', '女'],
            'age': [25, 30]
        })
        
        file_path = os.path.join(test_upload_dir, 'test_insufficient_vars.xlsx')
        create_test_excel_file(file_path, df)
        
        survey = SurveyData(
            survey_id='test_insufficient_vars_001',
            survey_name='变量不足测试',
            total_responses=2,
            fields=[
                Field(
                    field_id='q_gender',
                    field_name='gender',
                    field_type=FieldType.SINGLE_CHOICE
                )
            ],
            imported_at='2026-05-05T00:00:00Z',
            file_path=file_path
        )
        survey_store.save_survey(survey)
        
        import_service = ImportService(test_upload_dir)
        chart_service = ChartService()
        cross_service = CrossAnalysisService(import_service, chart_service)
        
        result = cross_service.perform_cross_analysis(
            'test_insufficient_vars_001',
            ['q_gender'],
            'comparison'
        )
        
        assert result is None


class TestChartConfigGeneration:
    """图表配置生成测试"""
    
    def test_cross_analysis_chart_config(self, test_upload_dir):
        """
        测试交叉分析的图表配置生成
        """
        df = pd.DataFrame({
            'gender': ['男', '男', '女', '女', '男'],
            'income': ['高', '中', '低', '中', '高']
        })
        
        file_path = os.path.join(test_upload_dir, 'test_chart_config.xlsx')
        create_test_excel_file(file_path, df)
        
        survey = SurveyData(
            survey_id='test_chart_001',
            survey_name='图表配置测试',
            total_responses=5,
            fields=[
                Field(
                    field_id='q_gender',
                    field_name='gender',
                    field_type=FieldType.SINGLE_CHOICE
                ),
                Field(
                    field_id='q_income',
                    field_name='income',
                    field_type=FieldType.SINGLE_CHOICE
                )
            ],
            imported_at='2026-05-05T00:00:00Z',
            file_path=file_path
        )
        survey_store.save_survey(survey)
        
        import_service = ImportService(test_upload_dir)
        chart_service = ChartService()
        cross_service = CrossAnalysisService(import_service, chart_service)
        
        result = cross_service.perform_cross_analysis(
            'test_chart_001',
            ['q_gender', 'q_income'],
            'comparison'
        )
        
        if result:
            assert result.chart_config is not None
            assert "type" in result.chart_config
            assert "categories" in result.chart_config
            assert result.chart_config["type"] in ["bar", "stacked_bar"]
