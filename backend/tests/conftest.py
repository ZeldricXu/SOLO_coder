"""
pytest 配置和测试夹具
"""
import os
import sys
import tempfile
import shutil
from datetime import datetime
from typing import Dict, List, Any

import pytest
import pandas as pd
import numpy as np

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from app.models import (
    SurveyData, Field, FieldType, 
    FrequencyResult, FrequencyItem, DescriptiveStats,
    CrossAnalysisResult, CrossTableCell, SignificanceResult,
    Report, ReportSection,
    survey_store, generate_id, get_current_timestamp
)


@pytest.fixture(scope="session")
def temp_dir():
    """创建临时目录"""
    temp_path = tempfile.mkdtemp(prefix="survey_test_")
    yield temp_path
    shutil.rmtree(temp_path, ignore_errors=True)


@pytest.fixture(scope="session")
def test_upload_dir(temp_dir):
    """测试用上传目录"""
    upload_dir = os.path.join(temp_dir, "uploads")
    os.makedirs(upload_dir, exist_ok=True)
    return upload_dir


@pytest.fixture(scope="session")
def test_export_dir(temp_dir):
    """测试用导出目录"""
    export_dir = os.path.join(temp_dir, "exports")
    os.makedirs(export_dir, exist_ok=True)
    return export_dir


@pytest.fixture(autouse=True)
def reset_survey_store():
    """每个测试前后重置 survey_store"""
    survey_store._surveys = {}
    survey_store._analysis_results = {}
    survey_store._frequency_results = {}
    survey_store._descriptive_results = {}
    survey_store._reports = {}
    yield
    survey_store._surveys = {}
    survey_store._analysis_results = {}
    survey_store._frequency_results = {}
    survey_store._descriptive_results = {}
    survey_store._reports = {}


@pytest.fixture
def sample_numeric_data():
    """样本数值数据"""
    return pd.Series([1, 2, 3, 4, 5, 6, 7, 8, 9, 10])


@pytest.fixture
def sample_categorical_data():
    """样本分类数据"""
    return pd.Series(['A', 'A', 'B', 'B', 'B', 'C', 'A', 'C', 'B', 'A'])


@pytest.fixture
def sample_dataframe():
    """样本DataFrame"""
    return pd.DataFrame({
        'gender': ['男', '男', '女', '女', '男', '女', '男', '女', '男', '女'],
        'age': [25, 32, 28, 45, 35, 22, 40, 33, 29, 38],
        'satisfaction': [8, 7, 9, 6, 8, 9, 7, 8, 6, 7],
        'income': ['高', '中', '低', '中', '高', '中', '高', '低', '中', '高']
    })


@pytest.fixture
def sample_fields():
    """样本字段列表"""
    return [
        Field(
            field_id='q_gender',
            field_name='性别',
            field_type=FieldType.SINGLE_CHOICE,
            options=['男', '女']
        ),
        Field(
            field_id='q_age',
            field_name='年龄',
            field_type=FieldType.NUMERIC,
            range=[22, 45]
        ),
        Field(
            field_id='q_satisfaction',
            field_name='满意度评分',
            field_type=FieldType.NUMERIC,
            range=[6, 9]
        ),
        Field(
            field_id='q_income',
            field_name='收入水平',
            field_type=FieldType.SINGLE_CHOICE,
            options=['高', '中', '低']
        )
    ]


@pytest.fixture
def sample_survey(sample_fields, test_upload_dir):
    """样本问卷数据"""
    survey = SurveyData(
        survey_id='test_survey_001',
        survey_name='消费者满意度调查',
        total_responses=10,
        fields=sample_fields,
        imported_at=get_current_timestamp(),
        file_path=os.path.join(test_upload_dir, 'test_survey.xlsx')
    )
    survey_store.save_survey(survey)
    return survey


@pytest.fixture
def sample_frequency_result():
    """样本频数分析结果"""
    return FrequencyResult(
        field_id='q_gender',
        field_name='性别',
        frequencies=[
            FrequencyItem(value='男', count=5, percentage=50.0),
            FrequencyItem(value='女', count=5, percentage=50.0)
        ],
        total_valid=10,
        missing_count=0
    )


@pytest.fixture
def sample_descriptive_result():
    """样本描述性统计结果"""
    return DescriptiveStats(
        field_id='q_age',
        field_name='年龄',
        count=10,
        mean=32.7,
        median=32.5,
        std=6.8,
        min=22,
        max=45,
        q25=28.25,
        q75=37.25
    )


