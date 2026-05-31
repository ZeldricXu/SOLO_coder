"""
测试基类
提供通用的测试Setup/Teardown和辅助方法
"""
from __future__ import annotations

import asyncio
import time
from typing import Any, Dict, List, Optional
import pytest
from unittest.mock import Mock, patch, MagicMock, AsyncMock

from tests.http_client import TestHttpClient, ConcurrentRequestRunner, create_mock_response
from tests.data_factory import TestDataFactory, get_factory


class BaseTest:
    """测试基类"""

    client: TestHttpClient
    factory: TestDataFactory
    created_resources: List[Dict[str, Any]]

    @pytest.fixture(autouse=True)
    def setup(self, base_url: str, api_prefix: str, default_timeout: int):
        """测试初始化"""
        self.client = TestHttpClient(
            base_url=base_url,
            api_prefix=api_prefix,
            default_timeout=default_timeout
        )
        self.factory = get_factory()
        self.created_resources = []
        yield
        # 测试清理
        asyncio.run(self._cleanup_resources())

    async def _cleanup_resources(self):
        """清理创建的测试资源"""
        for resource in self.created_resources:
            try:
                if resource.get('path') and resource.get('id'):
                    await self.client.delete(f"{resource['path']}/{resource['id']}")
            except Exception as e:
                pass
        self.created_resources.clear()

    def register_resource(self, path: str, resource_id: str):
        """注册需要清理的资源"""
        self.created_resources.append({
            'path': path,
            'id': resource_id
        })

    def assert_success(self, response, status_code: int = 200):
        """断言请求成功"""
        assert response.is_success, f"Request failed: {response.error_message}"
        assert response.status_code == status_code, f"Unexpected status code: {response.status_code}"
        if response.data and isinstance(response.data, dict):
            assert response.code == 200 or response.code == 201, f"Business error: {response.message}"

    def assert_error(self, response, expected_code: int, expected_message: str = None):
        """断言请求失败"""
        if expected_message:
            assert expected_message in response.message or expected_message in str(response.error_message)

    def assert_data_consistency(self, data1: Dict[str, Any], data2: Dict[str, Any], keys: List[str] = None):
        """断言数据一致性"""
        if keys is None:
            keys = set(data1.keys()) & set(data2.keys())
        for key in keys:
            assert data1.get(key) == data2.get(key), f"Data mismatch for key '{key}': {data1.get(key)} != {data2.get(key)}"

    async def measure_performance(self, func, *args, **kwargs) -> tuple[Any, float]:
        """测量函数执行时间"""
        start = time.time()
        result = await func(*args, **kwargs)
        elapsed = time.time() - start
        return result, elapsed

    def create_concurrent_runner(self) -> ConcurrentRequestRunner:
        """创建并发执行器"""
        return ConcurrentRequestRunner(self.client)


class MockBaseTest(BaseTest):
    """Mock测试基类"""

    @pytest.fixture(autouse=True)
    def setup(self, base_url: str, api_prefix: str, default_timeout: int):
        """Mock测试初始化"""
        self.client = TestHttpClient(
            base_url=base_url,
            api_prefix=api_prefix,
            default_timeout=default_timeout,
            use_mock=True
        )
        self.factory = get_factory()
        self.created_resources = []
        self._setup_default_mocks()
        yield
        self.client.clear_mocks()

    def _setup_default_mocks(self):
        """设置默认Mock响应"""
        # 成功的创建响应
        self.client.register_mock_response(
            path="*",
            method="POST",
            response=create_mock_response(
                status_code=201,
                data={"id": "mock_id_001", "status": "success"},
                code=201
            )
        )

        # 成功的查询响应
        self.client.register_mock_response(
            path="*",
            method="GET",
            response=create_mock_response(
                status_code=200,
                data={"id": "mock_id_001"}
            )
        )

        # 成功的更新响应
        self.client.register_mock_response(
            path="*",
            method="PUT",
            response=create_mock_response(
                status_code=200,
                data={"status": "updated"}
            )
        )

        # 成功的删除响应
        self.client.register_mock_response(
            path="*",
            method="DELETE",
            response=create_mock_response(
                status_code=200,
                data={"status": "deleted"}
            )
        )

    def mock_timeout(self, path: str, method: str = "GET"):
        """Mock超时响应"""
        self.client.register_mock_response(
            path=path,
            method=method,
            response=create_mock_response(
                status_code=504,
                data=None,
                code=504,
                message="Gateway Timeout"
            )
        )

    def mock_error(self, path: str, method: str, status_code: int, message: str):
        """Mock错误响应"""
        self.client.register_mock_response(
            path=path,
            method=method,
            response=create_mock_response(
                status_code=status_code,
                data=None,
                code=status_code,
                message=message
            )
        )

    def mock_slow_response(self, path: str, method: str, delay_seconds: float = 2.0):
        """Mock慢响应"""
        response = create_mock_response(status_code=200, data={"slow": True})
        response.elapsed = delay_seconds
        self.client.register_mock_response(path, method, response)


class PerformanceTestMixin:
    """性能测试混入类"""

    async def run_load_test(
        self,
        func,
        iterations: int = 100,
        max_concurrent: int = 20
    ) -> Dict[str, Any]:
        """运行负载测试"""
        start_time = time.time()
        tasks = [asyncio.create_task(func()) for _ in range(iterations)]

        semaphore = asyncio.Semaphore(max_concurrent)

        async def bounded_run(task):
            async with semaphore:
                return await task

        results = await asyncio.gather(*[bounded_run(t) for t in tasks], return_exceptions=True)
        total_time = time.time() - start_time

        success_count = sum(1 for r in results if not isinstance(r, Exception))
        error_count = len(results) - success_count

        return {
            "total_requests": iterations,
            "success_count": success_count,
            "error_count": error_count,
            "total_time": total_time,
            "qps": iterations / total_time if total_time > 0 else 0,
            "error_rate": error_count / iterations
        }
