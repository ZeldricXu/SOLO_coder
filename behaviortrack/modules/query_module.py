import logging
from typing import Any, Dict, List, Optional

from ..storage import MongoStorage
from ..models import BehaviorEvent


logger = logging.getLogger(__name__)


class QueryModule:
    def __init__(self) -> None:
        self.storage = MongoStorage()
    
    def query_events(
        self,
        user_id: Optional[str] = None,
        event_type: Optional[str] = None,
        event_name: Optional[str] = None,
        start_date: Optional[str] = None,
        end_date: Optional[str] = None,
        session_id: Optional[str] = None,
        limit: int = 100,
        offset: int = 0
    ) -> Dict[str, Any]:
        try:
            query: Dict[str, Any] = {}
            
            if user_id:
                query["user_id"] = user_id
            if event_type:
                query["event_type"] = event_type
            if event_name:
                query["event_name"] = event_name
            if session_id:
                query["session_id"] = session_id
            
            if start_date or end_date:
                query["timestamp"] = {}
                if start_date:
                    query["timestamp"]["$gte"] = start_date
                if end_date:
                    query["timestamp"]["$lte"] = end_date
            
            total = self.storage.count_events(query)
            
            cursor = self.storage.events_collection.find(query)
            cursor = cursor.sort("timestamp", -1).skip(offset).limit(limit)
            
            events = [BehaviorEvent.from_dict(doc).to_dict() for doc in cursor]
            
            return {
                "success": True,
                "total": total,
                "limit": limit,
                "offset": offset,
                "events": events
            }
            
        except Exception as e:
            logger.exception(f"Error querying events: {str(e)}")
            return {
                "success": False,
                "error": str(e)
            }
    
    def get_event_by_id(self, event_id: str) -> Dict[str, Any]:
        try:
            event = self.storage.find_event_by_id(event_id)
            
            if event:
                return {
                    "success": True,
                    "event": event.to_dict()
                }
            
            return {
                "success": False,
                "error": "Event not found"
            }
            
        except Exception as e:
            logger.exception(f"Error getting event: {str(e)}")
            return {
                "success": False,
                "error": str(e)
            }
    
    def get_user_events(
        self,
        user_id: str,
        limit: int = 100,
        start_date: Optional[str] = None,
        end_date: Optional[str] = None
    ) -> Dict[str, Any]:
        return self.query_events(
            user_id=user_id,
            start_date=start_date,
            end_date=end_date,
            limit=limit
        )
    
    def get_session_events(
        self,
        session_id: str,
        limit: int = 100
    ) -> Dict[str, Any]:
        return self.query_events(session_id=session_id, limit=limit)
    
    def get_event_types(self) -> Dict[str, Any]:
        try:
            pipeline = [
                {"$group": {"_id": "$event_type", "count": {"$sum": 1}}},
                {"$sort": {"count": -1}}
            ]
            
            results = self.storage.aggregate_events(pipeline)
            
            event_types = []
            for result in results:
                event_types.append({
                    "event_type": result["_id"],
                    "count": result["count"]
                })
            
            return {
                "success": True,
                "event_types": event_types
            }
            
        except Exception as e:
            logger.exception(f"Error getting event types: {str(e)}")
            return {
                "success": False,
                "error": str(e)
            }
    
    def get_user_sessions(
        self,
        user_id: str,
        limit: int = 50
    ) -> Dict[str, Any]:
        try:
            pipeline = [
                {"$match": {"user_id": user_id}},
                {
                    "$group": {
                        "_id": "$session_id",
                        "first_event": {"$min": "$timestamp"},
                        "last_event": {"$max": "$timestamp"},
                        "event_count": {"$sum": 1}
                    }
                },
                {"$sort": {"first_event": -1}},
                {"$limit": limit}
            ]
            
            results = self.storage.aggregate_events(pipeline)
            
            sessions = []
            for result in results:
                sessions.append({
                    "session_id": result["_id"],
                    "first_event": result["first_event"],
                    "last_event": result["last_event"],
                    "event_count": result["event_count"]
                })
            
            return {
                "success": True,
                "sessions": sessions
            }
            
        except Exception as e:
            logger.exception(f"Error getting user sessions: {str(e)}")
            return {
                "success": False,
                "error": str(e)
            }
    
    def get_abnormal_behaviors(
        self,
        user_id: Optional[str] = None,
        abnormal_type: Optional[str] = None,
        status: Optional[str] = None,
        limit: int = 100
    ) -> Dict[str, Any]:
        try:
            query: Dict[str, Any] = {}
            
            if user_id:
                query["user_id"] = user_id
            if abnormal_type:
                query["abnormal_type"] = abnormal_type
            if status:
                query["status"] = status
            
            abnormal_list = self.storage.find_abnormal(query, limit=limit)
            
            return {
                "success": True,
                "abnormal_behaviors": [ab.to_dict() for ab in abnormal_list]
            }
            
        except Exception as e:
            logger.exception(f"Error getting abnormal behaviors: {str(e)}")
            return {
                "success": False,
                "error": str(e)
            }
    
    def search_events(
        self,
        keyword: str,
        limit: int = 100
    ) -> Dict[str, Any]:
        try:
            query = {
                "$or": [
                    {"event_name": {"$regex": keyword, "$options": "i"}},
                    {"event_type": {"$regex": keyword, "$options": "i"}},
                    {"user_id": {"$regex": keyword, "$options": "i"}}
                ]
            }
            
            events = self.storage.find_events(query, limit=limit)
            
            return {
                "success": True,
                "events": [e.to_dict() for e in events]
            }
            
        except Exception as e:
            logger.exception(f"Error searching events: {str(e)}")
            return {
                "success": False,
                "error": str(e)
            }
