"""
pytest fixtures and shared utilities for testing
"""

import pytest
import tempfile
import os
from pathlib import Path
from unittest.mock import MagicMock, Mock, patch
from typing import Dict, Any, Optional, List
from dataclasses import dataclass


@pytest.fixture
def temp_dir():
    """创建临时目录，测试结束后自动清理"""
    with tempfile.TemporaryDirectory() as tmpdir:
        yield Path(tmpdir)


@pytest.fixture
def test_config_dir(temp_dir):
    """创建测试配置目录"""
    config_dir = temp_dir / "configs"
    config_dir.mkdir()
    return config_dir


@pytest.fixture
def test_log_dir(temp_dir):
    """创建测试日志目录"""
    log_dir = temp_dir / "logs"
    log_dir.mkdir()
    return log_dir


@pytest.fixture
def sample_valid_config():
    """返回有效的测试配置字典"""
    return {
        "env_name": "test_env",
        "servers": [
            {
                "host": "192.168.1.100",
                "port": 22,
                "user": "test_user",
                "key_file": "/home/test/.ssh/key.pem"
            }
        ],
        "deploy_path": "/var/www/test",
        "build_command": "npm run build",
        "build_output": "./dist",
        "start_command": "systemctl start test-service",
        "health_check": {
            "type": "http",
            "url": "http://localhost:8080/health",
            "timeout": 30
        },
        "rollback_enabled": True
    }


@pytest.fixture
def mock_ssh_connection():
    """创建模拟的 SSHConnection 对象"""
    mock_conn = MagicMock()
    
    mock_sftp = MagicMock()
    mock_conn.get_sftp.return_value = mock_sftp
    
    mock_client = MagicMock()
    mock_conn.get_client.return_value = mock_client
    
    mock_conn.is_connected.return_value = True
    
    def mock_execute_command(command, timeout=None):
        if "md5sum" in command or "md5 " in command:
            return 0, "d41d8cd98f00b204e9800998ecf8427e  test_file.txt", ""
        elif "pgrep" in command or "ps aux" in command:
            return 0, "12345", ""
        return 0, "success", ""
    
    mock_conn.execute_command.side_effect = mock_execute_command
    
    return mock_conn


@pytest.fixture
def mock_sftp_client():
    """创建模拟的 SFTPClient 对象"""
    mock_sftp = MagicMock()
    
    def mock_stat(path):
        if "existing" in path or "remote" in path:
            mock_attr = MagicMock()
            mock_attr.st_size = 100
            mock_attr.st_mode = 33188
            return mock_attr
        raise FileNotFoundError(f"No such file: {path}")
    
    mock_sftp.stat.side_effect = mock_stat
    mock_sftp.put = MagicMock()
    mock_sftp.get = MagicMock()
    mock_sftp.rename = MagicMock()
    mock_sftp.remove = MagicMock()
    mock_sftp.mkdir = MagicMock()
    mock_sftp.listdir = MagicMock(return_value=[])
    
    return mock_sftp


class MockSSHTransport:
    """模拟的 SSH Transport 类"""
    def __init__(self, active=True):
        self._active = active
    
    def is_active(self):
        return self._active


@pytest.fixture
def mock_paramiko(monkeypatch):
    """
    Mock paramiko 模块的 fixture
    使用 monkeypatch 来替换 paramiko 的类和方法
    """
    mock_sshclient = MagicMock()
    mock_transport = MockSSHTransport(active=True)
    mock_sshclient.get_transport.return_value = mock_transport
    
    mock_sftpclient = MagicMock()
    
    mock_rsakey = MagicMock()
    
    mock_sftpclient.stat = MagicMock()
    mock_sftpclient.put = MagicMock()
    mock_sftpclient.get = MagicMock()
    mock_sftpclient.rename = MagicMock()
    mock_sftpclient.remove = MagicMock()
    mock_sftpclient.mkdir = MagicMock()
    mock_sftpclient.listdir = MagicMock(return_value=[])
    
    mock_sshclient.open_sftp.return_value = mock_sftpclient
    mock_sshclient.exec_command.return_value = (
        MagicMock(),
        MagicMock(read=lambda: b"d41d8cd98f00b204e9800998ecf8427e  test_file"),
        MagicMock(read=lambda: b"")
    )
    
    with patch('paramiko.SSHClient', return_value=mock_sshclient):
        with patch('paramiko.RSAKey.from_private_key_file', return_value=mock_rsakey):
            with patch('paramiko.AutoAddPolicy'):
                yield {
                    'sshclient': mock_sshclient,
                    'sftpclient': mock_sftpclient,
                    'rsakey': mock_rsakey
                }


@pytest.fixture
def create_test_file(temp_dir):
    """创建测试文件的工厂函数"""
    def _create_test_file(filename: str, content: str = "test content"):
        file_path = temp_dir / filename
        file_path.write_text(content, encoding='utf-8')
        return file_path
    return _create_test_file


@pytest.fixture
def mock_thread_pool_executor():
    """模拟 ThreadPoolExecutor 的 fixture"""
    mock_executor = MagicMock()
    
    def mock_submit(fn, *args, **kwargs):
        mock_future = MagicMock()
        
        try:
            result = fn(*args, **kwargs)
            mock_future.result.return_value = result
        except Exception as e:
            mock_future.result.side_effect = e
        
        return mock_future
    
    mock_executor.submit.side_effect = mock_submit
    
    def mock_as_completed(futures):
        return futures
    
    return {
        'executor': mock_executor,
        'as_completed': mock_as_completed
    }


def create_mock_server_result(host: str, success: bool = True, 
                              status: str = "completed") -> Dict[str, Any]:
    """创建模拟的服务器部署结果"""
    return {
        "server_host": host,
        "server_port": 22,
        "success": success,
        "status": status,
        "steps": [
            {
                "step": "connect",
                "status": "success" if success else "failed",
                "duration": "0.5s"
            },
            {
                "step": "transfer_artifacts",
                "status": "success" if success else "failed",
                "duration": "5.2s"
            }
        ],
        "error_message": None if success else f"部署失败: {host}"
    }
