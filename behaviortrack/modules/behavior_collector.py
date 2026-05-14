import logging
import uuid
from datetime import datetime, timezone, timedelta
from typing import Any, Dict, List, Optional

from ..config import settings
from ..storage import MongoStorage
from ..models import (
    BehaviorEvent,
    UserTrajectory,
    TrajectoryEvent,
    AbnormalBehavior
)
from .queue import EventQueue, AsyncQueueResult


logger = logging.getLogger(__name__)


class BehaviorCollector:
    def __init__(
        self,
        use_async: bool = True,
        queue_max_size: int = 10000,
        batch_size: int = 100,
        flush_interval_ms: int = 100,
        worker_count: int = 2
    ) -> None:
        self.storage = MongoStorage()
        self._use_async = use_async
        
        self._event_queue: Optional[EventQueue] = None
        
        if use_async:
            self._event_queue = EventQueue(
                processor=self._process_queued_events,
                max_size=queue_max_size,
                batch_size=batch_size,
                flush_interval_ms=flush_interval_ms,
                worker_count=worker_count
            )
    
    def start_async_processing(self) -> None:
        if self._event_queue and not self._event_queue.is_running():
            self._event_queue.start()
            logger.info("Async event processing started")
    
    def stop_async_processing(self) -> None:
        if self._event_queue and self._event_queue.is_running():
            self._event_queue.stop()
            logger.info("Async event processing stopped")
    
    def get_queue_stats(self) -> Optional[Dict[str, Any]]:
        if self._event_queue:
            stats = self._event_queue.get_stats()
            return {
                "total_enqueued": stats.total_enqueued,
                "total_processed": stats.total_processed,
                "total_failed": stats.total_failed,
                "current_queue_size": stats.current_queue_size,
                "avg_processing_time_ms": stats.avg_processing_time_ms,
                "is_running": self._event_queue.is_running()
            }
        return None
    
    def collect_async(self, data: Dict[str, Any]) -> Dict[str, Any]:
        if not self._event_queue:
            return self.collect(data)
        
        validation_result = self._validate_data(data)
        if not validation_result["valid"]:
            logger.error(f"Data validation failed: {validation_result['errors']}")
            return {
                "success": False,
                "async": True,
                "error": "Invalid data format",
                "details": validation_result["errors"]
            }
        
        task_id = self._event_queue.enqueue(data)
        
        logger.info(f"Enqueued event for async processing, task_id: {task_id}")
        
        return {
            "success": True,
            "async": True,
            "task_id": task_id,
            "message": "Event queued for processing"
        }
    
    def collect_batch_async(self, data_list: List[Dict[str, Any]]) -> Dict[str, Any]:
        if not self._event_queue:
            return self.collect_batch(data_list)
        
        if not data_list:
            return {"success": True, "async": True, "task_ids": []}
        
        valid_data = []
        errors = []
        
        for idx, data in enumerate(data_list):
            validation_result = self._validate_data(data)
            if not validation_result["valid"]:
                errors.append({
                    "index": idx,
                    "error": "Invalid data format",
                    "details": validation_result["errors"]
                })
                continue
            valid_data.append(data)
        
        task_ids = []
        for data in valid_data:
            task_id = self._event_queue.enqueue(data)
            task_ids.append(task_id)
        
        logger.info(f"Enqueued {len(task_ids)} events for async processing")
        
        return {
            "success": len(errors) == 0,
            "async": True,
            "task_ids": task_ids,
            "count": len(task_ids),
            "errors": errors
        }
    
    def get_task_result(self, task_id: str) -> Optional[AsyncQueueResult]:
        if self._event_queue:
            return self._event_queue.get_result(task_id)
        return None
    
    def wait_for_processing(self, timeout_seconds: float = 10.0) -> bool:
        if not self._event_queue:
            return True
        
        start_time = datetime.now(timezone.utc)
        while (datetime.now(timezone.utc) - start_time).total_seconds() < timeout_seconds:
            stats = self._event_queue.get_stats()
            if stats.current_queue_size == 0:
                return True
            import time
            time.sleep(0.1)
        
        return False
    
    def collect(self, data: Dict[str, Any]) -> Dict[str, Any]:
        try:
            validation_result = self._validate_data(data)
            if not validation_result["valid"]:
                logger.error(f"Data validation failed: {validation_result['errors']}")
                return {
                    "success": False,
                    "error": "Invalid data format",
                    "details": validation_result["errors"]
                }
            
            event = self._create_event(data)
            self.storage.insert_event(event)
            
            self._update_trajectory(event)
            self._detect_abnormal_behavior(event)
            
            logger.info(f"Collected event: {event.event_id}")
            return {
                "success": True,
                "event_id": event.event_id
            }
            
        except Exception as e:
            logger.exception(f"Error collecting behavior: {str(e)}")
            return {
                "success": False,
                "error": str(e)
            }
    
    def collect_batch(self, data_list: List[Dict[str, Any]]) -> Dict[str, Any]:
        if not data_list:
            return {"success": True, "event_ids": []}
        
        events = []
        errors = []
        
        for idx, data in enumerate(data_list):
            validation_result = self._validate_data(data)
            if not validation_result["valid"]:
                errors.append({
                    "index": idx,
                    "error": "Invalid data format",
                    "details": validation_result["errors"]
                })
                continue
            
            try:
                event = self._create_event(data)
                events.append(event)
            except Exception as e:
                errors.append({
                    "index": idx,
                    "error": str(e)
                })
        
        if events:
            self.storage.insert_events(events)
            
            for event in events:
                self._update_trajectory(event)
                self._detect_abnormal_behavior(event)
        
        event_ids = [e.event_id for e in events]
        logger.info(f"Collected {len(event_ids)} events, {len(errors)} errors")
        
        return {
            "success": len(errors) == 0,
            "event_ids": event_ids,
            "errors": errors
        }
    
    def _process_queued_events(self, items: List[Dict[str, Any]]) -> Dict[str, Any]:
        return self.collect_batch(items)
    
    def _validate_data(self, data: Dict[str, Any]) -> Dict[str, Any]:
        errors = []
        
        required_fields = ["user_id", "event_type"]
        for field in required_fields:
            if field not in data or not data[field]:
                errors.append(f"Missing required field: {field}")
        
        if "user_id" in data and not isinstance(data["user_id"], str):
            errors.append("user_id must be a string")
        
        if "event_type" in data and not isinstance(data["event_type"], str):
            errors.append("event_type must be a string")
        
        if "event_data" in data and not isinstance(data["event_data"], dict):
            errors.append("event_data must be a dictionary")
        
        if "device" in data and not isinstance(data["device"], dict):
            errors.append("device must be a dictionary")
        
        if "location" in data and not isinstance(data["location"], dict):
            errors.append("location must be a dictionary")
        
        return {
            "valid": len(errors) == 0,
            "errors": errors
        }
    
    def _create_event(self, data: Dict[str, Any]) -> BehaviorEvent:
        return BehaviorEvent(
            user_id=data.get("user_id", ""),
            event_type=data.get("event_type", ""),
            event_name=data.get("event_name", ""),
            event_data=data.get("event_data", {}),
            device=data.get("device", {}),
            session_id=data.get("session_id", self._generate_session_id()),
            timestamp=data.get("timestamp", self._get_current_timestamp()),
            location=data.get("location", {})
        )
    
    def _update_trajectory(self, event: BehaviorEvent) -> None:
        try:
            trajectory = self.storage.find_trajectory_by_session(
                event.user_id,
                event.session_id
            )
            
            if trajectory is None:
                trajectory = UserTrajectory(
                    user_id=event.user_id,
                    session_id=event.session_id,
                    event_sequence=[],
                    duration=0
                )
            
            trajectory.event_sequence.append(
                TrajectoryEvent(
                    event=event.event_type,
                    timestamp=event.timestamp
                )
            )
            
            if len(trajectory.event_sequence) >= 2:
                first_ts = self._parse_timestamp(trajectory.event_sequence[0].timestamp)
                last_ts = self._parse_timestamp(trajectory.event_sequence[-1].timestamp)
                trajectory.duration = int((last_ts - first_ts).total_seconds())
            
            self.storage.upsert_trajectory(trajectory)
            
        except Exception as e:
            logger.exception(f"Error updating trajectory: {str(e)}")
    
    def _detect_abnormal_behavior(self, event: BehaviorEvent) -> None:
        try:
            one_minute_ago = (
                datetime.now(timezone.utc) - timedelta(minutes=1)
            ).isoformat()
            
            recent_events_count = self.storage.count_events({
                "user_id": event.user_id,
                "timestamp": {"$gte": one_minute_ago}
            })
            
            if recent_events_count > settings.EVENT_FREQUENCY_THRESHOLD:
                abnormal = AbnormalBehavior(
                    user_id=event.user_id,
                    abnormal_type="high_frequency",
                    abnormal_data={
                        "event_count_per_minute": recent_events_count,
                        "last_event_type": event.event_type
                    },
                    status="detected"
                )
                self.storage.insert_abnormal(abnormal)
                logger.warning(f"Detected high frequency behavior for user: {event.user_id}")
                
        except Exception as e:
            logger.exception(f"Error detecting abnormal behavior: {str(e)}")
    
    def _generate_session_id(self) -> str:
        return f"session_{uuid.uuid4().hex[:8]}"
    
    def _get_current_timestamp(self) -> str:
        return datetime.now(timezone.utc).isoformat()
    
    def _parse_timestamp(self, ts: str) -> datetime:
        try:
            return datetime.fromisoformat(ts.replace("Z", "+00:00"))
        except ValueError:
            return datetime.now(timezone.utc)
