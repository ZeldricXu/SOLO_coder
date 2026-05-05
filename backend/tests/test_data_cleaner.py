"""
数据清洗模块单元测试
测试配置驱动的数据清洗功能：空值处理、异常值检测、格式标准化
"""
import os
import sys
import pandas as pd
import numpy as np
import pytest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from app.services.data_cleaner import (
    DataCleaner, CleaningResult, FieldCleaningConfig,
    NullTreatment, OutlierMethod, TextNormalization
)
from tests.conftest import assert_almost_equal


class TestNullTreatment:
    """空值处理测试"""
    
    def test_null_keep(self):
        """测试空值保留"""
        df = pd.DataFrame({
            'value': [1, 2, None, 4, 5, None, 7, 8, 9, 10]
        })
        
        config = {
            "field_configs": [
                {
                    "field_id": "value",
                    "field_name": "value",
                    "null_treatment": "keep",
                    "outlier_method": "none"
                }
            ]
        }
        
        cleaner = DataCleaner(config)
        mappings = [{"field_id": "value", "source_column": "value"}]
        
        df_cleaned, result = cleaner.clean(df, mappings)
        
        assert df_cleaned.isna().sum().sum() == 2
        assert result.null_count == 2
    
    def test_null_drop(self):
        """测试空值删除"""
        df = pd.DataFrame({
            'value': [1, 2, None, 4, 5, None, 7, 8, 9, 10]
        })
        
        config = {
            "field_configs": [
                {
                    "field_id": "value",
                    "field_name": "value",
                    "null_treatment": "drop",
                    "outlier_method": "none"
                }
            ]
        }
        
        cleaner = DataCleaner(config)
        mappings = [{"field_id": "value", "source_column": "value"}]
        
        df_cleaned, result = cleaner.clean(df, mappings)
        
        assert len(df_cleaned) == 8
        assert result.dropped_rows == 2
        assert result.null_count == 2
    
    def test_null_fill(self):
        """测试空值填充指定值"""
        df = pd.DataFrame({
            'value': [1, 2, None, 4, 5, None, 7, 8, 9, 10]
        })
        
        config = {
            "field_configs": [
                {
                    "field_id": "value",
                    "field_name": "value",
                    "null_treatment": "fill",
                    "fill_value": 0,
                    "outlier_method": "none"
                }
            ]
        }
        
        cleaner = DataCleaner(config)
        mappings = [{"field_id": "value", "source_column": "value"}]
        
        df_cleaned, result = cleaner.clean(df, mappings)
        
        assert df_cleaned.isna().sum().sum() == 0
        assert (df_cleaned['value'] == 0).sum() == 2
        assert result.null_count == 2
    
    def test_null_fill_mean(self):
        """测试空值使用均值填充"""
        values = [1, 2, None, 4, 5, None, 7, 8, 9, 10]
        df = pd.DataFrame({'value': values})
        
        valid_values = [1, 2, 4, 5, 7, 8, 9, 10]
        expected_mean = np.mean(valid_values)
        
        config = {
            "field_configs": [
                {
                    "field_id": "value",
                    "field_name": "value",
                    "null_treatment": "fill_mean",
                    "outlier_method": "none"
                }
            ]
        }
        
        cleaner = DataCleaner(config)
        mappings = [{"field_id": "value", "source_column": "value"}]
        
        df_cleaned, result = cleaner.clean(df, mappings)
        
        assert df_cleaned.isna().sum().sum() == 0
        
        filled_values = df_cleaned[df.isna().any(axis=1)]['value'].values
        for val in filled_values:
            assert_almost_equal(val, expected_mean)
        
        assert result.null_count == 2
    
    def test_null_fill_median(self):
        """测试空值使用中位数填充"""
        values = [1, 2, None, 4, 5, None, 7, 8, 9, 10]
        df = pd.DataFrame({'value': values})
        
        valid_values = [1, 2, 4, 5, 7, 8, 9, 10]
        expected_median = np.median(valid_values)
        
        config = {
            "field_configs": [
                {
                    "field_id": "value",
                    "field_name": "value",
                    "null_treatment": "fill_median",
                    "outlier_method": "none"
                }
            ]
        }
        
        cleaner = DataCleaner(config)
        mappings = [{"field_id": "value", "source_column": "value"}]
        
        df_cleaned, result = cleaner.clean(df, mappings)
        
        assert df_cleaned.isna().sum().sum() == 0
        assert result.null_count == 2
    
    def test_null_fill_mode(self):
        """测试空值使用众数填充"""
        values = ['A', 'A', 'B', None, 'A', None, 'B', 'A', 'B', 'A']
        df = pd.DataFrame({'category': values})
        
        config = {
            "field_configs": [
                {
                    "field_id": "category",
                    "field_name": "category",
                    "null_treatment": "fill_mode",
                    "outlier_method": "none"
                }
            ]
        }
        
        cleaner = DataCleaner(config)
        mappings = [{"field_id": "category", "source_column": "category"}]
        
        df_cleaned, result = cleaner.clean(df, mappings)
        
        assert df_cleaned.isna().sum().sum() == 0
        
        mode_count = (df_cleaned['category'] == 'A').sum()
        assert mode_count >= 5
        assert result.null_count == 2
    
    def test_null_mark(self):
        """测试空值标记"""
        df = pd.DataFrame({
            'value': [1, 2, None, 4, 5, None, 7, 8, 9, 10]
        })
        
        config = {
            "field_configs": [
                {
                    "field_id": "value",
                    "field_name": "value",
                    "null_treatment": "mark",
                    "outlier_method": "none"
                }
            ]
        }
        
        cleaner = DataCleaner(config)
        mappings = [{"field_id": "value", "source_column": "value"}]
        
        df_cleaned, result = cleaner.clean(df, mappings)
        
        assert 'value_is_null' in df_cleaned.columns
        assert df_cleaned['value_is_null'].sum() == 2
        assert result.null_count == 2


