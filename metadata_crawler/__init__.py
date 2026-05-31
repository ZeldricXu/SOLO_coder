"""
元数据爬虫模块
提供数据源抽象、Schema提取、统计信息采集、样例数据采集和元数据爬虫功能
"""

from .data_source import (
    DataSource,
    RelationalDataSource,
    CSVDataSource,
    ParquetDataSource,
    RESTDataSource,
    DataSourceType,
    ConnectionConfig,
    SQLAlchemyConnectionManager,
)
from .schema_extractor import (
    SchemaExtractor,
    MySQLSchemaExtractor,
    PostgreSQLSchemaExtractor,
    SQLiteSchemaExtractor,
    TableSchema,
    ColumnSchema,
    IndexSchema,
    ForeignKeySchema,
    ConstraintType,
    IndexType,
)
from .stats_collector import (
    StatsCollector,
    ColumnStatistics,
    TableStatistics,
    Histogram,
    QuantileStatistics,
    CorrelationResult,
)
from .sample_collector import (
    SampleCollector,
    SampleMethod,
    SampleConfig,
    SampleResult,
)
from .crawler import (
    MetadataCrawler,
    CrawlConfig,
    CrawlTask,
    CrawlStatus,
    IncrementalConfig,
    MetadataStorage,
    CrawlScheduler,
)

__all__ = [
    "DataSource",
    "RelationalDataSource",
    "CSVDataSource",
    "ParquetDataSource",
    "RESTDataSource",
    "DataSourceType",
    "ConnectionConfig",
    "SQLAlchemyConnectionManager",
    "SchemaExtractor",
    "MySQLSchemaExtractor",
    "PostgreSQLSchemaExtractor",
    "SQLiteSchemaExtractor",
    "TableSchema",
    "ColumnSchema",
    "IndexSchema",
    "ForeignKeySchema",
    "ConstraintType",
    "IndexType",
    "StatsCollector",
    "ColumnStatistics",
    "TableStatistics",
    "Histogram",
    "QuantileStatistics",
    "CorrelationResult",
    "SampleCollector",
    "SampleMethod",
    "SampleConfig",
    "SampleResult",
    "MetadataCrawler",
    "CrawlConfig",
    "CrawlTask",
    "CrawlStatus",
    "IncrementalConfig",
    "MetadataStorage",
    "CrawlScheduler",
]

__version__ = "1.0.0"
