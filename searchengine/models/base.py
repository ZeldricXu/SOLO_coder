from pydantic import BaseModel, Field
from typing import Optional, List, Dict, Any
from datetime import datetime


class SearchIndex(BaseModel):
    index_id: str = Field(..., description="索引唯一编号")
    content_id: str = Field(..., description="内容标识")
    content_type: str = Field(default="article", description="内容类型")
    title: str = Field(..., description="内容标题")
    content: str = Field(..., description="内容正文")
    keywords: List[str] = Field(default_factory=list, description="关键词列表")
    category: Optional[str] = Field(default=None, description="内容分类")
    author: Optional[str] = Field(default=None, description="作者")
    publish_time: Optional[datetime] = Field(default=None, description="发布时间")
    click_count: int = Field(default=0, description="点击次数")
    index_time: datetime = Field(default_factory=datetime.utcnow, description="索引时间")

    class Config:
        json_schema_extra = {
            "example": {
                "index_id": "index_content_001",
                "content_id": "content_001",
                "content_type": "article",
                "title": "技术文章标题",
                "content": "文章内容摘要...",
                "keywords": ["技术", "编程"],
                "category": "技术",
                "author": "author_001",
                "publish_time": "2026-05-04T21:00:00Z",
                "click_count": 100,
                "index_time": "2026-05-04T21:00:00Z"
            }
        }


class SearchRequest(BaseModel):
    request_id: Optional[str] = Field(default=None, description="请求唯一编号")
    user_id: Optional[str] = Field(default=None, description="用户标识")
    keyword: str = Field(..., description="搜索关键字")
    filters: Dict[str, Any] = Field(default_factory=dict, description="过滤条件")
    sort_type: str = Field(default="relevance", description="排序类型")
    page: int = Field(default=1, ge=1, description="页码")
    page_size: int = Field(default=10, ge=1, le=100, description="每页大小")
    search_time: datetime = Field(default_factory=datetime.utcnow, description="搜索时间")

    class Config:
        json_schema_extra = {
            "example": {
                "request_id": "req_001",
                "user_id": "user_10086",
                "keyword": "编程技术",
                "filters": {"category": "技术", "author": "author_001"},
                "sort_type": "relevance",
                "page": 1,
                "page_size": 10,
                "search_time": "2026-05-04T21:10:00Z"
            }
        }


class SearchResultItem(BaseModel):
    content_id: str = Field(..., description="内容标识")
    title: str = Field(..., description="内容标题")
    relevance: float = Field(..., description="相关性分数")
    position: int = Field(..., description="排名位置")
    category: Optional[str] = Field(default=None, description="内容分类")
    author: Optional[str] = Field(default=None, description="作者")
    publish_time: Optional[datetime] = Field(default=None, description="发布时间")
    click_count: int = Field(default=0, description="点击次数")


class SearchResult(BaseModel):
    result_id: str = Field(..., description="结果唯一编号")
    request_id: str = Field(..., description="请求唯一编号")
    results: List[SearchResultItem] = Field(default_factory=list, description="搜索结果列表")
    total_count: int = Field(default=0, description="总记录数")
    page_count: int = Field(default=0, description="总页数")
    search_duration: int = Field(default=0, description="搜索耗时(毫秒)")
    from_cache: bool = Field(default=False, description="是否来自缓存")

    class Config:
        json_schema_extra = {
            "example": {
                "result_id": "result_001",
                "request_id": "req_001",
                "results": [
                    {"content_id": "content_001", "title": "文章标题", "relevance": 0.95, "position": 1}
                ],
                "total_count": 50,
                "page_count": 5,
                "search_duration": 50
            }
        }


class SortStrategy(BaseModel):
    strategy_id: str = Field(..., description="策略唯一编号")
    strategy_name: str = Field(..., description="策略名称")
    strategy_type: str = Field(..., description="策略类型")
    strategy_config: Dict[str, Any] = Field(default_factory=dict, description="策略配置")
    enabled: bool = Field(default=True, description="是否启用")

    class Config:
        json_schema_extra = {
            "example": {
                "strategy_id": "strategy_relevance",
                "strategy_name": "相关性排序",
                "strategy_type": "relevance",
                "strategy_config": {
                    "weight_title": 0.4,
                    "weight_content": 0.3,
                    "weight_click": 0.2,
                    "weight_time": 0.1
                },
                "enabled": True
            }
        }


class RecommendItem(BaseModel):
    content_id: str = Field(..., description="内容标识")
    title: str = Field(..., description="内容标题")
    recommend_score: float = Field(..., description="推荐分数")


