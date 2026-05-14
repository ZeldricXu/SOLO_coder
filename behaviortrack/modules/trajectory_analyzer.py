import logging
from collections import Counter
from datetime import datetime, timedelta
from typing import Any, Dict, List, Optional

import pandas as pd

from ..config import settings
from ..storage import MongoStorage
from ..models import UserTrajectory


logger = logging.getLogger(__name__)


class TrajectoryAnalyzer:
    def __init__(self) -> None:
        self.storage = MongoStorage()
    
    def analyze_user_trajectories(
        self,
        user_id: Optional[str] = None,
        start_date: Optional[str] = None,
        end_date: Optional[str] = None
    ) -> Dict[str, Any]:
        try:
            query: Dict[str, Any] = {}
            if user_id:
                query["user_id"] = user_id
            if start_date or end_date:
                query["created_at"] = {}
                if start_date:
                    query["created_at"]["$gte"] = start_date
                if end_date:
                    query["created_at"]["$lte"] = end_date
            
            trajectories = self.storage.find_trajectories(query, limit=1000)
            
            if not trajectories:
                return {
                    "success": True,
                    "total_trajectories": 0,
                    "path_analysis": {},
                    "average_duration": 0,
                    "top_paths": []
                }
            
            path_analysis = self._analyze_paths(trajectories)
            duration_stats = self._analyze_durations(trajectories)
            top_paths = self._get_top_paths(trajectories, top_n=10)
            
            return {
                "success": True,
                "total_trajectories": len(trajectories),
                "path_analysis": path_analysis,
                "duration_analysis": duration_stats,
                "top_paths": top_paths
            }
            
        except Exception as e:
            logger.exception(f"Error analyzing trajectories: {str(e)}")
            return {
                "success": False,
                "error": str(e)
            }
    
    def _analyze_paths(self, trajectories: List[UserTrajectory]) -> Dict[str, Any]:
        all_paths = []
        path_lengths = []
        
        for trajectory in trajectories:
            if not trajectory.event_sequence:
                continue
            
            path = [e.event for e in trajectory.event_sequence]
            all_paths.append(path)
            path_lengths.append(len(path))
        
        if not all_paths:
            return {
                "average_path_length": 0,
                "max_path_length": 0,
                "min_path_length": 0,
                "total_paths": 0
            }
        
        return {
            "average_path_length": round(sum(path_lengths) / len(path_lengths), 2),
            "max_path_length": max(path_lengths),
            "min_path_length": min(path_lengths),
            "total_paths": len(all_paths)
        }
    
    def _analyze_durations(self, trajectories: List[UserTrajectory]) -> Dict[str, Any]:
        durations = [t.duration for t in trajectories if t.duration > 0]
        
        if not durations:
            return {
                "average_duration": 0,
                "max_duration": 0,
                "min_duration": 0,
                "total_sessions": 0
            }
        
        return {
            "average_duration": round(sum(durations) / len(durations), 2),
            "max_duration": max(durations),
            "min_duration": min(durations),
            "total_sessions": len(durations)
        }
    
    def _get_top_paths(
        self,
        trajectories: List[UserTrajectory],
        top_n: int = 10
    ) -> List[Dict[str, Any]]:
        path_counter: Counter = Counter()
        
        for trajectory in trajectories:
            if not trajectory.event_sequence:
                continue
            
            path_key = " -> ".join([e.event for e in trajectory.event_sequence])
            path_counter[path_key] += 1
        
        top_paths = []
        for path, count in path_counter.most_common(top_n):
            top_paths.append({
                "path": path,
                "count": count,
                "percentage": round(count / len(trajectories) * 100, 2) if trajectories else 0
            })
        
        return top_paths
    
    def get_user_trajectory(
        self,
        user_id: str,
        session_id: Optional[str] = None
    ) -> Dict[str, Any]:
        try:
            if session_id:
                trajectory = self.storage.find_trajectory_by_session(user_id, session_id)
                if trajectory:
                    return {
                        "success": True,
                        "trajectory": trajectory.to_dict()
                    }
                return {
                    "success": False,
                    "error": "Trajectory not found"
                }
            else:
                trajectories = self.storage.find_trajectories(
                    {"user_id": user_id},
                    limit=100
                )
                return {
                    "success": True,
                    "trajectories": [t.to_dict() for t in trajectories]
                }
                
        except Exception as e:
            logger.exception(f"Error getting user trajectory: {str(e)}")
            return {
                "success": False,
                "error": str(e)
            }
    
    def analyze_funnel(
        self,
        events: List[str],
        start_date: Optional[str] = None,
        end_date: Optional[str] = None
    ) -> Dict[str, Any]:
        try:
            query: Dict[str, Any] = {}
            if start_date or end_date:
                query["created_at"] = {}
                if start_date:
                    query["created_at"]["$gte"] = start_date
                if end_date:
                    query["created_at"]["$lte"] = end_date
            
            trajectories = self.storage.find_trajectories(query, limit=5000)
            
            funnel_steps = []
            total_sessions = len(trajectories)
            
            for i, target_event in enumerate(events):
                required_events = events[:i+1]
                
                count = 0
                for trajectory in trajectories:
                    event_sequence = [e.event for e in trajectory.event_sequence]
                    
                    found_all = True
                    last_index = -1
                    for req_event in required_events:
                        try:
                            idx = event_sequence.index(req_event, last_index + 1)
                            last_index = idx
                        except ValueError:
                            found_all = False
                            break
                    
                    if found_all:
                        count += 1
                
                conversion_rate = round(count / total_sessions * 100, 2) if total_sessions > 0 else 0
                drop_off_rate = round((1 - count / total_sessions) * 100, 2) if total_sessions > 0 else 0
                
                funnel_steps.append({
                    "step": i + 1,
                    "event": target_event,
                    "count": count,
                    "conversion_rate": conversion_rate,
                    "drop_off_rate": drop_off_rate
                })
                
                total_sessions = count
            
            return {
                "success": True,
                "funnel_events": events,
                "steps": funnel_steps
            }
            
        except Exception as e:
            logger.exception(f"Error analyzing funnel: {str(e)}")
            return {
                "success": False,
                "error": str(e)
            }
    
    def get_popular_entry_points(self, limit: int = 10) -> Dict[str, Any]:
        try:
            trajectories = self.storage.find_trajectories({}, limit=5000)
            
            entry_points: Counter = Counter()
            for trajectory in trajectories:
                if trajectory.event_sequence:
                    first_event = trajectory.event_sequence[0].event
                    entry_points[first_event] += 1
            
            total = len(trajectories)
            popular_entries = []
            
            for event, count in entry_points.most_common(limit):
                popular_entries.append({
                    "event": event,
                    "count": count,
                    "percentage": round(count / total * 100, 2) if total > 0 else 0
                })
            
            return {
                "success": True,
                "entry_points": popular_entries
            }
            
        except Exception as e:
            logger.exception(f"Error getting entry points: {str(e)}")
            return {
                "success": False,
                "error": str(e)
            }
    
    def get_popular_exit_points(self, limit: int = 10) -> Dict[str, Any]:
        try:
            trajectories = self.storage.find_trajectories({}, limit=5000)
            
            exit_points: Counter = Counter()
            for trajectory in trajectories:
                if trajectory.event_sequence:
                    last_event = trajectory.event_sequence[-1].event
                    exit_points[last_event] += 1
            
            total = len(trajectories)
            popular_exits = []
            
            for event, count in exit_points.most_common(limit):
                popular_exits.append({
                    "event": event,
                    "count": count,
                    "percentage": round(count / total * 100, 2) if total > 0 else 0
                })
            
            return {
                "success": True,
                "exit_points": popular_exits
            }
            
        except Exception as e:
            logger.exception(f"Error getting exit points: {str(e)}")
            return {
                "success": False,
                "error": str(e)
            }