class TestOutlierDetection:
    """异常值检测测试"""
    
    def test_outlier_zscore(self):
        """测试Z-score异常值检测"""
        values = [1, 2, 3, 4, 5, 6, 7, 8, 100, 150]
        df = pd.DataFrame({'value': values})
        
        config = {
            "field_configs": [
                {
                    "field_id": "value",
                    "field_name": "value",
                    "null_treatment": "keep",
                    "outlier_method": "z_score",
                    "outlier_threshold": 2.0,
                    "outlier_action": "mark"
                }
            ]
        }
        
        cleaner = DataCleaner(config)
        mappings = [{"field_id": "value", "source_column": "value"}]
        
        df_cleaned, result = cleaner.clean(df, mappings)
        
        assert 'value_is_outlier' in df_cleaned.columns
        assert result.outlier_count > 0
    
    def test_outlier_iqr(self):
        """测试IQR异常值检测"""
        values = [1, 2, 3, 4, 5, 6, 7, 8, 100, 150]
        df = pd.DataFrame({'value': values})
        
        config = {
            "field_configs": [
                {
                    "field_id": "value",
                    "field_name": "value",
                    "null_treatment": "keep",
                    "outlier_method": "iqr",
                    "outlier_action": "mark"
                }
            ]
        }
        
        cleaner = DataCleaner(config)
        mappings = [{"field_id": "value", "source_column": "value"}]
        
        df_cleaned, result = cleaner.clean(df, mappings)
        
        assert 'value_is_outlier' in df_cleaned.columns
        assert result.outlier_count > 0
    
    def test_outlier_drop(self):
        """测试异常值删除"""
        values = [1, 2, 3, 4, 5, 6, 7, 8, 100, 150]
        df = pd.DataFrame({'value': values})
        
        config = {
            "field_configs": [
                {
                    "field_id": "value",
                    "field_name": "value",
                    "null_treatment": "keep",
                    "outlier_method": "z_score",
                    "outlier_threshold": 1.5,
                    "outlier_action": "drop"
                }
            ]
        }
        
        cleaner = DataCleaner(config)
        mappings = [{"field_id": "value", "source_column": "value"}]
        
        df_cleaned, result = cleaner.clean(df, mappings)
        
        assert len(df_cleaned) < 10
        assert result.outlier_count > 0
        assert result.dropped_rows > 0
    
    def test_outlier_cap(self):
        """测试异常值缩尾处理"""
        values = [1, 2, 3, 4, 5, 6, 7, 8, 100, 150]
        df = pd.DataFrame({'value': values})
        
        config = {
            "field_configs": [
                {
                    "field_id": "value",
                    "field_name": "value",
                    "null_treatment": "keep",
                    "outlier_method": "z_score",
                    "outlier_threshold": 1.5,
                    "outlier_action": "cap"
                }
            ]
        }
        
        cleaner = DataCleaner(config)
        mappings = [{"field_id": "value", "source_column": "value"}]
        
        df_cleaned, result = cleaner.clean(df, mappings)
        
        assert df_cleaned['value'].max() < 150
        assert result.outlier_count > 0


