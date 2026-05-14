import logging
import time
import uuid
from typing import Optional, List, Dict, Any
from datetime import datetime
from pydantic import BaseModel, Field

from fastapi import APIRouter, HTTPException, Query, Body
from fastapi.responses import JSONResponse

from searchengine.models.base import (
    SearchRequest,
    SearchResult,
    SearchResultItem,
    IndexUpdateRequest,
    RecommendRequest,
    RecommendResult,
    ApiResponse,
    SortStrategy
)
from searchengine.modules.index_manager import index_manager
from searchengine.modules.query_processor import query_processor
from searchengine.modules.sort_module import sort_module
from searchengine.modules.recommend_module import recommend_module
from searchengine.modules.stats_module import stats_module
from searchengine.modules.log_module import log_module
from searchengine.modules.keyword_module import keyword_module
from searchengine.modules.cache_module import cache_module
from searchengine.modules.performance_monitor import performance_monitor
from searchengine.modules.cache_invalidator import cache_invalidator
from searchengine.modules.recommend_queue import task_manager, TaskStatus, RecommendTask
from searchengine.modules.sort_strategy_config import StrategyConfig, ScorerConfig

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/api/v1/search", tags=["search"])
admin_router = APIRouter(prefix="/api/v1/admin", tags=["admin"])


class ScorerConfigRequest(BaseModel):
    name: str
    weight: float = 1.0
    description: str = ""
    params: Dict[str, Any] = Field(default_factory=dict)


class StrategyConfigRequest(BaseModel):
    strategy_id: str
    name: str = ""
    description: str = ""
    enabled: bool = True
    scorers: List[ScorerConfigRequest] = Field(default_factory=list)


class RecommendTaskRequest(BaseModel):
    user_id: Optional[str] = None
    content_id: Optional[str] = None
    recommend_type: str = "related"
    limit: int = 10
    priority: int = 5


def _generate_request_id() -> str:
    return f"req_{uuid.uuid4().hex[:12]}"


def _generate_result_id() -> str:
    return f"result_{uuid.uuid4().hex[:12]}"


@router.post("/query", response_model=ApiResponse)
async def search_query(request: SearchRequest):
    start_time = time.time()
    
    try:
        if not request.request_id:
            request.request_id = _generate_request_id()
        
        cache_key = query_processor.build_cache_key(request)
        cached_result = cache_module.get(cache_key)
        
        if cached_result:
            search_duration = int((time.time() - start_time) * 1000)
            cached_result["from_cache"] = True
            cached_result["search_duration"] = search_duration
            
            performance_monitor.record_search(search_duration, from_cache=True)
            stats_module.increment_search_count()
            stats_module.update_search_time(search_duration)
            keyword_module.record_search_keyword(request.keyword)
            log_module.log_search(
                request_id=request.request_id,
                user_id=request.user_id,
                keyword=request.keyword,
                result_count=len(cached_result.get("results", [])),
                search_duration=search_duration
            )
            
            return ApiResponse(
                code=200,
                message="success (from cache)",
                data=cached_result
            )
        
        parsed_query = query_processor.parse_request(request)
        
        if not query_processor.validate_filters(request.filters):
            raise HTTPException(status_code=400, detail="Invalid filter parameters")
        
        candidate_indexes = index_manager.search_indexes(
            keyword=parsed_query["keyword"],
            filters=parsed_query["filters"]
        )
        
        if not candidate_indexes:
            empty_result = SearchResult(
                result_id=_generate_result_id(),
                request_id=request.request_id,
                results=[],
                total_count=0,
                page_count=0,
                search_duration=int((time.time() - start_time) * 1000)
            )
            
            search_duration = int((time.time() - start_time) * 1000)
            performance_monitor.record_search(search_duration, from_cache=False)
            stats_module.increment_search_count()
            stats_module.update_search_time(search_duration)
            keyword_module.record_search_keyword(request.keyword)
            log_module.log_search(
                request_id=request.request_id,
                user_id=request.user_id,
                keyword=request.keyword,
                result_count=0,
                search_duration=search_duration
            )
            
            return ApiResponse(
                code=200,
                message="success (no results)",
                data=empty_result.model_dump()
            )
        
        sorted_results = sort_module.sort_results(
            indexes=candidate_indexes,
            sort_type=request.sort_type,
            search_keywords=parsed_query["keywords_tokens"]
        )
        
        total_count = len(sorted_results)
        page_size = request.page_size
        page = request.page
        total_pages = (total_count + page_size - 1) // page_size
        
        start_idx = (page - 1) * page_size
        end_idx = start_idx + page_size
        paginated_results = sorted_results[start_idx:end_idx]
        
        search_duration = int((time.time() - start_time) * 1000)
        
        search_result = SearchResult(
            result_id=_generate_result_id(),
            request_id=request.request_id,
            results=paginated_results,
            total_count=total_count,
            page_count=total_pages,
            search_duration=search_duration,
            from_cache=False
        )
        
        cache_module.set(cache_key, search_result.model_dump())
        
        performance_monitor.record_search(search_duration, from_cache=False)
        stats_module.increment_search_count()
        stats_module.update_search_time(search_duration)
        stats_module.update_hot_keyword(request.keyword)
        keyword_module.record_search_keyword(request.keyword)
        log_module.log_search(
            request_id=request.request_id,
            user_id=request.user_id,
            keyword=request.keyword,
            result_count=total_count,
            search_duration=search_duration
        )
        
        return ApiResponse(
            code=200,
            message="success",
            data=search_result.model_dump()
        )
    
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Search query error: {e}")
        performance_monitor.record_error(type(e).__name__, str(e))
        raise HTTPException(status_code=500, detail=f"Search failed: {str(e)}")


