"""
远程连接模块
负责SSH连接的建立和管理
"""

from .ssh_connection import SSHConnection, SSHConnectionError

__all__ = ["SSHConnection", "SSHConnectionError"]
