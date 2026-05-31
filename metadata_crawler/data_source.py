"""
数据源抽象模块
提供统一的数据源访问接口，支持关系型数据库、CSV文件、Parquet文件和REST API
"""

import os
import threading
import time
from abc import ABC, abstractmethod
from contextlib import contextmanager
from dataclasses import dataclass, field
from enum import Enum
from typing import Any, Dict, Iterator, List, Optional, Tuple, Union

import pandas as pd
import requests
from sqlalchemy import create_engine, text
from sqlalchemy.engine import Connection, Engine
from sqlalchemy.exc import SQLAlchemyError
from sqlalchemy.orm import Session, sessionmaker

from session307.exceptions import ConnectionError, ConfigurationError, MetadataCrawlError


class DataSourceType(Enum):
    """数据源类型枚举"""

    RELATIONAL = "relational"
    MYSQL = "mysql"
    POSTGRESQL = "postgresql"
    SQLITE = "sqlite"
    CSV = "csv"
    PARQUET = "parquet"
    REST_API = "rest_api"


@dataclass
class ConnectionConfig:
    """连接配置数据类

    Attributes:
        host: 数据库主机地址
        port: 数据库端口
        database: 数据库名称
        username: 用户名
        password: 密码
        driver: 数据库驱动名称
        connection_timeout: 连接超时时间（秒）
        pool_size: 连接池大小
        max_overflow: 连接池最大溢出数
        pool_recycle: 连接回收时间（秒）
        extra_params: 额外的连接参数
    """

    host: Optional[str] = None
    port: Optional[int] = None
    database: Optional[str] = None
    username: Optional[str] = None
    password: Optional[str] = None
    driver: Optional[str] = None
    connection_timeout: int = 30
    pool_size: int = 5
    max_overflow: int = 10
    pool_recycle: int = 3600
    extra_params: Dict[str, Any] = field(default_factory=dict)

    def to_connection_string(self, source_type: DataSourceType) -> str:
        """生成SQLAlchemy连接字符串

        Args:
            source_type: 数据源类型

        Returns:
            SQLAlchemy连接字符串

        Raises:
            ConfigurationError: 当配置不完整时抛出
        """
        if source_type == DataSourceType.SQLITE:
            if not self.database:
                raise ConfigurationError("SQLite requires database file path")
            return f"sqlite:///{self.database}"

        if not all([self.host, self.port, self.database, self.username]):
            raise ConfigurationError(
                f"{source_type.value} requires host, port, database, and username"
            )

        driver = self.driver or source_type.value
        auth = f"{self.username}:{self.password}" if self.password else self.username
        params = "&".join([f"{k}={v}" for k, v in self.extra_params.items()])
        params = f"?{params}" if params else ""

        return f"{driver}://{auth}@{self.host}:{self.port}/{self.database}{params}"