class TestTextNormalization:
    """文本标准化测试"""
    
    def test_text_lower(self):
        """测试文本转小写"""
        df = pd.DataFrame({
            'text': ['HELLO', 'World', 'Test', 'DATA', 'Clean']
        })
        
        config = {
            "field_configs": [
                {
                    "field_id": "text",
                    "field_name": "text",
                    "null_treatment": "keep",
                    "outlier_method": "none",
                    "text_normalization": ["lower"]
                }
            ]
        }
        
        cleaner = DataCleaner(config)
        mappings = [{"field_id": "text", "source_column": "text"}]
        
        df_cleaned, result = cleaner.clean(df, mappings)
        
        expected = ['hello', 'world', 'test', 'data', 'clean']
        actual = df_cleaned['text'].tolist()
        
        assert actual == expected
    
    def test_text_upper(self):
        """测试文本转大写"""
        df = pd.DataFrame({
            'text': ['hello', 'World', 'Test', 'data', 'Clean']
        })
        
        config = {
            "field_configs": [
                {
                    "field_id": "text",
                    "field_name": "text",
                    "null_treatment": "keep",
                    "outlier_method": "none",
                    "text_normalization": ["upper"]
                }
            ]
        }
        
        cleaner = DataCleaner(config)
        mappings = [{"field_id": "text", "source_column": "text"}]
        
        df_cleaned, result = cleaner.clean(df, mappings)
        
        expected = ['HELLO', 'WORLD', 'TEST', 'DATA', 'CLEAN']
        actual = df_cleaned['text'].tolist()
        
        assert actual == expected
    
    def test_text_trim(self):
        """测试文本去空格"""
        df = pd.DataFrame({
            'text': ['  hello  ', ' world ', '  test', 'data  ', ' Clean  ']
        })
        
        config = {
            "field_configs": [
                {
                    "field_id": "text",
                    "field_name": "text",
                    "null_treatment": "keep",
                    "outlier_method": "none",
                    "text_normalization": ["trim"]
                }
            ]
        }
        
        cleaner = DataCleaner(config)
        mappings = [{"field_id": "text", "source_column": "text"}]
        
        df_cleaned, result = cleaner.clean(df, mappings)
        
        expected = ['hello', 'world', 'test', 'data', 'Clean']
        actual = df_cleaned['text'].tolist()
        
        assert actual == expected
    
    def test_text_remove_special(self):
        """测试文本去特殊字符"""
        df = pd.DataFrame({
            'text': ['hello!', 'world@', 'test#', 'data$', 'Clean%']
        })
        
        config = {
            "field_configs": [
                {
                    "field_id": "text",
                    "field_name": "text",
                    "null_treatment": "keep",
                    "outlier_method": "none",
                    "text_normalization": ["remove_special"]
                }
            ]
        }
        
        cleaner = DataCleaner(config)
        mappings = [{"field_id": "text", "source_column": "text"}]
        
        df_cleaned, result = cleaner.clean(df, mappings)
        
        expected = ['hello', 'world', 'test', 'data', 'Clean']
        actual = df_cleaned['text'].tolist()
        
        assert actual == expected
    
    def test_text_multiple_normalizations(self):
        """测试多文本标准化组合"""
        df = pd.DataFrame({
            'text': ['  Hello!  ', ' World@', '  Test#  ', 'Data$', ' CLEAN%  ']
        })
        
        config = {
            "field_configs": [
                {
                    "field_id": "text",
                    "field_name": "text",
                    "null_treatment": "keep",
                    "outlier_method": "none",
                    "text_normalization": ["trim", "lower", "remove_special"]
                }
            ]
        }
        
        cleaner = DataCleaner(config)
        mappings = [{"field_id": "text", "source_column": "text"}]
        
        df_cleaned, result = cleaner.clean(df, mappings)
        
        expected = ['hello', 'world', 'test', 'data', 'clean']
        actual = df_cleaned['text'].tolist()
        
        assert actual == expected


