import json
import os
import re
from datetime import datetime, timedelta
from typing import Dict, List, Any, Optional
from dataclasses import dataclass, asdict, field
from pathlib import Path
from uuid import uuid4


@dataclass
class StepRecord:
    """
    单个步骤的记录
    """
    step: str
    status: str
    duration: str
    start_time: Optional[str] = None
    end_time: Optional[str] = None
    message: Optional[str] = None
    error: Optional[str] = None


@dataclass
class DeployRecord:
    """
    部署记录
    """
    deploy_id: str
    env_name: str
    trigger_time: str
    status: str
    steps: List[StepRecord] = field(default_factory=list)
    rollback_available: bool = False
    rollback_record_id: Optional[str] = None
    error_message: Optional[str] = None
    total_duration: Optional[str] = None
    servers: List[Dict[str, Any]] = field(default_factory=list)
    
    def to_dict(self) -> Dict[str, Any]:
        """
        转换为字典格式
        
        Returns:
            字典表示
        """
        data = asdict(self)
        return data
    
    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> 'DeployRecord':
        """
        从字典创建部署记录
        
        Args:
            data: 字典数据
            
        Returns:
            DeployRecord 实例
        """
        steps_data = data.get("steps", [])
        steps = [StepRecord(**step) for step in steps_data]
        
        return cls(
            deploy_id=data["deploy_id"],
            env_name=data["env_name"],
            trigger_time=data["trigger_time"],
            status=data["status"],
            steps=steps,
            rollback_available=data.get("rollback_available", False),
            rollback_record_id=data.get("rollback_record_id"),
            error_message=data.get("error_message"),
            total_duration=data.get("total_duration"),
            servers=data.get("servers", [])
        )


