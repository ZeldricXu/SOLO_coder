"""
元数据爬虫模块
提供自动扫描、增量更新、调度和元数据存储功能
"""

import hashlib
import json
import os
import threading
import time
import uuid
from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from datetime import datetime, timedelta
from enum import Enum
from typing import Any, Callable, Dict, List, Optional, Tuple, Union

import pandas as pd
from croniter import croniter

from .data_source import DataSource, DataSourceType, RelationalDataSource
from .schema_extractor import (
    SchemaExtractor,
    TableSchema,
    create_schema_extractor,
)
from .stats_collector import StatsCollector, TableStatistics
from .sample_collector import SampleCollector, SampleConfig, SampleResult
from session307.exceptions import MetadataCrawlError


class CrawlStatus(Enum):
    """爬取任务状态枚举"""

    PENDING = "pending"
    RUNNING = "running"
    COMPLETED = "completed"
    FAILED = "failed"
    CANCELLED = "cancelled"
    PARTIAL = "partial"


@dataclass
class IncrementalConfig:
    """增量更新配置数据类

    Attributes:
        enabled: 是否启用增量更新
        watermark_column: 水位线列名（通常是时间戳或自增ID）
        watermark_value: 当前水位线值
        tracking_mode: 跟踪模式（watermark, checksum, timestamp）
        checksum_columns: 用于校验和的列名列表
        last_crawl_time: 上次爬取时间
        batch_size: 批量处理大小
    """

    enabled: bool = False
    watermark_column: Optional[str] = None
    watermark_value: Optional[Any] = None
    tracking_mode: str = "watermark"
    checksum_columns: Optional[List[str]] = None
    last_crawl_time: Optional[datetime] = None
    batch_size: int = 10000


@dataclass
class CrawlConfig:
    """爬取配置数据类

    Attributes:
        data_source_name: 数据源名称
        tables: 要爬取的表名列表，None表示所有表
        exclude_tables: 要排除的表名列表
        extract_schema: 是否提取Schema
        collect_stats: 是否收集统计信息
        collect_samples: 是否收集样例数据
        sample_config: 样例数据采集配置
        stats_sample_size: 统计信息采样大小
        incremental_config: 增量更新配置
        max_parallel_tables: 最大并行处理表数
        timeout_seconds: 超时时间（秒）
        retry_count: 重试次数
        retry_interval_seconds: 重试间隔（秒）
    """

    data_source_name: str
    tables: Optional[List[str]] = None
    exclude_tables: Optional[List[str]] = None
    extract_schema: bool = True
    collect_stats: bool = True
    collect_samples: bool = False
    sample_config: SampleConfig = field(default_factory=SampleConfig)
    stats_sample_size: Optional[int] = 10000
    incremental_config: IncrementalConfig = field(default_factory=IncrementalConfig)
    max_parallel_tables: int = 1
    timeout_seconds: int = 3600
    retry_count: int = 3
    retry_interval_seconds: int = 5

    def to_dict(self) -> Dict[str, Any]:
        """转换为字典

        Returns:
            爬取配置的字典表示
        """
        return {
            "data_source_name": self.data_source_name,
            "tables": self.tables,
            "exclude_tables": self.exclude_tables,
            "extract_schema": self.extract_schema,
            "collect_stats": self.collect_stats,
            "collect_samples": self.collect_samples,
            "sample_config": self.sample_config.to_dict(),
            "stats_sample_size": self.stats_sample_size,
            "incremental_config": {
                "enabled": self.incremental_config.enabled,
                "watermark_column": self.incremental_config.watermark_column,
                "watermark_value": str(self.incremental_config.watermark_value)
                if self.incremental_config.watermark_value
                else None,
                "tracking_mode": self.incremental_config.tracking_mode,
                "checksum_columns": self.incremental_config.checksum_columns,
                "last_crawl_time": self.incremental_config.last_crawl_time.isoformat()
                if self.incremental_config.last_crawl_time
                else None,
                "batch_size": self.incremental_config.batch_size,
            },
            "max_parallel_tables": self.max_parallel_tables,
            "timeout_seconds": self.timeout_seconds,
            "retry_count": self.retry_count,
            "retry_interval_seconds": self.retry_interval_seconds,
        }


