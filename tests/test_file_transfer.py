"""
远程传输模块单元测试
使用 Mock 模拟 paramiko 的 SFTP 连接与传输操作
测试覆盖：
1. 正常文件传输流程
2. 传输中断时的重试机制
3. MD5校验失败时的错误标记
4. 文件不存在时的异常处理
"""

import pytest
import hashlib
from pathlib import Path
from unittest.mock import MagicMock, patch, Mock, call
from typing import Dict, Any

from autodeploy.executor.file_transfer import (
    FileTransfer, TransferResult, BatchTransferResult
)


class TestTransferResult:
    """TransferResult 数据类测试"""
    
    def test_transfer_result_default_values(self):
        """测试默认值"""
        result = TransferResult(
            success=True,
            source_path="/local/file.txt",
            target_path="/remote/file.txt"
        )
        
        assert result.success is True
        assert result.source_path == "/local/file.txt"
        assert result.target_path == "/remote/file.txt"
        assert result.file_size == 0
        assert result.md5_checksum is None
        assert result.error_message is None
        assert result.backup_path is None
        assert result.retry_count == 0
        assert result.max_retries == 0
    
    def test_transfer_result_with_retry_info(self):
        """测试包含重试信息的结果"""
        result = TransferResult(
            success=False,
            source_path="/local/file.txt",
            target_path="/remote/file.txt",
            file_size=1024,
            md5_checksum="d41d8cd98f00b204e9800998ecf8427e",
            error_message="MD5校验失败",
            retry_count=3,
            max_retries=3
        )
        
        assert result.retry_count == 3
        assert result.max_retries == 3
        assert result.error_message == "MD5校验失败"


class TestBatchTransferResult:
    """BatchTransferResult 数据类测试"""
    
    def test_batch_transfer_result_default_values(self):
        """测试默认值"""
        result = BatchTransferResult()
        
        assert result.total_files == 0
        assert result.success_count == 0
        assert result.failed_count == 0
        assert result.results == []
        assert result.failed_files == []
        assert result.total_retries == 0
    
    def test_batch_transfer_result_with_data(self):
        """测试包含数据的结果"""
        transfer_results = [
            TransferResult(success=True, source_path="a.txt", target_path="a.txt", retry_count=1),
            TransferResult(success=True, source_path="b.txt", target_path="b.txt", retry_count=0),
            TransferResult(success=False, source_path="c.txt", target_path="c.txt", retry_count=3)
        ]
        
        batch_result = BatchTransferResult(
            total_files=3,
            success_count=2,
            failed_count=1,
            results=transfer_results,
            failed_files=["c.txt"],
            total_retries=4
        )
        
        assert batch_result.total_files == 3
        assert batch_result.success_count == 2
        assert batch_result.failed_count == 1
        assert batch_result.total_retries == 4


