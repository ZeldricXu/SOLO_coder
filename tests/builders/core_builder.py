"""
核心处理模块测试数据构建器
负责构造核心处理相关的测试数据，包括：
- 正常的请求参数
- 各种边界条件和异常参数
- 参数校验相关的测试场景
"""
from typing import Any, Dict, List
from .base_builder import BaseTestDataBuilder
from datetime import datetime
import uuid
import random


class CoreProcessingTestDataBuilder(BaseTestDataBuilder[Dict[str, Any]]):
    """核心处理模块测试数据构建器"""

    RESOURCE_TYPES = ['workflow', 'task', 'job', 'pipeline']

    def _reset(self) -> None:
        """重置构建器状态"""
        self._data = {
            'traceId': self.generate_trace_id(),
            'params': {
                'required_field': 'valid_value',
                'optional_field': 'optional_value',
                'numeric_field': 42,
                'boolean_field': True
            },
            'namespace': 'development',
            'payload': {
                'action': 'process',
                'data': {'key': 'value'},
                'priority': 'normal'
            },
            'resource': {
                'type': 'workflow',
                'config': {},
                'labels': {}
            }
        }

    def with_trace_id(self, trace_id: str) -> 'CoreProcessingTestDataBuilder':
        """设置追踪ID"""
        self._data['traceId'] = trace_id
        return self

    def with_namespace(self, namespace: str) -> 'CoreProcessingTestDataBuilder':
        """设置命名空间"""
        self._data['namespace'] = namespace
        return self

    def with_production_namespace(self) -> 'CoreProcessingTestDataBuilder':
        """设置为生产环境命名空间"""
        self._data['namespace'] = 'production'
        return self

    def with_params(self, params: Dict[str, Any]) -> 'CoreProcessingTestDataBuilder':
        """设置请求参数"""
        self._data['params'] = params
        return self

    def with_required_field(self, value: Any) -> 'CoreProcessingTestDataBuilder':
        """设置必填字段"""
        self._data['params']['required_field'] = value
        return self

    def without_required_field(self) -> 'CoreProcessingTestDataBuilder':
        """移除必填字段（异常场景）"""
        if 'required_field' in self._data['params']:
            del self._data['params']['required_field']
        return self

    def with_empty_params(self) -> 'CoreProcessingTestDataBuilder':
        """设置空参数（异常场景）"""
        self._data['params'] = {}
        return self

    def with_null_params(self) -> 'CoreProcessingTestDataBuilder':
        """设置null参数（异常场景）"""
        self._data['params'] = None
        return self

    def with_large_params(self, field_count: int = 100) -> 'CoreProcessingTestDataBuilder':
        """设置大量参数（边界场景）"""
        params = {'required_field': 'valid_value'}
        for i in range(field_count):
            params[f'field_{i}'] = f'value_{i}'
        self._data['params'] = params
        return self

    def with_special_characters_in_params(self) -> 'CoreProcessingTestDataBuilder':
        """参数包含特殊字符（边界场景）"""
        self._data['params'] = {
            'required_field': 'valid_value',
            'special_chars': '!@#$%^&*()_+-=[]{}|;:,.<>?/~`',
            'unicode': '中文测试_🇨🇳_émoji_🎉',
            'sql_injection': "'; DROP TABLE users;--",
            'xss': '<script>alert("xss")</script>'
        }
        return self

    def with_payload(self, payload: Dict[str, Any]) -> 'CoreProcessingTestDataBuilder':
        """设置请求体"""
        self._data['payload'] = payload
        return self

    def with_empty_payload(self) -> 'CoreProcessingTestDataBuilder':
        """设置空请求体"""
        self._data['payload'] = {}
        return self

    def with_large_payload(self, size_kb: int = 10) -> 'CoreProcessingTestDataBuilder':
        """设置大请求体（边界场景）"""
        large_data = 'x' * (size_kb * 1024)
        self._data['payload'] = {
            'action': 'process',
            'large_field': large_data,
            'data': {'key': 'value'}
        }
        return self

    def with_nested_payload(self, depth: int = 10) -> 'CoreProcessingTestDataBuilder':
        """设置深度嵌套的请求体（边界场景）"""
        nested = {'level': depth}
        current = nested
        for i in range(depth - 1, 0, -1):
            current['child'] = {'level': i}
            current = current['child']
        self._data['payload'] = nested
        return self

    def with_resource_type(self, resource_type: str) -> 'CoreProcessingTestDataBuilder':
        """设置资源类型"""
        self._data['resource']['type'] = resource_type
        return self

    def with_resource_config(self, config: Dict[str, Any]) -> 'CoreProcessingTestDataBuilder':
        """设置资源配置"""
        self._data['resource']['config'] = config
        return self

    def with_resource_labels(self, labels: Dict[str, str]) -> 'CoreProcessingTestDataBuilder':
        """设置资源标签"""
        self._data['resource']['labels'] = labels
        return self

    def with_validation_error_scenario(self) -> 'CoreProcessingTestDataBuilder':
        """设置参数验证错误场景"""
        self.without_required_field()
        return self

    def with_timeout_scenario(self) -> 'CoreProcessingTestDataBuilder':
        """设置超时场景"""
        self._data['payload']['timeout'] = 0  # 零超时
        return self

    def with_concurrent_scenario(self, request_id: str = None) -> 'CoreProcessingTestDataBuilder':
        """设置并发场景"""
        self._data['params']['request_id'] = request_id or self.generate_id('req_')
        self._data['params']['concurrency_key'] = 'shared_resource'
        return self

    def with_invalid_resource_type(self) -> 'CoreProcessingTestDataBuilder':
        """设置无效的资源类型"""
        self._data['resource']['type'] = 'invalid_type_that_does_not_exist'
        return self

    def build_execute_request(self) -> Dict[str, Any]:
        """构建执行请求的API请求体"""
        return {
            'traceId': self._data['traceId'],
            'params': self._data['params'],
            'namespace': self._data['namespace'],
            'payload': self._data['payload']
        }

    def build_create_resource_request(self) -> Dict[str, Any]:
        """构建创建资源的API请求体"""
        return {
            'type': self._data['resource']['type'],
            'config': self._data['resource']['config'],
            'labels': self._data['resource']['labels']
        }

    def build_batch_operation_request(self, operations: List[Dict[str, Any]] = None) -> Dict[str, Any]:
        """构建批量操作请求"""
        if operations is None:
            operations = [
                {'action': 'restart', 'id': self.generate_id('rsc_')},
                {'action': 'delete', 'id': self.generate_id('rsc_')}
            ]
        return {'operations': operations}

    def build(self) -> Dict[str, Any]:
        """构建完整的测试数据"""
        return dict(self._data)

    @staticmethod
    def create_valid_request() -> Dict[str, Any]:
        """静态工厂：创建有效的执行请求"""
        builder = CoreProcessingTestDataBuilder()
        return builder.build_execute_request()

    @staticmethod
    def create_invalid_request_missing_required() -> Dict[str, Any]:
        """静态工厂：创建缺少必填字段的无效请求"""
        builder = CoreProcessingTestDataBuilder()
        builder.without_required_field()
        return builder.build_execute_request()

    @staticmethod
    def create_invalid_request_empty_params() -> Dict[str, Any]:
        """静态工厂：创建空参数的无效请求"""
        builder = CoreProcessingTestDataBuilder()
        builder.with_empty_params()
        return builder.build_execute_request()

    @staticmethod
    def create_resource_request(resource_type: str = 'workflow') -> Dict[str, Any]:
        """静态工厂：创建资源请求"""
        builder = CoreProcessingTestDataBuilder()
        builder.with_resource_type(resource_type)
        builder.with_resource_config({
            'timeout': 30,
            'retries': 3,
            'poolSize': 10
        })
        builder.with_resource_labels({
            'env': 'test',
            'team': 'engineering',
            'project': 'delivery-tracker'
        })
        return builder.build_create_resource_request()

    @staticmethod
    def create_batch_operations(count: int = 5) -> Dict[str, Any]:
        """静态工厂：创建批量操作请求"""
        operations = []
        actions = ['restart', 'stop', 'start', 'delete']
        for _ in range(count):
            operations.append({
                'action': random.choice(actions),
                'id': CoreProcessingTestDataBuilder.generate_id('rsc_')
            })
        return {'operations': operations}
