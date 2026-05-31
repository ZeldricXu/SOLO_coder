"""
API 客户端封装
"""
from typing import Dict, Any, Optional, List
from urllib.parse import urljoin

import requests
from tenacity import retry, stop_after_attempt, wait_exponential, retry_if_exception_type


class ChaosLabClient:
    """ChaosLab API 客户端"""
    
    def __init__(self, base_url: str, session: Optional[requests.Session] = None):
        self.base_url = base_url.rstrip('/')
        self.session = session or requests.Session()
        self.session.headers.update({
            'Content-Type': 'application/json',
            'Accept': 'application/json',
        })
    
    def _url(self, path: str) -> str:
        """构建完整 URL"""
        return urljoin(f"{self.base_url}/api/v1/", path.lstrip('/'))
    
    @retry(
        stop=stop_after_attempt(3),
        wait=wait_exponential(multiplier=1, min=1, max=5),
        retry=retry_if_exception_type((requests.ConnectionError, requests.Timeout)),
    )
    def _request(self, method: str, path: str, **kwargs) -> requests.Response:
        """发送请求（带重试）"""
        kwargs.setdefault('timeout', 10)
        response = self.session.request(method, self._url(path), **kwargs)
        response.raise_for_status()
        return response
    
    # ========== 故障注入模块 ==========
    
    def create_scenario(self, data: Dict[str, Any]) -> Dict[str, Any]:
        """创建故障场景"""
        response = self._request('POST', 'chaos/scenarios', json=data)
        return response.json()['data']
    
    def get_scenario(self, scenario_id: str) -> Dict[str, Any]:
        """获取故障场景"""
        response = self._request('GET', f'chaos/scenarios/{scenario_id}')
        return response.json()['data']
    
    def list_scenarios(self, page: int = 1, page_size: int = 20) -> Dict[str, Any]:
        """列出故障场景"""
        params = {'page': page, 'pageSize': page_size}
        response = self._request('GET', 'chaos/scenarios', params=params)
        return response.json()['data']
    
    def update_scenario(self, scenario_id: str, data: Dict[str, Any]) -> Dict[str, Any]:
        """更新故障场景"""
        response = self._request('PUT', f'chaos/scenarios/{scenario_id}', json=data)
        return response.json()['data']
    
    def delete_scenario(self, scenario_id: str) -> Dict[str, Any]:
        """删除故障场景"""
        response = self._request('DELETE', f'chaos/scenarios/{scenario_id}')
        return response.json()
    
    def start_injection(self, scenario_id: str, target_ids: Optional[List[str]] = None) -> Dict[str, Any]:
        """开始故障注入"""
        data = {'scenarioId': scenario_id}
        if target_ids:
            data['targetIds'] = target_ids
        response = self._request('POST', 'chaos/injections', json=data)
        return response.json()['data']
    
    def get_injection(self, injection_id: str) -> Dict[str, Any]:
        """获取注入状态"""
        response = self._request('GET', f'chaos/injections/{injection_id}')
        return response.json()['data']
    
    def rollback_injection(self, injection_id: str) -> Dict[str, Any]:
        """回滚故障注入"""
        response = self._request('POST', f'chaos/injections/{injection_id}/rollback')
        return response.json()['data']
    
    # ========== 命令审计模块 ==========
    
    def create_command(self, data: Dict[str, Any]) -> Dict[str, Any]:
        """创建命令"""
        response = self._request('POST', 'audit/commands', json=data)
        return response.json()['data']
    
    def get_command(self, command_id: str) -> Dict[str, Any]:
        """获取命令"""
        response = self._request('GET', f'audit/commands/{command_id}')
        return response.json()['data']
    
    def list_commands(self, page: int = 1, page_size: int = 20, 
                     aggregate_id: Optional[str] = None) -> Dict[str, Any]:
        """列出命令"""
        params = {'page': page, 'pageSize': page_size}
        if aggregate_id:
            params['aggregateId'] = aggregate_id
        response = self._request('GET', 'audit/commands', params=params)
        return response.json()['data']
    
    def get_commands_by_aggregate(self, aggregate_id: str) -> List[Dict[str, Any]]:
        """获取聚合的所有命令"""
        response = self._request('GET', f'audit/aggregates/{aggregate_id}/commands')
        return response.json()['data']
    
    def create_audit_log(self, data: Dict[str, Any]) -> Dict[str, Any]:
        """创建审计日志"""
        response = self._request('POST', 'audit/audit-logs', json=data)
        return response.json()['data']
    
    def get_audit_log(self, log_id: str) -> Dict[str, Any]:
        """获取审计日志"""
        response = self._request('GET', f'audit/audit-logs/{log_id}')
        return response.json()['data']
    
    def list_audit_logs(self, page: int = 1, page_size: int = 20,
                        actor_id: Optional[str] = None,
                        action: Optional[str] = None) -> Dict[str, Any]:
        """列出审计日志"""
        params = {'page': page, 'pageSize': page_size}
        if actor_id:
            params['actorId'] = actor_id
        if action:
            params['action'] = action
        response = self._request('GET', 'audit/audit-logs', params=params)
        return response.json()['data']
    
    def generate_compliance_report(self, start_date: str, end_date: str,
                                  format: str = 'json') -> Dict[str, Any]:
        """生成合规报告"""
        data = {
            'startDate': start_date,
            'endDate': end_date,
            'format': format,
        }
        response = self._request('POST', 'audit/compliance-report', json=data)
        return response.json()['data']
    
    # ========== 健康检查 ==========
    
    def health_check(self) -> Dict[str, Any]:
        """健康检查"""
        response = self.session.get(f"{self.base_url}/health", timeout=10)
        return response.json()['data']