@router.post("/index", response_model=ApiResponse)
async def update_index(request: IndexUpdateRequest):
    try:
        if not request.content_id or not request.title or not request.content:
            raise HTTPException(status_code=400, detail="content_id, title, and content are required")
        
        if not request.keywords:
            request.keywords = keyword_module.extract_keywords_from_content(
                content=request.content,
                title=request.title
            )
        
        search_index = index_manager.create_index(request)
        
        cache_module.delete_pattern("search:query:*")
        
        return ApiResponse(
            code=200,
            message="Index updated successfully",
            data={
                "index_id": search_index.index_id,
                "content_id": search_index.content_id,
                "index_time": search_index.index_time.isoformat()
            }
        )
    
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Index update error: {e}")
        performance_monitor.record_error(type(e).__name__, str(e))
        raise HTTPException(status_code=500, detail=f"Index update failed: {str(e)}")


@router.get("/recommend", response_model=ApiResponse)
async def get_recommendations(
    user_id: Optional[str] = Query(default=None, description="User ID"),
    content_id: Optional[str] = Query(default=None, description="Reference content ID"),
    recommend_type: str = Query(default="related", description="Recommendation type: related, hot, personalized"),
    limit: int = Query(default=10, ge=1, le=50, description="Number of recommendations")
):
    try:
        request = RecommendRequest(
            user_id=user_id,
            content_id=content_id,
            recommend_type=recommend_type,
            limit=limit
        )
        
        recommendations = recommend_module.generate_recommendations(request)
        
        return ApiResponse(
            code=200,
            message="success",
            data=recommendations.model_dump()
        )
    
    except Exception as e:
        logger.error(f"Recommendation error: {e}")
        performance_monitor.record_error(type(e).__name__, str(e))
        raise HTTPException(status_code=500, detail=f"Recommendation failed: {str(e)}")


