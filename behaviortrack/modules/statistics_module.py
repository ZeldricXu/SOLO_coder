import logging
from datetime import datetime, timezone, date, timedelta
from typing import Any, Dict, List, Optional

from ..config import settings
from ..storage import MongoStorage
from .queue import (
    StatisticsCache, 
    TimeWindowManager,
    AnalysisTaskQueue,
    QueueProcessor
)


logger = logging.getLogger(__name__)


class StatisticsModule:
    def __init__(
        self,
        cache_ttl_seconds: int = 300,
        use_cache: bool = True,
        time_window_seconds: Optional[int] = None
    ) -> None:
        self.storage = MongoStorage()
        self._use_cache = use_cache
        self._cache = StatisticsCache(default_ttl_seconds=cache_ttl_seconds)
        
        self._incremental_stats: Dict[str, Dict[str, Any]] = {}
        self._window_seconds = time_window_seconds or settings.TIME_WINDOW_SECONDS
        self._time_window_manager = TimeWindowManager(self._window_seconds)
        self._last_window_rollover_check: Optional[datetime] = None
        
        self._analysis_task_queue: Optional[AnalysisTaskQueue] = None
        self._task_processor_registered = False
    
    def _ensure_task_processor(self) -> None:
        if self._task_processor_registered:
            return
        
        def process_tasks(tasks: List[Any]) -> Dict[str, Any]:
            results = []
            for task in tasks:
                try:
                    task_type = task.get("task_type")
                    params = task.get("params", {})
                    
                    if task_type == "daily_stats":
                        result = self.get_daily_stats(
                            start_date=params.get("start_date"),
                            end_date=params.get("end_date"),
                            use_cache=False
                        )
                        results.append({"task": task, "result": result})
                    elif task_type == "user_profile":
                        results.append({"task": task, "status": "delegated_to_user_profiler"})
                    elif task_type == "event_analysis":
                        results.append({"task": task, "status": "delegated_to_event_analyzer"})
                    else:
                        results.append({"task": task, "status": "unknown_task_type"})
                        
                except Exception as e:
                    logger.exception(f"Error processing analysis task: {str(e)}")
                    results.append({"task": task, "error": str(e)})
            
            return {
                "success": True,
                "processed_count": len(tasks),
                "results": results
            }
        
        self._analysis_task_queue = AnalysisTaskQueue(
            processor=process_tasks,
            batch_size=20,
            flush_interval_ms=1000,
            worker_count=2
        )
        self._task_processor_registered = True
    
    def start_analysis_workers(self) -> None:
        self._ensure_task_processor()
        if self._analysis_task_queue:
            self._analysis_task_queue.start()
            logger.info("Analysis task workers started")
    
    def stop_analysis_workers(self, wait: bool = True) -> None:
        if self._analysis_task_queue:
            self._analysis_task_queue.stop(wait=wait)
            logger.info("Analysis task workers stopped")
    
    def submit_analysis_task(self, task_type: str, params: Dict[str, Any]) -> str:
        self._ensure_task_processor()
        if self._analysis_task_queue:
            return self._analysis_task_queue.submit_analysis_task(task_type, params)
        return ""
    
    def get_analysis_task_result(self, task_id: str) -> Optional[Any]:
        if self._analysis_task_queue:
            return self._analysis_task_queue.get_task_result(task_id)
        return None
    
    def get_analysis_queue_stats(self) -> Dict[str, Any]:
        if self._analysis_task_queue:
            stats = self._analysis_task_queue.get_stats()
            return {
                "total_enqueued": stats.total_enqueued,
                "total_processed": stats.total_processed,
                "total_failed": stats.total_failed,
                "current_queue_size": stats.current_queue_size,
                "avg_processing_time_ms": stats.avg_processing_time_ms,
                "is_running": self._analysis_task_queue.is_running()
            }
        return {
            "is_running": False,
            "total_enqueued": 0,
            "total_processed": 0
        }
    
    def _check_window_rollover(self) -> bool:
        rolled_over = self._time_window_manager.check_window_rollover()
        
        if rolled_over:
            logger.info("Time window rolled over, clearing incremental stats and cache")
            self.clear_incremental_stats()
            self.invalidate_cache()
        
        return rolled_over
    
    def get_time_window_info(self) -> Dict[str, Any]:
        return self._time_window_manager.get_window_info()
    
    def _get_cache_key(self, *args: Any, **kwargs: Any) -> str:
        key_parts = list(args)
        for k, v in sorted(kwargs.items()):
            key_parts.append(f"{k}:{v}")
        return "|".join(str(p) for p in key_parts)
    
    def _get_or_cache(
        self,
        cache_key: str,
        compute_func,
        ttl_seconds: Optional[int] = None
    ) -> Dict[str, Any]:
        if self._use_cache:
            cached_result = self._cache.get(cache_key)
            if cached_result is not None:
                logger.debug(f"Cache hit for key: {cache_key}")
                return cached_result
        
        result = compute_func()
        
        if self._use_cache and result.get("success", False):
            self._cache.set(cache_key, result, ttl_seconds)
            logger.debug(f"Cache set for key: {cache_key}")
        
        return result
    
    def invalidate_cache(self, cache_key: Optional[str] = None) -> None:
        if cache_key:
            self._cache.delete(cache_key)
            logger.debug(f"Invalidated cache key: {cache_key}")
        else:
            self._cache.clear()
            logger.debug("Cleared entire cache")
    
    def get_cache_stats(self) -> Dict[str, Any]:
        return self._cache.get_stats()
    
    def update_incremental_stat(
        self,
        event_type: str,
        event_date: str,
        user_id: str,
        duration_seconds: float = 0.0
    ) -> Dict[str, Any]:
        self._check_window_rollover()
        
        cache_key = f"incremental:{event_type}:{event_date}"
        
        if cache_key not in self._incremental_stats:
            self._incremental_stats[cache_key] = {
                "event_count": 0,
                "unique_users": set(),
                "total_duration": 0.0,
                "durations": []
            }
        
        stat = self._incremental_stats[cache_key]
        stat["event_count"] += 1
        stat["unique_users"].add(user_id)
        stat["total_duration"] += duration_seconds
        if duration_seconds > 0:
            stat["durations"].append(duration_seconds)
        
        logger.debug(
            f"Updated incremental stat for {event_type} on {event_date}: "
            f"count={stat['event_count']}, users={len(stat['unique_users'])}"
        )
        
        return {
            "success": True,
            "event_type": event_type,
            "event_date": event_date,
            "event_count": stat["event_count"],
            "unique_users": len(stat["unique_users"])
        }
    
    def flush_incremental_stats(self) -> Dict[str, Any]:
        if not self._incremental_stats:
            return {
                "success": True,
                "flushed_count": 0,
                "message": "No incremental stats to flush"
            }
        
        from ..models import BehaviorStat
        
        flushed_count = 0
        errors = []
        
        for cache_key, stat in list(self._incremental_stats.items()):
            try:
                parts = cache_key.split(":")
                event_type = parts[1]
                event_date = parts[2]
                
                avg_duration = 0.0
                if stat["durations"]:
                    avg_duration = sum(stat["durations"]) / len(stat["durations"])
                
                behavior_stat = BehaviorStat(
                    event_type=event_type,
                    stat_date=event_date,
                    event_count=stat["event_count"],
                    user_count=len(stat["unique_users"]),
                    avg_duration=round(avg_duration, 2),
                    unique_users=len(stat["unique_users"])
                )
                
                self.storage.upsert_stat(behavior_stat)
                flushed_count += 1
                del self._incremental_stats[cache_key]
                
            except Exception as e:
                logger.exception(f"Error flushing incremental stat {cache_key}: {str(e)}")
                errors.append({
                    "cache_key": cache_key,
                    "error": str(e)
                })
        
        self.invalidate_cache()
        
        return {
            "success": len(errors) == 0,
            "flushed_count": flushed_count,
            "errors": errors
        }
    
    def clear_incremental_stats(self) -> None:
        self._incremental_stats.clear()
        logger.info("Cleared all incremental stats (time window rollover)")
    
    def get_incremental_stats_summary(self) -> Dict[str, Any]:
        self._check_window_rollover()
        
        window_info = self.get_time_window_info()
        
        return {
            "pending_count": len(self._incremental_stats),
            "total_event_count": sum(
                s["event_count"] for s in self._incremental_stats.values()
            ),
            "cache_keys": list(self._incremental_stats.keys()),
            "time_window": window_info
        }
    
    def get_daily_stats(
        self,
        start_date: Optional[str] = None,
        end_date: Optional[str] = None,
        use_cache: Optional[bool] = None
    ) -> Dict[str, Any]:
        if not start_date:
            start_date = (date.today() - timedelta(days=7)).isoformat()
        if not end_date:
            end_date = date.today().isoformat()
        
        use_cache = use_cache if use_cache is not None else self._use_cache
        
        self._check_window_rollover()
        
        cache_key = self._get_cache_key(
            "daily_stats",
            start_date=start_date,
            end_date=end_date
        )
        
        def compute_func() -> Dict[str, Any]:
            try:
                pipeline = [
                    {
                        "$match": {
                            "timestamp": {
                                "$gte": f"{start_date}T00:00:00Z",
                                "$lte": f"{end_date}T23:59:59Z"
                            }
                        }
                    },
                    {
                        "$project": {
                            "date": {"$substr": ["$timestamp", 0, 10]},
                            "user_id": 1,
                            "event_type": 1
                        }
                    },
                    {
                        "$group": {
                            "_id": {
                                "date": "$date",
                                "event_type": "$event_type"
                            },
                            "event_count": {"$sum": 1},
                            "users": {"$addToSet": "$user_id"}
                        }
                    },
                    {
                        "$project": {
                            "_id": 0,
                            "date": "$_id.date",
                            "event_type": "$_id.event_type",
                            "event_count": 1,
                            "user_count": {"$size": "$users"}
                        }
                    },
                    {"$sort": {"date": 1, "event_type": 1}}
                ]
                
                results = self.storage.aggregate_events(pipeline)
                
                daily_stats: Dict[str, Dict[str, Any]] = {}
                for result in results:
                    day = result["date"]
                    if day not in daily_stats:
                        daily_stats[day] = {
                            "date": day,
                            "total_events": 0,
                            "total_users": 0,
                            "event_types": {}
                        }
                    
                    daily_stats[day]["total_events"] += result["event_count"]
                    daily_stats[day]["total_users"] = max(
                        daily_stats[day]["total_users"],
                        result["user_count"]
                    )
                    daily_stats[day]["event_types"][result["event_type"]] = {
                        "event_count": result["event_count"],
                        "user_count": result["user_count"]
                    }
                
                return {
                    "success": True,
                    "start_date": start_date,
                    "end_date": end_date,
                    "daily_stats": list(daily_stats.values()),
                    "time_window": self.get_time_window_info()
                }
                
            except Exception as e:
                logger.exception(f"Error getting daily stats: {str(e)}")
                return {
                    "success": False,
                    "error": str(e)
                }
        
        if use_cache:
            return self._get_or_cache(cache_key, compute_func)
        return compute_func()
    
    def get_active_users_stats(
        self,
        period: str = "daily",
        start_date: Optional[str] = None,
        end_date: Optional[str] = None,
        use_cache: Optional[bool] = None
    ) -> Dict[str, Any]:
        if not start_date:
            if period == "daily":
                start_date = (date.today() - timedelta(days=7)).isoformat()
            elif period == "weekly":
                start_date = (date.today() - timedelta(weeks=4)).isoformat()
            else:
                start_date = (date.today() - timedelta(days=30)).isoformat()
        
        if not end_date:
            end_date = date.today().isoformat()
        
        use_cache = use_cache if use_cache is not None else self._use_cache
        
        self._check_window_rollover()
        
        cache_key = self._get_cache_key(
            "active_users",
            period=period,
            start_date=start_date,
            end_date=end_date
        )
        
        def compute_func() -> Dict[str, Any]:
            try:
                if period == "daily":
                    date_format = {"$substr": ["$timestamp", 0, 10]}
                elif period == "weekly":
                    date_format = {
                        "$dateToString": {
                            "format": "%G-W%V",
                            "date": {"$toDate": "$timestamp"}
                        }
                    }
                else:
                    date_format = {"$substr": ["$timestamp", 0, 7]}
                
                pipeline = [
                    {
                        "$match": {
                            "timestamp": {
                                "$gte": f"{start_date}T00:00:00Z",
                                "$lte": f"{end_date}T23:59:59Z"
                            }
                        }
                    },
                    {
                        "$project": {
                            "period": date_format,
                            "user_id": 1
                        }
                    },
                    {
                        "$group": {
                            "_id": {
                                "period": "$period",
                                "user_id": "$user_id"
                            }
                        }
                    },
                    {
                        "$group": {
                            "_id": "$_id.period",
                            "active_users": {"$sum": 1}
                        }
                    },
                    {"$sort": {"_id": 1}}
                ]
                
                results = self.storage.aggregate_events(pipeline)
                
                active_users_stats = []
                for result in results:
                    active_users_stats.append({
                        "period": result["_id"],
                        "active_users": result["active_users"]
                    })
                
                return {
                    "success": True,
                    "period": period,
                    "start_date": start_date,
                    "end_date": end_date,
                    "stats": active_users_stats,
                    "time_window": self.get_time_window_info()
                }
                
            except Exception as e:
                logger.exception(f"Error getting active users stats: {str(e)}")
                return {
                    "success": False,
                    "error": str(e)
                }
        
        if use_cache:
            return self._get_or_cache(cache_key, compute_func)
        return compute_func()
    
    def get_retention_stats(
        self,
        cohort_date: str,
        days: int = 7,
        use_cache: Optional[bool] = None
    ) -> Dict[str, Any]:
        use_cache = use_cache if use_cache is not None else self._use_cache
        
        self._check_window_rollover()
        
        cache_key = self._get_cache_key(
            "retention",
            cohort_date=cohort_date,
            days=days
        )
        
        def compute_func() -> Dict[str, Any]:
            try:
                cohort_dt = datetime.fromisoformat(cohort_date).date()
                
                cohort_users_pipeline = [
                    {
                        "$match": {
                            "timestamp": {
                                "$gte": f"{cohort_date}T00:00:00Z",
                                "$lte": f"{cohort_date}T23:59:59Z"
                            }
                        }
                    },
                    {"$group": {"_id": "$user_id"}},
                    {"$group": {"_id": None, "users": {"$addToSet": "$_id"}}},
                    {"$project": {"_id": 0, "users": 1, "count": {"$size": "$users"}}}
                ]
                
                cohort_result = self.storage.aggregate_events(cohort_users_pipeline)
                
                if not cohort_result:
                    return {
                        "success": False,
                        "error": "No users found for cohort date"
                    }
                
                cohort_users = set(cohort_result[0]["users"])
                total_cohort_users = cohort_result[0]["count"]
                
                retention_stats = []
                
                for day in range(days + 1):
                    day_date = cohort_dt + timedelta(days=day)
                    day_str = day_date.isoformat()
                    
                    active_users_pipeline = [
                        {
                            "$match": {
                                "timestamp": {
                                    "$gte": f"{day_str}T00:00:00Z",
                                    "$lte": f"{day_str}T23:59:59Z"
                                },
                                "user_id": {"$in": list(cohort_users)}
                            }
                        },
                        {"$group": {"_id": "$user_id"}},
                        {"$count": "active_users"}
                    ]
                    
                    active_result = self.storage.aggregate_events(active_users_pipeline)
                    active_users = active_result[0]["active_users"] if active_result else 0
                    
                    retention_rate = round(
                        active_users / total_cohort_users * 100,
                        2
                    ) if total_cohort_users > 0 else 0
                    
                    retention_stats.append({
                        "day": day,
                        "date": day_str,
                        "active_users": active_users,
                        "retention_rate": retention_rate
                    })
                
                return {
                    "success": True,
                    "cohort_date": cohort_date,
                    "total_cohort_users": total_cohort_users,
                    "retention_stats": retention_stats,
                    "time_window": self.get_time_window_info()
                }
                
            except Exception as e:
                logger.exception(f"Error getting retention stats: {str(e)}")
                return {
                    "success": False,
                    "error": str(e)
                }
        
        if use_cache:
            return self._get_or_cache(cache_key, compute_func, ttl_seconds=600)
        return compute_func()
    
    def get_event_distribution(
        self,
        limit: int = 20,
        use_cache: Optional[bool] = None
    ) -> Dict[str, Any]:
        use_cache = use_cache if use_cache is not None else self._use_cache
        
        self._check_window_rollover()
        
        cache_key = self._get_cache_key(
            "event_distribution",
            limit=limit
        )
        
        def compute_func() -> Dict[str, Any]:
            try:
                pipeline = [
                    {"$group": {"_id": "$event_type", "count": {"$sum": 1}}},
                    {"$sort": {"count": -1}},
                    {"$limit": limit}
                ]
                
                results = self.storage.aggregate_events(pipeline)
                
                total = sum(r["count"] for r in results)
                
                distribution = []
                for result in results:
                    distribution.append({
                        "event_type": result["_id"],
                        "count": result["count"],
                        "percentage": round(result["count"] / total * 100, 2) if total > 0 else 0
                    })
                
                return {
                    "success": True,
                    "total_events": total,
                    "distribution": distribution,
                    "time_window": self.get_time_window_info()
                }
                
            except Exception as e:
                logger.exception(f"Error getting event distribution: {str(e)}")
                return {
                    "success": False,
                    "error": str(e)
                }
        
        if use_cache:
            return self._get_or_cache(cache_key, compute_func)
        return compute_func()
    
    def get_hourly_distribution(
        self,
        use_cache: Optional[bool] = None
    ) -> Dict[str, Any]:
        use_cache = use_cache if use_cache is not None else self._use_cache
        
        self._check_window_rollover()
        
        cache_key = self._get_cache_key("hourly_distribution")
        
        def compute_func() -> Dict[str, Any]:
            try:
                pipeline = [
                    {
                        "$project": {
                            "hour": {
                                "$hour": {"$toDate": "$timestamp"}
                            }
                        }
                    },
                    {"$group": {"_id": "$hour", "count": {"$sum": 1}}},
                    {"$sort": {"_id": 1}}
                ]
                
                results = self.storage.aggregate_events(pipeline)
                
                total = sum(r["count"] for r in results)
                
                distribution = []
                for result in results:
                    distribution.append({
                        "hour": result["_id"],
                        "count": result["count"],
                        "percentage": round(result["count"] / total * 100, 2) if total > 0 else 0
                    })
                
                return {
                    "success": True,
                    "total_events": total,
                    "hourly_distribution": distribution,
                    "time_window": self.get_time_window_info()
                }
                
            except Exception as e:
                logger.exception(f"Error getting hourly distribution: {str(e)}")
                return {
                    "success": False,
                    "error": str(e)
                }
        
        if use_cache:
            return self._get_or_cache(cache_key, compute_func)
        return compute_func()
    
    def get_overview_stats(
        self,
        use_cache: Optional[bool] = None
    ) -> Dict[str, Any]:
        use_cache = use_cache if use_cache is not None else self._use_cache
        
        self._check_window_rollover()
        
        cache_key = self._get_cache_key("overview")
        
        def compute_func() -> Dict[str, Any]:
            try:
                total_events = self.storage.count_events({})
                
                unique_users_pipeline = [
                    {"$group": {"_id": "$user_id"}},
                    {"$count": "count"}
                ]
                unique_users_result = self.storage.aggregate_events(unique_users_pipeline)
                total_users = unique_users_result[0]["count"] if unique_users_result else 0
                
                today = date.today().isoformat()
                today_events = self.storage.count_events({
                    "timestamp": {"$gte": f"{today}T00:00:00Z"}
                })
                
                today_users_pipeline = [
                    {
                        "$match": {
                            "timestamp": {"$gte": f"{today}T00:00:00Z"}
                        }
                    },
                    {"$group": {"_id": "$user_id"}},
                    {"$count": "count"}
                ]
                today_users_result = self.storage.aggregate_events(today_users_pipeline)
                today_users = today_users_result[0]["count"] if today_users_result else 0
                
                yesterday = (date.today() - timedelta(days=1)).isoformat()
                yesterday_events = self.storage.count_events({
                    "timestamp": {
                        "$gte": f"{yesterday}T00:00:00Z",
                        "$lte": f"{yesterday}T23:59:59Z"
                    }
                })
                
                events_growth = round(
                    (today_events - yesterday_events) / yesterday_events * 100,
                    2
                ) if yesterday_events > 0 else 0
                
                return {
                    "success": True,
                    "overview": {
                        "total_events": total_events,
                        "total_users": total_users,
                        "today_events": today_events,
                        "today_users": today_users,
                        "yesterday_events": yesterday_events,
                        "events_growth_rate": events_growth
                    },
                    "time_window": self.get_time_window_info()
                }
                
            except Exception as e:
                logger.exception(f"Error getting overview stats: {str(e)}")
                return {
                    "success": False,
                    "error": str(e)
                }
        
        if use_cache:
            return self._get_or_cache(cache_key, compute_func, ttl_seconds=60)
        return compute_func()