@dataclass
class CrawlTask:
    """爬取任务数据类

    Attributes:
        task_id: 任务ID
        config: 爬取配置
        status: 任务状态
        start_time: 开始时间
        end_time: 结束时间
        tables_processed: 已处理的表数
        tables_total: 总表数
        error_messages: 错误信息列表
        metadata: 元数据结果字典
        created_at: 创建时间
        updated_at: 更新时间
    """

    task_id: str
    config: CrawlConfig
    status: CrawlStatus = CrawlStatus.PENDING
    start_time: Optional[datetime] = None
    end_time: Optional[datetime] = None
    tables_processed: int = 0
    tables_total: int = 0
    error_messages: List[str] = field(default_factory=list)
    metadata: Dict[str, Any] = field(default_factory=dict)
    created_at: datetime = field(default_factory=datetime.now)
    updated_at: datetime = field(default_factory=datetime.now)

    @property
    def duration_seconds(self) -> Optional[float]:
        """获取任务持续时间

        Returns:
            持续时间（秒），未完成返回None
        """
        if self.start_time and self.end_time:
            return (self.end_time - self.start_time).total_seconds()
        return None

    @property
    def progress(self) -> float:
        """获取任务进度

        Returns:
            进度百分比（0-100）
        """
        if self.tables_total == 0:
            return 0.0
        return (self.tables_processed / self.tables_total) * 100

    def to_dict(self) -> Dict[str, Any]:
        """转换为字典

        Returns:
            爬取任务的字典表示
        """
        return {
            "task_id": self.task_id,
            "config": self.config.to_dict(),
            "status": self.status.value,
            "start_time": self.start_time.isoformat() if self.start_time else None,
            "end_time": self.end_time.isoformat() if self.end_time else None,
            "duration_seconds": self.duration_seconds,
            "tables_processed": self.tables_processed,
            "tables_total": self.tables_total,
            "progress": self.progress,
            "error_messages": self.error_messages.copy(),
            "created_at": self.created_at.isoformat(),
            "updated_at": self.updated_at.isoformat(),
        }


class MetadataStorage(ABC):
    """元数据存储抽象基类"""

    @abstractmethod
    def save_metadata(self, source_name: str, table_name: str, metadata: Dict[str, Any]) -> None:
        """保存元数据

        Args:
            source_name: 数据源名称
            table_name: 表名
            metadata: 元数据字典
        """
        pass

    @abstractmethod
    def load_metadata(self, source_name: str, table_name: str) -> Optional[Dict[str, Any]]:
        """加载元数据

        Args:
            source_name: 数据源名称
            table_name: 表名

        Returns:
            元数据字典，未找到返回None
        """
        pass

    @abstractmethod
    def list_tables(self, source_name: str) -> List[str]:
        """列出已存储元数据的表

        Args:
            source_name: 数据源名称

        Returns:
            表名列表
        """
        pass

    @abstractmethod
    def delete_metadata(self, source_name: str, table_name: str) -> bool:
        """删除元数据

        Args:
            source_name: 数据源名称
            table_name: 表名

        Returns:
            删除成功返回True，否则返回False
        """
        pass

    @abstractmethod
    def get_last_crawl_info(self, source_name: str, table_name: str) -> Optional[Dict[str, Any]]:
        """获取上次爬取信息

        Args:
            source_name: 数据源名称
            table_name: 表名

        Returns:
            上次爬取信息字典，未找到返回None
        """
        pass