class SQLAlchemyConnectionManager:
    """SQLAlchemy连接管理器

    提供连接池管理、会话管理和线程安全的连接访问

    Attributes:
        engine: SQLAlchemy引擎实例
        session_factory: 会话工厂
    """

    def __init__(self, config: ConnectionConfig, source_type: DataSourceType):
        """初始化连接管理器

        Args:
            config: 连接配置
            source_type: 数据源类型
        """
        self._config = config
        self._source_type = source_type
        self._engine: Optional[Engine] = None
        self._session_factory: Optional[sessionmaker] = None
        self._lock = threading.RLock()
        self._connection_count = 0
        self._last_usage_time = 0.0

    def _create_engine(self) -> Engine:
        """创建SQLAlchemy引擎

        Returns:
            SQLAlchemy引擎实例

        Raises:
            ConnectionError: 当引擎创建失败时抛出
        """
        try:
            connection_string = self._config.to_connection_string(self._source_type)
            engine = create_engine(
                connection_string,
                pool_size=self._config.pool_size,
                max_overflow=self._config.max_overflow,
                pool_recycle=self._config.pool_recycle,
                pool_timeout=self._config.connection_timeout,
                pool_pre_ping=True,
                future=True,
            )
            return engine
        except SQLAlchemyError as e:
            raise ConnectionError(f"Failed to create engine: {e}") from e
        except Exception as e:
            raise ConnectionError(f"Unexpected error creating engine: {e}") from e

    @property
    def engine(self) -> Engine:
        """获取SQLAlchemy引擎实例（懒加载）

        Returns:
            SQLAlchemy引擎实例
        """
        with self._lock:
            if self._engine is None:
                self._engine = self._create_engine()
                self._session_factory = sessionmaker(bind=self._engine)
        return self._engine

    @property
    def session_factory(self) -> sessionmaker:
        """获取会话工厂

        Returns:
            SQLAlchemy会话工厂
        """
        _ = self.engine
        assert self._session_factory is not None
        return self._session_factory

    @contextmanager
    def get_connection(self) -> Iterator[Connection]:
        """获取数据库连接上下文管理器

        Yields:
            SQLAlchemy连接对象

        Raises:
            ConnectionError: 当获取连接失败时抛出
        """
        conn: Optional[Connection] = None
        try:
            conn = self.engine.connect()
            with self._lock:
                self._connection_count += 1
                self._last_usage_time = time.time()
            yield conn
        except SQLAlchemyError as e:
            raise ConnectionError(f"Failed to get connection: {e}") from e
        finally:
            if conn is not None:
                conn.close()
                with self._lock:
                    self._connection_count -= 1

    @contextmanager
    def get_session(self) -> Iterator[Session]:
        """获取数据库会话上下文管理器

        Yields:
            SQLAlchemy会话对象

        Raises:
            ConnectionError: 当获取会话失败时抛出
        """
        session: Optional[Session] = None
        try:
            session = self.session_factory()
            with self._lock:
                self._connection_count += 1
                self._last_usage_time = time.time()
            yield session
        except SQLAlchemyError as e:
            if session is not None:
                session.rollback()
            raise ConnectionError(f"Failed to get session: {e}") from e
        finally:
            if session is not None:
                session.close()
                with self._lock:
                    self._connection_count -= 1

    def execute_query(
        self,
        query: str,
        params: Optional[Dict[str, Any]] = None,
        return_pandas: bool = False,
    ) -> Union[List[Tuple[Any, ...]], pd.DataFrame]:
        """执行SQL查询

        Args:
            query: SQL查询语句
            params: 查询参数
            return_pandas: 是否返回pandas DataFrame

        Returns:
            查询结果，可以是元组列表或pandas DataFrame

        Raises:
            MetadataCrawlError: 当查询执行失败时抛出
        """
        try:
            with self.get_connection() as conn:
                result = conn.execute(text(query), params or {})
                if return_pandas:
                    df = pd.DataFrame(result.fetchall(), columns=result.keys())
                    return df
                return result.fetchall()
        except SQLAlchemyError as e:
            raise MetadataCrawlError(f"Query execution failed: {e}") from e

    def test_connection(self) -> bool:
        """测试数据库连接

        Returns:
            连接成功返回True，失败返回False
        """
        try:
            with self.get_connection() as conn:
                conn.execute(text("SELECT 1"))
            return True
        except Exception:
            return False

    def get_stats(self) -> Dict[str, Any]:
        """获取连接池统计信息

        Returns:
            连接池统计信息字典
        """
        with self._lock:
            pool = self.engine.pool if self._engine else None
            return {
                "current_connections": self._connection_count,
                "pool_size": self._config.pool_size,
                "max_overflow": self._config.max_overflow,
                "last_usage_time": self._last_usage_time,
                "pool_overflow": pool.overflow() if pool else 0,
                "pool_checkedin": pool.checkedin() if pool else 0,
                "pool_checkedout": pool.checkedout() if pool else 0,
            }

    def dispose(self) -> None:
        """释放连接池资源"""
        with self._lock:
            if self._engine is not None:
                self._engine.dispose()
                self._engine = None
                self._session_factory = None


