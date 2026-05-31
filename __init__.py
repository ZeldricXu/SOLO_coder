"""
数据基础设施核心模块包
包含流式SQL解析、向量索引、数据生命周期管理、元数据采集、
数据质量校验、时序数据压缩、CDC增量捕获、数据血缘解析等核心功能
"""

__version__ = "1.0.0"
__all__ = [
    "streaming_query",
    "vector_index",
    "lifecycle_manager",
    "metadata_crawler",
    "data_quality",
    "timeseries_compression",
    "cdc_capture",
    "data_lineage",
]
