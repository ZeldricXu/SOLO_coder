import logging
from datetime import datetime, timezone, date
from dateutil.parser import parse as parse_date
from typing import Any, Dict, List, Optional

import pandas as pd
import numpy as np

from ..config import settings
from ..storage import MongoStorage
from ..models import BehaviorStat, EventRelation


logger = logging.getLogger(__name__)


class EventAnalyzer:
    def __init__(self) -> None:
        self.storage = MongoStorage()
    
    def analyze_events(
        self,
        event_type: Optional[str] = None,
        start_date: Optional[str] = None,
        end_date: Optional[str] = None
    ) -> Dict[str, Any]:
        try:
            query = self._build_query(event_type, start_date, end_date)
            
            stats = self._calculate_event_stats(query, event_type)
            relations = self._analyze_event_relations(query)
            
            analysis_date = date.today().isoformat()
            
            for stat in stats:
                self.storage.upsert_stat(stat)
            
            for relation in relations:
                relation.analysis_date = analysis_date
                self.storage.upsert_relation(relation)
            
            return {
                "success": True,
                "stats": [s.to_dict() for s in stats],
                "relations": [r.to_dict() for r in relations],
                "analysis_date": analysis_date
            }
            
        except Exception as e:
            logger.exception(f"Error analyzing events: {str(e)}")
            return {
                "success": False,
                "error": str(e)
            }
    
    def _build_query(
        self,
        event_type: Optional[str],
        start_date: Optional[str],
        end_date: Optional[str]
    ) -> Dict[str, Any]:
        query: Dict[str, Any] = {}
        
        if event_type:
            query["event_type"] = event_type
        
        if start_date or end_date:
            ts_query: Dict[str, Any] = {}
            if start_date:
                ts_query["$gte"] = start_date
            if end_date:
                ts_query["$lte"] = end_date
            if ts_query:
                query["timestamp"] = ts_query
        
        return query
    
    def _calculate_event_stats(
        self,
        query: Dict[str, Any],
        specific_event_type: Optional[str]
    ) -> List[BehaviorStat]:
        stats: List[BehaviorStat] = []
        
        pipeline = [
            {"$match": query},
            {
                "$group": {
                    "_id": {
                        "event_type": "$event_type",
                        "event_name": "$event_name"
                    },
                    "event_count": {"$sum": 1},
                    "user_count": {"$addToSet": "$user_id"}
                }
            },
            {
                "$project": {
                    "_id": 0,
                    "event_type": "$_id.event_type",
                    "event_name": "$_id.event_name",
                    "event_count": 1,
                    "user_count": {"$size": "$user_count"}
                }
            }
        ]
        
        results = self.storage.aggregate_events(pipeline)
        
        today = date.today().isoformat()
        
        for result in results:
            unique_users = result.get("user_count", 0)
            avg_duration = 0.0
            
            stat = BehaviorStat(
                event_type=result.get("event_type", ""),
                event_name=result.get("event_name", ""),
                stat_date=today,
                event_count=result.get("event_count", 0),
                user_count=unique_users,
                avg_duration=avg_duration,
                unique_users=unique_users
            )
            stats.append(stat)
        
        return stats
    
    def _analyze_event_relations(
        self,
        query: Dict[str, Any]
    ) -> List[EventRelation]:
        relations: List[EventRelation] = []
        
        pipeline = [
            {"$match": query},
            {"$sort": {"session_id": 1, "timestamp": 1}},
            {
                "$group": {
                    "_id": "$session_id",
                    "events": {
                        "$push": {
                            "event_type": "$event_type",
                            "timestamp": "$timestamp"
                        }
                    }
                }
            }
        ]
        
        results = self.storage.aggregate_events(pipeline)
        
        event_transitions: Dict[str, Dict[str, Dict[str, Any]]] = {}
        
        for session in results:
            events = session.get("events", [])
            if len(events) < 2:
                continue
            
            for i in range(len(events) - 1):
                source = events[i]["event_type"]
                target = events[i + 1]["event_type"]
                
                if source == target:
                    continue
                
                if source not in event_transitions:
                    event_transitions[source] = {}
                
                if target not in event_transitions[source]:
                    event_transitions[source][target] = {
                        "count": 0,
                        "intervals": []
                    }
                
                event_transitions[source][target]["count"] += 1
                
                try:
                    source_ts = parse_date(events[i]["timestamp"])
                    target_ts = parse_date(events[i + 1]["timestamp"])
                    interval = (target_ts - source_ts).total_seconds()
                    event_transitions[source][target]["intervals"].append(interval)
                except Exception:
                    pass
        
        for source, targets in event_transitions.items():
            total_source_transitions = sum(t["count"] for t in targets.values())
            
            for target, data in targets.items():
                correlation_rate = data["count"] / total_source_transitions if total_source_transitions > 0 else 0
                avg_interval = np.mean(data["intervals"]) if data["intervals"] else 0
                
                relation = EventRelation(
                    source_event=source,
                    target_event=target,
                    correlation_rate=round(correlation_rate, 4),
                    avg_interval=round(avg_interval, 2),
                    analysis_date=date.today().isoformat()
                )
                relations.append(relation)
        
        return relations
    
    def get_event_types(self) -> List[str]:
        try:
            pipeline = [
                {"$group": {"_id": "$event_type"}},
                {"$project": {"_id": 0, "event_type": "$_id"}}
            ]
            results = self.storage.aggregate_events(pipeline)
            return [r.get("event_type", "") for r in results if r.get("event_type")]
        except Exception as e:
            logger.exception(f"Error getting event types: {str(e)}")
            return []
    
    def get_event_stats_by_type(
        self,
        event_type: str,
        start_date: Optional[str] = None,
        end_date: Optional[str] = None
    ) -> Dict[str, Any]:
        try:
            query = {"event_type": event_type}
            
            if start_date:
                query["timestamp"] = {"$gte": start_date}
            if end_date:
                if "timestamp" not in query:
                    query["timestamp"] = {}
                query["timestamp"]["$lte"] = end_date
            
            event_count = self.storage.count_events(query)
            
            unique_users_pipeline = [
                {"$match": query},
                {"$group": {"_id": "$user_id"}},
                {"$count": "count"}
            ]
            unique_users_result = self.storage.aggregate_events(unique_users_pipeline)
            unique_users = unique_users_result[0]["count"] if unique_users_result else 0
            
            daily_stats_pipeline = [
                {"$match": query},
                {
                    "$project": {
                        "date": {"$substr": ["$timestamp", 0, 10]}
                    }
                },
                {
                    "$group": {
                        "_id": "$date",
                        "count": {"$sum": 1}
                    }
                },
                {"$sort": {"_id": 1}}
            ]
            daily_stats = self.storage.aggregate_events(daily_stats_pipeline)
            
            return {
                "success": True,
                "event_type": event_type,
                "total_events": event_count,
                "unique_users": unique_users,
                "daily_stats": [
                    {"date": s["_id"], "count": s["count"]}
                    for s in daily_stats
                ]
            }
            
        except Exception as e:
            logger.exception(f"Error getting event stats: {str(e)}")
            return {
                "success": False,
                "error": str(e)
            }