class FileMetadataStorage(MetadataStorage):
    """基于文件的元数据存储实现"""

    def __init__(self, base_dir: str = "./metadata"):
        """初始化文件元数据存储

        Args:
            base_dir: 元数据存储根目录
        """
        self.base_dir = base_dir
        os.makedirs(base_dir, exist_ok=True)

    def _get_table_path(self, source_name: str, table_name: str) -> str:
        """获取表元数据文件路径

        Args:
            source_name: 数据源名称
            table_name: 表名

        Returns:
            文件路径
        """
        source_dir = os.path.join(self.base_dir, self._safe_name(source_name))
        os.makedirs(source_dir, exist_ok=True)
        return os.path.join(source_dir, f"{self._safe_name(table_name)}.json")

    def _safe_name(self, name: str) -> str:
        """生成安全的文件名

        Args:
            name: 原始名称

        Returns:
            安全的文件名
        """
        return hashlib.md5(name.encode()).hexdigest()[:16] + "_" + "".join(
            c for c in name if c.isalnum() or c in ("_", "-")
        )

    def save_metadata(self, source_name: str, table_name: str, metadata: Dict[str, Any]) -> None:
        """保存元数据到文件

        Args:
            source_name: 数据源名称
            table_name: 表名
            metadata: 元数据字典
        """
        file_path = self._get_table_path(source_name, table_name)
        metadata["_source_name"] = source_name
        metadata["_table_name"] = table_name
        metadata["_saved_at"] = datetime.now().isoformat()

        with open(file_path, "w", encoding="utf-8") as f:
            json.dump(metadata, f, indent=2, default=str, ensure_ascii=False)

    def load_metadata(self, source_name: str, table_name: str) -> Optional[Dict[str, Any]]:
        """从文件加载元数据

        Args:
            source_name: 数据源名称
            table_name: 表名

        Returns:
            元数据字典，未找到返回None
        """
        file_path = self._get_table_path(source_name, table_name)
        if not os.path.exists(file_path):
            return None

        with open(file_path, "r", encoding="utf-8") as f:
            return json.load(f)

    def list_tables(self, source_name: str) -> List[str]:
        """列出已存储元数据的表

        Args:
            source_name: 数据源名称

        Returns:
            表名列表
        """
        source_dir = os.path.join(self.base_dir, self._safe_name(source_name))
        if not os.path.exists(source_dir):
            return []

        tables: List[str] = []
        for filename in os.listdir(source_dir):
            if filename.endswith(".json"):
                file_path = os.path.join(source_dir, filename)
                try:
                    with open(file_path, "r", encoding="utf-8") as f:
                        metadata = json.load(f)
                        if "_table_name" in metadata:
                            tables.append(metadata["_table_name"])
                except Exception:
                    pass
        return tables

    def delete_metadata(self, source_name: str, table_name: str) -> bool:
        """删除元数据文件

        Args:
            source_name: 数据源名称
            table_name: 表名

        Returns:
            删除成功返回True，否则返回False
        """
        file_path = self._get_table_path(source_name, table_name)
        if os.path.exists(file_path):
            os.remove(file_path)
            return True
        return False

    def get_last_crawl_info(self, source_name: str, table_name: str) -> Optional[Dict[str, Any]]:
        """获取上次爬取信息

        Args:
            source_name: 数据源名称
            table_name: 表名

        Returns:
            上次爬取信息字典，未找到返回None
        """
        metadata = self.load_metadata(source_name, table_name)
        if not metadata:
            return None

        return {
            "last_crawl_time": metadata.get("_saved_at"),
            "schema_hash": metadata.get("schema_hash"),
            "row_count": metadata.get("row_count"),
            "watermark_value": metadata.get("watermark_value"),
            "checksum": metadata.get("checksum"),
        }