class DataSource(ABC):
    """数据源抽象基类

    定义所有数据源的统一接口
    """

    def __init__(self, name: str, source_type: DataSourceType):
        """初始化数据源

        Args:
            name: 数据源名称
            source_type: 数据源类型
        """
        self.name = name
        self.source_type = source_type
        self._last_access_time: Optional[float] = None
        self._access_count = 0

    @abstractmethod
    def connect(self) -> None:
        """连接到数据源"""
        pass

    @abstractmethod
    def disconnect(self) -> None:
        """断开数据源连接"""
        pass

    @abstractmethod
    def is_connected(self) -> bool:
        """检查是否已连接

        Returns:
            已连接返回True，否则返回False
        """
        pass

    @abstractmethod
    def list_tables(self) -> List[str]:
        """列出数据源中的所有表/数据集

        Returns:
            表名/数据集名称列表
        """
        pass

    @abstractmethod
    def read_data(
        self,
        table_name: str,
        columns: Optional[List[str]] = None,
        limit: Optional[int] = None,
        offset: Optional[int] = None,
        filters: Optional[Dict[str, Any]] = None,
    ) -> pd.DataFrame:
        """读取数据

        Args:
            table_name: 表名/数据集名称
            columns: 要读取的列名列表，None表示读取所有列
            limit: 读取的最大行数
            offset: 读取的起始偏移量
            filters: 过滤条件字典

        Returns:
            包含数据的pandas DataFrame
        """
        pass

    @abstractmethod
    def get_row_count(self, table_name: str) -> int:
        """获取表的行数

        Args:
            table_name: 表名/数据集名称

        Returns:
            表的行数
        """
        pass

    def _update_access_stats(self) -> None:
        """更新访问统计信息"""
        self._last_access_time = time.time()
        self._access_count += 1

    def get_access_stats(self) -> Dict[str, Any]:
        """获取访问统计信息

        Returns:
            访问统计信息字典
        """
        return {
            "name": self.name,
            "source_type": self.source_type.value,
            "access_count": self._access_count,
            "last_access_time": self._last_access_time,
        }


