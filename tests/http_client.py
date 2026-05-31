"""
HTTP客户端封装
提供统一的API调用接口，支持超时、重试、并发等特性
"""
from __future__ import annotations

import asyncio
import time
from typing import Any, Dict, List, Optional, Tuple, Union
from dataclasses import dataclass
import httpx
import logging
from unittest.mock import Mock, MagicMock, patch
import json

logger = logging.getLogger(__name__)


@dataclass
class ApiResponse:
    """API响应封装"""
    status_code: int
    data: Any
    headers: Dict[str, str]
    elapsed: float
    is_success: bool
    error_message: Optional[str] = None

    @property
    def code(self) -> int:
        """获取业务状态码"""
        if isinstance(self.data, dict) and 'code' in self.data:
            return self.data['code']
        return self.status_code

    @property
    def message(self) -> str:
        """获取响应消息"""
        if isinstance(self.data, dict) and 'message' in self.data:
            return self.data['message']
        return ''

    @property
    def result(self) -> Any:
        """获取响应数据"""
        if isinstance(self.data, dict) and 'data' in self.data:
            return self.data['data']
        return self.data


class TestHttpClient:
    """测试用HTTP客户端"""

    def __init__(
        self,
        base_url: str = "http://localhost:8080",
        api_prefix: str = "/api/v1",
        default_timeout: int = 30,
        use_mock: bool = False
    ):
        self.base_url = base_url.rstrip('/')
        self.api_prefix = api_prefix.rstrip('/')
        self.default_timeout = default_timeout
        self.use_mock = use_mock
        self._mock_responses: Dict[str, ApiResponse] = {}
        self._request_history: List[Tuple[str, str, Dict[str, Any]]] = []

        if not use_mock:
            self._client = httpx.AsyncClient(
                timeout=httpx.Timeout(default_timeout),
                follow_redirects=True
            )

    def _build_url(self, path: str) -> str:
        """构建完整URL"""
        if path.startswith('http'):
            return path
        if path.startswith(self.api_prefix):
            return f"{self.base_url}{path}"
        return f"{self.base_url}{self.api_prefix}/{path.lstrip('/')}"

    def register_mock_response(
        self,
        path: str,
        method: str,
        response: ApiResponse
    ):
        """注册Mock响应"""
        key = f"{method.upper()}:{path}"
        self._mock_responses[key] = response

    def clear_mocks(self):
        """清除所有Mock响应"""
        self._mock_responses.clear()
        self._request_history.clear()

    def get_request_history(self) -> List[Tuple[str, str, Dict[str, Any]]]:
        """获取请求历史"""
        return self._request_history.copy()

    async def _request(
        self,
        method: str,
        path: str,
        **kwargs
    ) -> ApiResponse:
        """执行HTTP请求"""
        url = self._build_url(path)
        timeout = kwargs.pop('timeout', self.default_timeout)

        self._request_history.append((method, url, kwargs.copy()))

        # Mock模式
        if self.use_mock:
            return self._handle_mock_request(method, path, kwargs)

        start_time = time.time()
        try:
            async with httpx.AsyncClient(timeout=httpx.Timeout(timeout)) as client:
                response = await client.request(method, url, **kwargs)
                elapsed = time.time() - start_time

                try:
                    data = response.json()
                except json.JSONDecodeError:
                    data = response.text

                return ApiResponse(
                    status_code=response.status_code,
                    data=data,
                    headers=dict(response.headers),
                    elapsed=elapsed,
                    is_success=response.is_success
                )
        except httpx.TimeoutException as e:
            elapsed = time.time() - start_time
            return ApiResponse(
                status_code=408,
                data=None,
                headers={},
                elapsed=elapsed,
                is_success=False,
                error_message=f"Request timeout: {str(e)}"
            )
        except Exception as e:
            elapsed = time.time() - start_time
            logger.error(f"Request failed: {method} {url}", exc_info=True)
            return ApiResponse(
                status_code=500,
                data=None,
                headers={},
                elapsed=elapsed,
                is_success=False,
                error_message=str(e)
            )

    def _handle_mock_request(
        self,
        method: str,
        path: str,
        kwargs: Dict[str, Any]
    ) -> ApiResponse:
        """处理Mock请求"""
        key = f"{method.upper()}:{path}"
        if key in self._mock_responses:
            return self._mock_responses[key]

        # 默认成功响应
        return ApiResponse(
            status_code=200,
            data={"code": 200, "message": "success", "data": {"mock": True}},
            headers={},
            elapsed=0.01,
            is_success=True
        )

    async def get(self, path: str, **kwargs) -> ApiResponse:
        """GET请求"""
        return await self._request('GET', path, **kwargs)

    async def post(self, path: str, **kwargs) -> ApiResponse:
        """POST请求"""
        return await self._request('POST', path, **kwargs)

    async def put(self, path: str, **kwargs) -> ApiResponse:
        """PUT请求"""
        return await self._request('PUT', path, **kwargs)

    async def delete(self, path: str, **kwargs) -> ApiResponse:
        """DELETE请求"""
        return await self._request('DELETE', path, **kwargs)

    async def patch(self, path: str, **kwargs) -> ApiResponse:
        """PATCH请求"""
        return await self._request('PATCH', path, **kwargs)

    async def close(self):
        """关闭客户端"""
        if not self.use_mock and hasattr(self, '_client'):
            await self._client.aclose()

    # 上下文管理器支持
    async def __aenter__(self):
        return self

    async def __aexit__(self, exc_type, exc_val, exc_tb):
        await self.close()


class ConcurrentRequestRunner:
    """并发请求执行器"""

    def __init__(self, client: TestHttpClient):
        self.client = client

    async def run_concurrent(
        self,
        requests: List[Tuple[str, str, Dict[str, Any]]],
        max_concurrent: int = 20
    ) -> List[ApiResponse]:
        """并发执行多个请求"""
        semaphore = asyncio.Semaphore(max_concurrent)

        async def bounded_request(method, path, kwargs):
            async with semaphore:
                return await self.client._request(method, path, **kwargs)

        tasks = [
            bounded_request(method, path, kwargs)
            for method, path, kwargs in requests
        ]

        return await asyncio.gather(*tasks, return_exceptions=True)

    async def run_with_retry(
        self,
        method: str,
        path: str,
        max_retries: int = 3,
        retry_delay: float = 1.0,
        **kwargs
    ) -> ApiResponse:
        """带重试的请求"""
        last_exception = None
        for attempt in range(max_retries):
            try:
                response = await self.client._request(method, path, **kwargs)
                if response.is_success:
                    return response
                last_exception = Exception(f"Request failed with status {response.status_code}")
            except Exception as e:
                last_exception = e

            if attempt < max_retries - 1:
                await asyncio.sleep(retry_delay * (2 ** attempt))

        return ApiResponse(
            status_code=500,
            data=None,
            headers={},
            elapsed=0,
            is_success=False,
            error_message=f"All retries failed: {str(last_exception)}"
        )


def create_mock_response(
    status_code: int = 200,
    data: Any = None,
    code: int = None,
    message: str = "success",
    elapsed: float = 0.01
) -> ApiResponse:
    """创建Mock响应"""
    if code is None:
        code = status_code

    return ApiResponse(
        status_code=status_code,
        data={"code": code, "message": message, "data": data},
        headers={},
        elapsed=elapsed,
        is_success=200 <= status_code < 300
    )
