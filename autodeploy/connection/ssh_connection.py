import paramiko
import time
from typing import Optional, Dict, Any, Tuple
from dataclasses import dataclass
from pathlib import Path


class SSHConnectionError(Exception):
    """
    SSH连接错误
    """
    pass


@dataclass
class ServerConfig:
    """
    服务器配置
    """
    host: str
    port: int = 22
    user: str = "root"
    key_file: Optional[str] = None
    password: Optional[str] = None
    timeout: int = 10
    connect_timeout: int = 10
    banner_timeout: int = 10


class SSHConnection:
    """
    SSH连接基类
    负责SSH连接的建立、管理和断开
    """
    
    MAX_RETRY_COUNT = 3
    RETRY_DELAY = 2
    
    def __init__(self, server_config: ServerConfig):
        """
        初始化SSH连接
        
        Args:
            server_config: 服务器配置
        """
        self.server_config = server_config
        self._client: Optional[paramiko.SSHClient] = None
        self._sftp: Optional[paramiko.SFTPClient] = None
        self._connected = False
    
    def connect(self) -> bool:
        """
        建立SSH连接
        支持重试机制
        
        Returns:
            True 表示连接成功
            
        Raises:
            SSHConnectionError: 连接失败（重试后仍然失败）
        """
        last_error = None
        
        for retry in range(self.MAX_RETRY_COUNT):
            try:
                self._client = paramiko.SSHClient()
                self._client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
                
                connect_kwargs = {
                    "hostname": self.server_config.host,
                    "port": self.server_config.port,
                    "username": self.server_config.user,
                    "timeout": self.server_config.connect_timeout,
                    "banner_timeout": self.server_config.banner_timeout
                }
                
                if self.server_config.key_file:
                    key_path = Path(self.server_config.key_file)
                    if not key_path.exists():
                        raise SSHConnectionError(f"密钥文件不存在: {self.server_config.key_file}")
                    
                    private_key = paramiko.RSAKey.from_private_key_file(str(key_path))
                    connect_kwargs["pkey"] = private_key
                elif self.server_config.password:
                    connect_kwargs["password"] = self.server_config.password
                else:
                    raise SSHConnectionError("必须提供密钥文件或密码")
                
                self._client.connect(**connect_kwargs)
                self._connected = True
                
                return True
                
            except paramiko.AuthenticationException as e:
                last_error = SSHConnectionError(f"认证失败: {str(e)}")
                break
            except paramiko.SSHException as e:
                last_error = SSHConnectionError(f"SSH协议错误: {str(e)}")
            except Exception as e:
                last_error = SSHConnectionError(f"连接失败: {str(e)}")
            
            if retry < self.MAX_RETRY_COUNT - 1:
                time.sleep(self.RETRY_DELAY)
        
        if last_error:
            self._cleanup()
            raise last_error
        
        return False
    
    def disconnect(self) -> None:
        """
        断开SSH连接
        """
        self._cleanup()
        self._connected = False
    
    def _cleanup(self) -> None:
        """
        清理连接资源
        """
        if self._sftp is not None:
            try:
                self._sftp.close()
            except:
                pass
            self._sftp = None
        
        if self._client is not None:
            try:
                self._client.close()
            except:
                pass
            self._client = None
    
    def get_client(self) -> paramiko.SSHClient:
        """
        获取SSH客户端实例
        
        Returns:
            paramiko SSHClient 实例
            
        Raises:
            SSHConnectionError: 连接未建立
        """
        if not self._connected or self._client is None:
            raise SSHConnectionError("SSH连接未建立")
        return self._client
    
    def get_sftp(self) -> paramiko.SFTPClient:
        """
        获取SFTP客户端实例
        如果尚未创建，则创建一个
        
        Returns:
            paramiko SFTPClient 实例
            
        Raises:
            SSHConnectionError: 连接未建立或SFTP创建失败
        """
        if not self._connected or self._client is None:
            raise SSHConnectionError("SSH连接未建立")
        
        if self._sftp is None:
            try:
                self._sftp = self._client.open_sftp()
            except Exception as e:
                raise SSHConnectionError(f"创建SFTP客户端失败: {str(e)}")
        
        return self._sftp
    
    def is_connected(self) -> bool:
        """
        检查连接状态
        
        Returns:
            True 表示已连接
        """
        if not self._connected:
            return False
        
        if self._client is None:
            return False
        
        try:
            transport = self._client.get_transport()
            if transport is None:
                return False
            return transport.is_active()
        except:
            return False
    
    def execute_command(self, command: str, timeout: Optional[int] = None) -> Tuple[int, str, str]:
        """
        执行远程命令
        
        Args:
            command: 要执行的命令
            timeout: 超时时间（秒），为None则使用默认值
            
        Returns:
            (退出码, 标准输出, 标准错误) 元组
            
        Raises:
            SSHConnectionError: 连接未建立或命令执行失败
        """
        client = self.get_client()
        
        actual_timeout = timeout if timeout is not None else self.server_config.timeout
        
        try:
            stdin, stdout, stderr = client.exec_command(command, timeout=actual_timeout)
            
            exit_code = stdout.channel.recv_exit_status()
            stdout_output = stdout.read().decode('utf-8', errors='replace')
            stderr_output = stderr.read().decode('utf-8', errors='replace')
            
            return exit_code, stdout_output, stderr_output
            
        except paramiko.SSHException as e:
            raise SSHConnectionError(f"命令执行失败: {str(e)}")
        except Exception as e:
            raise SSHConnectionError(f"执行命令时发生错误: {str(e)}")
    
    def __enter__(self) -> 'SSHConnection':
        """
        上下文管理器入口
        """
        self.connect()
        return self
    
    def __exit__(self, exc_type, exc_val, exc_tb) -> bool:
        """
        上下文管理器出口
        """
        self.disconnect()
        return False