@router.post("/index/batch", response_model=ApiResponse)
async def batch_update_index(requests: list[IndexUpdateRequest]):
    try:
        results = []
        for request in requests:
            if not request.keywords:
                request.keywords = keyword_module.extract_keywords_from_content(
                    content=request.content,
                    title=request.title
                )
            search_index = index_manager.create_index(request)
            results.append({
                "index_id": search_index.index_id,
                "content_id": search_index.content_id
            })
        
        cache_module.delete_pattern("search:query:*")
        
        return ApiResponse(
            code=200,
            message=f"Batch updated {len(results)} indexes",
            data={"results": results}
        )
    
    except Exception as e:
        logger.error(f"Batch index update error: {e}")
        performance_monitor.record_error(type(e).__name__, str(e))
        raise HTTPException(status_code=500, detail=f"Batch index update failed: {str(e)}")


@router.delete("/index/{content_id}", response_model=ApiResponse)
async def delete_index(content_id: str):
    try:
        success = index_manager.delete_index(content_id)
        
        if success:
            cache_module.delete_pattern("search:query:*")
            return ApiResponse(
                code=200,
                message="Index deleted successfully",
                data={"content_id": content_id}
            )
        else:
            raise HTTPException(status_code=404, detail=f"Index not found for content_id: {content_id}")
    
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Index delete error: {e}")
        performance_monitor.record_error(type(e).__name__, str(e))
        raise HTTPException(status_code=500, detail=f"Index delete failed: {str(e)}")


@router.get("/stats", response_model=ApiResponse)
async def get_statistics():
    try:
        today_stats = stats_module.get_today_stats()
        total_search_count = stats_module.get_total_search_count()
        total_click_count = stats_module.get_total_click_count()
        avg_search_time = stats_module.get_overall_avg_search_time()
        hot_keywords = stats_module.get_hot_keywords(20)
        
        return ApiResponse(
            code=200,
            message="success",
            data={
                "today": today_stats.model_dump(),
                "total_search_count": total_search_count,
                "total_click_count": total_click_count,
                "overall_avg_search_time": avg_search_time,
                "hot_keywords": hot_keywords,
                "index_count": index_manager.get_index_count()
            }
        )
    
    except Exception as e:
        logger.error(f"Stats retrieval error: {e}")
        raise HTTPException(status_code=500, detail=f"Failed to get stats: {str(e)}")


@router.get("/performance", response_model=ApiResponse)
async def get_performance():
    try:
        summary = performance_monitor.get_metrics_summary()
        health = performance_monitor.get_health_status()
        cache_stats = cache_module.get_stats()
        
        return ApiResponse(
            code=200,
            message="success",
            data={
                "metrics": summary,
                "health": health,
                "cache": cache_stats
            }
        )
    
    except Exception as e:
        logger.error(f"Performance retrieval error: {e}")
        raise HTTPException(status_code=500, detail=f"Failed to get performance: {str(e)}")


@router.get("/keywords/hot", response_model=ApiResponse)
async def get_hot_keywords(top_n: int = Query(default=20, ge=1, le=100)):
    try:
        hot_keywords = keyword_module.get_hot_keywords(top_n)
        return ApiResponse(
            code=200,
            message="success",
            data={"hot_keywords": hot_keywords}
        )
    
    except Exception as e:
        logger.error(f"Hot keywords retrieval error: {e}")
        raise HTTPException(status_code=500, detail=f"Failed to get hot keywords: {str(e)}")


@router.post("/keywords/analyze", response_model=ApiResponse)
async def analyze_keywords(text: str):
    try:
        analysis = keyword_module.analyze_keyword(text)
        return ApiResponse(
            code=200,
            message="success",
            data=analysis
        )
    
    except Exception as e:
        logger.error(f"Keyword analysis error: {e}")
        raise HTTPException(status_code=500, detail=f"Keyword analysis failed: {str(e)}")