class DatabaseMetadataStorage(MetadataStorage):
    """基于数据库的元数据存储实现"""

    def __init__(self, connection_string: str, table_name: str = "metadata_store"):
        """初始化数据库元数据存储

        Args:
            connection_string: 数据库连接字符串
            table_name: 元数据表名
        """
        from sqlalchemy import (
            Column,
            DateTime,
            String,
            Text,
            create_engine,
        )
        from sqlalchemy.orm import declarative_base, sessionmaker

        self.table_name = table_name
        self.engine = create_engine(connection_string)
        self.Base = declarative_base()

        class MetadataRecord(self.Base):
            __tablename__ = table_name

            source_name = Column(String(255), primary_key=True)
            table_name = Column(String(255), primary_key=True)
            metadata_json = Column(Text, nullable=False)
            created_at = Column(DateTime, default=datetime.now)
            updated_at = Column(DateTime, default=datetime.now, onupdate=datetime.now)

        self.MetadataRecord = MetadataRecord
        self.Base.metadata.create_all(self.engine)
        self.Session = sessionmaker(bind=self.engine)

    def save_metadata(self, source_name: str, table_name: str, metadata: Dict[str, Any]) -> None:
        """保存元数据到数据库

        Args:
            source_name: 数据源名称
            table_name: 表名
            metadata: 元数据字典
        """
        with self.Session() as session:
            record = (
                session.query(self.MetadataRecord)
                .filter_by(source_name=source_name, table_name=table_name)
                .first()
            )

            metadata_json = json.dumps(metadata, default=str, ensure_ascii=False)

            if record:
                record.metadata_json = metadata_json
                record.updated_at = datetime.now()
            else:
                record = self.MetadataRecord(
                    source_name=source_name,
                    table_name=table_name,
                    metadata_json=metadata_json,
                )
                session.add(record)

            session.commit()

    def load_metadata(self, source_name: str, table_name: str) -> Optional[Dict[str, Any]]:
        """从数据库加载元数据

        Args:
            source_name: 数据源名称
            table_name: 表名

        Returns:
            元数据字典，未找到返回None
        """
        with self.Session() as session:
            record = (
                session.query(self.MetadataRecord)
                .filter_by(source_name=source_name, table_name=table_name)
                .first()
            )

            if record:
                return json.loads(record.metadata_json)
            return None

    def list_tables(self, source_name: str) -> List[str]:
        """列出已存储元数据的表

        Args:
            source_name: 数据源名称

        Returns:
            表名列表
        """
        with self.Session() as session:
            records = (
                session.query(self.MetadataRecord.table_name)
                .filter_by(source_name=source_name)
                .all()
            )
            return [r[0] for r in records]

    def delete_metadata(self, source_name: str, table_name: str) -> bool:
        """从数据库删除元数据

        Args:
            source_name: 数据源名称
            table_name: 表名

        Returns:
            删除成功返回True，否则返回False
        """
        with self.Session() as session:
            record = (
                session.query(self.MetadataRecord)
                .filter_by(source_name=source_name, table_name=table_name)
                .first()
            )

            if record:
                session.delete(record)
                session.commit()
                return True
            return False

    def get_last_crawl_info(self, source_name: str, table_name: str) -> Optional[Dict[str, Any]]:
        """获取上次爬取信息

        Args:
            source_name: 数据源名称
            table_name: 表名

        Returns:
            上次爬取信息字典，未找到返回None
        """
        metadata = self.load_metadata(source_name, table_name)
        if not metadata:
            return None

        return {
            "schema_hash": metadata.get("schema_hash"),
            "row_count": metadata.get("row_count"),
            "watermark_value": metadata.get("watermark_value"),
            "checksum": metadata.get("checksum"),
        }


