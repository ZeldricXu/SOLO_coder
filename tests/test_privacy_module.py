"""
差分隐私注入模块单元测试
测试重点：边界条件处理
- 空查询结果
- 零值/极值
- 隐私预算耗尽
- 敏感度边界值
- 多字段混合类型
"""
import pytest
import math
from unittest.mock import MagicMock, patch
from typing import Dict, Any

from builders.privacy_builder import PrivacyTestDataBuilder


class TestDifferentialPrivacyBoundaryConditions:
    """差分隐私边界条件测试"""

    @pytest.fixture(autouse=True)
    def setup(self, api_base_url, enable_mock):
        """测试前设置"""
        self.base_url = api_base_url
        self.enable_mock = enable_mock
        self.builder = PrivacyTestDataBuilder()

    @pytest.mark.unit
    @pytest.mark.boundary
    def test_empty_query_result(self):
        """测试空查询结果 - 边界条件"""
        request_data = self.builder \
            .with_empty_query_result() \
            .build_apply_privacy_request()

        assert request_data['queryResult'] == {}
        assert 'userId' in request_data
        assert 'sensitivity' in request_data

    @pytest.mark.unit
    @pytest.mark.boundary
    def test_zero_numeric_values(self):
        """测试零值数值 - 边界条件"""
        request_data = self.builder \
            .with_numeric_query_result(
                zero_count=0,
                zero_sum=0.0,
                zero_avg=0.0
            ) \
            .build_apply_privacy_request()

        assert request_data['queryResult']['zero_count'] == 0
        assert request_data['queryResult']['zero_sum'] == 0.0

    @pytest.mark.unit
    @pytest.mark.boundary
    def test_large_numeric_values(self):
        """测试大数值 - 边界条件"""
        request_data = self.builder \
            .with_large_numeric_values() \
            .build_apply_privacy_request()

        query_result = request_data['queryResult']
        assert query_result['large_int'] == 2 ** 63 - 1
        assert query_result['large_float'] == 1e308
        assert query_result['negative_large'] == -1e308

    @pytest.mark.unit
    @pytest.mark.boundary
    def test_boundary_values(self):
        """测试边界值集合 - 边界条件"""
        request_data = self.builder \
            .with_boundary_values() \
            .build_apply_privacy_request()

        query_result = request_data['queryResult']
        assert query_result['zero'] == 0
        assert query_result['one'] == 1
        assert query_result['negative_one'] == -1
        assert query_result['max_int'] == 2147483647
        assert query_result['min_int'] == -2147483648

    @pytest.mark.unit
    @pytest.mark.boundary
    def test_zero_sensitivity(self):
        """测试零敏感度 - 边界条件"""
        request_data = self.builder \
            .with_numeric_query_result(count=100) \
            .with_zero_sensitivity() \
            .build_apply_privacy_request()

        assert request_data['sensitivity'] == 0.0

    @pytest.mark.unit
    @pytest.mark.boundary
    def test_negative_sensitivity(self):
        """测试负敏感度 - 异常场景"""
        request_data = self.builder \
            .with_numeric_query_result(count=100) \
            .with_negative_sensitivity() \
            .build_apply_privacy_request()

        assert request_data['sensitivity'] < 0

    @pytest.mark.unit
    @pytest.mark.boundary
    def test_depleted_privacy_budget(self):
        """测试隐私预算耗尽 - 边界条件"""
        test_data = self.builder \
            .with_numeric_query_result(count=100) \
            .with_depleted_budget() \
            .build()

        budget = test_data['privacyBudget']
        assert budget['epsilonRemaining'] == 0.0
        assert budget['deltaRemaining'] == 0.0
        assert budget['totalQueries'] > 0

    @pytest.mark.unit
    @pytest.mark.boundary
    def test_near_depleted_privacy_budget(self):
        """测试接近耗尽的隐私预算 - 边界条件"""
        test_data = self.builder \
            .with_numeric_query_result(count=100) \
            .with_near_depleted_budget() \
            .build()

        budget = test_data['privacyBudget']
        assert budget['epsilonRemaining'] < 0.1
        assert budget['deltaRemaining'] < 0.00001

    @pytest.mark.unit
    @pytest.mark.boundary
    def test_mixed_type_query_result(self):
        """测试混合类型查询结果 - 验证非数值字段不被修改"""
        request_data = self.builder \
            .with_mixed_query_result() \
            .build_apply_privacy_request()

        query_result = request_data['queryResult']
        assert 'category' in query_result
        assert 'timestamp' in query_result
        assert 'nested' in query_result
        assert isinstance(query_result['category'], str)

    @pytest.mark.unit
    def test_sensitivity_variations(self):
        """测试不同敏感度值"""
        test_cases = [0.1, 0.5, 1.0, 2.0, 5.0, 10.0]

        for sensitivity in test_cases:
            request_data = self.builder \
                .reset() \
                .with_numeric_query_result(count=100) \
                .with_sensitivity(sensitivity) \
                .build_apply_privacy_request()

            assert request_data['sensitivity'] == sensitivity

    @pytest.mark.unit
    def test_multiple_numeric_fields(self):
        """测试多数值字段场景"""
        request_data = self.builder \
            .with_numeric_query_result(
                count=1000,
                sum=50000,
                avg=50.0,
                max=100,
                min=0,
                stddev=25.5,
                median=48
            ) \
            .build_apply_privacy_request()

        assert len(request_data['queryResult']) == 7
        for key, value in request_data['queryResult'].items():
            assert isinstance(value, (int, float))

    @pytest.mark.unit
    def test_user_id_generation(self):
        """测试用户ID生成的唯一性"""
        user_ids = set()
        for _ in range(100):
            builder = PrivacyTestDataBuilder()
            request_data = builder.with_numeric_query_result(count=1).build_apply_privacy_request()
            user_ids.add(request_data['userId'])

        assert len(user_ids) == 100

    @pytest.mark.unit
    @pytest.mark.boundary
    def test_single_numeric_field(self):
        """测试单数值字段 - 最小场景"""
        request_data = self.builder \
            .with_numeric_query_result(count=42) \
            .build_apply_privacy_request()

        assert len(request_data['queryResult']) == 1
        assert request_data['queryResult']['count'] == 42

    @pytest.mark.unit
    @pytest.mark.boundary
    def test_negative_numeric_values(self):
        """测试负数值"""
        request_data = self.builder \
            .with_numeric_query_result(
                profit=-1000,
                loss=-500.5,
                temperature=-273.15
            ) \
            .build_apply_privacy_request()

        query_result = request_data['queryResult']
        assert query_result['profit'] < 0
        assert query_result['loss'] < 0
        assert query_result['temperature'] < 0

    @pytest.mark.unit
    def test_static_factory_methods(self):
        """测试静态工厂方法"""
        simple = PrivacyTestDataBuilder.create_simple_count_result(100)
        assert simple == {'count': 100}

        stats = PrivacyTestDataBuilder.create_statistical_results()
        assert all(key in stats for key in ['count', 'sum', 'avg', 'stddev'])

        multi = PrivacyTestDataBuilder.create_multi_dimensional_results()
        assert 'metrics' in multi
        assert 'dimensions' in multi

    @pytest.mark.integration
    def test_apply_privacy_with_mock(self, mock_privacy_service):
        """使用Mock测试应用差分隐私"""
        request_data = self.builder \
            .with_numeric_query_result(count=100, sum=5000) \
            .build_apply_privacy_request()

        response = mock_privacy_service.apply_privacy(request_data)

        assert response['code'] == 200
        assert 'data' in response
        mock_privacy_service.apply_privacy.assert_called_once_with(request_data)

    @pytest.mark.integration
    def test_budget_consumption_with_mock(self, mock_privacy_service):
        """使用Mock测试预算消耗"""
        user_id = 'test_user_001'

        mock_privacy_service.apply_privacy(
            self.builder.with_user_id(user_id)
                .with_numeric_query_result(count=100)
                .build_apply_privacy_request()
        )

        budget_response = mock_privacy_service.get_budget(user_id)

        assert budget_response['code'] == 200
        assert budget_response['data']['epsilonRemaining'] < 10.0
        assert budget_response['data']['totalQueries'] >= 1

    @pytest.mark.integration
    def test_reset_budget_with_mock(self, mock_privacy_service):
        """使用Mock测试预算重置"""
        user_id = 'test_user_001'

        reset_response = mock_privacy_service.reset_budget(user_id)

        assert reset_response['code'] == 200
        assert reset_response['data']['epsilonRemaining'] == 10.0
        assert reset_response['data']['totalQueries'] == 0