@router.get("/logs", response_model=ApiResponse)
async def get_logs(
    limit: int = Query(default=100, ge=1, le=1000),
    user_id: Optional[str] = Query(default=None)
):
    try:
        if user_id:
            logs = log_module.get_logs_by_user(user_id, limit)
        else:
            logs = log_module.get_latest_logs(limit)
        
        return ApiResponse(
            code=200,
            message="success",
            data={
                "logs": [log.model_dump() for log in logs],
                "count": len(logs)
            }
        )
    
    except Exception as e:
        logger.error(f"Logs retrieval error: {e}")
        raise HTTPException(status_code=500, detail=f"Failed to get logs: {str(e)}")


@router.get("/indexes", response_model=ApiResponse)
async def list_indexes(
    page: int = Query(default=1, ge=1),
    page_size: int = Query(default=20, ge=1, le=100)
):
    try:
        all_indexes = index_manager.get_all_indexes()
        total_count = len(all_indexes)
        
        start_idx = (page - 1) * page_size
        end_idx = start_idx + page_size
        paginated = all_indexes[start_idx:end_idx]
        
        return ApiResponse(
            code=200,
            message="success",
            data={
                "indexes": [idx.model_dump() for idx in paginated],
                "total_count": total_count,
                "page": page,
                "page_size": page_size,
                "page_count": (total_count + page_size - 1) // page_size
            }
        )
    
    except Exception as e:
        logger.error(f"Index list error: {e}")
        raise HTTPException(status_code=500, detail=f"Failed to list indexes: {str(e)}")


@router.get("/indexes/{content_id}", response_model=ApiResponse)
async def get_index(content_id: str):
    try:
        index = index_manager.get_index_by_content_id(content_id)
        if not index:
            raise HTTPException(status_code=404, detail=f"Index not found: {content_id}")
        
        return ApiResponse(
            code=200,
            message="success",
            data=index.model_dump()
        )
    
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Get index error: {e}")
        raise HTTPException(status_code=500, detail=f"Failed to get index: {str(e)}")


@router.get("/strategies", response_model=ApiResponse)
async def list_sort_strategies():
    try:
        strategies = sort_module.list_strategies()
        return ApiResponse(
            code=200,
            message="success",
            data={
                "strategies": [s.model_dump() for s in strategies]
            }
        )
    
    except Exception as e:
        logger.error(f"List strategies error: {e}")
        raise HTTPException(status_code=500, detail=f"Failed to list strategies: {str(e)}")


@router.post("/strategies", response_model=ApiResponse)
async def add_sort_strategy(strategy: SortStrategy):
    try:
        success = sort_module.add_strategy(strategy)
        return ApiResponse(
            code=200,
            message="Strategy added successfully",
            data={"strategy_id": strategy.strategy_id}
        )
    
    except Exception as e:
        logger.error(f"Add strategy error: {e}")
        raise HTTPException(status_code=500, detail=f"Failed to add strategy: {str(e)}")


@router.post("/click", response_model=ApiResponse)
async def record_click(request_id: str, content_id: str):
    try:
        log = log_module.log_click(request_id, content_id)
        if log:
            index_manager.increment_click_count(content_id)
            stats_module.increment_click_count()
            keyword_module.record_click_keyword(log.keyword)
            
            return ApiResponse(
                code=200,
                message="Click recorded successfully",
                data={"log_id": log.log_id}
            )
        else:
            raise HTTPException(status_code=404, detail=f"Request not found: {request_id}")
    
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Record click error: {e}")
        raise HTTPException(status_code=500, detail=f"Failed to record click: {str(e)}")


@router.post("/cache/clear", response_model=ApiResponse)
async def clear_cache():
    try:
        count = cache_module.clear()
        return ApiResponse(
            code=200,
            message="Cache cleared successfully",
            data={"cleared_count": count}
        )
    
    except Exception as e:
        logger.error(f"Clear cache error: {e}")
        raise HTTPException(status_code=500, detail=f"Failed to clear cache: {str(e)}")