class CrawlScheduler:
    """爬取调度器

    支持定时调度和Cron表达式调度
    """

    def __init__(self, check_interval_seconds: int = 60):
        """初始化爬取调度器

        Args:
            check_interval_seconds: 检查间隔秒数
        """
        self.check_interval_seconds = check_interval_seconds
        self._scheduled_tasks: Dict[str, Dict[str, Any]] = {}
        self._lock = threading.RLock()
        self._running = False
        self._scheduler_thread: Optional[threading.Thread] = None
        self._stop_event = threading.Event()

    def add_cron_task(
        self,
        task_name: str,
        cron_expression: str,
        crawl_func: Callable[[], Any],
        config: Optional[CrawlConfig] = None,
        enabled: bool = True,
    ) -> str:
        """添加Cron调度任务

        Args:
            task_name: 任务名称
            cron_expression: Cron表达式
            crawl_func: 爬取函数
            config: 爬取配置
            enabled: 是否启用

        Returns:
            任务ID
        """
        task_id = str(uuid.uuid4())
        with self._lock:
            self._scheduled_tasks[task_id] = {
                "task_name": task_name,
                "cron_expression": cron_expression,
                "croniter": croniter(cron_expression, datetime.now()),
                "crawl_func": crawl_func,
                "config": config,
                "enabled": enabled,
                "last_run_time": None,
                "next_run_time": None,
                "run_count": 0,
            }
            self._update_next_run_time(task_id)
        return task_id

    def add_interval_task(
        self,
        task_name: str,
        interval_seconds: int,
        crawl_func: Callable[[], Any],
        config: Optional[CrawlConfig] = None,
        enabled: bool = True,
    ) -> str:
        """添加间隔调度任务

        Args:
            task_name: 任务名称
            interval_seconds: 间隔秒数
            crawl_func: 爬取函数
            config: 爬取配置
            enabled: 是否启用

        Returns:
            任务ID
        """
        base_time = datetime(2000, 1, 1)
        cron_expression = f"*/{interval_seconds} * * * *"

        if interval_seconds >= 60:
            minutes = interval_seconds // 60
            if interval_seconds % 60 == 0:
                cron_expression = f"*/{minutes} * * * *"
            else:
                cron_expression = f"* * * * *"

        return self.add_cron_task(task_name, cron_expression, crawl_func, config, enabled)

    def _update_next_run_time(self, task_id: str) -> None:
        """更新任务的下次运行时间

        Args:
            task_id: 任务ID
        """
        with self._lock:
            task = self._scheduled_tasks.get(task_id)
            if task:
                last_run = task["last_run_time"] or datetime.now()
                task["next_run_time"] = task["croniter"].get_next(datetime, start_time=last_run)

    def remove_task(self, task_id: str) -> bool:
        """移除调度任务

        Args:
            task_id: 任务ID

        Returns:
            移除成功返回True，否则返回False
        """
        with self._lock:
            if task_id in self._scheduled_tasks:
                del self._scheduled_tasks[task_id]
                return True
            return False

    def enable_task(self, task_id: str) -> bool:
        """启用任务

        Args:
            task_id: 任务ID

        Returns:
            启用成功返回True，否则返回False
        """
        with self._lock:
            task = self._scheduled_tasks.get(task_id)
            if task:
                task["enabled"] = True
                return True
            return False

    def disable_task(self, task_id: str) -> bool:
        """禁用任务

        Args:
            task_id: 任务ID

        Returns:
            禁用成功返回True，否则返回False
        """
        with self._lock:
            task = self._scheduled_tasks.get(task_id)
            if task:
                task["enabled"] = False
                return True
            return False

    def list_tasks(self) -> List[Dict[str, Any]]:
        """列出所有调度任务

        Returns:
            任务信息列表
        """
        with self._lock:
            tasks = []
            for task_id, task in self._scheduled_tasks.items():
                tasks.append(
                    {
                        "task_id": task_id,
                        "task_name": task["task_name"],
                        "cron_expression": task["cron_expression"],
                        "enabled": task["enabled"],
                        "last_run_time": task["last_run_time"].isoformat()
                        if task["last_run_time"]
                        else None,
                        "next_run_time": task["next_run_time"].isoformat()
                        if task["next_run_time"]
                        else None,
                        "run_count": task["run_count"],
                    }
                )
            return tasks

    def _run_scheduler_loop(self) -> None:
        """调度器主循环"""
        while not self._stop_event.is_set():
            try:
                self._check_and_run_tasks()
            except Exception:
                pass

            self._stop_event.wait(self.check_interval_seconds)

    def _check_and_run_tasks(self) -> None:
        """检查并运行到期的任务"""
        now = datetime.now()
        tasks_to_run = []

        with self._lock:
            for task_id, task in self._scheduled_tasks.items():
                if not task["enabled"]:
                    continue

                if task["next_run_time"] and now >= task["next_run_time"]:
                    tasks_to_run.append(task_id)

        for task_id in tasks_to_run:
            if self._stop_event.is_set():
                break

            with self._lock:
                task = self._scheduled_tasks.get(task_id)
                if not task:
                    continue

                task["last_run_time"] = datetime.now()
                task["run_count"] += 1
                self._update_next_run_time(task_id)

            try:
                task["crawl_func"]()
            except Exception:
                pass

    def start(self) -> None:
        """启动调度器"""
        if self._running:
            return

        self._running = True
        self._stop_event.clear()
        self._scheduler_thread = threading.Thread(target=self._run_scheduler_loop, daemon=True)
        self._scheduler_thread.start()

    def stop(self) -> None:
        """停止调度器"""
        if not self._running:
            return

        self._running = False
        self._stop_event.set()

        if self._scheduler_thread:
            self._scheduler_thread.join(timeout=5)
            self._scheduler_thread = None

    def is_running(self) -> bool:
        """检查调度器是否运行中

        Returns:
            运行中返回True，否则返回False
        """
        return self._running


