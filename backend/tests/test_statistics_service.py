"""
统计分析模块单元测试
测试频数统计、均值计算、标准差计算的数值准确性
测试边界场景：空数据集、单一数据点、极端数值
"""
import os
import sys
import pandas as pd
import numpy as np
import pytest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from app.services.statistics_service import StatisticsService
from app.services.import_service import ImportService
from app.models import (
    SurveyData, Field, FieldType, 
    FrequencyResult, FrequencyItem, DescriptiveStats,
    survey_store, generate_id
)
from tests.conftest import create_test_excel_file, assert_almost_equal


class TestFrequencyCalculation:
    """频数统计测试类"""
    
    def test_basic_frequency_calculation(self, test_upload_dir):
        """
        测试基本频数统计
        数据：['A', 'A', 'B', 'B', 'B', 'C', 'A', 'C', 'B', 'A']
        预期：A出现4次(40%), B出现4次(40%), C出现2次(20%)
        """
        df = pd.DataFrame({
            'category': ['A', 'A', 'B', 'B', 'B', 'C', 'A', 'C', 'B', 'A']
        })
        
        file_path = os.path.join(test_upload_dir, 'test_frequency.xlsx')
        create_test_excel_file(file_path, df)
        
        survey = SurveyData(
            survey_id='test_freq_001',
            survey_name='频数统计测试',
            total_responses=10,
            fields=[
                Field(
                    field_id='q_category',
                    field_name='category',
                    field_type=FieldType.SINGLE_CHOICE,
                    options=['A', 'B', 'C']
                )
            ],
            imported_at='2026-05-05T00:00:00Z',
            file_path=file_path
        )
        survey_store.save_survey(survey)
        
        import_service = ImportService(test_upload_dir)
        stat_service = StatisticsService(import_service)
        
        result = stat_service.calculate_frequency('test_freq_001', 'q_category')
        
        assert result is not None
        assert result.field_id == 'q_category'
        assert result.total_valid == 10
        assert result.missing_count == 0
        
        freq_dict = {f.value: f for f in result.frequencies}
        
        assert 'A' in freq_dict
        assert freq_dict['A'].count == 4
        assert_almost_equal(freq_dict['A'].percentage, 40.0)
        
        assert 'B' in freq_dict
        assert freq_dict['B'].count == 4
        assert_almost_equal(freq_dict['B'].percentage, 40.0)
        
        assert 'C' in freq_dict
        assert freq_dict['C'].count == 2
        assert_almost_equal(freq_dict['C'].percentage, 20.0)
    
    def test_frequency_with_missing_values(self, test_upload_dir):
        """
        测试包含空值的频数统计
        验证空值是否被正确排除
        """
        df = pd.DataFrame({
            'category': ['A', 'A', None, 'B', 'B', None, 'C', 'A', None, 'A']
        })
        
        file_path = os.path.join(test_upload_dir, 'test_frequency_nan.xlsx')
        create_test_excel_file(file_path, df)
        
        survey = SurveyData(
            survey_id='test_freq_002',
            survey_name='频数统计测试(含空值)',
            total_responses=10,
            fields=[
                Field(
                    field_id='q_category',
                    field_name='category',
                    field_type=FieldType.SINGLE_CHOICE
                )
            ],
            imported_at='2026-05-05T00:00:00Z',
            file_path=file_path
        )
        survey_store.save_survey(survey)
        
        import_service = ImportService(test_upload_dir)
        stat_service = StatisticsService(import_service)
        
        result = stat_service.calculate_frequency('test_freq_002', 'q_category')
        
        assert result is not None
        assert result.total_valid == 7
        assert result.missing_count == 3
        
        freq_dict = {f.value: f for f in result.frequencies}
        
        assert 'A' in freq_dict
        assert freq_dict['A'].count == 4
    
    def test_empty_frequency_data(self, test_upload_dir):
        """
        测试空数据集的频数统计
        验证边界场景处理
        """
        df = pd.DataFrame({
            'category': [None, None, None, None, None]
        })
        
        file_path = os.path.join(test_upload_dir, 'test_frequency_empty.xlsx')
        create_test_excel_file(file_path, df)
        
        survey = SurveyData(
            survey_id='test_freq_003',
            survey_name='空数据集测试',
            total_responses=5,
            fields=[
                Field(
                    field_id='q_category',
                    field_name='category',
                    field_type=FieldType.SINGLE_CHOICE
                )
            ],
            imported_at='2026-05-05T00:00:00Z',
            file_path=file_path
        )
        survey_store.save_survey(survey)
        
        import_service = ImportService(test_upload_dir)
        stat_service = StatisticsService(import_service)
        
        result = stat_service.calculate_frequency('test_freq_003', 'q_category')
        
        assert result is not None
        assert result.total_valid == 0
        assert result.missing_count == 5
        assert len(result.frequencies) == 0
    
    def test_single_value_frequency(self, test_upload_dir):
        """
        测试单一数据点的频数统计
        """
        df = pd.DataFrame({
            'category': ['A', 'A', 'A', 'A', 'A']
        })
        
        file_path = os.path.join(test_upload_dir, 'test_frequency_single.xlsx')
        create_test_excel_file(file_path, df)
        
        survey = SurveyData(
            survey_id='test_freq_004',
            survey_name='单一值测试',
            total_responses=5,
            fields=[
                Field(
                    field_id='q_category',
                    field_name='category',
                    field_type=FieldType.SINGLE_CHOICE
                )
            ],
            imported_at='2026-05-05T00:00:00Z',
            file_path=file_path
        )
        survey_store.save_survey(survey)
        
        import_service = ImportService(test_upload_dir)
        stat_service = StatisticsService(import_service)
        
        result = stat_service.calculate_frequency('test_freq_004', 'q_category')
        
        assert result is not None
        assert result.total_valid == 5
        assert len(result.frequencies) == 1
        assert result.frequencies[0].value == 'A'
        assert result.frequencies[0].count == 5
        assert_almost_equal(result.frequencies[0].percentage, 100.0)