class TestFileTransfer:
    """FileTransfer 类单元测试"""
    
    def test_init_default_values(self, mock_ssh_connection):
        """测试默认初始化"""
        transfer = FileTransfer(mock_ssh_connection)
        
        assert transfer.ssh_connection == mock_ssh_connection
        assert transfer.max_retries == 3
        assert transfer.retry_delay == 2
    
    def test_init_custom_retry_values(self, mock_ssh_connection):
        """测试自定义重试参数"""
        transfer = FileTransfer(
            mock_ssh_connection,
            max_retries=5,
            retry_delay=3
        )
        
        assert transfer.max_retries == 5
        assert transfer.retry_delay == 3
    
    def test_transfer_file_source_not_exists(self, mock_ssh_connection, temp_dir):
        """测试源文件不存在时的异常处理"""
        transfer = FileTransfer(mock_ssh_connection)
        
        nonexistent_file = temp_dir / "nonexistent.txt"
        
        result = transfer.transfer_file(
            source_path=str(nonexistent_file),
            target_path="/remote/file.txt"
        )
        
        assert result.success is False
        assert "源文件不存在" in result.error_message
        assert result.retry_count == 0
    
    def test_transfer_file_source_is_directory(self, mock_ssh_connection, temp_dir):
        """测试源路径是目录时的处理"""
        transfer = FileTransfer(mock_ssh_connection)
        
        result = transfer.transfer_file(
            source_path=str(temp_dir),
            target_path="/remote/dir"
        )
        
        assert result.success is False
        assert "源路径不是文件" in result.error_message
    
    def test_transfer_file_normal_flow(self, mock_ssh_connection, create_test_file):
        """测试正常文件传输流程"""
        test_content = "Hello, World!"
        test_file = create_test_file("test_file.txt", test_content)
        
        expected_md5 = hashlib.md5(test_content.encode()).hexdigest()
        
        mock_sftp = MagicMock()
        mock_ssh_connection.get_sftp.return_value = mock_sftp
        
        def mock_stat(path):
            if "existing" in path:
                mock_attr = MagicMock()
                mock_attr.st_size = len(test_content)
                return mock_attr
            raise FileNotFoundError(f"No such file: {path}")
        
        mock_sftp.stat.side_effect = mock_stat
        
        def mock_execute_command(command, timeout=None):
            if "md5sum" in command or "md5 " in command:
                return 0, f"{expected_md5}  test_file.txt", ""
            return 0, "", ""
        
        mock_ssh_connection.execute_command.side_effect = mock_execute_command
        
        transfer = FileTransfer(mock_ssh_connection, max_retries=0)
        
        result = transfer.transfer_file(
            source_path=str(test_file),
            target_path="/remote/existing_file.txt",
            backup=True,
            verify=True
        )
        
        assert result.success is True
        assert result.md5_checksum == expected_md5
        assert result.retry_count == 0
        
        mock_sftp.put.assert_called_once()
        mock_sftp.rename.assert_called_once()
    
    def test_transfer_file_md5_verification_failure(self, mock_ssh_connection, create_test_file):
        """测试MD5校验失败时的错误标记"""
        test_content = "Hello, World!"
        test_file = create_test_file("test_file.txt", test_content)
        
        source_md5 = hashlib.md5(test_content.encode()).hexdigest()
        wrong_md5 = "00000000000000000000000000000000"
        
        mock_sftp = MagicMock()
        mock_ssh_connection.get_sftp.return_value = mock_sftp
        
        def mock_execute_command(command, timeout=None):
            if "md5sum" in command or "md5 " in command:
                return 0, f"{wrong_md5}  test_file.txt", ""
            return 0, "", ""
        
        mock_ssh_connection.execute_command.side_effect = mock_execute_command
        
        transfer = FileTransfer(mock_ssh_connection, max_retries=0)
        
        result = transfer.transfer_file(
            source_path=str(test_file),
            target_path="/remote/file.txt",
            verify=True
        )
        
        assert result.success is False
        assert "MD5校验失败" in result.error_message
        assert source_md5 in result.error_message
        assert wrong_md5 in result.error_message
        assert result.md5_checksum == source_md5
    
    def test_transfer_file_retry_mechanism_success(self, mock_ssh_connection, create_test_file):
        """测试重试机制 - 最终成功"""
        test_content = "test content"
        test_file = create_test_file("test_file.txt", test_content)
        
        expected_md5 = hashlib.md5(test_content.encode()).hexdigest()
        
        mock_sftp = MagicMock()
        mock_ssh_connection.get_sftp.return_value = mock_sftp
        
        call_count = [0]
        
        def mock_execute_command(command, timeout=None):
            if "md5sum" in command or "md5 " in command:
                call_count[0] += 1
                if call_count[0] < 3:
                    wrong_md5 = "00000000000000000000000000000000"
                    return 0, f"{wrong_md5}  test_file.txt", ""
                else:
                    return 0, f"{expected_md5}  test_file.txt", ""
            return 0, "", ""
        
        mock_ssh_connection.execute_command.side_effect = mock_execute_command
        
        transfer = FileTransfer(mock_ssh_connection, max_retries=3, retry_delay=0.01)
        
        result = transfer.transfer_file(
            source_path=str(test_file),
            target_path="/remote/file.txt",
            verify=True
        )
        
        assert result.success is True
        assert result.retry_count == 2
        assert result.max_retries == 3
    
    def test_transfer_file_retry_mechanism_failure(self, mock_ssh_connection, create_test_file):
        """测试重试机制 - 最终失败"""
        test_content = "test content"
        test_file = create_test_file("test_file.txt", test_content)
        
        wrong_md5 = "00000000000000000000000000000000"
        
        mock_sftp = MagicMock()
        mock_ssh_connection.get_sftp.return_value = mock_sftp
        
        def mock_execute_command(command, timeout=None):
            if "md5sum" in command or "md5 " in command:
                return 0, f"{wrong_md5}  test_file.txt", ""
            return 0, "", ""
        
        mock_ssh_connection.execute_command.side_effect = mock_execute_command
        
        transfer = FileTransfer(mock_ssh_connection, max_retries=2, retry_delay=0.01)
        
        result = transfer.transfer_file(
            source_path=str(test_file),
            target_path="/remote/file.txt",
            verify=True
        )
        
        assert result.success is False
        assert result.retry_count == 2
        assert result.max_retries == 2
    
    def test_transfer_file_connection_error_retry(self, mock_ssh_connection, create_test_file):
        """测试连接错误时的重试"""
        test_file = create_test_file("test_file.txt", "test")
        
        mock_sftp = MagicMock()
        
        call_count = [0]
        
        def mock_get_sftp():
            call_count[0] += 1
            if call_count[0] < 3:
                from autodeploy.connection import SSHConnectionError
                raise SSHConnectionError("Connection failed")
            return mock_sftp
        
        mock_ssh_connection.get_sftp.side_effect = mock_get_sftp
        
        def mock_execute_command(command, timeout=None):
            expected_md5 = "d41d8cd98f00b204e9800998ecf8427e"
            return 0, f"{expected_md5}  test_file.txt", ""
        
        mock_ssh_connection.execute_command.side_effect = mock_execute_command
        
        transfer = FileTransfer(mock_ssh_connection, max_retries=3, retry_delay=0.01)
        
        result = transfer.transfer_file(
            source_path=str(test_file),
            target_path="/remote/file.txt"
        )
        
        assert result.success is True
        assert result.retry_count == 2
    
    def test_transfer_file_exponential_backoff(self, mock_ssh_connection, create_test_file):
        """测试指数退避延迟"""
        test_file = create_test_file("test_file.txt", "test")
        
        mock_sftp = MagicMock()
        mock_ssh_connection.get_sftp.return_value = mock_sftp
        
        call_count = [0]
        
        def mock_execute_command(command, timeout=None):
            call_count[0] += 1
            wrong_md5 = "00000000000000000000000000000000"
            return 0, f"{wrong_md5}  test_file.txt", ""
        
        mock_ssh_connection.execute_command.side_effect = mock_execute_command
        
        transfer = FileTransfer(mock_ssh_connection, max_retries=2, retry_delay=0.01)
        
        with patch('time.sleep') as mock_sleep:
            transfer.transfer_file(
                source_path=str(test_file),
                target_path="/remote/file.txt",
                verify=True
            )
            
            assert mock_sleep.call_count == 2
            
            expected_delays = [0.01, 0.015]
            actual_delays = [call.args[0] for call in mock_sleep.call_args_list]
            
            for expected, actual in zip(expected_delays, actual_delays):
                assert abs(expected - actual) < 0.001
    
    def test_transfer_directory_success(self, mock_ssh_connection, temp_dir):
        """测试传输目录成功"""
        file1 = temp_dir / "file1.txt"
        file2 = temp_dir / "subdir" / "file2.txt"
        
        file1.write_text("content1")
        (temp_dir / "subdir").mkdir()
        file2.write_text("content2")
        
        mock_sftp = MagicMock()
        mock_ssh_connection.get_sftp.return_value = mock_sftp
        
        def mock_execute_command(command, timeout=None):
            expected_md5 = "d41d8cd98f00b204e9800998ecf8427e"
            return 0, f"{expected_md5}  file.txt", ""
        
        mock_ssh_connection.execute_command.side_effect = mock_execute_command
        
        transfer = FileTransfer(mock_ssh_connection, max_retries=0)
        
        result = transfer.transfer_directory(
            source_dir=str(temp_dir),
            target_dir="/remote/target"
        )
        
        assert result.total_files == 2
        assert result.success_count == 2
        assert result.failed_count == 0
    
    def test_transfer_directory_source_not_exists(self, mock_ssh_connection, temp_dir):
        """测试源目录不存在"""
        transfer = FileTransfer(mock_ssh_connection)
        
        nonexistent_dir = temp_dir / "nonexistent"
        
        result = transfer.transfer_directory(
            source_dir=str(nonexistent_dir),
            target_dir="/remote/target"
        )
        
        assert result.failed_count == 1
        assert "源目录不存在" in result.results[0].error_message
    
    def test_transfer_directory_source_is_file(self, mock_ssh_connection, create_test_file):
        """测试源路径是文件"""
        test_file = create_test_file("test.txt", "test")
        transfer = FileTransfer(mock_ssh_connection)
        
        result = transfer.transfer_directory(
            source_dir=str(test_file),
            target_dir="/remote/target"
        )
        
        assert result.failed_count == 1
        assert "源路径不是目录" in result.results[0].error_message
    
    def test_restore_backup_success(self, mock_ssh_connection):
        """测试恢复备份成功"""
        mock_sftp = MagicMock()
        mock_ssh_connection.get_sftp.return_value = mock_sftp
        
        def mock_stat(path):
            if "backup" in path:
                return MagicMock()
            if "target" in path:
                return MagicMock()
            raise FileNotFoundError()
        
        mock_sftp.stat.side_effect = mock_stat
        
        transfer = FileTransfer(mock_ssh_connection)
        transfer._backup_paths["/remote/target.txt"] = "/remote/target.txt.backup_20260505_100000"
        
        result = transfer.restore_backup("/remote/target.txt")
        
        assert result is True
        mock_sftp.rename.assert_called_once_with(
            "/remote/target.txt.backup_20260505_100000",
            "/remote/target.txt"
        )
        assert "/remote/target.txt" not in transfer._backup_paths
    
    def test_restore_backup_not_found(self, mock_ssh_connection):
        """测试备份不存在时的恢复"""
        transfer = FileTransfer(mock_ssh_connection)
        
        result = transfer.restore_backup("/remote/nonexistent.txt")
        
        assert result is False
    
    def test_restore_all_backups(self, mock_ssh_connection):
        """测试恢复所有备份"""
        mock_sftp = MagicMock()
        mock_ssh_connection.get_sftp.return_value = mock_sftp
        
        mock_sftp.stat.return_value = MagicMock()
        
        transfer = FileTransfer(mock_ssh_connection)
        transfer._backup_paths = {
            "/remote/file1.txt": "/remote/file1.txt.backup",
            "/remote/file2.txt": "/remote/file2.txt.backup"
        }
        
        results = transfer.restore_all_backups()
        
        assert len(results) == 2
        assert all(results.values())
        assert len(transfer._backup_paths) == 0
    
    def test_verify_file_integrity(self, mock_ssh_connection, create_test_file):
        """测试文件完整性验证"""
        test_content = "test content"
        test_file = create_test_file("test.txt", test_content)
        
        expected_md5 = hashlib.md5(test_content.encode()).hexdigest()
        
        mock_sftp = MagicMock()
        mock_ssh_connection.get_sftp.return_value = mock_sftp
        
        def mock_execute_command(command, timeout=None):
            return 0, f"{expected_md5}  test.txt", ""
        
        mock_ssh_connection.execute_command.side_effect = mock_execute_command
        
        transfer = FileTransfer(mock_ssh_connection)
        
        is_same, local_md5, remote_md5 = transfer.verify_file_integrity(
            source_path=str(test_file),
            target_path="/remote/test.txt"
        )
        
        assert is_same is True
        assert local_md5 == expected_md5
        assert remote_md5 == expected_md5
    
    def test_verify_file_integrity_mismatch(self, mock_ssh_connection, create_test_file):
        """测试文件完整性验证不匹配"""
        test_content = "test content"
        test_file = create_test_file("test.txt", test_content)
        
        source_md5 = hashlib.md5(test_content.encode()).hexdigest()
        wrong_md5 = "00000000000000000000000000000000"
        
        mock_sftp = MagicMock()
        mock_ssh_connection.get_sftp.return_value = mock_sftp
        
        def mock_execute_command(command, timeout=None):
            return 0, f"{wrong_md5}  test.txt", ""
        
        mock_ssh_connection.execute_command.side_effect = mock_execute_command
        
        transfer = FileTransfer(mock_ssh_connection)
        
        is_same, local_md5, remote_md5 = transfer.verify_file_integrity(
            source_path=str(test_file),
            target_path="/remote/test.txt"
        )
        
        assert is_same is False
        assert local_md5 == source_md5
        assert remote_md5 == wrong_md5
    
    def test_backup_paths_property(self, mock_ssh_connection):
        """测试backup_paths属性"""
        transfer = FileTransfer(mock_ssh_connection)
        transfer._backup_paths = {
            "/remote/file1.txt": "/remote/file1.txt.backup",
            "/remote/file2.txt": "/remote/file2.txt.backup"
        }
        
        result = transfer.backup_paths
        
        assert result == transfer._backup_paths
        assert result is not transfer._backup_paths