class RelationalDataSource(DataSource):
    """关系型数据源

    基于SQLAlchemy的关系型数据库访问实现
    """

    def __init__(
        self,
        name: str,
        source_type: DataSourceType,
        config: ConnectionConfig,
    ):
        """初始化关系型数据源

        Args:
            name: 数据源名称
            source_type: 数据源类型（MYSQL/POSTGRESQL/SQLITE/RELATIONAL）
            config: 连接配置
        """
        super().__init__(name, source_type)
        self._config = config
        self._connection_manager = SQLAlchemyConnectionManager(config, source_type)
        self._connected = False

    @property
    def connection_manager(self) -> SQLAlchemyConnectionManager:
        """获取连接管理器

        Returns:
            SQLAlchemy连接管理器实例
        """
        return self._connection_manager

    def connect(self) -> None:
        """连接到关系型数据库

        Raises:
            ConnectionError: 当连接失败时抛出
        """
        try:
            if not self._connection_manager.test_connection():
                raise ConnectionError("Failed to establish database connection")
            self._connected = True
            self._update_access_stats()
        except Exception as e:
            raise ConnectionError(f"Failed to connect to database: {e}") from e

    def disconnect(self) -> None:
        """断开数据库连接"""
        self._connection_manager.dispose()
        self._connected = False

    def is_connected(self) -> bool:
        """检查是否已连接

        Returns:
            已连接返回True，否则返回False
        """
        if not self._connected:
            return False
        return self._connection_manager.test_connection()

    def list_tables(self) -> List[str]:
        """列出数据库中的所有表

        Returns:
            表名列表

        Raises:
            MetadataCrawlError: 当获取表列表失败时抛出
        """
        try:
            self._update_access_stats()
            query = """
                SELECT table_name
                FROM information_schema.tables
                WHERE table_schema = DATABASE()
                AND table_type = 'BASE TABLE'
            """

            if self.source_type == DataSourceType.POSTGRESQL:
                query = """
                    SELECT table_name
                    FROM information_schema.tables
                    WHERE table_schema = 'public'
                    AND table_type = 'BASE TABLE'
                """
            elif self.source_type == DataSourceType.SQLITE:
                query = """
                    SELECT name
                    FROM sqlite_master
                    WHERE type = 'table'
                    AND name NOT LIKE 'sqlite_%'
                """

            result = self._connection_manager.execute_query(query)
            return [row[0] for row in result]
        except Exception as e:
            raise MetadataCrawlError(f"Failed to list tables: {e}") from e

    def read_data(
        self,
        table_name: str,
        columns: Optional[List[str]] = None,
        limit: Optional[int] = None,
        offset: Optional[int] = None,
        filters: Optional[Dict[str, Any]] = None,
    ) -> pd.DataFrame:
        """从数据库表读取数据

        Args:
            table_name: 表名
            columns: 要读取的列名列表，None表示读取所有列
            limit: 读取的最大行数
            offset: 读取的起始偏移量
            filters: 过滤条件字典

        Returns:
            包含数据的pandas DataFrame

        Raises:
            MetadataCrawlError: 当读取数据失败时抛出
        """
        try:
            self._update_access_stats()
            col_str = ", ".join(columns) if columns else "*"
            query = f"SELECT {col_str} FROM {table_name}"
            params: Dict[str, Any] = {}

            if filters:
                conditions = []
                for i, (key, value) in enumerate(filters.items()):
                    param_key = f"filter_{i}"
                    if isinstance(value, (list, tuple)):
                        placeholders = ", ".join([f":{param_key}_{j}" for j in range(len(value))])
                        conditions.append(f"{key} IN ({placeholders})")
                        for j, v in enumerate(value):
                            params[f"{param_key}_{j}"] = v
                    else:
                        conditions.append(f"{key} = :{param_key}")
                        params[param_key] = value
                if conditions:
                    query += " WHERE " + " AND ".join(conditions)

            if limit is not None:
                query += " LIMIT :limit"
                params["limit"] = limit

            if offset is not None:
                query += " OFFSET :offset"
                params["offset"] = offset

            return self._connection_manager.execute_query(query, params, return_pandas=True)
        except Exception as e:
            raise MetadataCrawlError(f"Failed to read data from {table_name}: {e}") from e

    def get_row_count(self, table_name: str) -> int:
        """获取表的行数

        Args:
            table_name: 表名

        Returns:
            表的行数

        Raises:
            MetadataCrawlError: 当获取行数失败时抛出
        """
        try:
            self._update_access_stats()
            query = f"SELECT COUNT(*) FROM {table_name}"
            result = self._connection_manager.execute_query(query)
            return result[0][0] if result else 0
        except Exception as e:
            raise MetadataCrawlError(f"Failed to get row count for {table_name}: {e}") from e

    def execute_raw_query(
        self,
        query: str,
        params: Optional[Dict[str, Any]] = None,
        return_pandas: bool = False,
    ) -> Union[List[Tuple[Any, ...]], pd.DataFrame]:
        """执行原始SQL查询

        Args:
            query: SQL查询语句
            params: 查询参数
            return_pandas: 是否返回pandas DataFrame

        Returns:
            查询结果
        """
        self._update_access_stats()
        return self._connection_manager.execute_query(query, params, return_pandas)