@router.get("/health", response_model=ApiResponse)
async def health_check():
    try:
        health_status = performance_monitor.get_health_status()
        return ApiResponse(
            code=200,
            message="success",
            data={
                "status": "healthy" if health_status["healthy"] else "degraded",
                "issues": health_status["issues"],
                "index_count": index_manager.get_index_count(),
                "log_count": log_module.get_log_count(),
                "cache_stats": cache_module.get_stats()
            }
        )
    
    except Exception as e:
        logger.error(f"Health check error: {e}")
        return ApiResponse(
            code=500,
            message="error",
            data={"error": str(e)}
        )


@admin_router.get("/index/versions", response_model=ApiResponse)
async def get_index_versions(
    content_id: Optional[str] = Query(default=None, description="Filter by content_id"),
    limit: int = Query(default=10, ge=1, le=100, description="Maximum versions to return")
):
    try:
        versions = index_manager.export_version_history()
        
        if content_id:
            versions = [v for v in versions if v.get("content_id") == content_id]
        
        versions = versions[-limit:]
        
        return ApiResponse(
            code=200,
            message="success",
            data={
                "versions": versions,
                "count": len(versions)
            }
        )
    except Exception as e:
        logger.error(f"Get index versions error: {e}")
        raise HTTPException(status_code=500, detail=f"Failed to get versions: {str(e)}")


@admin_router.get("/index/version/{version}", response_model=ApiResponse)
async def get_index_by_version(version: int):
    try:
        version_info = index_manager.get_version(version)
        if not version_info:
            raise HTTPException(status_code=404, detail=f"Version {version} not found")
        
        return ApiResponse(
            code=200,
            message="success",
            data={
                "version": version,
                "info": version_info
            }
        )
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Get index version error: {e}")
        raise HTTPException(status_code=500, detail=f"Failed to get version: {str(e)}")


@admin_router.get("/index/stats", response_model=ApiResponse)
async def get_index_update_stats():
    try:
        stats = index_manager.get_update_stats()
        return ApiResponse(
            code=200,
            message="success",
            data=stats
        )
    except Exception as e:
        logger.error(f"Get index stats error: {e}")
        raise HTTPException(status_code=500, detail=f"Failed to get stats: {str(e)}")


@admin_router.post("/cache/invalidate", response_model=ApiResponse)
async def invalidate_cache(
    keys: Optional[List[str]] = Body(default=None, description="Specific keys to invalidate"),
    pattern: Optional[str] = Body(default=None, description="Pattern to match keys")
):
    try:
        if keys:
            count = cache_invalidator.invalidate_specific_keys(keys)
            return ApiResponse(
                code=200,
                message=f"Invalidated {count} keys",
                data={"invalidated_count": count, "keys": keys}
            )
        elif pattern:
            count = cache_module.delete_pattern(pattern)
            return ApiResponse(
                code=200,
                message=f"Invalidated {count} keys with pattern",
                data={"invalidated_count": count, "pattern": pattern}
            )
        else:
            count = cache_invalidator.invalidate_all()
            return ApiResponse(
                code=200,
                message=f"Cleared all cache ({count} items)",
                data={"invalidated_count": count}
            )
    except Exception as e:
        logger.error(f"Cache invalidation error: {e}")
        raise HTTPException(status_code=500, detail=f"Failed to invalidate cache: {str(e)}")


@admin_router.get("/cache/stats", response_model=ApiResponse)
async def get_cache_invalidation_stats():
    try:
        cache_stats = cache_module.get_stats()
        invalidation_stats = cache_invalidator.get_stats()
        
        return ApiResponse(
            code=200,
            message="success",
            data={
                "cache": cache_stats,
                "invalidation": invalidation_stats
            }
        )
    except Exception as e:
        logger.error(f"Get cache stats error: {e}")
        raise HTTPException(status_code=500, detail=f"Failed to get cache stats: {str(e)}")


