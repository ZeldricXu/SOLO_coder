"""
差分隐私模块测试数据构建器
负责构造差分隐私相关的测试数据，包括：
- 正常场景的查询结果
- 边界值场景（空数据、极值、零值）
- 隐私预算相关数据
"""
from typing import Any, Dict, List
from .base_builder import BaseTestDataBuilder
import random
from decimal import Decimal


class PrivacyTestDataBuilder(BaseTestDataBuilder[Dict[str, Any]]):
    """差分隐私测试数据构建器"""

    def _reset(self) -> None:
        """重置构建器状态"""
        self._data = {
            'userId': self.generate_user_id(),
            'queryResult': {},
            'sensitivity': 1.0,
            'privacyBudget': {
                'epsilonRemaining': 10.0,
                'deltaRemaining': 0.001,
                'totalQueries': 0
            }
        }

    def with_user_id(self, user_id: str) -> 'PrivacyTestDataBuilder':
        """设置用户ID"""
        self._data['userId'] = user_id
        return self

    def with_sensitivity(self, sensitivity: float) -> 'PrivacyTestDataBuilder':
        """设置敏感度"""
        self._data['sensitivity'] = sensitivity
        return self

    def with_query_result(self, query_result: Dict[str, Any]) -> 'PrivacyTestDataBuilder':
        """设置查询结果"""
        self._data['queryResult'] = query_result
        return self

    def with_privacy_budget(self, epsilon: float, delta: float, total_queries: int = 0) -> 'PrivacyTestDataBuilder':
        """设置隐私预算"""
        self._data['privacyBudget'] = {
            'epsilonRemaining': epsilon,
            'deltaRemaining': delta,
            'totalQueries': total_queries
        }
        return self

    def with_numeric_query_result(self, **numeric_fields) -> 'PrivacyTestDataBuilder':
        """设置包含数值字段的查询结果"""
        self._data['queryResult'] = numeric_fields
        return self

    def with_mixed_query_result(self) -> 'PrivacyTestDataBuilder':
        """设置混合类型的查询结果（包含数值和非数值）"""
        self._data['queryResult'] = {
            'count': random.randint(1, 1000),
            'sum': random.uniform(100.0, 10000.0),
            'avg': random.uniform(10.0, 100.0),
            'max': random.randint(50, 200),
            'min': random.randint(0, 50),
            'category': self._fake.word(),
            'timestamp': self._fake.iso8601(),
            'nested': {
                'value1': random.randint(1, 100),
                'value2': random.uniform(0, 1)
            }
        }
        return self

    def with_empty_query_result(self) -> 'PrivacyTestDataBuilder':
        """设置空查询结果（边界场景）"""
        self._data['queryResult'] = {}
        return self

    def with_large_numeric_values(self) -> 'PrivacyTestDataBuilder':
        """设置大数值查询结果（边界场景）"""
        self._data['queryResult'] = {
            'large_int': 2 ** 63 - 1,
            'large_float': 1e308,
            'negative_large': -1e308,
            'exact_zero': 0,
            'small_value': 1e-308
        }
        return self

    def with_boundary_values(self) -> 'PrivacyTestDataBuilder':
        """设置边界值场景"""
        self._data['queryResult'] = {
            'zero': 0,
            'one': 1,
            'negative_one': -1,
            'max_int': 2147483647,
            'min_int': -2147483648,
            'float_max': 3.4028235e38,
            'float_min': 1.4e-45,
            'decimal_max': Decimal('99999999999999999999.9999999999'),
            'decimal_min': Decimal('-99999999999999999999.9999999999')
        }
        return self

    def with_zero_sensitivity(self) -> 'PrivacyTestDataBuilder':
        """设置零敏感度（边界场景）"""
        self._data['sensitivity'] = 0.0
        return self

    def with_negative_sensitivity(self) -> 'PrivacyTestDataBuilder':
        """设置负敏感度（异常场景）"""
        self._data['sensitivity'] = -1.0
        return self

    def with_depleted_budget(self) -> 'PrivacyTestDataBuilder':
        """设置已耗尽的隐私预算（边界场景）"""
        self._data['privacyBudget'] = {
            'epsilonRemaining': 0.0,
            'deltaRemaining': 0.0,
            'totalQueries': 1000
        }
        return self

    def with_near_depleted_budget(self) -> 'PrivacyTestDataBuilder':
        """设置接近耗尽的隐私预算"""
        self._data['privacyBudget'] = {
            'epsilonRemaining': 0.05,
            'deltaRemaining': 0.000001,
            'totalQueries': 99
        }
        return self

    def build_apply_privacy_request(self) -> Dict[str, Any]:
        """构建应用差分隐私的API请求体"""
        return {
            'userId': self._data['userId'],
            'queryResult': self._data['queryResult'],
            'sensitivity': self._data['sensitivity']
        }

    def build(self) -> Dict[str, Any]:
        """构建完整的测试数据"""
        return dict(self._data)

    @staticmethod
    def create_simple_count_result(count: int = 42) -> Dict[str, Any]:
        """静态工厂：创建简单的计数查询结果"""
        return {'count': count}

    @staticmethod
    def create_statistical_results() -> Dict[str, Any]:
        """静态工厂：创建统计查询结果"""
        return {
            'count': random.randint(100, 10000),
            'sum': random.uniform(1000, 100000),
            'avg': random.uniform(10, 100),
            'stddev': random.uniform(1, 50)
        }

    @staticmethod
    def create_multi_dimensional_results() -> Dict[str, Any]:
        """静态工厂：创建多维查询结果"""
        return {
            'metrics': {
                'throughput': random.randint(100, 10000),
                'latency_p99': random.uniform(10, 500),
                'error_rate': random.uniform(0, 0.1),
                'success_rate': random.uniform(0.9, 1.0)
            },
            'dimensions': {
                'region': ['cn-east', 'cn-west', 'cn-south'],
                'service': ['api', 'web', 'worker']
            }
        }
