import time
from typing import Optional, Dict, Any, List
from dataclasses import dataclass
from enum import Enum
from urllib.parse import urlparse

try:
    import requests
    REQUESTS_AVAILABLE = True
except ImportError:
    REQUESTS_AVAILABLE = False

from ..connection import SSHConnection
from ..executor import RemoteExecutor


class HealthCheckType(Enum):
    """
    健康检查类型
    """
    HTTP = "http"
    PROCESS = "process"
    PORT = "port"
    CUSTOM = "custom"


@dataclass
class HealthCheckResult:
    """
    健康检查结果
    """
    success: bool
    check_type: str
    message: str
    response_time: Optional[float] = None
    status_code: Optional[int] = None
    process_pid: Optional[int] = None
    raw_response: Optional[str] = None
    error: Optional[str] = None


class HealthChecker:
    """
    健康检查器
    支持HTTP健康检测、进程状态检测、端口检测
    """
    
    DEFAULT_TIMEOUT = 30
    DEFAULT_RETRY_COUNT = 3
    DEFAULT_RETRY_DELAY = 2
    
    def __init__(self, ssh_connection: Optional[SSHConnection] = None):
        """
        初始化健康检查器
        
        Args:
            ssh_connection: SSH连接实例（用于进程检查等远程操作）
        """
        self.ssh_connection = ssh_connection
        self._remote_executor: Optional[RemoteExecutor] = None
        
        if ssh_connection:
            self._remote_executor = RemoteExecutor(ssh_connection)
    
    def check_http(self, url: str, timeout: Optional[int] = None,
                   expected_status_codes: Optional[List[int]] = None,
                   expected_content: Optional[str] = None,
                   headers: Optional[Dict[str, str]] = None,
                   method: str = "GET") -> HealthCheckResult:
        """
        执行HTTP健康检查
        
        Args:
            url: 健康检查URL
            timeout: 超时时间（秒）
            expected_status_codes: 期望的HTTP状态码列表，默认[200]
            expected_content: 期望响应中包含的内容
            headers: 请求头
            method: HTTP方法（GET/POST等）
            
        Returns:
            健康检查结果
        """
        if not REQUESTS_AVAILABLE:
            return HealthCheckResult(
                success=False,
                check_type="http",
                message="requests库未安装，无法执行HTTP检查",
                error="ModuleNotFoundError: No module named 'requests'"
            )
        
        actual_timeout = timeout if timeout is not None else self.DEFAULT_TIMEOUT
        expected_codes = expected_status_codes if expected_status_codes else [200]
        
        start_time = time.time()
        
        try:
            request_kwargs = {
                "url": url,
                "timeout": actual_timeout,
                "headers": headers or {},
                "allow_redirects": True
            }
            
            if method.upper() == "GET":
                response = requests.get(**request_kwargs)
            elif method.upper() == "POST":
                response = requests.post(**request_kwargs)
            else:
                response = requests.request(method, **request_kwargs)
            
            response_time = time.time() - start_time
            
            status_code = response.status_code
            
            if status_code not in expected_codes:
                return HealthCheckResult(
                    success=False,
                    check_type="http",
                    message=f"HTTP状态码不匹配: 期望{expected_codes}，实际{status_code}",
                    response_time=response_time,
                    status_code=status_code,
                    raw_response=response.text[:500] if len(response.text) > 500 else response.text
                )
            
            if expected_content:
                response_text = response.text
                if expected_content not in response_text:
                    return HealthCheckResult(
                        success=False,
                        check_type="http",
                        message=f"响应内容不包含期望内容: {expected_content}",
                        response_time=response_time,
                        status_code=status_code,
                        raw_response=response_text[:500] if len(response_text) > 500 else response_text
                    )
            
            return HealthCheckResult(
                success=True,
                check_type="http",
                message=f"HTTP健康检查成功: {status_code}",
                response_time=response_time,
                status_code=status_code
            )
            
        except requests.exceptions.Timeout:
            response_time = time.time() - start_time
            return HealthCheckResult(
                success=False,
                check_type="http",
                message=f"HTTP请求超时: {actual_timeout}秒",
                response_time=response_time,
                error="Timeout"
            )
        except requests.exceptions.ConnectionError as e:
            response_time = time.time() - start_time
            return HealthCheckResult(
                success=False,
                check_type="http",
                message=f"HTTP连接失败: {str(e)}",
                response_time=response_time,
                error=str(e)
            )
        except Exception as e:
            response_time = time.time() - start_time
            return HealthCheckResult(
                success=False,
                check_type="http",
                message=f"HTTP检查发生错误: {str(e)}",
                response_time=response_time,
                error=str(e)
            )
    
    def check_process(self, process_name: str, timeout: Optional[int] = None) -> HealthCheckResult:
        """
        执行进程健康检查
        
        Args:
            process_name: 进程名称或匹配模式
            timeout: 超时时间
            
        Returns:
            健康检查结果
        """
        if not self._remote_executor:
            return HealthCheckResult(
                success=False,
                check_type="process",
                message="未配置SSH连接，无法执行进程检查",
                error="No SSH connection available"
            )
        
        start_time = time.time()
        
        try:
            is_running = self._remote_executor.check_process_running(process_name)
            response_time = time.time() - start_time
            
            if is_running:
                pid = self._remote_executor.get_process_pid(process_name)
                
                return HealthCheckResult(
                    success=True,
                    check_type="process",
                    message=f"进程 '{process_name}' 正在运行",
                    response_time=response_time,
                    process_pid=pid
                )
            else:
                return HealthCheckResult(
                    success=False,
                    check_type="process",
                    message=f"进程 '{process_name}' 未运行",
                    response_time=response_time
                )
                
        except Exception as e:
            response_time = time.time() - start_time
            return HealthCheckResult(
                success=False,
                check_type="process",
                message=f"进程检查发生错误: {str(e)}",
                response_time=response_time,
                error=str(e)
            )
    
    def check_port(self, host: str, port: int, timeout: Optional[int] = None) -> HealthCheckResult:
        """
        执行端口健康检查
        
        Args:
            host: 主机地址
            port: 端口号
            timeout: 超时时间
            
        Returns:
            健康检查结果
        """
        import socket
        
        actual_timeout = timeout if timeout is not None else 5
        start_time = time.time()
        
        try:
            sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            sock.settimeout(actual_timeout)
            
            result = sock.connect_ex((host, port))
            sock.close()
            
            response_time = time.time() - start_time
            
            if result == 0:
                return HealthCheckResult(
                    success=True,
                    check_type="port",
                    message=f"端口 {host}:{port} 可连接",
                    response_time=response_time
                )
            else:
                return HealthCheckResult(
                    success=False,
                    check_type="port",
                    message=f"端口 {host}:{port} 无法连接",
                    response_time=response_time,
                    error=f"Connection error code: {result}"
                )
                
        except Exception as e:
            response_time = time.time() - start_time
            return HealthCheckResult(
                success=False,
                check_type="port",
                message=f"端口检查发生错误: {str(e)}",
                response_time=response_time,
                error=str(e)
            )
    
    def check_with_retry(self, check_type: str, check_config: Dict[str, Any],
                         max_retries: Optional[int] = None,
                         retry_delay: Optional[int] = None) -> HealthCheckResult:
        """
        带重试的健康检查
        
        Args:
            check_type: 检查类型（http/process/port）
            check_config: 检查配置参数
            max_retries: 最大重试次数
            retry_delay: 重试间隔（秒）
            
        Returns:
            健康检查结果
        """
        actual_max_retries = max_retries if max_retries is not None else self.DEFAULT_RETRY_COUNT
        actual_retry_delay = retry_delay if retry_delay is not None else self.DEFAULT_RETRY_DELAY
        
        last_result = None
        
        for attempt in range(actual_max_retries):
            if check_type.lower() == "http":
                result = self.check_http(
                    url=check_config.get("url"),
                    timeout=check_config.get("timeout"),
                    expected_status_codes=check_config.get("expected_status_codes"),
                    expected_content=check_config.get("expected_content"),
                    headers=check_config.get("headers"),
                    method=check_config.get("method", "GET")
                )
            elif check_type.lower() == "process":
                result = self.check_process(
                    process_name=check_config.get("process_name"),
                    timeout=check_config.get("timeout")
                )
            elif check_type.lower() == "port":
                result = self.check_port(
                    host=check_config.get("host"),
                    port=check_config.get("port"),
                    timeout=check_config.get("timeout")
                )
            else:
                return HealthCheckResult(
                    success=False,
                    check_type=check_type,
                    message=f"不支持的检查类型: {check_type}",
                    error=f"Unsupported check type: {check_type}"
                )
            
            last_result = result
            
            if result.success:
                return result
            
            if attempt < actual_max_retries - 1:
                time.sleep(actual_retry_delay)
        
        return last_result
    
    def check_from_config(self, config: Dict[str, Any]) -> HealthCheckResult:
        """
        根据配置执行健康检查
        
        Args:
            config: 健康检查配置字典
            
        Returns:
            健康检查结果
        """
        check_type = config.get("type", "http").lower()
        
        if check_type == "http":
            return self.check_with_retry(
                check_type="http",
                check_config={
                    "url": config.get("url"),
                    "timeout": config.get("timeout"),
                    "expected_status_codes": config.get("expected_status_codes"),
                    "expected_content": config.get("expected_content"),
                    "headers": config.get("headers"),
                    "method": config.get("method")
                },
                max_retries=config.get("retry_count"),
                retry_delay=config.get("retry_delay")
            )
        elif check_type == "process":
            return self.check_with_retry(
                check_type="process",
                check_config={
                    "process_name": config.get("process_name"),
                    "timeout": config.get("timeout")
                },
                max_retries=config.get("retry_count"),
                retry_delay=config.get("retry_delay")
            )
        elif check_type == "port":
            return self.check_with_retry(
                check_type="port",
                check_config={
                    "host": config.get("host", "localhost"),
                    "port": config.get("port"),
                    "timeout": config.get("timeout")
                },
                max_retries=config.get("retry_count"),
                retry_delay=config.get("retry_delay")
            )
        else:
            return HealthCheckResult(
                success=False,
                check_type=check_type,
                message=f"不支持的健康检查类型: {check_type}",
                error=f"Unsupported health check type: {check_type}"
            )