class CSVDataSource(DataSource):
    """CSV文件数据源"""

    def __init__(
        self,
        name: str,
        file_path: str,
        delimiter: str = ",",
        encoding: str = "utf-8",
        has_header: bool = True,
    ):
        """初始化CSV数据源

        Args:
            name: 数据源名称
            file_path: CSV文件路径
            delimiter: 字段分隔符
            encoding: 文件编码
            has_header: 是否包含表头
        """
        super().__init__(name, DataSourceType.CSV)
        self.file_path = file_path
        self.delimiter = delimiter
        self.encoding = encoding
        self.has_header = has_header
        self._data: Optional[pd.DataFrame] = None
        self._loaded = False

    def connect(self) -> None:
        """加载CSV文件

        Raises:
            ConnectionError: 当文件加载失败时抛出
        """
        try:
            if not os.path.exists(self.file_path):
                raise FileNotFoundError(f"CSV file not found: {self.file_path}")

            self._data = pd.read_csv(
                self.file_path,
                delimiter=self.delimiter,
                encoding=self.encoding,
                header=0 if self.has_header else None,
            )
            self._loaded = True
            self._update_access_stats()
        except Exception as e:
            raise ConnectionError(f"Failed to load CSV file: {e}") from e

    def disconnect(self) -> None:
        """卸载CSV数据"""
        self._data = None
        self._loaded = False

    def is_connected(self) -> bool:
        """检查CSV文件是否已加载

        Returns:
            已加载返回True，否则返回False
        """
        return self._loaded and self._data is not None

    def list_tables(self) -> List[str]:
        """列出CSV文件作为单个表

        Returns:
            包含文件名的列表
        """
        self._update_access_stats()
        return [os.path.basename(self.file_path)]

    def read_data(
        self,
        table_name: str,
        columns: Optional[List[str]] = None,
        limit: Optional[int] = None,
        offset: Optional[int] = None,
        filters: Optional[Dict[str, Any]] = None,
    ) -> pd.DataFrame:
        """从CSV读取数据

        Args:
            table_name: 表名（忽略，CSV只有一个表）
            columns: 要读取的列名列表
            limit: 读取的最大行数
            offset: 读取的起始偏移量
            filters: 过滤条件字典

        Returns:
            包含数据的pandas DataFrame

        Raises:
            MetadataCrawlError: 当读取数据失败时抛出
        """
        try:
            if not self.is_connected():
                self.connect()

            assert self._data is not None
            self._update_access_stats()
            df = self._data.copy()

            if columns:
                df = df[columns]

            if filters:
                for key, value in filters.items():
                    if isinstance(value, (list, tuple)):
                        df = df[df[key].isin(value)]
                    else:
                        df = df[df[key] == value]

            if offset is not None:
                df = df.iloc[offset:]

            if limit is not None:
                df = df.iloc[:limit]

            return df
        except Exception as e:
            raise MetadataCrawlError(f"Failed to read CSV data: {e}") from e

    def get_row_count(self, table_name: str) -> int:
        """获取CSV的行数

        Args:
            table_name: 表名（忽略）

        Returns:
            CSV文件的行数
        """
        if not self.is_connected():
            self.connect()

        assert self._data is not None
        self._update_access_stats()
        return len(self._data)