class TestDescriptiveStatistics:
    """描述性统计测试类"""
    
    def test_basic_descriptive_stats(self, test_upload_dir):
        """
        测试基本描述性统计
        数据：[1, 2, 3, 4, 5, 6, 7, 8, 9, 10]
        验证均值、中位数、标准差、分位数计算
        """
        df = pd.DataFrame({
            'value': [1, 2, 3, 4, 5, 6, 7, 8, 9, 10]
        })
        
        file_path = os.path.join(test_upload_dir, 'test_descriptive.xlsx')
        create_test_excel_file(file_path, df)
        
        survey = SurveyData(
            survey_id='test_desc_001',
            survey_name='描述性统计测试',
            total_responses=10,
            fields=[
                Field(
                    field_id='q_value',
                    field_name='value',
                    field_type=FieldType.NUMERIC,
                    range=[1, 10]
                )
            ],
            imported_at='2026-05-05T00:00:00Z',
            file_path=file_path
        )
        survey_store.save_survey(survey)
        
        import_service = ImportService(test_upload_dir)
        stat_service = StatisticsService(import_service)
        
        result = stat_service.calculate_descriptive_stats('test_desc_001', 'q_value')
        
        assert result is not None
        assert result.field_id == 'q_value'
        assert result.count == 10
        
        expected_mean = 5.5
        assert_almost_equal(result.mean, expected_mean)
        
        expected_median = 5.5
        assert_almost_equal(result.median, expected_median)
        
        expected_std = np.std([1, 2, 3, 4, 5, 6, 7, 8, 9, 10], ddof=1)
        assert_almost_equal(result.std, expected_std, 1e-3)
        
        assert result.min == 1.0
        assert result.max == 10.0
        
        expected_q25 = 3.25
        expected_q75 = 7.75
        assert_almost_equal(result.q25, expected_q25)
        assert_almost_equal(result.q75, expected_q75)
    
    def test_descriptive_with_missing_values(self, test_upload_dir):
        """
        测试包含空值的描述性统计
        """
        df = pd.DataFrame({
            'value': [1, 2, None, 4, 5, None, 7, 8, 9, 10]
        })
        
        file_path = os.path.join(test_upload_dir, 'test_descriptive_nan.xlsx')
        create_test_excel_file(file_path, df)
        
        survey = SurveyData(
            survey_id='test_desc_002',
            survey_name='描述性统计测试(含空值)',
            total_responses=10,
            fields=[
                Field(
                    field_id='q_value',
                    field_name='value',
                    field_type=FieldType.NUMERIC
                )
            ],
            imported_at='2026-05-05T00:00:00Z',
            file_path=file_path
        )
        survey_store.save_survey(survey)
        
        import_service = ImportService(test_upload_dir)
        stat_service = StatisticsService(import_service)
        
        result = stat_service.calculate_descriptive_stats('test_desc_002', 'q_value')
        
        assert result is not None
        assert result.count == 8
        
        valid_values = [1, 2, 4, 5, 7, 8, 9, 10]
        expected_mean = np.mean(valid_values)
        assert_almost_equal(result.mean, expected_mean)
    
    def test_descriptive_with_extreme_values(self, test_upload_dir):
        """
        测试包含极端数值的描述性统计
        验证异常值对统计结果的影响
        """
        df = pd.DataFrame({
            'value': [1, 2, 3, 4, 5, 6, 7, 8, 100, 150]
        })
        
        file_path = os.path.join(test_upload_dir, 'test_descriptive_extreme.xlsx')
        create_test_excel_file(file_path, df)
        
        survey = SurveyData(
            survey_id='test_desc_003',
            survey_name='极端值测试',
            total_responses=10,
            fields=[
                Field(
                    field_id='q_value',
                    field_name='value',
                    field_type=FieldType.NUMERIC
                )
            ],
            imported_at='2026-05-05T00:00:00Z',
            file_path=file_path
        )
        survey_store.save_survey(survey)
        
        import_service = ImportService(test_upload_dir)
        stat_service = StatisticsService(import_service)
        
        result = stat_service.calculate_descriptive_stats('test_desc_003', 'q_value')
        
        assert result is not None
        assert result.count == 10
        
        values = [1, 2, 3, 4, 5, 6, 7, 8, 100, 150]
        expected_mean = np.mean(values)
        assert_almost_equal(result.mean, expected_mean)
        
        assert result.min == 1.0
        assert result.max == 150.0
        
        expected_median = 5.5
        assert_almost_equal(result.median, expected_median)
    
    def test_single_value_descriptive(self, test_upload_dir):
        """
        测试单一数据点的描述性统计
        """
        df = pd.DataFrame({
            'value': [42]
        })
        
        file_path = os.path.join(test_upload_dir, 'test_descriptive_single.xlsx')
        create_test_excel_file(file_path, df)
        
        survey = SurveyData(
            survey_id='test_desc_004',
            survey_name='单一值测试',
            total_responses=1,
            fields=[
                Field(
                    field_id='q_value',
                    field_name='value',
                    field_type=FieldType.NUMERIC
                )
            ],
            imported_at='2026-05-05T00:00:00Z',
            file_path=file_path
        )
        survey_store.save_survey(survey)
        
        import_service = ImportService(test_upload_dir)
        stat_service = StatisticsService(import_service)
        
        result = stat_service.calculate_descriptive_stats('test_desc_004', 'q_value')
        
        assert result is not None
        assert result.count == 1
        assert result.mean == 42.0
        assert result.median == 42.0
        assert result.min == 42.0
        assert result.max == 42.0
    
    def test_empty_descriptive_data(self, test_upload_dir):
        """
        测试空数据集的描述性统计
        """
        df = pd.DataFrame({
            'value': [None, None, None]
        })
        
        file_path = os.path.join(test_upload_dir, 'test_descriptive_empty.xlsx')
        create_test_excel_file(file_path, df)
        
        survey = SurveyData(
            survey_id='test_desc_005',
            survey_name='空数据集测试',
            total_responses=3,
            fields=[
                Field(
                    field_id='q_value',
                    field_name='value',
                    field_type=FieldType.NUMERIC
                )
            ],
            imported_at='2026-05-05T00:00:00Z',
            file_path=file_path
        )
        survey_store.save_survey(survey)
        
        import_service = ImportService(test_upload_dir)
        stat_service = StatisticsService(import_service)
        
        result = stat_service.calculate_descriptive_stats('test_desc_005', 'q_value')
        
        assert result is None
    
    def test_non_numeric_field_descriptive(self, test_upload_dir):
        """
        测试对非数值字段执行描述性统计
        验证是否返回None
        """
        df = pd.DataFrame({
            'category': ['A', 'B', 'C']
        })
        
        file_path = os.path.join(test_upload_dir, 'test_descriptive_non_numeric.xlsx')
        create_test_excel_file(file_path, df)
        
        survey = SurveyData(
            survey_id='test_desc_006',
            survey_name='非数值字段测试',
            total_responses=3,
            fields=[
                Field(
                    field_id='q_category',
                    field_name='category',
                    field_type=FieldType.SINGLE_CHOICE
                )
            ],
            imported_at='2026-05-05T00:00:00Z',
            file_path=file_path
        )
        survey_store.save_survey(survey)
        
        import_service = ImportService(test_upload_dir)
        stat_service = StatisticsService(import_service)
        
        result = stat_service.calculate_descriptive_stats('test_desc_006', 'q_category')
        
        assert result is None
    
    def test_standard_deviation_calculation(self, test_upload_dir):
        """
        专门测试标准差计算
        验证是否使用样本标准差（ddof=1）
        """
        values = [2, 4, 6, 8, 10]
        df = pd.DataFrame({
            'value': values
        })
        
        file_path = os.path.join(test_upload_dir, 'test_std.xlsx')
        create_test_excel_file(file_path, df)
        
        survey = SurveyData(
            survey_id='test_std_001',
            survey_name='标准差测试',
            total_responses=5,
            fields=[
                Field(
                    field_id='q_value',
                    field_name='value',
                    field_type=FieldType.NUMERIC
                )
            ],
            imported_at='2026-05-05T00:00:00Z',
            file_path=file_path
        )
        survey_store.save_survey(survey)
        
        import_service = ImportService(test_upload_dir)
        stat_service = StatisticsService(import_service)
        
        result = stat_service.calculate_descriptive_stats('test_std_001', 'q_value')
        
        assert result is not None
        
        sample_std = np.std(values, ddof=1)
        population_std = np.std(values, ddof=0)
        
        assert_almost_equal(result.std, sample_std)
        assert not np.isclose(result.std, population_std)


