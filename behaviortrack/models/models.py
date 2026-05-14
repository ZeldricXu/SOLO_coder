import uuid
from dataclasses import dataclass, field
from datetime import datetime, timezone
from typing import Any, Dict, List, Optional


def generate_id(prefix: str) -> str:
    return f"{prefix}_{uuid.uuid4().hex[:8]}"


def get_current_timestamp() -> str:
    return datetime.now(timezone.utc).isoformat()


@dataclass
class BehaviorEvent:
    event_id: str = field(default_factory=lambda: generate_id("event"))
    user_id: str = ""
    event_type: str = ""
    event_name: str = ""
    event_data: Dict[str, Any] = field(default_factory=dict)
    device: Dict[str, Any] = field(default_factory=dict)
    session_id: str = ""
    timestamp: str = field(default_factory=get_current_timestamp)
    location: Dict[str, Any] = field(default_factory=dict)
    
    def to_dict(self) -> Dict[str, Any]:
        return {
            "event_id": self.event_id,
            "user_id": self.user_id,
            "event_type": self.event_type,
            "event_name": self.event_name,
            "event_data": self.event_data,
            "device": self.device,
            "session_id": self.session_id,
            "timestamp": self.timestamp,
            "location": self.location
        }
    
    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> "BehaviorEvent":
        return cls(
            event_id=data.get("event_id", generate_id("event")),
            user_id=data.get("user_id", ""),
            event_type=data.get("event_type", ""),
            event_name=data.get("event_name", ""),
            event_data=data.get("event_data", {}),
            device=data.get("device", {}),
            session_id=data.get("session_id", ""),
            timestamp=data.get("timestamp", get_current_timestamp()),
            location=data.get("location", {})
        )


@dataclass
class BehaviorStat:
    stat_id: str = field(default_factory=lambda: generate_id("stat"))
    event_type: str = ""
    event_name: str = ""
    stat_date: str = ""
    event_count: int = 0
    user_count: int = 0
    avg_duration: float = 0.0
    unique_users: int = 0
    
    def to_dict(self) -> Dict[str, Any]:
        return {
            "stat_id": self.stat_id,
            "event_type": self.event_type,
            "event_name": self.event_name,
            "stat_date": self.stat_date,
            "event_count": self.event_count,
            "user_count": self.user_count,
            "avg_duration": self.avg_duration,
            "unique_users": self.unique_users
        }
    
    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> "BehaviorStat":
        return cls(
            stat_id=data.get("stat_id", generate_id("stat")),
            event_type=data.get("event_type", ""),
            event_name=data.get("event_name", ""),
            stat_date=data.get("stat_date", ""),
            event_count=data.get("event_count", 0),
            user_count=data.get("user_count", 0),
            avg_duration=data.get("avg_duration", 0.0),
            unique_users=data.get("unique_users", 0)
        )


@dataclass
class TrajectoryEvent:
    event: str = ""
    timestamp: str = ""
    
    def to_dict(self) -> Dict[str, Any]:
        return {
            "event": self.event,
            "timestamp": self.timestamp
        }
    
    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> "TrajectoryEvent":
        return cls(
            event=data.get("event", ""),
            timestamp=data.get("timestamp", "")
        )


@dataclass
class UserTrajectory:
    trajectory_id: str = field(default_factory=lambda: generate_id("trajectory"))
    user_id: str = ""
    session_id: str = ""
    event_sequence: List[TrajectoryEvent] = field(default_factory=list)
    duration: int = 0
    created_at: str = field(default_factory=get_current_timestamp)
    
    def to_dict(self) -> Dict[str, Any]:
        return {
            "trajectory_id": self.trajectory_id,
            "user_id": self.user_id,
            "session_id": self.session_id,
            "event_sequence": [e.to_dict() for e in self.event_sequence],
            "duration": self.duration,
            "created_at": self.created_at
        }
    
    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> "UserTrajectory":
        event_sequence = data.get("event_sequence", [])
        if event_sequence and isinstance(event_sequence[0], dict):
            event_sequence = [TrajectoryEvent.from_dict(e) for e in event_sequence]
        
        return cls(
            trajectory_id=data.get("trajectory_id", generate_id("trajectory")),
            user_id=data.get("user_id", ""),
            session_id=data.get("session_id", ""),
            event_sequence=event_sequence,
            duration=data.get("duration", 0),
            created_at=data.get("created_at", get_current_timestamp())
        )


@dataclass
class UserProfile:
    profile_id: str = field(default_factory=lambda: generate_id("profile"))
    user_id: str = ""
    basic_attributes: Dict[str, Any] = field(default_factory=dict)
    behavior_attributes: Dict[str, Any] = field(default_factory=dict)
    profile_tags: List[str] = field(default_factory=list)
    updated_at: str = field(default_factory=get_current_timestamp)
    
    def to_dict(self) -> Dict[str, Any]:
        return {
            "profile_id": self.profile_id,
            "user_id": self.user_id,
            "basic_attributes": self.basic_attributes,
            "behavior_attributes": self.behavior_attributes,
            "profile_tags": self.profile_tags,
            "updated_at": self.updated_at
        }
    
    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> "UserProfile":
        return cls(
            profile_id=data.get("profile_id", generate_id("profile")),
            user_id=data.get("user_id", ""),
            basic_attributes=data.get("basic_attributes", {}),
            behavior_attributes=data.get("behavior_attributes", {}),
            profile_tags=data.get("profile_tags", []),
            updated_at=data.get("updated_at", get_current_timestamp())
        )


@dataclass
class EventRelation:
    relation_id: str = field(default_factory=lambda: generate_id("relation"))
    source_event: str = ""
    target_event: str = ""
    correlation_rate: float = 0.0
    avg_interval: float = 0.0
    analysis_date: str = ""
    
    def to_dict(self) -> Dict[str, Any]:
        return {
            "relation_id": self.relation_id,
            "source_event": self.source_event,
            "target_event": self.target_event,
            "correlation_rate": self.correlation_rate,
            "avg_interval": self.avg_interval,
            "analysis_date": self.analysis_date
        }
    
    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> "EventRelation":
        return cls(
            relation_id=data.get("relation_id", generate_id("relation")),
            source_event=data.get("source_event", ""),
            target_event=data.get("target_event", ""),
            correlation_rate=data.get("correlation_rate", 0.0),
            avg_interval=data.get("avg_interval", 0.0),
            analysis_date=data.get("analysis_date", "")
        )


@dataclass
class AbnormalBehavior:
    abnormal_id: str = field(default_factory=lambda: generate_id("abnormal"))
    user_id: str = ""
    abnormal_type: str = ""
    abnormal_data: Dict[str, Any] = field(default_factory=dict)
    detected_at: str = field(default_factory=get_current_timestamp)
    status: str = "detected"
    
    def to_dict(self) -> Dict[str, Any]:
        return {
            "abnormal_id": self.abnormal_id,
            "user_id": self.user_id,
            "abnormal_type": self.abnormal_type,
            "abnormal_data": self.abnormal_data,
            "detected_at": self.detected_at,
            "status": self.status
        }
    
    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> "AbnormalBehavior":
        return cls(
            abnormal_id=data.get("abnormal_id", generate_id("abnormal")),
            user_id=data.get("user_id", ""),
            abnormal_type=data.get("abnormal_type", ""),
            abnormal_data=data.get("abnormal_data", {}),
            detected_at=data.get("detected_at", get_current_timestamp()),
            status=data.get("status", "detected")
        )