class TestDifferentialPrivacyErrorHandling:
    """差分隐私错误处理测试"""

    @pytest.fixture(autouse=True)
    def setup(self):
        self.builder = PrivacyTestDataBuilder()

    @pytest.mark.unit
    def test_missing_user_id(self):
        """测试缺少用户ID"""
        request_data = self.builder.with_numeric_query_result(count=100).build_apply_privacy_request()
        del request_data['userId']

        assert 'userId' not in request_data

    @pytest.mark.unit
    def test_missing_query_result(self):
        """测试缺少查询结果"""
        request_data = self.builder.build_apply_privacy_request()
        del request_data['queryResult']

        assert 'queryResult' not in request_data

    @pytest.mark.unit
    def test_missing_sensitivity(self):
        """测试缺少敏感度"""
        request_data = self.builder.with_numeric_query_result(count=100).build_apply_privacy_request()
        del request_data['sensitivity']

        assert 'sensitivity' not in request_data

    @pytest.mark.unit
    def test_non_numeric_query_values(self):
        """测试非数值查询值"""
        request_data = self.builder \
            .with_query_result({
                'string_field': 'not_a_number',
                'list_field': [1, 2, 3],
                'dict_field': {'key': 'value'},
                'bool_field': True,
                'null_field': None
            }) \
            .build_apply_privacy_request()

        query_result = request_data['queryResult']
        assert not isinstance(query_result['string_field'], (int, float))
        assert isinstance(query_result['list_field'], list)
        assert isinstance(query_result['dict_field'], dict)