@admin_router.post("/cache/toggle", response_model=ApiResponse)
async def toggle_cache_invalidation(enable: bool = Body(..., description="Enable or disable invalidation")):
    try:
        if enable:
            cache_invalidator.enable()
        else:
            cache_invalidator.disable()
        
        return ApiResponse(
            code=200,
            message=f"Cache invalidation {'enabled' if enable else 'disabled'}",
            data={"enabled": enable}
        )
    except Exception as e:
        logger.error(f"Toggle cache invalidation error: {e}")
        raise HTTPException(status_code=500, detail=f"Failed to toggle: {str(e)}")


@admin_router.post("/recommend/task", response_model=ApiResponse)
async def create_recommend_task(request: RecommendTaskRequest):
    try:
        if not request.user_id and not request.content_id:
            raise HTTPException(status_code=400, detail="Either user_id or content_id is required")
        
        task_id = task_manager.create_task(
            user_id=request.user_id,
            content_id=request.content_id,
            recommend_type=request.recommend_type,
            limit=request.limit,
            priority=request.priority
        )
        
        return ApiResponse(
            code=200,
            message="Task created",
            data={
                "task_id": task_id,
                "status": "pending"
            }
        )
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Create recommend task error: {e}")
        raise HTTPException(status_code=500, detail=f"Failed to create task: {str(e)}")


@admin_router.get("/recommend/task/{task_id}", response_model=ApiResponse)
async def get_recommend_task_status(task_id: str):
    try:
        status = task_manager.get_task_status(task_id)
        if not status:
            raise HTTPException(status_code=404, detail=f"Task {task_id} not found")
        
        return ApiResponse(
            code=200,
            message="success",
            data=status
        )
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Get recommend task error: {e}")
        raise HTTPException(status_code=500, detail=f"Failed to get task: {str(e)}")


@admin_router.get("/recommend/queue", response_model=ApiResponse)
async def get_recommend_queue_size():
    try:
        size = task_manager.get_queue_size()
        return ApiResponse(
            code=200,
            message="success",
            data={
                "queue_size": size
            }
        )
    except Exception as e:
        logger.error(f"Get queue size error: {e}")
        raise HTTPException(status_code=500, detail=f"Failed to get queue size: {str(e)}")


@admin_router.post("/recommend/worker/start", response_model=ApiResponse)
async def start_recommend_worker():
    try:
        task_manager.start_worker(recommend_module, cache_module)
        return ApiResponse(
            code=200,
            message="Worker started",
            data={"status": "running"}
        )
    except Exception as e:
        logger.error(f"Start worker error: {e}")
        raise HTTPException(status_code=500, detail=f"Failed to start worker: {str(e)}")


@admin_router.post("/recommend/worker/stop", response_model=ApiResponse)
async def stop_recommend_worker():
    try:
        task_manager.stop_worker()
        return ApiResponse(
            code=200,
            message="Worker stopped",
            data={"status": "stopped"}
        )
    except Exception as e:
        logger.error(f"Stop worker error: {e}")
        raise HTTPException(status_code=500, detail=f"Failed to stop worker: {str(e)}")


@admin_router.delete("/recommend/queue", response_model=ApiResponse)
async def clear_recommend_queue():
    try:
        count = task_manager.clear_queue()
        return ApiResponse(
            code=200,
            message=f"Queue cleared ({count} tasks)",
            data={"cleared_count": count}
        )
    except Exception as e:
        logger.error(f"Clear queue error: {e}")
        raise HTTPException(status_code=500, detail=f"Failed to clear queue: {str(e)}")


@admin_router.get("/sort/strategies", response_model=ApiResponse)
async def list_configured_strategies():
    try:
        strategies = sort_module.list_configured_strategies()
        default_strategy = sort_module.get_default_strategy()
        
        return ApiResponse(
            code=200,
            message="success",
            data={
                "strategies": strategies,
                "default_strategy": default_strategy
            }
        )
    except Exception as e:
        logger.error(f"List sort strategies error: {e}")
        raise HTTPException(status_code=500, detail=f"Failed to list strategies: {str(e)}")


