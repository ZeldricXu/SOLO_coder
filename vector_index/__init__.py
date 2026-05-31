"""
vector_index - 向量索引模块
支持多种索引类型切换，提供完整的增删改查和持久化功能
"""
from typing import Dict, Any, Optional

from .index_base import (
    VectorIndexBase,
    SearchResult,
    IndexType,
    MetricType,
)
from .hnsw_index import HNSWIndex
from .ivf_index import IVFIndex
from .annoy_index import AnnoyVectorIndex
from .embedding import (
    EmbeddingProcessor,
    BaseEmbeddingProvider,
    EmbeddingModelType,
    OpenAIEmbeddingProvider,
    SentenceTransformerProvider,
    HuggingFaceEmbeddingProvider,
    CohereEmbeddingProvider,
    CustomEmbeddingProvider,
)
from .search_optimizer import (
    SearchOptimizer,
    QueryRewriter,
    HybridSearcher,
    Reranker,
    OptimizedSearchResult,
    RerankMethod,
    QueryRewriteStrategy,
)


__version__ = "1.0.0"


def create_index(
    index_type: IndexType,
    dimension: int,
    metric: MetricType = MetricType.COSINE,
    index_params: Optional[Dict[str, Any]] = None,
) -> VectorIndexBase:
    """
    工厂函数：根据索引类型创建对应的索引实例

    Args:
        index_type: 索引类型 (HNSW, IVF, ANNOY)
        dimension: 向量维度
        metric: 距离度量类型
        index_params: 索引参数

    Returns:
        VectorIndexBase: 索引实例

    Raises:
        ValueError: 不支持的索引类型
    """
    if index_type == IndexType.HNSW:
        return HNSWIndex(dimension, metric, index_params)
    elif index_type == IndexType.IVF:
        return IVFIndex(dimension, metric, index_params)
    elif index_type == IndexType.ANNOY:
        return AnnoyVectorIndex(dimension, metric, index_params)
    else:
        raise ValueError(f"Unsupported index type: {index_type}")


__all__ = [
    "VectorIndexBase",
    "SearchResult",
    "IndexType",
    "MetricType",
    "HNSWIndex",
    "IVFIndex",
    "AnnoyVectorIndex",
    "EmbeddingProcessor",
    "BaseEmbeddingProvider",
    "EmbeddingModelType",
    "OpenAIEmbeddingProvider",
    "SentenceTransformerProvider",
    "HuggingFaceEmbeddingProvider",
    "CohereEmbeddingProvider",
    "CustomEmbeddingProvider",
    "SearchOptimizer",
    "QueryRewriter",
    "HybridSearcher",
    "Reranker",
    "OptimizedSearchResult",
    "RerankMethod",
    "QueryRewriteStrategy",
    "create_index",
]