class TestSurveyStatisticsIntegration:
    """问卷统计集成测试"""
    
    def test_get_survey_statistics_mixed_fields(self, test_upload_dir):
        """
        测试获取包含混合类型字段的问卷统计
        """
        df = pd.DataFrame({
            'gender': ['男', '男', '女', '女', '男', '女', '男', '女', '男', '女'],
            'age': [25, 32, 28, 45, 35, 22, 40, 33, 29, 38],
            'satisfaction': [8, 7, 9, 6, 8, 9, 7, 8, 6, 7]
        })
        
        file_path = os.path.join(test_upload_dir, 'test_mixed_stats.xlsx')
        create_test_excel_file(file_path, df)
        
        survey = SurveyData(
            survey_id='test_mixed_001',
            survey_name='混合字段测试',
            total_responses=10,
            fields=[
                Field(
                    field_id='q_gender',
                    field_name='gender',
                    field_type=FieldType.SINGLE_CHOICE,
                    options=['男', '女']
                ),
                Field(
                    field_id='q_age',
                    field_name='age',
                    field_type=FieldType.NUMERIC,
                    range=[22, 45]
                ),
                Field(
                    field_id='q_satisfaction',
                    field_name='satisfaction',
                    field_type=FieldType.NUMERIC,
                    range=[6, 9]
                )
            ],
            imported_at='2026-05-05T00:00:00Z',
            file_path=file_path
        )
        survey_store.save_survey(survey)
        
        import_service = ImportService(test_upload_dir)
        stat_service = StatisticsService(import_service)
        
        result = stat_service.get_survey_statistics('test_mixed_001')
        
        assert result is not None
        assert result['survey_id'] == 'test_mixed_001'
        assert len(result['statistics']) == 3
        
        stats_by_type = {s['type']: s for s in result['statistics']}
        
        assert 'frequency' in [s['type'] for s in result['statistics']]
        assert 'descriptive' in [s['type'] for s in result['statistics']]
        
        freq_stat = next(s for s in result['statistics'] if s['type'] == 'frequency')
        assert freq_stat['field_id'] == 'q_gender'
        
        desc_stats = [s for s in result['statistics'] if s['type'] == 'descriptive']
        assert len(desc_stats) == 2


