import os
import hashlib
import stat
import time
from typing import Optional, List, Dict, Any, Tuple
from dataclasses import dataclass, field
from pathlib import Path
from datetime import datetime

from ..connection import SSHConnection, SSHConnectionError, ServerConfig


@dataclass
class TransferResult:
    """
    文件传输结果
    """
    success: bool
    source_path: str
    target_path: str
    file_size: int = 0
    md5_checksum: Optional[str] = None
    error_message: Optional[str] = None
    backup_path: Optional[str] = None
    retry_count: int = 0
    max_retries: int = 0


@dataclass
class BatchTransferResult:
    """
    批量文件传输结果
    """
    total_files: int = 0
    success_count: int = 0
    failed_count: int = 0
    results: List[TransferResult] = field(default_factory=list)
    failed_files: List[str] = field(default_factory=list)
    total_retries: int = 0


class FileTransfer:
    """
    文件传输器
    使用SFTP实现文件传输，支持完整性校验、目录递归传输和文件备份
    
    增强功能：
    - MD5校验机制：传输完成后计算目标服务器文件的MD5值与本地源文件对比
    - 自动重试：若校验不一致或传输失败则标记失败并重试
    - 重试延迟：每次重试之间有间隔避免网络拥塞
    """
    
    DEFAULT_BACKUP_SUFFIX = ".backup_{timestamp}"
    DEFAULT_MAX_RETRIES = 3
    DEFAULT_RETRY_DELAY = 2
    DEFAULT_RETRY_BACKOFF_FACTOR = 1.5
    
    def __init__(self, ssh_connection: SSHConnection,
                 max_retries: Optional[int] = None,
                 retry_delay: Optional[float] = None):
        """
        初始化文件传输器
        
        Args:
            ssh_connection: SSH连接实例
            max_retries: 最大重试次数（默认3次）
            retry_delay: 重试初始延迟（秒，默认2秒）
        """
        self.ssh_connection = ssh_connection
        self._backup_paths: Dict[str, str] = {}
        self.max_retries = max_retries if max_retries is not None else self.DEFAULT_MAX_RETRIES
        self.retry_delay = retry_delay if retry_delay is not None else self.DEFAULT_RETRY_DELAY
    
    def transfer_file(self, source_path: str, target_path: str, 
                     backup: bool = True, verify: bool = True,
                     max_retries: Optional[int] = None,
                     retry_delay: Optional[float] = None) -> TransferResult:
        """
        传输单个文件（支持重试机制）
        
        传输流程：
        1. 检查源文件是否存在
        2. 备份远程目标文件（如果已存在且启用备份）
        3. 计算本地源文件MD5校验和（如果启用校验）
        4. 确保远程目录存在
        5. 执行SFTP文件传输
        6. 计算远程目标文件MD5校验和并与本地对比（如果启用校验）
        7. 若校验失败或传输失败，自动重试（最多max_retries次）
        
        Args:
            source_path: 源文件路径（本地）
            target_path: 目标文件路径（远程）
            backup: 是否备份目标文件（如果已存在）
            verify: 是否进行完整性校验（MD5对比）
            max_retries: 最大重试次数（覆盖实例默认值）
            retry_delay: 重试延迟（覆盖实例默认值）
            
        Returns:
            传输结果
        """
        actual_max_retries = max_retries if max_retries is not None else self.max_retries
        actual_retry_delay = retry_delay if retry_delay is not None else self.retry_delay
        
        source = Path(source_path)
        
        if not source.exists():
            return TransferResult(
                success=False,
                source_path=source_path,
                target_path=target_path,
                error_message=f"源文件不存在: {source_path}",
                retry_count=0,
                max_retries=actual_max_retries
            )
        
        if not source.is_file():
            return TransferResult(
                success=False,
                source_path=source_path,
                target_path=target_path,
                error_message=f"源路径不是文件: {source_path}",
                retry_count=0,
                max_retries=actual_max_retries
            )
        
        last_error: Optional[str] = None
        last_transfer_result: Optional[TransferResult] = None
        
        for attempt in range(actual_max_retries + 1):
            is_retry = attempt > 0
            
            if is_retry:
                backoff_delay = actual_retry_delay * (self.DEFAULT_RETRY_BACKOFF_FACTOR ** (attempt - 1))
                time.sleep(backoff_delay)
            
            try:
                sftp = self.ssh_connection.get_sftp()
                
                backup_path = None
                if backup and not is_retry:
                    backup_path = self._backup_remote_file(sftp, target_path)
                
                source_md5 = None
                if verify:
                    source_md5 = self._calculate_md5(source_path)
                
                self._ensure_remote_dir(sftp, target_path)
                
                sftp.put(str(source), target_path)
                
                if verify and source_md5:
                    target_md5 = self._calculate_remote_md5(sftp, target_path)
                    
                    if target_md5 is None:
                        error_msg = f"无法计算远程文件MD5校验和"
                        last_error = error_msg
                        last_transfer_result = TransferResult(
                            success=False,
                            source_path=source_path,
                            target_path=target_path,
                            file_size=source.stat().st_size,
                            md5_checksum=source_md5,
                            backup_path=backup_path,
                            error_message=error_msg,
                            retry_count=attempt,
                            max_retries=actual_max_retries
                        )
                        continue
                    
                    if source_md5 != target_md5:
                        error_msg = f"MD5校验失败: 源文件={source_md5}, 目标文件={target_md5}"
                        last_error = error_msg
                        last_transfer_result = TransferResult(
                            success=False,
                            source_path=source_path,
                            target_path=target_path,
                            file_size=source.stat().st_size,
                            md5_checksum=source_md5,
                            backup_path=backup_path,
                            error_message=error_msg,
                            retry_count=attempt,
                            max_retries=actual_max_retries
                        )
                        
                        try:
                            sftp.remove(target_path)
                        except Exception:
                            pass
                        
                        continue
                
                if backup_path:
                    self._backup_paths[target_path] = backup_path
                
                return TransferResult(
                    success=True,
                    source_path=source_path,
                    target_path=target_path,
                    file_size=source.stat().st_size,
                    md5_checksum=source_md5,
                    backup_path=backup_path,
                    retry_count=attempt,
                    max_retries=actual_max_retries
                )
                
            except SSHConnectionError as e:
                last_error = f"连接错误: {str(e)}"
                last_transfer_result = TransferResult(
                    success=False,
                    source_path=source_path,
                    target_path=target_path,
                    error_message=last_error,
                    retry_count=attempt,
                    max_retries=actual_max_retries
                )
            except Exception as e:
                last_error = f"传输失败: {str(e)}"
                last_transfer_result = TransferResult(
                    success=False,
                    source_path=source_path,
                    target_path=target_path,
                    error_message=last_error,
                    retry_count=attempt,
                    max_retries=actual_max_retries
                )
        
        if last_transfer_result:
            return last_transfer_result
        
        return TransferResult(
            success=False,
            source_path=source_path,
            target_path=target_path,
            error_message=last_error or "未知错误",
            retry_count=actual_max_retries,
            max_retries=actual_max_retries
        )
    
    def transfer_directory(self, source_dir: str, target_dir: str,
                          backup: bool = True, verify: bool = True,
                          recursive: bool = True,
                          max_retries: Optional[int] = None,
                          retry_delay: Optional[float] = None) -> BatchTransferResult:
        """
        传输目录
        
        Args:
            source_dir: 源目录路径（本地）
            target_dir: 目标目录路径（远程）
            backup: 是否备份目标文件（如果已存在）
            verify: 是否进行完整性校验
            recursive: 是否递归传输子目录
            max_retries: 每个文件的最大重试次数
            retry_delay: 重试延迟
            
        Returns:
            批量传输结果
        """
        source = Path(source_dir)
        
        if not source.exists():
            result = BatchTransferResult()
            result.results.append(TransferResult(
                success=False,
                source_path=source_dir,
                target_path=target_dir,
                error_message=f"源目录不存在: {source_dir}"
            ))
            result.failed_count = 1
            result.total_files = 1
            return result
        
        if not source.is_dir():
            result = BatchTransferResult()
            result.results.append(TransferResult(
                success=False,
                source_path=source_dir,
                target_path=target_dir,
                error_message=f"源路径不是目录: {source_dir}"
            ))
            result.failed_count = 1
            result.total_files = 1
            return result
        
        batch_result = BatchTransferResult()
        
        if recursive:
            files_to_transfer = list(source.rglob("*"))
        else:
            files_to_transfer = list(source.iterdir())
        
        total_retries = 0
        
        for item in files_to_transfer:
            if item.is_file():
                relative_path = item.relative_to(source)
                target_path = str(Path(target_dir) / relative_path)
                
                transfer_result = self.transfer_file(
                    str(item),
                    target_path,
                    backup=backup,
                    verify=verify,
                    max_retries=max_retries,
                    retry_delay=retry_delay
                )
                
                batch_result.results.append(transfer_result)
                batch_result.total_files += 1
                total_retries += transfer_result.retry_count
                
                if transfer_result.success:
                    batch_result.success_count += 1
                else:
                    batch_result.failed_count += 1
                    batch_result.failed_files.append(str(item))
        
        batch_result.total_retries = total_retries
        
        return batch_result
    
    def restore_backup(self, target_path: str) -> bool:
        """
        恢复备份文件
        
        Args:
            target_path: 目标文件路径（需要恢复的文件）
            
        Returns:
            True 表示恢复成功
        """
        if target_path not in self._backup_paths:
            return False
        
        backup_path = self._backup_paths[target_path]
        
        try:
            sftp = self.ssh_connection.get_sftp()
            
            try:
                sftp.stat(backup_path)
            except FileNotFoundError:
                return False
            
            try:
                sftp.stat(target_path)
                sftp.remove(target_path)
            except FileNotFoundError:
                pass
            
            sftp.rename(backup_path, target_path)
            
            del self._backup_paths[target_path]
            
            return True
            
        except Exception:
            return False
    
    def restore_all_backups(self) -> Dict[str, bool]:
        """
        恢复所有备份文件
        
        Returns:
            恢复结果字典，key为目标路径，value为是否成功
        """
        results = {}
        
        for target_path in list(self._backup_paths.keys()):
            results[target_path] = self.restore_backup(target_path)
        
        return results
    
    def _backup_remote_file(self, sftp, target_path: str) -> Optional[str]:
        """
        备份远程文件
        
        Args:
            sftp: SFTP客户端
            target_path: 目标文件路径
            
        Returns:
            备份文件路径，如果文件不存在则返回None
        """
        try:
            sftp.stat(target_path)
        except FileNotFoundError:
            return None
        
        timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
        backup_suffix = self.DEFAULT_BACKUP_SUFFIX.format(timestamp=timestamp)
        backup_path = f"{target_path}{backup_suffix}"
        
        try:
            sftp.rename(target_path, backup_path)
            return backup_path
        except Exception:
            return None
    
    def _ensure_remote_dir(self, sftp, file_path: str) -> None:
        """
        确保远程目录存在
        如果不存在则创建
        
        Args:
            sftp: SFTP客户端
            file_path: 文件路径（目录部分需要存在）
        """
        target_dir = str(Path(file_path).parent)
        
        if target_dir == "." or target_dir == "/":
            return
        
        try:
            sftp.stat(target_dir)
        except FileNotFoundError:
            parent_dir = str(Path(target_dir).parent)
            if parent_dir != target_dir:
                self._ensure_remote_dir(sftp, parent_dir)
            
            sftp.mkdir(target_dir)
    
    def _calculate_md5(self, file_path: str) -> str:
        """
        计算本地文件的MD5校验和
        
        Args:
            file_path: 文件路径
            
        Returns:
            MD5校验和字符串
        """
        hash_md5 = hashlib.md5()
        
        with open(file_path, "rb") as f:
            for chunk in iter(lambda: f.read(8192), b""):
                hash_md5.update(chunk)
        
        return hash_md5.hexdigest()
    
    def _calculate_remote_md5(self, sftp, file_path: str) -> Optional[str]:
        """
        计算远程文件的MD5校验和
        优先使用远程命令计算，失败则下载后计算
        
        Args:
            sftp: SFTP客户端
            file_path: 远程文件路径
            
        Returns:
            MD5校验和字符串，如果失败则返回None
        """
        try:
            exit_code, stdout, stderr = self.ssh_connection.execute_command(
                f"md5sum {file_path} 2>/dev/null || md5 {file_path} 2>/dev/null"
            )
            
            if exit_code == 0:
                output = stdout.strip()
                if output:
                    parts = output.split()
                    if parts:
                        return parts[0].lower()
        except Exception:
            pass
        
        import tempfile
        
        try:
            with tempfile.NamedTemporaryFile(delete=False) as temp_file:
                temp_path = temp_file.name
            
            sftp.get(file_path, temp_path)
            
            md5_sum = self._calculate_md5(temp_path)
            
            try:
                os.unlink(temp_path)
            except Exception:
                pass
            
            return md5_sum
            
        except Exception:
            return None
    
    def verify_file_integrity(self, source_path: str, target_path: str) -> Tuple[bool, Optional[str], Optional[str]]:
        """
        验证文件完整性（对比本地和远程文件的MD5）
        
        Args:
            source_path: 本地源文件路径
            target_path: 远程目标文件路径
            
        Returns:
            (是否一致, 本地MD5, 远程MD5) 元组
        """
        source = Path(source_path)
        
        if not source.exists():
            return False, None, None
        
        try:
            local_md5 = self._calculate_md5(source_path)
            
            sftp = self.ssh_connection.get_sftp()
            remote_md5 = self._calculate_remote_md5(sftp, target_path)
            
            if remote_md5 is None:
                return False, local_md5, None
            
            return local_md5 == remote_md5, local_md5, remote_md5
            
        except Exception:
            return False, None, None
    
    @property
    def backup_paths(self) -> Dict[str, str]:
        """
        获取所有备份文件路径
        
        Returns:
            字典，key为目标路径，value为备份路径
        """
        return self._backup_paths.copy()