class ParquetDataSource(DataSource):
    """Parquet文件数据源"""

    def __init__(
        self,
        name: str,
        file_path: str,
        columns: Optional[List[str]] = None,
    ):
        """初始化Parquet数据源

        Args:
            name: 数据源名称
            file_path: Parquet文件路径
            columns: 预定义的列列表
        """
        super().__init__(name, DataSourceType.PARQUET)
        self.file_path = file_path
        self._pre_columns = columns
        self._data: Optional[pd.DataFrame] = None
        self._loaded = False

    def connect(self) -> None:
        """加载Parquet文件

        Raises:
            ConnectionError: 当文件加载失败时抛出
        """
        try:
            if not os.path.exists(self.file_path):
                raise FileNotFoundError(f"Parquet file not found: {self.file_path}")

            self._data = pd.read_parquet(
                self.file_path,
                columns=self._pre_columns,
            )
            self._loaded = True
            self._update_access_stats()
        except Exception as e:
            raise ConnectionError(f"Failed to load Parquet file: {e}") from e

    def disconnect(self) -> None:
        """卸载Parquet数据"""
        self._data = None
        self._loaded = False

    def is_connected(self) -> bool:
        """检查Parquet文件是否已加载

        Returns:
            已加载返回True，否则返回False
        """
        return self._loaded and self._data is not None

    def list_tables(self) -> List[str]:
        """列出Parquet文件作为单个表

        Returns:
            包含文件名的列表
        """
        self._update_access_stats()
        return [os.path.basename(self.file_path)]

    def read_data(
        self,
        table_name: str,
        columns: Optional[List[str]] = None,
        limit: Optional[int] = None,
        offset: Optional[int] = None,
        filters: Optional[Dict[str, Any]] = None,
    ) -> pd.DataFrame:
        """从Parquet读取数据

        Args:
            table_name: 表名（忽略）
            columns: 要读取的列名列表
            limit: 读取的最大行数
            offset: 读取的起始偏移量
            filters: 过滤条件字典

        Returns:
            包含数据的pandas DataFrame

        Raises:
            MetadataCrawlError: 当读取数据失败时抛出
        """
        try:
            if not self.is_connected():
                self.connect()

            assert self._data is not None
            self._update_access_stats()
            df = self._data.copy()

            if columns:
                df = df[columns]

            if filters:
                for key, value in filters.items():
                    if isinstance(value, (list, tuple)):
                        df = df[df[key].isin(value)]
                    else:
                        df = df[df[key] == value]

            if offset is not None:
                df = df.iloc[offset:]

            if limit is not None:
                df = df.iloc[:limit]

            return df
        except Exception as e:
            raise MetadataCrawlError(f"Failed to read Parquet data: {e}") from e

    def get_row_count(self, table_name: str) -> int:
        """获取Parquet的行数

        Args:
            table_name: 表名（忽略）

        Returns:
            Parquet文件的行数
        """
        if not self.is_connected():
            self.connect()

        assert self._data is not None
        self._update_access_stats()
        return len(self._data)