class MetadataCrawler:
    """元数据爬虫

    整合Schema提取、统计信息采集、样例数据采集功能，支持自动扫描和增量更新
    """

    def __init__(
        self,
        data_source: DataSource,
        storage: Optional[MetadataStorage] = None,
        scheduler: Optional[CrawlScheduler] = None,
    ):
        """初始化元数据爬虫

        Args:
            data_source: 数据源
            storage: 元数据存储，默认使用文件存储
            scheduler: 爬取调度器，默认创建新的调度器
        """
        self.data_source = data_source
        self.storage = storage or FileMetadataStorage()
        self.scheduler = scheduler or CrawlScheduler()

        self._schema_extractor: Optional[SchemaExtractor] = None
        self._stats_collector: Optional[StatsCollector] = None
        self._sample_collector: Optional[SampleCollector] = None

        self._tasks: Dict[str, CrawlTask] = {}
        self._lock = threading.RLock()
        self._init_extractors()

    def _init_extractors(self) -> None:
        """初始化各个采集器"""
        if isinstance(self.data_source, RelationalDataSource):
            try:
                self._schema_extractor = create_schema_extractor(self.data_source)
            except Exception:
                self._schema_extractor = None

        self._stats_collector = StatsCollector(self.data_source)
        self._sample_collector = SampleCollector(self.data_source)

    @property
    def schema_extractor(self) -> Optional[SchemaExtractor]:
        """获取Schema提取器

        Returns:
            Schema提取器实例
        """
        return self._schema_extractor

    @property
    def stats_collector(self) -> Optional[StatsCollector]:
        """获取统计信息采集器

        Returns:
            统计信息采集器实例
        """
        return self._stats_collector

    @property
    def sample_collector(self) -> Optional[SampleCollector]:
        """获取样例数据采集器

        Returns:
            样例数据采集器实例
        """
        return self._sample_collector

    def _get_tables_to_crawl(self, config: CrawlConfig) -> List[str]:
        """获取要爬取的表列表

        Args:
            config: 爬取配置

        Returns:
            表名列表
        """
        if config.tables:
            tables = config.tables
        else:
            tables = self.data_source.list_tables()

        if config.exclude_tables:
            tables = [t for t in tables if t not in config.exclude_tables]

        return tables

    def _compute_schema_hash(self, schema: TableSchema) -> str:
        """计算Schema的哈希值

        Args:
            schema: 表Schema

        Returns:
            哈希值字符串
        """
        schema_dict = schema.to_dict()
        schema_str = json.dumps(
            {
                "columns": [(c["name"], c["data_type"], c["nullable"]) for c in schema_dict["columns"]],
                "primary_key_columns": schema_dict["primary_key_columns"],
                "indexes": [(i["name"], i["columns"]) for i in schema_dict["indexes"]],
            },
            sort_keys=True,
        )
        return hashlib.md5(schema_str.encode()).hexdigest()

    def _check_for_updates(
        self,
        config: CrawlConfig,
        table_name: str,
        current_schema: Optional[TableSchema],
    ) -> Tuple[bool, Dict[str, Any]]:
        """检查数据是否有更新

        Args:
            config: 爬取配置
            table_name: 表名
            current_schema: 当前Schema

        Returns:
            (是否有更新, 更新信息字典)
        """
        if not config.incremental_config.enabled:
            return True, {"reason": "full_crawl"}

        last_info = self.storage.get_last_crawl_info(config.data_source_name, table_name)
        if not last_info:
            return True, {"reason": "first_crawl"}

        updates: Dict[str, Any] = {}

        if current_schema:
            current_hash = self._compute_schema_hash(current_schema)
            if current_hash != last_info.get("schema_hash"):
                updates["schema_changed"] = True
                updates["old_schema_hash"] = last_info.get("schema_hash")
                updates["new_schema_hash"] = current_hash

        try:
            current_row_count = self.data_source.get_row_count(table_name)
            if current_row_count != last_info.get("row_count"):
                updates["row_count_changed"] = True
                updates["old_row_count"] = last_info.get("row_count")
                updates["new_row_count"] = current_row_count
        except Exception:
            pass

        inc_config = config.incremental_config
        if inc_config.tracking_mode == "watermark" and inc_config.watermark_column:
            try:
                df = self.data_source.read_data(
                    table_name,
                    columns=[inc_config.watermark_column],
                    limit=1,
                    filters={},
                )
                if not df.empty:
                    current_watermark = df.iloc[0][inc_config.watermark_column]
                    if str(current_watermark) != str(last_info.get("watermark_value")):
                        updates["watermark_changed"] = True
                        updates["old_watermark"] = last_info.get("watermark_value")
                        updates["new_watermark"] = str(current_watermark)
            except Exception:
                pass

        has_updates = len(updates) > 0
        if not has_updates:
            updates["reason"] = "no_changes"

        return has_updates, updates

    def _crawl_table(
        self,
        config: CrawlConfig,
        table_name: str,
    ) -> Dict[str, Any]:
        """爬取单个表的元数据

        Args:
            config: 爬取配置
            table_name: 表名

        Returns:
            元数据结果字典

        Raises:
            MetadataCrawlError: 当爬取失败时抛出
        """
        result: Dict[str, Any] = {
            "table_name": table_name,
            "source_name": config.data_source_name,
            "crawled_at": datetime.now().isoformat(),
            "success": True,
        }

        try:
            schema: Optional[TableSchema] = None
            if config.extract_schema and self._schema_extractor:
                schema = self._schema_extractor.extract_table_schema(table_name)
                result["schema"] = schema.to_dict()
                result["schema_hash"] = self._compute_schema_hash(schema)

            has_updates, update_info = self._check_for_updates(config, table_name, schema)
            result["update_info"] = update_info

            if not has_updates and config.incremental_config.enabled:
                result["skipped"] = True
                result["skip_reason"] = "no_changes_detected"
                return result

            if config.collect_stats and self._stats_collector:
                stats = self._stats_collector.collect_table_statistics(
                    table_name,
                    sample_size=config.stats_sample_size,
                )
                result["statistics"] = stats.to_dict()
                result["row_count"] = stats.row_count

            if config.collect_samples and self._sample_collector:
                sample_result = self._sample_collector.collect_sample(
                    table_name,
                    config.sample_config,
                )
                result["sample"] = {
                    "columns": list(sample_result.data.columns),
                    "sample_size": sample_result.sample_size,
                    "total_size": sample_result.total_size,
                    "sampling_rate": sample_result.sampling_rate,
                    "method": sample_result.method.value,
                    "statistics": sample_result.statistics,
                    "preview": sample_result.data.head(10).to_dict(orient="records"),
                }

            inc_config = config.incremental_config
            if inc_config.enabled and inc_config.watermark_column:
                try:
                    df = self.data_source.read_data(
                        table_name,
                        columns=[inc_config.watermark_column],
                        limit=1,
                    )
                    if not df.empty:
                        result["watermark_value"] = str(
                            df.iloc[0][inc_config.watermark_column]
                        )
                except Exception:
                    pass

            return result

        except Exception as e:
            result["success"] = False
            result["error"] = str(e)
            raise MetadataCrawlError(f"Failed to crawl table {table_name}: {e}") from e

    def crawl(
        self,
        config: CrawlConfig,
        task_id: Optional[str] = None,
    ) -> CrawlTask:
        """执行元数据爬取

        Args:
            config: 爬取配置
            task_id: 任务ID，自动生成

        Returns:
            爬取任务对象

        Raises:
            MetadataCrawlError: 当爬取失败时抛出
        """
        if not task_id:
            task_id = str(uuid.uuid4())

        task = CrawlTask(task_id=task_id, config=config)
        task.status = CrawlStatus.RUNNING
        task.start_time = datetime.now()

        with self._lock:
            self._tasks[task_id] = task

        try:
            if not self.data_source.is_connected():
                self.data_source.connect()

            tables = self._get_tables_to_crawl(config)
            task.tables_total = len(tables)
            task.updated_at = datetime.now()

            for i, table_name in enumerate(tables):
                for retry in range(config.retry_count):
                    try:
                        table_result = self._crawl_table(config, table_name)
                        task.metadata[table_name] = table_result

                        if table_result.get("success", False):
                            self.storage.save_metadata(
                                config.data_source_name,
                                table_name,
                                table_result,
                            )
                        break
                    except Exception as e:
                        if retry < config.retry_count - 1:
                            time.sleep(config.retry_interval_seconds)
                        else:
                            task.error_messages.append(
                                f"Table {table_name} failed after {config.retry_count} retries: {e}"
                            )
                            task.metadata[table_name] = {
                                "table_name": table_name,
                                "success": False,
                                "error": str(e),
                            }

                task.tables_processed = i + 1
                task.updated_at = datetime.now()

                with self._lock:
                    self._tasks[task_id] = task

            if task.error_messages:
                task.status = CrawlStatus.PARTIAL
            else:
                task.status = CrawlStatus.COMPLETED

        except Exception as e:
            task.status = CrawlStatus.FAILED
            task.error_messages.append(str(e))
            raise MetadataCrawlError(f"Crawl failed: {e}") from e
        finally:
            task.end_time = datetime.now()
            task.updated_at = datetime.now()
            with self._lock:
                self._tasks[task_id] = task

        return task

    def crawl_incremental(
        self,
        config: CrawlConfig,
        task_id: Optional[str] = None,
    ) -> CrawlTask:
        """执行增量元数据爬取

        Args:
            config: 爬取配置
            task_id: 任务ID

        Returns:
            爬取任务对象
        """
        config.incremental_config.enabled = True
        config.incremental_config.last_crawl_time = datetime.now()
        return self.crawl(config, task_id)

    def get_task(self, task_id: str) -> Optional[CrawlTask]:
        """获取爬取任务

        Args:
            task_id: 任务ID

        Returns:
            爬取任务对象，未找到返回None
        """
        with self._lock:
            return self._tasks.get(task_id)

    def list_tasks(
        self,
        status: Optional[CrawlStatus] = None,
    ) -> List[CrawlTask]:
        """列出所有爬取任务

        Args:
            status: 可选的状态过滤

        Returns:
            爬取任务列表
        """
        with self._lock:
            tasks = list(self._tasks.values())

        if status:
            tasks = [t for t in tasks if t.status == status]

        return sorted(tasks, key=lambda t: t.created_at, reverse=True)

    def schedule_crawl(
        self,
        config: CrawlConfig,
        cron_expression: str,
        task_name: Optional[str] = None,
    ) -> str:
        """调度定时爬取任务

        Args:
            config: 爬取配置
            cron_expression: Cron表达式
            task_name: 任务名称

        Returns:
            调度任务ID
        """
        task_name = task_name or f"crawl_{config.data_source_name}"

        def crawl_job():
            try:
                self.crawl(config)
            except Exception:
                pass

        return self.scheduler.add_cron_task(task_name, cron_expression, crawl_job, config)

    def start_scheduler(self) -> None:
        """启动调度器"""
        self.scheduler.start()

    def stop_scheduler(self) -> None:
        """停止调度器"""
        self.scheduler.stop()

    def get_crawl_summary(self, source_name: str) -> Dict[str, Any]:
        """获取爬取摘要信息

        Args:
            source_name: 数据源名称

        Returns:
            摘要信息字典
        """
        tables = self.storage.list_tables(source_name)
        summary: Dict[str, Any] = {
            "source_name": source_name,
            "total_tables": len(tables),
            "tables": {},
        }

        for table_name in tables:
            metadata = self.storage.load_metadata(source_name, table_name)
            if metadata:
                summary["tables"][table_name] = {
                    "crawled_at": metadata.get("_saved_at"),
                    "has_schema": "schema" in metadata,
                    "has_statistics": "statistics" in metadata,
                    "has_sample": "sample" in metadata,
                    "row_count": metadata.get("row_count"),
                    "success": metadata.get("success", False),
                }

        return summary

    def discover_schemas(self) -> List[TableSchema]:
        """自动扫描并发现所有Schema

        Returns:
            表Schema列表

        Raises:
            MetadataCrawlError: 当扫描失败时抛出
        """
        if not self._schema_extractor:
            raise MetadataCrawlError("Schema extractor not available for this data source")

        try:
            return self._schema_extractor.extract_all_tables()
        except Exception as e:
            raise MetadataCrawlError(f"Schema discovery failed: {e}") from e

    def compare_schemas(
        self,
        source_name: str,
        table_name: str,
    ) -> Dict[str, Any]:
        """比较当前Schema与存储的Schema

        Args:
            source_name: 数据源名称
            table_name: 表名

        Returns:
            比较结果字典
        """
        if not self._schema_extractor:
            raise MetadataCrawlError("Schema extractor not available")

        current_schema = self._schema_extractor.extract_table_schema(table_name)
        stored_metadata = self.storage.load_metadata(source_name, table_name)

        comparison: Dict[str, Any] = {
            "table_name": table_name,
            "current_schema_hash": self._compute_schema_hash(current_schema),
            "stored_schema_hash": None,
            "differences": [],
        }

        if not stored_metadata or "schema" not in stored_metadata:
            comparison["differences"].append({"type": "new_table", "message": "No stored schema found"})
            return comparison

        stored_schema = stored_metadata["schema"]
        comparison["stored_schema_hash"] = stored_metadata.get("schema_hash")

        current_columns = {c["name"]: c for c in current_schema.to_dict()["columns"]}
        stored_columns = {c["name"]: c for c in stored_schema["columns"]}

        for col_name in current_columns:
            if col_name not in stored_columns:
                comparison["differences"].append(
                    {"type": "column_added", "column": col_name}
                )
            elif current_columns[col_name] != stored_columns[col_name]:
                comparison["differences"].append(
                    {
                        "type": "column_modified",
                        "column": col_name,
                        "old": stored_columns[col_name],
                        "new": current_columns[col_name],
                    }
                )

        for col_name in stored_columns:
            if col_name not in current_columns:
                comparison["differences"].append(
                    {"type": "column_removed", "column": col_name}
                )

        return comparison