class RecommendResult(BaseModel):
    recommend_id: str = Field(..., description="推荐唯一编号")
    user_id: Optional[str] = Field(default=None, description="用户标识")
    content_id: Optional[str] = Field(default=None, description="参考内容标识")
    recommend_type: str = Field(..., description="推荐类型")
    recommend_items: List[RecommendItem] = Field(default_factory=list, description="推荐列表")
    generated_at: datetime = Field(default_factory=datetime.utcnow, description="生成时间")

    class Config:
        json_schema_extra = {
            "example": {
                "recommend_id": "recommend_001",
                "user_id": "user_10086",
                "recommend_type": "related",
                "recommend_items": [
                    {"content_id": "content_002", "title": "推荐文章", "recommend_score": 0.8}
                ],
                "generated_at": "2026-05-04T21:10:00Z"
            }
        }


class SearchStats(BaseModel):
    stat_id: str = Field(..., description="统计唯一编号")
    stat_date: str = Field(..., description="统计日期(YYYY-MM-DD)")
    search_count: int = Field(default=0, description="搜索次数")
    click_count: int = Field(default=0, description="点击次数")
    avg_search_time: float = Field(default=0.0, description="平均搜索时间")
    hot_keywords: List[Dict[str, Any]] = Field(default_factory=list, description="热门关键词")

    class Config:
        json_schema_extra = {
            "example": {
                "stat_id": "stat_001",
                "stat_date": "2026-05-04",
                "search_count": 1000,
                "click_count": 500,
                "avg_search_time": 50,
                "hot_keywords": [{"keyword": "编程", "count": 200}]
            }
        }


class SearchLog(BaseModel):
    log_id: str = Field(..., description="日志唯一编号")
    request_id: str = Field(..., description="请求唯一编号")
    user_id: Optional[str] = Field(default=None, description="用户标识")
    keyword: str = Field(..., description="搜索关键字")
    result_count: int = Field(default=0, description="结果数量")
    click_result: Optional[str] = Field(default=None, description="点击的结果")
    search_time: datetime = Field(default_factory=datetime.utcnow, description="搜索时间")
    search_duration: int = Field(default=0, description="搜索耗时")

    class Config:
        json_schema_extra = {
            "example": {
                "log_id": "log_001",
                "request_id": "req_001",
                "user_id": "user_10086",
                "keyword": "编程技术",
                "result_count": 10,
                "click_result": None,
                "search_time": "2026-05-04T21:10:00Z",
                "search_duration": 50
            }
        }


class IndexUpdateRequest(BaseModel):
    content_id: str = Field(..., description="内容标识")
    content_type: str = Field(default="article", description="内容类型")
    title: str = Field(..., description="内容标题")
    content: str = Field(..., description="内容正文")
    keywords: List[str] = Field(default_factory=list, description="关键词列表")
    category: Optional[str] = Field(default=None, description="内容分类")
    author: Optional[str] = Field(default=None, description="作者")
    publish_time: Optional[datetime] = Field(default=None, description="发布时间")

    class Config:
        json_schema_extra = {
            "example": {
                "content_id": "content_001",
                "content_type": "article",
                "title": "文章标题",
                "content": "文章内容",
                "keywords": ["技术"],
                "category": "技术",
                "author": "author_001",
                "publish_time": "2026-05-04T21:00:00Z"
            }
        }


class RecommendRequest(BaseModel):
    user_id: Optional[str] = Field(default=None, description="用户标识")
    content_id: Optional[str] = Field(default=None, description="参考内容标识")
    recommend_type: str = Field(default="related", description="推荐类型")
    limit: int = Field(default=10, ge=1, le=50, description="推荐数量")

    class Config:
        json_schema_extra = {
            "example": {
                "user_id": "user_10086",
                "content_id": "content_001",
                "recommend_type": "related",
                "limit": 10
            }
        }


class ApiResponse(BaseModel):
    code: int = Field(default=200, description="响应状态码")
    message: str = Field(default="success", description="响应消息")
    data: Optional[Any] = Field(default=None, description="响应数据")

    class Config:
        json_schema_extra = {
            "example": {
                "code": 200,
                "message": "success",
                "data": {"results": []}
            }
        }


class PerformanceMetrics(BaseModel):
    metric_id: str = Field(..., description="指标唯一编号")
    timestamp: datetime = Field(default_factory=datetime.utcnow, description="时间戳")
    total_requests: int = Field(default=0, description="总请求数")
    total_search_time: float = Field(default=0.0, description="总搜索时间")
    avg_search_time: float = Field(default=0.0, description="平均搜索时间")
    max_search_time: float = Field(default=0.0, description="最大搜索时间")
    min_search_time: float = Field(default=float('inf'), description="最小搜索时间")
    cache_hits: int = Field(default=0, description="缓存命中次数")
    cache_misses: int = Field(default=0, description="缓存未命中次数")
    cache_hit_rate: float = Field(default=0.0, description="缓存命中率")
    error_count: int = Field(default=0, description="错误次数")