@admin_router.get("/sort/strategy/{strategy_id}", response_model=ApiResponse)
async def get_configured_strategy(strategy_id: str):
    try:
        strategy = sort_module.get_configured_strategy(strategy_id)
        if not strategy:
            raise HTTPException(status_code=404, detail=f"Strategy {strategy_id} not found")
        
        return ApiResponse(
            code=200,
            message="success",
            data={
                "strategy_id": strategy.strategy_id,
                "name": strategy.name,
                "description": strategy.description,
                "enabled": strategy.enabled,
                "scorers": [s.to_dict() for s in strategy.scorers]
            }
        )
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Get sort strategy error: {e}")
        raise HTTPException(status_code=500, detail=f"Failed to get strategy: {str(e)}")


@admin_router.post("/sort/strategy", response_model=ApiResponse)
async def create_sort_strategy(request: StrategyConfigRequest):
    try:
        scorers = [s.model_dump() for s in request.scorers]
        
        success = sort_module.add_configured_strategy(
            strategy_id=request.strategy_id,
            name=request.name or request.strategy_id,
            description=request.description,
            scorers=scorers,
            enabled=request.enabled
        )
        
        if not success:
            raise HTTPException(status_code=409, detail=f"Strategy {request.strategy_id} already exists")
        
        return ApiResponse(
            code=200,
            message="Strategy created",
            data={"strategy_id": request.strategy_id}
        )
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Create sort strategy error: {e}")
        raise HTTPException(status_code=500, detail=f"Failed to create strategy: {str(e)}")


@admin_router.put("/sort/strategy/{strategy_id}", response_model=ApiResponse)
async def update_sort_strategy(strategy_id: str, request: StrategyConfigRequest):
    try:
        scorers = [s.model_dump() for s in request.scorers] if request.scorers else None
        
        success = sort_module.update_configured_strategy(
            strategy_id=strategy_id,
            name=request.name if request.name else None,
            description=request.description if request.description else None,
            scorers=scorers,
            enabled=request.enabled if request.enabled is not None else None
        )
        
        if not success:
            raise HTTPException(status_code=404, detail=f"Strategy {strategy_id} not found")
        
        return ApiResponse(
            code=200,
            message="Strategy updated",
            data={"strategy_id": strategy_id}
        )
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Update sort strategy error: {e}")
        raise HTTPException(status_code=500, detail=f"Failed to update strategy: {str(e)}")


@admin_router.delete("/sort/strategy/{strategy_id}", response_model=ApiResponse)
async def delete_sort_strategy(strategy_id: str):
    try:
        success = sort_module.delete_configured_strategy(strategy_id)
        if not success:
            raise HTTPException(status_code=404, detail=f"Strategy {strategy_id} not found or is default")
        
        return ApiResponse(
            code=200,
            message="Strategy deleted",
            data={"strategy_id": strategy_id}
        )
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Delete sort strategy error: {e}")
        raise HTTPException(status_code=500, detail=f"Failed to delete strategy: {str(e)}")


@admin_router.post("/sort/default/{strategy_id}", response_model=ApiResponse)
async def set_default_sort_strategy(strategy_id: str):
    try:
        success = sort_module.set_default_strategy(strategy_id)
        if not success:
            raise HTTPException(status_code=400, detail=f"Cannot set strategy {strategy_id} as default")
        
        return ApiResponse(
            code=200,
            message="Default strategy updated",
            data={"default_strategy": strategy_id}
        )
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Set default strategy error: {e}")
        raise HTTPException(status_code=500, detail=f"Failed to set default: {str(e)}")


@admin_router.post("/sort/reload", response_model=ApiResponse)
async def reload_sort_config():
    try:
        success = sort_module.reload_config()
        return ApiResponse(
            code=200,
            message="Config reloaded" if success else "Config reload failed",
            data={"reloaded": success}
        )
    except Exception as e:
        logger.error(f"Reload sort config error: {e}")
        raise HTTPException(status_code=500, detail=f"Failed to reload config: {str(e)}")
