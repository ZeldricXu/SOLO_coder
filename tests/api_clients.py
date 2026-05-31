"""
API客户端封装
提供与Java后端服务通信的接口
"""
import aiohttp
import requests
from typing import Dict, Any, Optional
from abc import ABC, abstractmethod


class BaseApiClient(ABC):
    """API客户端基类"""

    def __init__(self, base_url: str, timeout: int = 30):
        self.base_url = base_url.rstrip('/')
        self.timeout = timeout
        self.session = requests.Session()

    def _get(self, path: str, params: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
        """发送GET请求"""
        url = f"{self.base_url}{path}"
        response = self.session.get(url, params=params, timeout=self.timeout)
        response.raise_for_status()
        return response.json()

    def _post(self, path: str, data: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
        """发送POST请求"""
        url = f"{self.base_url}{path}"
        response = self.session.post(url, json=data, timeout=self.timeout)
        response.raise_for_status()
        return response.json()

    def _delete(self, path: str) -> Dict[str, Any]:
        """发送DELETE请求"""
        url = f"{self.base_url}{path}"
        response = self.session.delete(url, timeout=self.timeout)
        response.raise_for_status()
        return response.json()

    def close(self):
        """关闭会话"""
        self.session.close()


class AsyncBaseApiClient(ABC):
    """异步API客户端基类"""

    def __init__(self, base_url: str, timeout: int = 30):
        self.base_url = base_url.rstrip('/')
        self.timeout = timeout

    async def _get(self, path: str, params: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
        """异步发送GET请求"""
        url = f"{self.base_url}{path}"
        async with aiohttp.ClientSession() as session:
            async with session.get(url, params=params, timeout=self.timeout) as response:
                response.raise_for_status()
                return await response.json()

    async def _post(self, path: str, data: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
        """异步发送POST请求"""
        url = f"{self.base_url}{path}"
        async with aiohttp.ClientSession() as session:
            async with session.post(url, json=data, timeout=self.timeout) as response:
                response.raise_for_status()
                return await response.json()


class PrivacyApiClient(BaseApiClient):
    """差分隐私API客户端"""

    def apply_privacy(self, request_data: Dict[str, Any]) -> Dict[str, Any]:
        """应用差分隐私"""
        return self._post('/privacy/apply', request_data)

    def get_budget(self, user_id: str) -> Dict[str, Any]:
        """获取隐私预算"""
        return self._get(f'/privacy/budget/{user_id}')

    def reset_budget(self, user_id: str) -> Dict[str, Any]:
        """重置隐私预算"""
        return self._post(f'/privacy/budget/{user_id}/reset')


class NotificationApiClient(BaseApiClient):
    """通知API客户端"""

    def create_notification(self, request_data: Dict[str, Any]) -> Dict[str, Any]:
        """创建通知"""
        return self._post('/notifications', request_data)

    def get_status(self, notification_id: str) -> Dict[str, Any]:
        """获取通知状态"""
        return self._get(f'/notifications/{notification_id}/status')

    def retry_notification(self, notification_id: str) -> Dict[str, Any]:
        """重试通知"""
        return self._post(f'/notifications/{notification_id}/retry')

    def process_pending(self) -> Dict[str, Any]:
        """处理待发送通知"""
        return self._post('/notifications/process')


class CoreApiClient(BaseApiClient):
    """核心处理API客户端"""

    def create_resource(self, request_data: Dict[str, Any]) -> Dict[str, Any]:
        """创建资源"""
        return self._post('/resources', request_data)

    def get_resource_status(self, resource_id: str) -> Dict[str, Any]:
        """获取资源状态"""
        return self._get(f'/resources/{resource_id}/status')

    def execute_handler(self, request_data: Dict[str, Any]) -> Dict[str, Any]:
        """执行处理"""
        return self._post('/resources/execute', request_data)

    def batch_operation(self, request_data: Dict[str, Any]) -> Dict[str, Any]:
        """批量操作"""
        return self._post('/resources/batch', request_data)


class AsyncPrivacyApiClient(AsyncBaseApiClient):
    """异步差分隐私API客户端"""

    async def apply_privacy(self, request_data: Dict[str, Any]) -> Dict[str, Any]:
        """应用差分隐私"""
        return await self._post('/privacy/apply', request_data)

    async def get_budget(self, user_id: str) -> Dict[str, Any]:
        """获取隐私预算"""
        return await self._get(f'/privacy/budget/{user_id}')

    async def reset_budget(self, user_id: str) -> Dict[str, Any]:
        """重置隐私预算"""
        return await self._post(f'/privacy/budget/{user_id}/reset')