class TestEdgeCases:
    """边界场景测试"""
    
    def test_large_value_range(self, test_upload_dir):
        """
        测试大数值范围的统计
        """
        values = [1, 10, 100, 1000, 10000, 100000]
        df = pd.DataFrame({
            'value': values
        })
        
        file_path = os.path.join(test_upload_dir, 'test_large_range.xlsx')
        create_test_excel_file(file_path, df)
        
        survey = SurveyData(
            survey_id='test_edge_001',
            survey_name='大数值范围测试',
            total_responses=6,
            fields=[
                Field(
                    field_id='q_value',
                    field_name='value',
                    field_type=FieldType.NUMERIC
                )
            ],
            imported_at='2026-05-05T00:00:00Z',
            file_path=file_path
        )
        survey_store.save_survey(survey)
        
        import_service = ImportService(test_upload_dir)
        stat_service = StatisticsService(import_service)
        
        result = stat_service.calculate_descriptive_stats('test_edge_001', 'q_value')
        
        assert result is not None
        assert result.count == 6
        assert result.min == 1.0
        assert result.max == 100000.0
    
    def test_negative_values(self, test_upload_dir):
        """
        测试负数的统计
        """
        values = [-10, -5, 0, 5, 10]
        df = pd.DataFrame({
            'value': values
        })
        
        file_path = os.path.join(test_upload_dir, 'test_negative.xlsx')
        create_test_excel_file(file_path, df)
        
        survey = SurveyData(
            survey_id='test_edge_002',
            survey_name='负数测试',
            total_responses=5,
            fields=[
                Field(
                    field_id='q_value',
                    field_name='value',
                    field_type=FieldType.NUMERIC
                )
            ],
            imported_at='2026-05-05T00:00:00Z',
            file_path=file_path
        )
        survey_store.save_survey(survey)
        
        import_service = ImportService(test_upload_dir)
        stat_service = StatisticsService(import_service)
        
        result = stat_service.calculate_descriptive_stats('test_edge_002', 'q_value')
        
        assert result is not None
        assert result.min == -10.0
        assert result.max == 10.0
        assert_almost_equal(result.mean, 0.0)
    
    def test_decimal_values(self, test_upload_dir):
        """
        测试小数的统计
        """
        values = [1.5, 2.25, 3.75, 4.5, 5.0]
        df = pd.DataFrame({
            'value': values
        })
        
        file_path = os.path.join(test_upload_dir, 'test_decimal.xlsx')
        create_test_excel_file(file_path, df)
        
        survey = SurveyData(
            survey_id='test_edge_003',
            survey_name='小数测试',
            total_responses=5,
            fields=[
                Field(
                    field_id='q_value',
                    field_name='value',
                    field_type=FieldType.NUMERIC
                )
            ],
            imported_at='2026-05-05T00:00:00Z',
            file_path=file_path
        )
        survey_store.save_survey(survey)
        
        import_service = ImportService(test_upload_dir)
        stat_service = StatisticsService(import_service)
        
        result = stat_service.calculate_descriptive_stats('test_edge_003', 'q_value')
        
        assert result is not None
        expected_mean = np.mean(values)
        assert_almost_equal(result.mean, expected_mean)
