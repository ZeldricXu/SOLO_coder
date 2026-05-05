import time
from typing import Optional, List, Dict, Any
from dataclasses import dataclass
from datetime import datetime

from ..connection import SSHConnection, SSHConnectionError


@dataclass
class ExecutionResult:
    """
    命令执行结果
    """
    success: bool
    command: str
    exit_code: int
    stdout: str
    stderr: str
    duration: float
    error_message: Optional[str] = None


class RemoteExecutor:
    """
    远程命令执行器
    使用SSH在目标服务器执行命令，支持超时控制和结果捕获
    """
    
    DEFAULT_TIMEOUT = 60
    SERVICE_START_DELAY = 5
    
    def __init__(self, ssh_connection: SSHConnection):
        """
        初始化远程执行器
        
        Args:
            ssh_connection: SSH连接实例
        """
        self.ssh_connection = ssh_connection
    
    def execute(self, command: str, timeout: Optional[int] = None) -> ExecutionResult:
        """
        执行单个命令
        
        Args:
            command: 要执行的命令
            timeout: 超时时间（秒），为None则使用默认值
            
        Returns:
            执行结果
        """
        actual_timeout = timeout if timeout is not None else self.DEFAULT_TIMEOUT
        
        start_time = time.time()
        
        try:
            exit_code, stdout, stderr = self.ssh_connection.execute_command(
                command,
                timeout=actual_timeout
            )
            
            duration = time.time() - start_time
            
            success = exit_code == 0
            
            return ExecutionResult(
                success=success,
                command=command,
                exit_code=exit_code,
                stdout=stdout,
                stderr=stderr,
                duration=duration
            )
            
        except SSHConnectionError as e:
            duration = time.time() - start_time
            return ExecutionResult(
                success=False,
                command=command,
                exit_code=-1,
                stdout="",
                stderr="",
                duration=duration,
                error_message=str(e)
            )
        except Exception as e:
            duration = time.time() - start_time
            return ExecutionResult(
                success=False,
                command=command,
                exit_code=-1,
                stdout="",
                stderr="",
                duration=duration,
                error_message=f"执行异常: {str(e)}"
            )
    
    def execute_multiple(self, commands: List[str], timeout: Optional[int] = None,
                        stop_on_failure: bool = True) -> List[ExecutionResult]:
        """
        执行多个命令
        
        Args:
            commands: 命令列表
            timeout: 每个命令的超时时间
            stop_on_failure: 失败时是否停止执行后续命令
            
        Returns:
            执行结果列表
        """
        results = []
        
        for command in commands:
            result = self.execute(command, timeout)
            results.append(result)
            
            if stop_on_failure and not result.success:
                break
        
        return results
    
    def stop_service(self, stop_command: str, timeout: Optional[int] = None) -> ExecutionResult:
        """
        停止服务
        
        Args:
            stop_command: 服务停止命令
            timeout: 超时时间
            
        Returns:
            执行结果
        """
        return self.execute(stop_command, timeout)
    
    def start_service(self, start_command: str, timeout: Optional[int] = None,
                     wait_delay: Optional[int] = None) -> ExecutionResult:
        """
        启动服务
        
        Args:
            start_command: 服务启动命令
            timeout: 超时时间
            wait_delay: 启动后的等待延迟时间（秒）
            
        Returns:
            执行结果
        """
        result = self.execute(start_command, timeout)
        
        if result.success and wait_delay is not None:
            time.sleep(wait_delay)
        elif result.success:
            time.sleep(self.SERVICE_START_DELAY)
        
        return result
    
    def restart_service(self, stop_command: str, start_command: str,
                       stop_timeout: Optional[int] = None,
                       start_timeout: Optional[int] = None,
                       wait_delay: Optional[int] = None) -> Dict[str, Any]:
        """
        重启服务（先停止，再启动）
        
        Args:
            stop_command: 服务停止命令
            start_command: 服务启动命令
            stop_timeout: 停止命令超时时间
            start_timeout: 启动命令超时时间
            wait_delay: 启动后的等待延迟时间
            
        Returns:
            包含停止和启动结果的字典
        """
        stop_result = self.stop_service(stop_command, stop_timeout)
        
        if not stop_result.success:
            return {
                "success": False,
                "stop_result": stop_result,
                "start_result": None,
                "error_message": f"服务停止失败: {stop_result.stderr or stop_result.error_message}"
            }
        
        start_result = self.start_service(start_command, start_timeout, wait_delay)
        
        return {
            "success": start_result.success,
            "stop_result": stop_result,
            "start_result": start_result,
            "error_message": None if start_result.success else 
                f"服务启动失败: {start_result.stderr or start_result.error_message}"
        }
    
    def check_process_running(self, process_name: str) -> bool:
        """
        检查进程是否正在运行
        
        Args:
            process_name: 进程名称或匹配模式
            
        Returns:
            True 表示进程正在运行
        """
        command = f"pgrep -f '{process_name}' 2>/dev/null || ps aux | grep -v grep | grep '{process_name}'"
        
        result = self.execute(command, timeout=10)
        
        if not result.success:
            return False
        
        output = result.stdout.strip()
        
        return bool(output)
    
    def get_process_pid(self, process_name: str) -> Optional[int]:
        """
        获取进程PID
        
        Args:
            process_name: 进程名称或匹配模式
            
        Returns:
            PID，如果进程不存在则返回None
        """
        command = f"pgrep -o -f '{process_name}' 2>/dev/null"
        
        result = self.execute(command, timeout=10)
        
        if not result.success:
            return None
        
        output = result.stdout.strip()
        
        try:
            return int(output)
        except ValueError:
            return None
    
    def execute_with_retry(self, command: str, max_retries: int = 3,
                           retry_delay: int = 2, timeout: Optional[int] = None) -> ExecutionResult:
        """
        带重试的命令执行
        
        Args:
            command: 要执行的命令
            max_retries: 最大重试次数
            retry_delay: 重试间隔（秒）
            timeout: 超时时间
            
        Returns:
            执行结果
        """
        last_result = None
        
        for attempt in range(max_retries):
            result = self.execute(command, timeout)
            last_result = result
            
            if result.success:
                return result
            
            if attempt < max_retries - 1:
                time.sleep(retry_delay)
        
        return last_result
    
    def run_sudo_command(self, command: str, password: Optional[str] = None,
                         timeout: Optional[int] = None) -> ExecutionResult:
        """
        执行sudo命令
        
        Args:
            command: 要执行的命令（不带sudo前缀）
            password: sudo密码（可选，如果已配置免密则不需要）
            timeout: 超时时间
            
        Returns:
            执行结果
        """
        if password:
            sudo_command = f"echo '{password}' | sudo -S {command}"
        else:
            sudo_command = f"sudo {command}"
        
        return self.execute(sudo_command, timeout)