class RESTDataSource(DataSource):
    """REST API数据源"""

    def __init__(
        self,
        name: str,
        base_url: str,
        headers: Optional[Dict[str, str]] = None,
        auth: Optional[Tuple[str, str]] = None,
        timeout: int = 30,
        pagination_param: Optional[str] = None,
        data_path: Optional[str] = None,
    ):
        """初始化REST API数据源

        Args:
            name: 数据源名称
            base_url: API基础URL
            headers: 请求头
            auth: 认证信息（用户名，密码）
            timeout: 请求超时时间
            pagination_param: 分页参数名称
            data_path: 响应数据的JSON路径（如"data.records"）
        """
        super().__init__(name, DataSourceType.REST_API)
        self.base_url = base_url.rstrip("/")
        self.headers = headers or {}
        self.auth = auth
        self.timeout = timeout
        self.pagination_param = pagination_param
        self.data_path = data_path
        self._session: Optional[requests.Session] = None
        self._connected = False

    def connect(self) -> None:
        """建立API连接会话

        Raises:
            ConnectionError: 当连接失败时抛出
        """
        try:
            self._session = requests.Session()
            self._session.headers.update(self.headers)
            if self.auth:
                self._session.auth = self.auth

            response = self._session.get(self.base_url, timeout=self.timeout)
            response.raise_for_status()
            self._connected = True
            self._update_access_stats()
        except Exception as e:
            raise ConnectionError(f"Failed to connect to REST API: {e}") from e

    def disconnect(self) -> None:
        """关闭API连接会话"""
        if self._session is not None:
            self._session.close()
            self._session = None
        self._connected = False

    def is_connected(self) -> bool:
        """检查API连接是否正常

        Returns:
            连接正常返回True，否则返回False
        """
        if not self._connected or self._session is None:
            return False
        try:
            response = self._session.get(self.base_url, timeout=self.timeout)
            return response.status_code == 200
        except Exception:
            return False

    def list_tables(self) -> List[str]:
        """列出可用的API端点

        Returns:
            API端点列表（需要子类或配置实现）
        """
        self._update_access_stats()
        return []

    def _extract_data(self, response_json: Dict[str, Any]) -> List[Dict[str, Any]]:
        """从响应JSON中提取数据

        Args:
            response_json: 响应JSON字典

        Returns:
            数据记录列表
        """
        if not self.data_path:
            return response_json if isinstance(response_json, list) else [response_json]

        data = response_json
        for key in self.data_path.split("."):
            if isinstance(data, dict) and key in data:
                data = data[key]
            else:
                return []

        return data if isinstance(data, list) else [data]

    def _build_url(self, endpoint: str) -> str:
        """构建完整的API URL

        Args:
            endpoint: API端点

        Returns:
            完整的URL
        """
        if endpoint.startswith("http"):
            return endpoint
        return f"{self.base_url}/{endpoint.lstrip('/')}"

    def read_data(
        self,
        table_name: str,
        columns: Optional[List[str]] = None,
        limit: Optional[int] = None,
        offset: Optional[int] = None,
        filters: Optional[Dict[str, Any]] = None,
    ) -> pd.DataFrame:
        """从REST API读取数据

        Args:
            table_name: API端点路径
            columns: 要读取的字段列表
            limit: 读取的最大记录数
            offset: 读取的起始偏移量
            filters: 查询参数

        Returns:
            包含数据的pandas DataFrame

        Raises:
            MetadataCrawlError: 当读取数据失败时抛出
        """
        try:
            if not self.is_connected():
                self.connect()

            assert self._session is not None
            self._update_access_stats()

            params: Dict[str, Any] = filters or {}
            if self.pagination_param:
                if limit is not None:
                    params[f"{self.pagination_param}_limit"] = limit
                if offset is not None:
                    params[f"{self.pagination_param}_offset"] = offset

            url = self._build_url(table_name)
            all_records: List[Dict[str, Any]] = []

            while True:
                response = self._session.get(
                    url,
                    params=params,
                    timeout=self.timeout,
                )
                response.raise_for_status()
                data = self._extract_data(response.json())
                all_records.extend(data)

                if limit is not None and len(all_records) >= limit:
                    all_records = all_records[:limit]
                    break

                if not data or not self.pagination_param:
                    break

                offset_val = (offset or 0) + len(data)
                params[f"{self.pagination_param}_offset"] = offset_val

            df = pd.DataFrame(all_records)
            if columns and not df.empty:
                available_cols = [c for c in columns if c in df.columns]
                if available_cols:
                    df = df[available_cols]

            return df
        except Exception as e:
            raise MetadataCrawlError(f"Failed to read from REST API: {e}") from e

    def get_row_count(self, table_name: str) -> int:
        """获取API端点的记录数

        Args:
            table_name: API端点路径

        Returns:
            记录数
        """
        df = self.read_data(table_name)
        return len(df)

    def post(self, endpoint: str, data: Dict[str, Any]) -> Dict[str, Any]:
        """发送POST请求

        Args:
            endpoint: API端点
            data: 请求数据

        Returns:
            响应JSON

        Raises:
            MetadataCrawlError: 当请求失败时抛出
        """
        try:
            if not self.is_connected():
                self.connect()

            assert self._session is not None
            self._update_access_stats()

            url = self._build_url(endpoint)
            response = self._session.post(url, json=data, timeout=self.timeout)
            response.raise_for_status()
            return response.json()
        except Exception as e:
            raise MetadataCrawlError(f"POST request failed: {e}") from e