class DeployLogger:
    """
    部署日志记录器
    负责记录每个部署步骤的执行日志，支持失败回滚记录
    
    增强功能：
    - 按日期分割存储：每日生成独立的日志文件
    - 日志文件格式：deploy_log_YYYYMMDD.json
    - 避免单一日志文件体积膨胀
    - 支持跨日期查询部署历史
    """
    
    DEFAULT_LOG_DIR = "logs"
    DEPLOY_LOG_PREFIX = "deploy_log_"
    DEPLOY_LOG_SUFFIX = ".json"
    ROLLBACK_LOG_PREFIX = "rollback_log_"
    ROLLBACK_LOG_SUFFIX = ".json"
    LEGACY_DEPLOY_LOG_FILE = "deploy_history.json"
    LEGACY_ROLLBACK_LOG_FILE = "rollback_history.json"
    
    DEPLOY_LOG_PATTERN = re.compile(r'^deploy_log_(\d{8})\.json$')
    ROLLBACK_LOG_PATTERN = re.compile(r'^rollback_log_(\d{8})\.json$')
    
    def __init__(self, log_dir: Optional[str] = None):
        """
        初始化部署日志记录器
        
        Args:
            log_dir: 日志目录，默认为当前目录下的 logs
        """
        self.log_dir = Path(log_dir) if log_dir else Path(self.DEFAULT_LOG_DIR)
        self._ensure_log_dir()
        self._current_deploy: Optional[DeployRecord] = None
        self._step_start_times: Dict[str, datetime] = {}
    
    def _ensure_log_dir(self):
        """
        确保日志目录存在
        """
        self.log_dir.mkdir(parents=True, exist_ok=True)
    
    def _get_date_from_timestamp(self, timestamp: str) -> str:
        """
        从ISO时间戳提取日期（YYYYMMDD格式）
        
        Args:
            timestamp: ISO格式时间戳，如 "2026-05-05T10:50:00Z"
            
        Returns:
            日期字符串，如 "20260505"
        """
        try:
            if timestamp.endswith('Z'):
                timestamp = timestamp[:-1]
            dt = datetime.fromisoformat(timestamp)
            return dt.strftime("%Y%m%d")
        except Exception:
            return datetime.utcnow().strftime("%Y%m%d")
    
    def _get_today_date_str(self) -> str:
        """
        获取今天的日期字符串（YYYYMMDD格式）
        
        Returns:
            日期字符串
        """
        return datetime.utcnow().strftime("%Y%m%d")
    
    def _get_deploy_log_file_name(self, date_str: Optional[str] = None) -> str:
        """
        获取部署日志文件名
        
        Args:
            date_str: 日期字符串（YYYYMMDD格式），为None则使用今天
            
        Returns:
            日志文件名，如 "deploy_log_20260505.json"
        """
        actual_date = date_str if date_str else self._get_today_date_str()
        return f"{self.DEPLOY_LOG_PREFIX}{actual_date}{self.DEPLOY_LOG_SUFFIX}"
    
    def _get_rollback_log_file_name(self, date_str: Optional[str] = None) -> str:
        """
        获取回滚日志文件名
        
        Args:
            date_str: 日期字符串（YYYYMMDD格式），为None则使用今天
            
        Returns:
            日志文件名，如 "rollback_log_20260505.json"
        """
        actual_date = date_str if date_str else self._get_today_date_str()
        return f"{self.ROLLBACK_LOG_PREFIX}{actual_date}{self.ROLLBACK_LOG_SUFFIX}"
    
    def _list_deploy_log_files(self) -> List[Path]:
        """
        列出所有部署日志文件（按日期倒序排列）
        
        Returns:
            日志文件路径列表，最新的在前
        """
        if not self.log_dir.exists():
            return []
        
        log_files = []
        
        for file_path in self.log_dir.iterdir():
            if file_path.is_file():
                match = self.DEPLOY_LOG_PATTERN.match(file_path.name)
                if match:
                    date_str = match.group(1)
                    log_files.append((date_str, file_path))
        
        log_files.sort(key=lambda x: x[0], reverse=True)
        
        return [f[1] for f in log_files]
    
    def _list_rollback_log_files(self) -> List[Path]:
        """
        列出所有回滚日志文件（按日期倒序排列）
        
        Returns:
            日志文件路径列表，最新的在前
        """
        if not self.log_dir.exists():
            return []
        
        log_files = []
        
        for file_path in self.log_dir.iterdir():
            if file_path.is_file():
                match = self.ROLLBACK_LOG_PATTERN.match(file_path.name)
                if match:
                    date_str = match.group(1)
                    log_files.append((date_str, file_path))
        
        log_files.sort(key=lambda x: x[0], reverse=True)
        
        return [f[1] for f in log_files]
    
    def _read_log_file(self, file_path: Path) -> List[Dict[str, Any]]:
        """
        读取日志文件内容
        
        Args:
            file_path: 日志文件路径
            
        Returns:
            记录列表
        """
        if not file_path.exists():
            return []
        
        try:
            with open(file_path, 'r', encoding='utf-8') as f:
                return json.load(f)
        except (json.JSONDecodeError, IOError):
            return []
    
    def _write_log_file(self, file_path: Path, records: List[Dict[str, Any]]) -> None:
        """
        写入日志文件
        
        Args:
            file_path: 日志文件路径
            records: 记录列表
        """
        file_path.parent.mkdir(parents=True, exist_ok=True)
        
        with open(file_path, 'w', encoding='utf-8') as f:
            json.dump(records, f, ensure_ascii=False, indent=2)
    
    def _migrate_legacy_files(self) -> None:
        """
        迁移旧格式的日志文件到新的按日期分割格式
        旧格式：deploy_history.json, rollback_history.json
        """
        legacy_deploy_file = self.log_dir / self.LEGACY_DEPLOY_LOG_FILE
        legacy_rollback_file = self.log_dir / self.LEGACY_ROLLBACK_LOG_FILE
        
        if legacy_deploy_file.exists():
            try:
                with open(legacy_deploy_file, 'r', encoding='utf-8') as f:
                    deploy_records = json.load(f)
                
                records_by_date: Dict[str, List[Dict[str, Any]]] = {}
                
                for record in deploy_records:
                    trigger_time = record.get("trigger_time", "")
                    date_str = self._get_date_from_timestamp(trigger_time)
                    
                    if date_str not in records_by_date:
                        records_by_date[date_str] = []
                    records_by_date[date_str].append(record)
                
                for date_str, records in records_by_date.items():
                    log_file = self.log_dir / self._get_deploy_log_file_name(date_str)
                    
                    if log_file.exists():
                        existing = self._read_log_file(log_file)
                        existing_ids = {r.get("deploy_id") for r in existing}
                        
                        for record in records:
                            if record.get("deploy_id") not in existing_ids:
                                existing.append(record)
                        
                        self._write_log_file(log_file, existing)
                    else:
                        self._write_log_file(log_file, records)
                
                backup_file = self.log_dir / f"{self.LEGACY_DEPLOY_LOG_FILE}.backup"
                legacy_deploy_file.rename(backup_file)
                
            except Exception:
                pass
        
        if legacy_rollback_file.exists():
            try:
                with open(legacy_rollback_file, 'r', encoding='utf-8') as f:
                    rollback_records = json.load(f)
                
                records_by_date: Dict[str, List[Dict[str, Any]]] = {}
                
                for record in rollback_records:
                    rollback_time = record.get("rollback_time", "")
                    date_str = self._get_date_from_timestamp(rollback_time)
                    
                    if date_str not in records_by_date:
                        records_by_date[date_str] = []
                    records_by_date[date_str].append(record)
                
                for date_str, records in records_by_date.items():
                    log_file = self.log_dir / self._get_rollback_log_file_name(date_str)
                    
                    if log_file.exists():
                        existing = self._read_log_file(log_file)
                        existing_ids = {r.get("rollback_id") for r in existing}
                        
                        for record in records:
                            if record.get("rollback_id") not in existing_ids:
                                existing.append(record)
                        
                        self._write_log_file(log_file, existing)
                    else:
                        self._write_log_file(log_file, records)
                
                backup_file = self.log_dir / f"{self.LEGACY_ROLLBACK_LOG_FILE}.backup"
                legacy_rollback_file.rename(backup_file)
                
            except Exception:
                pass
    
    def start_deploy(self, env_name: str, servers: Optional[List[Dict[str, Any]]] = None) -> DeployRecord:
        """
        开始部署记录
        
        Args:
            env_name: 环境名称
            servers: 服务器列表
            
        Returns:
            新的部署记录
        """
        deploy_id = self._generate_deploy_id()
        trigger_time = datetime.utcnow().isoformat() + "Z"
        
        self._current_deploy = DeployRecord(
            deploy_id=deploy_id,
            env_name=env_name,
            trigger_time=trigger_time,
            status="in_progress",
            steps=[],
            rollback_available=False,
            servers=servers or []
        )
        
        self._step_start_times.clear()
        
        return self._current_deploy
    
    def start_step(self, step_name: str) -> None:
        """
        开始记录一个步骤
        
        Args:
            step_name: 步骤名称
        """
        if self._current_deploy is None:
            raise RuntimeError("当前没有进行中的部署记录")
        
        self._step_start_times[step_name] = datetime.utcnow()
    
    def end_step(self, step_name: str, status: str, message: Optional[str] = None, 
                error: Optional[str] = None) -> StepRecord:
        """
        结束记录一个步骤
        
        Args:
            step_name: 步骤名称
            status: 步骤状态 (success/failed/skipped)
            message: 步骤消息
            error: 错误信息
            
        Returns:
            步骤记录
        """
        if self._current_deploy is None:
            raise RuntimeError("当前没有进行中的部署记录")
        
        end_time = datetime.utcnow()
        start_time = self._step_start_times.pop(step_name, end_time)
        
        duration_seconds = (end_time - start_time).total_seconds()
        duration = self._format_duration(duration_seconds)
        
        step_record = StepRecord(
            step=step_name,
            status=status,
            duration=duration,
            start_time=start_time.isoformat() + "Z",
            end_time=end_time.isoformat() + "Z",
            message=message,
            error=error
        )
        
        self._current_deploy.steps.append(step_record)
        
        return step_record
    
    def end_deploy(self, status: str, error_message: Optional[str] = None, 
                  rollback_available: bool = False) -> DeployRecord:
        """
        结束部署记录
        
        Args:
            status: 部署状态 (completed/failed/rolled_back)
            error_message: 错误信息
            rollback_available: 是否可回滚
            
        Returns:
            完整的部署记录
        """
        if self._current_deploy is None:
            raise RuntimeError("当前没有进行中的部署记录")
        
        self._current_deploy.status = status
        self._current_deploy.rollback_available = rollback_available
        self._current_deploy.error_message = error_message
        
        trigger_time = datetime.fromisoformat(self._current_deploy.trigger_time.rstrip("Z"))
        end_time = datetime.utcnow()
        total_seconds = (end_time - trigger_time).total_seconds()
        self._current_deploy.total_duration = self._format_duration(total_seconds)
        
        self._save_deploy_record(self._current_deploy)
        
        completed_deploy = self._current_deploy
        self._current_deploy = None
        
        return completed_deploy
    
    def log_rollback(self, original_deploy_id: str, rollback_status: str, 
                    message: Optional[str] = None) -> str:
        """
        记录回滚操作
        
        Args:
            original_deploy_id: 原始部署ID
            rollback_status: 回滚状态 (success/failed)
            message: 回滚消息
            
        Returns:
            回滚记录ID
        """
        rollback_id = self._generate_rollback_id()
        rollback_time = datetime.utcnow().isoformat() + "Z"
        
        rollback_record = {
            "rollback_id": rollback_id,
            "original_deploy_id": original_deploy_id,
            "rollback_time": rollback_time,
            "status": rollback_status,
            "message": message
        }
        
        date_str = self._get_date_from_timestamp(rollback_time)
        rollback_file = self.log_dir / self._get_rollback_log_file_name(date_str)
        
        rollback_history = self._read_log_file(rollback_file)
        rollback_history.append(rollback_record)
        
        self._write_log_file(rollback_file, rollback_history)
        
        original_deploy = self.get_deploy_record(original_deploy_id)
        if original_deploy:
            original_deploy.rollback_record_id = rollback_id
            self._save_deploy_record(original_deploy, overwrite=True)
        
        return rollback_id
    
    def get_deploy_record(self, deploy_id: str) -> Optional[DeployRecord]:
        """
        获取指定的部署记录
        
        Args:
            deploy_id: 部署ID
            
        Returns:
            部署记录，如果不存在则返回None
        """
        self._migrate_legacy_files()
        
        deploy_log_files = self._list_deploy_log_files()
        
        for log_file in deploy_log_files:
            deploy_history = self._read_log_file(log_file)
            
            for record_data in deploy_history:
                if record_data.get("deploy_id") == deploy_id:
                    return DeployRecord.from_dict(record_data)
        
        return None
    
    def list_deploy_history(self, env_name: Optional[str] = None, 
                           limit: int = 20) -> List[DeployRecord]:
        """
        列出部署历史
        
        Args:
            env_name: 环境名称筛选，为None则列出所有
            limit: 返回记录数量限制
            
        Returns:
            部署记录列表
        """
        self._migrate_legacy_files()
        
        deploy_log_files = self._list_deploy_log_files()
        
        records = []
        collected = 0
        
        for log_file in deploy_log_files:
            if collected >= limit:
                break
            
            deploy_history = self._read_log_file(log_file)
            
            for record_data in reversed(deploy_history):
                if collected >= limit:
                    break
                
                if env_name and record_data.get("env_name") != env_name:
                    continue
                
                records.append(DeployRecord.from_dict(record_data))
                collected += 1
        
        return records
    
    def _save_deploy_record(self, record: DeployRecord, overwrite: bool = False) -> None:
        """
        保存部署记录到文件（按日期分割存储）
        
        Args:
            record: 部署记录
            overwrite: 是否覆盖已有记录
        """
        self._migrate_legacy_files()
        
        date_str = self._get_date_from_timestamp(record.trigger_time)
        log_file = self.log_dir / self._get_deploy_log_file_name(date_str)
        
        deploy_history = self._read_log_file(log_file)
        record_dict = record.to_dict()
        
        if overwrite:
            found = False
            for i, existing in enumerate(deploy_history):
                if existing.get("deploy_id") == record.deploy_id:
                    deploy_history[i] = record_dict
                    found = True
                    break
            
            if not found:
                deploy_history.append(record_dict)
        else:
            deploy_history.append(record_dict)
        
        self._write_log_file(log_file, deploy_history)
    
    def _generate_deploy_id(self) -> str:
        """
        生成部署ID
        
        Returns:
            部署ID字符串
        """
        timestamp = datetime.utcnow().strftime("%Y%m%d")
        short_uuid = uuid4().hex[:6]
        return f"deploy_{timestamp}_{short_uuid}"
    
    def _generate_rollback_id(self) -> str:
        """
        生成回滚ID
        
        Returns:
            回滚ID字符串
        """
        timestamp = datetime.utcnow().strftime("%Y%m%d")
        short_uuid = uuid4().hex[:6]
        return f"rollback_{timestamp}_{short_uuid}"
    
    def _format_duration(self, seconds: float) -> str:
        """
        格式化持续时间
        
        Args:
            seconds: 秒数
            
        Returns:
            格式化的持续时间字符串
        """
        if seconds < 60:
            return f"{seconds:.1f}s"
        elif seconds < 3600:
            minutes = int(seconds // 60)
            remaining_seconds = seconds % 60
            return f"{minutes}m{remaining_seconds:.0f}s"
        else:
            hours = int(seconds // 3600)
            minutes = int((seconds % 3600) // 60)
            return f"{hours}h{minutes}m"
    
    def list_available_dates(self) -> List[str]:
        """
        列出所有有日志记录的日期
        
        Returns:
            日期字符串列表（YYYYMMDD格式），倒序排列
        """
        self._migrate_legacy_files()
        
        dates = set()
        
        deploy_files = self._list_deploy_log_files()
        for file_path in deploy_files:
            match = self.DEPLOY_LOG_PATTERN.match(file_path.name)
            if match:
                dates.add(match.group(1))
        
        rollback_files = self._list_rollback_log_files()
        for file_path in rollback_files:
            match = self.ROLLBACK_LOG_PATTERN.match(file_path.name)
            if match:
                dates.add(match.group(1))
        
        return sorted(dates, reverse=True)
    
    def get_records_by_date(self, date_str: str) -> List[DeployRecord]:
        """
        获取指定日期的所有部署记录
        
        Args:
            date_str: 日期字符串（YYYYMMDD格式）
            
        Returns:
            部署记录列表
        """
        self._migrate_legacy_files()
        
        log_file = self.log_dir / self._get_deploy_log_file_name(date_str)
        
        if not log_file.exists():
            return []
        
        deploy_history = self._read_log_file(log_file)
        
        return [DeployRecord.from_dict(r) for r in deploy_history]
    
    @property
    def current_deploy(self) -> Optional[DeployRecord]:
        """
        获取当前进行中的部署记录
        
        Returns:
            当前部署记录，如果没有则返回None
        """
        return self._current_deploy
