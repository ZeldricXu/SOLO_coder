"""
自定义异常类
"""


class DataInfraError(Exception):
    """基础异常类"""
    pass


class SQLParseError(DataInfraError):
    """SQL解析错误"""
    pass


class LogicalPlanError(DataInfraError):
    """逻辑计划错误"""
    pass


class PhysicalPlanError(DataInfraError):
    """物理计划错误"""
    pass


class IndexBuildError(DataInfraError):
    """索引构建错误"""
    pass


class SearchError(DataInfraError):
    """检索错误"""
    pass


class LifecycleError(DataInfraError):
    """生命周期管理错误"""
    pass


class MetadataCrawlError(DataInfraError):
    """元数据采集错误"""
    pass


class QualityCheckError(DataInfraError):
    """质量校验错误"""
    pass


class CompressionError(DataInfraError):
    """压缩错误"""
    pass


class CDCError(DataInfraError):
    """CDC捕获错误"""
    pass


class LineageError(DataInfraError):
    """血缘解析错误"""
    pass


class SchemaMismatchError(DataInfraError):
    """Schema不匹配错误"""
    pass


class ConnectionError(DataInfraError):
    """连接错误"""
    pass


class ConfigurationError(DataInfraError):
    """配置错误"""
    pass