class TestCustomRules:
    """自定义规则测试"""
    
    def test_custom_replace(self):
        """测试自定义替换规则"""
        df = pd.DataFrame({
            'category': ['高', '中', '低', '高', '中', '低', '高', '中', '低', '高']
        })
        
        config = {
            "field_configs": [
                {
                    "field_id": "category",
                    "field_name": "category",
                    "null_treatment": "keep",
                    "outlier_method": "none",
                    "custom_rules": [
                        {
                            "type": "replace",
                            "params": {
                                "old_value": "高",
                                "new_value": "High"
                            }
                        }
                    ]
                }
            ]
        }
        
        cleaner = DataCleaner(config)
        mappings = [{"field_id": "category", "source_column": "category"}]
        
        df_cleaned, result = cleaner.clean(df, mappings)
        
        assert 'High' in df_cleaned['category'].values
        assert '高' not in df_cleaned['category'].values
    
    def test_custom_regex_replace(self):
        """测试自定义正则替换规则"""
        df = pd.DataFrame({
            'id': ['ID_001', 'ID_002', 'ID_003', 'ID_004', 'ID_005']
        })
        
        config = {
            "field_configs": [
                {
                    "field_id": "id",
                    "field_name": "id",
                    "null_treatment": "keep",
                    "outlier_method": "none",
                    "custom_rules": [
                        {
                            "type": "regex_replace",
                            "params": {
                                "pattern": "^ID_",
                                "replacement": ""
                            }
                        }
                    ]
                }
            ]
        }
        
        cleaner = DataCleaner(config)
        mappings = [{"field_id": "id", "source_column": "id"}]
        
        df_cleaned, result = cleaner.clean(df, mappings)
        
        expected = ['001', '002', '003', '004', '005']
        actual = df_cleaned['id'].tolist()
        
        assert actual == expected


class TestEdgeCases:
    """边界场景测试"""
    
    def test_empty_dataframe(self):
        """测试空DataFrame"""
        df = pd.DataFrame({'value': []})
        
        config = {
            "field_configs": [
                {
                    "field_id": "value",
                    "field_name": "value",
                    "null_treatment": "keep",
                    "outlier_method": "none"
                }
            ]
        }
        
        cleaner = DataCleaner(config)
        mappings = [{"field_id": "value", "source_column": "value"}]
        
        df_cleaned, result = cleaner.clean(df, mappings)
        
        assert len(df_cleaned) == 0
        assert result.total_rows == 0
        assert result.null_count == 0
        assert result.outlier_count == 0
    
    def test_single_row_dataframe(self):
        """测试单行DataFrame"""
        df = pd.DataFrame({'value': [42]})
        
        config = {
            "field_configs": [
                {
                    "field_id": "value",
                    "field_name": "value",
                    "null_treatment": "keep",
                    "outlier_method": "none"
                }
            ]
        }
        
        cleaner = DataCleaner(config)
        mappings = [{"field_id": "value", "source_column": "value"}]
        
        df_cleaned, result = cleaner.clean(df, mappings)
        
        assert len(df_cleaned) == 1
        assert result.total_rows == 1
        assert df_cleaned['value'].iloc[0] == 42
    
    def test_all_nulls_dataframe(self):
        """测试全空值DataFrame"""
        df = pd.DataFrame({'value': [None, None, None, None, None]})
        
        config = {
            "field_configs": [
                {
                    "field_id": "value",
                    "field_name": "value",
                    "null_treatment": "keep",
                    "outlier_method": "none"
                }
            ]
        }
        
        cleaner = DataCleaner(config)
        mappings = [{"field_id": "value", "source_column": "value"}]
        
        df_cleaned, result = cleaner.clean(df, mappings)
        
        assert result.null_count == 5
    
    def test_no_outliers(self):
        """测试没有异常值的数据"""
        values = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10]
        df = pd.DataFrame({'value': values})
        
        config = {
            "field_configs": [
                {
                    "field_id": "value",
                    "field_name": "value",
                    "null_treatment": "keep",
                    "outlier_method": "z_score",
                    "outlier_threshold": 3.0,
                    "outlier_action": "mark"
                }
            ]
        }
        
        cleaner = DataCleaner(config)
        mappings = [{"field_id": "value", "source_column": "value"}]
        
        df_cleaned, result = cleaner.clean(df, mappings)
        
        assert result.outlier_count == 0