@pytest.fixture
def sample_cross_table():
    """样本交叉表数据"""
    return [
        CrossTableCell(
            row='男',
            col_values={
                '高': {'count': 3, 'percentage': 30.0, 'row_percentage': 60.0},
                '中': {'count': 1, 'percentage': 10.0, 'row_percentage': 20.0},
                '低': {'count': 1, 'percentage': 10.0, 'row_percentage': 20.0},
                '_total': {'count': 5, 'percentage': 50.0}
            }
        ),
        CrossTableCell(
            row='女',
            col_values={
                '高': {'count': 1, 'percentage': 10.0, 'row_percentage': 20.0},
                '中': {'count': 3, 'percentage': 30.0, 'row_percentage': 60.0},
                '低': {'count': 1, 'percentage': 10.0, 'row_percentage': 20.0},
                '_total': {'count': 5, 'percentage': 50.0}
            }
        )
    ]


@pytest.fixture
def sample_significance_result():
    """样本显著性检验结果"""
    return SignificanceResult(
        test_type='chi_square',
        p_value=0.0345,
        significant=True,
        details={
            'chi2_statistic': 6.523,
            'degrees_of_freedom': 2,
            'expected_frequencies': [[2.0, 2.0, 1.0], [2.0, 2.0, 1.0]]
        }
    )


@pytest.fixture
def sample_cross_analysis_result(sample_cross_table, sample_significance_result):
    """样本交叉分析结果"""
    return CrossAnalysisResult(
        analysis_id='cross_test_001',
        survey_id='test_survey_001',
        variables=['q_gender', 'q_income'],
        cross_table=sample_cross_table,
        significance=sample_significance_result,
        chart_config={
            'type': 'bar',
            'title': '性别与收入水平交叉分析',
            'categories': ['高', '中', '低']
        }
    )


@pytest.fixture
def sample_report_sections(sample_frequency_result, sample_descriptive_result, sample_cross_analysis_result):
    """样本报告章节"""
    return [
        ReportSection(
            section_type='summary',
            title='报告概述',
            content='本报告基于10份有效问卷数据，分析了消费者满意度情况。\n\n主要发现：\n1. 性别分布均衡\n2. 平均年龄为32.7岁\n3. 满意度评分为7.5分',
            data={'total_responses': 10, 'valid_responses': 10}
        ),
        ReportSection(
            section_type='frequency',
            title='频数分析',
            content='以下是各分类变量的频数分布情况。',
            data={
                'statistics': [
                    {
                        'field_name': '性别',
                        'field_id': 'q_gender',
                        'data': sample_frequency_result.to_dict()
                    }
                ]
            }
        ),
        ReportSection(
            section_type='descriptive',
            title='描述性统计',
            content='以下是数值变量的描述性统计结果。',
            data={
                'statistics': [
                    {
                        'field_name': '年龄',
                        'field_id': 'q_age',
                        'data': sample_descriptive_result.to_dict()
                    }
                ]
            }
        ),
        ReportSection(
            section_type='cross',
            title='交叉分析',
            content='以下是变量间的交叉分析结果。',
            data={
                'cross_analyses': [sample_cross_analysis_result.to_dict()]
            }
        )
    ]


@pytest.fixture
def sample_report(sample_report_sections):
    """样本报告"""
    report = Report(
        report_id='report_test_001',
        survey_id='test_survey_001',
        title='消费者满意度调查报告',
        created_at=get_current_timestamp(),
        sections=sample_report_sections
    )
    survey_store.save_report(report)
    return report


@pytest.fixture
def sample_contingency_table_small():
    """小样本列联表（用于测试期望频数校验）"""
    return pd.DataFrame({
        'A': [3, 1],
        'B': [1, 1]
    }, index=['X', 'Y'])


@pytest.fixture
def sample_contingency_table_large():
    """大样本列联表"""
    return pd.DataFrame({
        'A': [20, 15, 10],
        'B': [15, 25, 20],
        'C': [10, 20, 25]
    }, index=['X', 'Y', 'Z'])


@pytest.fixture
def sample_with_outliers():
    """包含异常值的数值数据"""
    return pd.Series([1, 2, 3, 4, 5, 6, 7, 8, 100, 150])


@pytest.fixture
def sample_with_nulls():
    """包含空值的数据"""
    return pd.Series([1, 2, None, 4, 5, None, 7, 8, 9, 10])


@pytest.fixture
def empty_series():
    """空数据序列"""
    return pd.Series([])


@pytest.fixture
def single_value_series():
    """单一数据点序列"""
    return pd.Series([42])


def create_test_excel_file(file_path: str, df: pd.DataFrame):
    """创建测试用Excel文件"""
    df.to_excel(file_path, index=False, engine='openpyxl')


def create_test_csv_file(file_path: str, df: pd.DataFrame):
    """创建测试用CSV文件"""
    df.to_csv(file_path, index=False, encoding='utf-8')


def assert_almost_equal(a: float, b: float, tol: float = 1e-6):
    """近似相等断言"""
    assert abs(a - b) < tol, f"{a} != {b} (tolerance: {tol})"