class TestCleaningResult:
    """清洗结果测试"""
    
    def test_cleaning_result_to_dict(self):
        """测试清洗结果转换为字典"""
        df = pd.DataFrame({
            'value': [1, 2, None, 4, 5, None, 7, 8, 100, 150]
        })
        
        config = {
            "field_configs": [
                {
                    "field_id": "value",
                    "field_name": "value",
                    "null_treatment": "keep",
                    "outlier_method": "z_score",
                    "outlier_threshold": 1.5,
                    "outlier_action": "mark"
                }
            ]
        }
        
        cleaner = DataCleaner(config)
        mappings = [{"field_id": "value", "source_column": "value"}]
        
        df_cleaned, result = cleaner.clean(df, mappings)
        
        result_dict = result.to_dict()
        
        assert "total_rows" in result_dict
        assert "cleaned_rows" in result_dict
        assert "dropped_rows" in result_dict
        assert "null_count" in result_dict
        assert "outlier_count" in result_dict
        assert "field_stats" in result_dict
        assert "warnings" in result_dict
        assert "errors" in result_dict
        
        assert result_dict["total_rows"] == 10
        assert result_dict["null_count"] == 2
        assert result_dict["outlier_count"] >= 0


class TestMultipleFields:
    """多字段清洗测试"""
    
    def test_multiple_fields_cleaning(self):
        """测试多字段同时清洗"""
        df = pd.DataFrame({
            'name': ['  Alice  ', '  Bob', 'Charlie  ', '  Dave  ', 'Eve'],
            'age': [25, 32, None, 45, 38],
            'score': [85, 92, 78, 1000, 88]
        })
        
        config = {
            "field_configs": [
                {
                    "field_id": "name",
                    "field_name": "name",
                    "null_treatment": "keep",
                    "outlier_method": "none",
                    "text_normalization": ["trim", "lower"]
                },
                {
                    "field_id": "age",
                    "field_name": "age",
                    "null_treatment": "fill_mean",
                    "outlier_method": "none"
                },
                {
                    "field_id": "score",
                    "field_name": "score",
                    "null_treatment": "keep",
                    "outlier_method": "z_score",
                    "outlier_threshold": 2.0,
                    "outlier_action": "mark"
                }
            ]
        }
        
        cleaner = DataCleaner(config)
        mappings = [
            {"field_id": "name", "source_column": "name"},
            {"field_id": "age", "source_column": "age"},
            {"field_id": "score", "source_column": "score"}
        ]
        
        df_cleaned, result = cleaner.clean(df, mappings)
        
        assert 'name' in df_cleaned.columns
        assert 'age' in df_cleaned.columns
        assert 'score' in df_cleaned.columns
        
        expected_names = ['alice', 'bob', 'charlie', 'dave', 'eve']
        actual_names = df_cleaned['name'].tolist()
        assert actual_names == expected_names
        
        assert df_cleaned['age'].isna().sum() == 0
        
        assert 'score_is_outlier' in df_cleaned.columns
        
        assert result.null_count >= 1
        assert result.outlier_count >= 1
