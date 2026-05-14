import uuid
from datetime import datetime, timezone, timedelta, date
from typing import Any, Dict, List, Optional

from behaviortrack.models import (
    BehaviorEvent,
    BehaviorStat,
    UserTrajectory,
    UserProfile,
    EventRelation,
    AbnormalBehavior,
    TrajectoryEvent
)


class TestDataBuilder:
    @staticmethod
    def generate_id(prefix: str) -> str:
        return f"{prefix}_{uuid.uuid4().hex[:8]}"
    
    @staticmethod
    def get_timestamp(offset_minutes: int = 0) -> str:
        return (
            datetime.now(timezone.utc) + timedelta(minutes=offset_minutes)
        ).isoformat()
    
    @staticmethod
    def build_behavior_event(
        user_id: Optional[str] = None,
        event_type: str = "page_view",
        event_name: str = "页面浏览",
        event_data: Optional[Dict[str, Any]] = None,
        device: Optional[Dict[str, Any]] = None,
        session_id: Optional[str] = None,
        timestamp: Optional[str] = None,
        location: Optional[Dict[str, Any]] = None
    ) -> BehaviorEvent:
        return BehaviorEvent(
            event_id=TestDataBuilder.generate_id("event"),
            user_id=user_id or TestDataBuilder.generate_id("user"),
            event_type=event_type,
            event_name=event_name,
            event_data=event_data or {"page": "home", "source": "organic"},
            device=device or {"type": "mobile", "os": "ios", "version": "15.0"},
            session_id=session_id or TestDataBuilder.generate_id("session"),
            timestamp=timestamp or TestDataBuilder.get_timestamp(),
            location=location or {"country": "中国", "city": "北京"}
        )
    
    @staticmethod
    def build_behavior_event_dict(**kwargs: Any) -> Dict[str, Any]:
        event = TestDataBuilder.build_behavior_event(**kwargs)
        return event.to_dict()
    
    @staticmethod
    def build_multiple_events(
        count: int,
        user_id: Optional[str] = None,
        event_types: Optional[List[str]] = None,
        base_user_id: Optional[str] = None
    ) -> List[BehaviorEvent]:
        events = []
        event_types = event_types or ["page_view", "click", "scroll", "purchase"]
        
        for i in range(count):
            uid = user_id or f"{base_user_id or TestDataBuilder.generate_id('user')}"
            event_type = event_types[i % len(event_types)]
            
            events.append(TestDataBuilder.build_behavior_event(
                user_id=uid,
                event_type=event_type,
                event_name=f"事件_{event_type}",
                session_id=f"{base_user_id or 'test'}_session_{i // 5}"
            ))
        
        return events
    
    @staticmethod
    def build_multiple_event_dicts(count: int, **kwargs: Any) -> List[Dict[str, Any]]:
        events = TestDataBuilder.build_multiple_events(count, **kwargs)
        return [e.to_dict() for e in events]
    
    @staticmethod
    def build_user_events_for_profile(
        user_id: str,
        active_days: int = 10,
        total_events: int = 150,
        device_type: str = "mobile",
        favorite_events: Optional[List[str]] = None,
        avg_session_duration: float = 120.0,
        include_purchases: bool = False,
        total_purchases: int = 0,
        total_spent: float = 0.0
    ) -> List[BehaviorEvent]:
        events = []
        favorite_events = favorite_events or ["page_view", "click", "page_view", "scroll", "page_view"]
        
        base_date = datetime.now(timezone.utc)
        
        for day_offset in range(active_days):
            day_date = base_date - timedelta(days=day_offset)
            events_per_day = total_events // active_days + (1 if day_offset < total_events % active_days else 0)
            
            for event_idx in range(events_per_day):
                event_type = favorite_events[event_idx % len(favorite_events)]
                event_time = day_date + timedelta(
                    hours=10 + event_idx,
                    minutes=event_idx * 15
                )
                
                events.append(TestDataBuilder.build_behavior_event(
                    user_id=user_id,
                    event_type=event_type,
                    event_name=f"{event_type}事件",
                    device={"type": device_type, "os": "ios", "version": "15.0"},
                    session_id=f"{user_id}_session_{day_offset}",
                    timestamp=event_time.isoformat(),
                    location={"country": "中国", "city": "北京"}
                ))
        
        if include_purchases:
            for i in range(total_purchases):
                purchase_time = base_date - timedelta(days=i % 5, hours=15)
                events.append(TestDataBuilder.build_behavior_event(
                    user_id=user_id,
                    event_type="purchase",
                    event_name="购买事件",
                    event_data={"amount": total_spent / max(total_purchases, 1), "currency": "CNY"},
                    device={"type": device_type, "os": "ios", "version": "15.0"},
                    session_id=f"{user_id}_session_purchase_{i}",
                    timestamp=purchase_time.isoformat(),
                    location={"country": "中国", "city": "北京"}
                ))
        
        return events
    
    @staticmethod
    def build_user_events_for_active_profile(
        user_id: str,
        active_days: int = 10,
        total_events: int = 200,
        mobile_ratio: float = 0.8
    ) -> List[BehaviorEvent]:
        events = TestDataBuilder.build_user_events_for_profile(
            user_id=user_id,
            active_days=active_days,
            total_events=total_events,
            device_type="mobile" if mobile_ratio > 0.5 else "desktop"
        )
        
        mobile_count = int(total_events * mobile_ratio)
        desktop_count = total_events - mobile_count
        
        for i, event in enumerate(events):
            if i >= mobile_count:
                event.device = {"type": "desktop", "os": "windows", "version": "11"}
        
        return events
    
    @staticmethod
    def build_behavior_stat(
        event_type: str = "page_view",
        event_count: int = 100,
        user_count: int = 50,
        stat_date: Optional[str] = None
    ) -> BehaviorStat:
        return BehaviorStat(
            stat_id=TestDataBuilder.generate_id("stat"),
            event_type=event_type,
            event_name=f"{event_type}事件",
            stat_date=stat_date or date.today().isoformat(),
            event_count=event_count,
            user_count=user_count,
            avg_duration=5.5,
            unique_users=user_count
        )
    
    @staticmethod
    def build_daily_stats(
        days: int = 7,
        event_types: Optional[List[str]] = None
    ) -> List[BehaviorStat]:
        stats = []
        event_types = event_types or ["page_view", "click", "scroll"]
        
        base_date = date.today()
        
        for day_offset in range(days):
            stat_date = (base_date - timedelta(days=day_offset)).isoformat()
            
            for event_type in event_types:
                stats.append(TestDataBuilder.build_behavior_stat(
                    event_type=event_type,
                    event_count=100 + day_offset * 10,
                    user_count=50 + day_offset * 5,
                    stat_date=stat_date
                ))
        
        return stats
    
    @staticmethod
    def build_user_trajectory(
        user_id: str,
        session_id: Optional[str] = None,
        event_count: int = 5
    ) -> UserTrajectory:
        event_sequence = []
        base_time = datetime.now(timezone.utc)
        
        for i in range(event_count):
            event_time = base_time + timedelta(minutes=i * 5)
            event_sequence.append(TrajectoryEvent(
                event=["page_view", "click", "scroll", "purchase"][i % 4],
                timestamp=event_time.isoformat()
            ))
        
        return UserTrajectory(
            trajectory_id=TestDataBuilder.generate_id("trajectory"),
            user_id=user_id,
            session_id=session_id or TestDataBuilder.generate_id("session"),
            event_sequence=event_sequence,
            duration=(event_count - 1) * 5 * 60,
            created_at=TestDataBuilder.get_timestamp()
        )
    
    @staticmethod
    def build_user_profile(
        user_id: Optional[str] = None,
        active_days: int = 15,
        total_events: int = 300,
        mobile_ratio: float = 0.9,
        tags: Optional[List[str]] = None
    ) -> UserProfile:
        user_id = user_id or TestDataBuilder.generate_id("user")
        
        basic_attributes = {
            "preferred_device": "mobile" if mobile_ratio > 0.5 else "desktop",
            "device_distribution": {
                "mobile": int(total_events * mobile_ratio),
                "desktop": int(total_events * (1 - mobile_ratio))
            },
            "most_common_city": "北京",
            "city_distribution": {"北京": total_events}
        }
        
        behavior_attributes = {
            "total_events": total_events,
            "unique_event_types": 4,
            "unique_sessions": 10,
            "active_days": active_days,
            "favorite_events": ["page_view", "click", "scroll", "purchase"],
            "event_type_distribution": {
                "page_view": 150,
                "click": 100,
                "scroll": 40,
                "purchase": 10
            },
            "most_active_hour": 14,
            "hour_distribution": {h: 10 + h for h in range(24)},
            "avg_session_duration": 180.5
        }
        
        profile_tags = tags or ["活跃用户", "移动端用户", "高频用户"]
        
        return UserProfile(
            profile_id=TestDataBuilder.generate_id("profile"),
            user_id=user_id,
            basic_attributes=basic_attributes,
            behavior_attributes=behavior_attributes,
            profile_tags=profile_tags,
            updated_at=TestDataBuilder.get_timestamp()
        )
    
    @staticmethod
    def build_event_relation(
        source_event: str = "page_view",
        target_event: str = "click",
        correlation_rate: float = 0.85,
        avg_interval: float = 5.2
    ) -> EventRelation:
        return EventRelation(
            relation_id=TestDataBuilder.generate_id("relation"),
            source_event=source_event,
            target_event=target_event,
            correlation_rate=correlation_rate,
            avg_interval=avg_interval,
            analysis_date=date.today().isoformat()
        )
    
    @staticmethod
    def build_abnormal_behavior(
        user_id: Optional[str] = None,
        abnormal_type: str = "high_frequency",
        event_count_per_minute: int = 150
    ) -> AbnormalBehavior:
        return AbnormalBehavior(
            abnormal_id=TestDataBuilder.generate_id("abnormal"),
            user_id=user_id or TestDataBuilder.generate_id("user"),
            abnormal_type=abnormal_type,
            abnormal_data={
                "event_count_per_minute": event_count_per_minute,
                "last_event_type": "click"
            },
            detected_at=TestDataBuilder.get_timestamp(),
            status="detected"
        )
    
    @staticmethod
    def build_high_concurrency_events(
        user_count: int = 10,
        events_per_user: int = 20
    ) -> List[Dict[str, Any]]:
        events = []
        
        for user_idx in range(user_count):
            user_id = f"concurrent_user_{user_idx:04d}"
            
            for event_idx in range(events_per_user):
                events.append(TestDataBuilder.build_behavior_event_dict(
                    user_id=user_id,
                    event_type=["page_view", "click", "scroll"][event_idx % 3],
                    session_id=f"{user_id}_session_001"
                ))
        
        return events
    
    @staticmethod
    def build_invalid_events_for_testing() -> List[Dict[str, Any]]:
        return [
            {},
            {"user_id": "test_user"},
            {"event_type": "click"},
            {"user_id": 123, "event_type": "click"},
            {"user_id": "test", "event_type": 123},
            {"user_id": "test", "event_type": "click", "event_data": "not_a_dict"},
            {"user_id": "test", "event_type": "click", "device": "not_a_dict"},
            {"user_id": "test", "event_type": "click", "location": "not_a_dict"},
            {"user_id": "", "event_type": "click"},
            {"user_id": "test", "event_type": ""}
        ]
    
    @staticmethod
    def build_user_events_for_tag_testing(
        user_id: str,
        active_days: int = 2,
        total_events: int = 10,
        device_type: str = "desktop",
        avg_session_duration: float = 30.0,
        favorite_events: Optional[List[str]] = None
    ) -> List[BehaviorEvent]:
        return TestDataBuilder.build_user_events_for_profile(
            user_id=user_id,
            active_days=active_days,
            total_events=total_events,
            device_type=device_type,
            favorite_events=favorite_events
        )
    
    @staticmethod
    def build_statistics_for_cache_testing() -> List[Dict[str, Any]]:
        return [
            {
                "cache_key": "overview",
                "data": {
                    "success": True,
                    "overview": {
                        "total_events": 1000,
                        "total_users": 100,
                        "today_events": 50,
                        "today_users": 20
                    }
                }
            },
            {
                "cache_key": "daily_stats|start_date:2026-05-01|end_date:2026-05-07",
                "data": {
                    "success": True,
                    "daily_stats": [
                        {"date": "2026-05-01", "total_events": 100, "total_users": 50},
                        {"date": "2026-05-02", "total_events": 150, "total_users": 75}
                    ]
                }
            }
        ]
    
    @staticmethod
    def build_normal_user_events(user_id: str, count: int = 50) -> List[Dict[str, Any]]:
        events = []
        base_time = datetime.now(timezone.utc)
        
        for i in range(count):
            event_time = base_time + timedelta(hours=10 + (i // 10), minutes=(i * 3) % 60)
            events.append(TestDataBuilder.build_behavior_event_dict(
                user_id=user_id,
                event_type=["page_view", "click", "scroll", "page_view", "click"][i % 5],
                session_id=f"{user_id}_session_{i // 10}",
                timestamp=event_time.isoformat(),
                device={"type": "mobile", "os": "ios", "version": "15.0"},
                location={"country": "中国", "city": "北京", "latitude": 39.9, "longitude": 116.4}
            ))
        
        return events
    
    @staticmethod
    def build_high_frequency_events(user_id: str, events_per_session: int = 150) -> List[Dict[str, Any]]:
        session_id = TestDataBuilder.generate_id("session")
        base_time = datetime.now(timezone.utc)
        
        events = []
        for i in range(events_per_session):
            event_time = base_time + timedelta(seconds=i)
            events.append(TestDataBuilder.build_behavior_event_dict(
                user_id=user_id,
                event_type="click",
                session_id=session_id,
                timestamp=event_time.isoformat()
            ))
        
        return events
    
    @staticmethod
    def build_abnormal_time_events(user_id: str, hour: int = 3, count: int = 20) -> List[Dict[str, Any]]:
        events = []
        base_date = datetime.now(timezone.utc).date()
        
        for i in range(count):
            event_time = datetime(
                year=base_date.year,
                month=base_date.month,
                day=base_date.day,
                hour=hour,
                minute=i * 3,
                tzinfo=timezone.utc
            )
            events.append(TestDataBuilder.build_behavior_event_dict(
                user_id=user_id,
                event_type="page_view",
                session_id=f"{user_id}_session_night",
                timestamp=event_time.isoformat()
            ))
        
        return events
    
    @staticmethod
    def build_geographic_anomaly_events(user_id: str) -> List[Dict[str, Any]]:
        events = []
        base_time = datetime.now(timezone.utc)
        
        events.append(TestDataBuilder.build_behavior_event_dict(
            user_id=user_id,
            event_type="login",
            session_id=f"{user_id}_session_geo1",
            timestamp=base_time.isoformat(),
            location={"country": "中国", "city": "北京", "latitude": 39.9, "longitude": 116.4}
        ))
        
        events.append(TestDataBuilder.build_behavior_event_dict(
            user_id=user_id,
            event_type="login",
            session_id=f"{user_id}_session_geo2",
            timestamp=(base_time + timedelta(minutes=30)).isoformat(),
            location={"country": "美国", "city": "纽约", "latitude": 40.7, "longitude": -74.0}
        ))
        
        return events
    
    @staticmethod
    def build_suspicious_pattern_events(user_id: str, repeat_count: int = 5) -> List[Dict[str, Any]]:
        session_id = TestDataBuilder.generate_id("session")
        base_time = datetime.now(timezone.utc)
        
        pattern = ["page_view", "add_to_cart", "remove_from_cart"]
        
        events = []
        for repeat in range(repeat_count):
            for idx, event_type in enumerate(pattern):
                event_time = base_time + timedelta(seconds=repeat * 10 + idx)
                events.append(TestDataBuilder.build_behavior_event_dict(
                    user_id=user_id,
                    event_type=event_type,
                    session_id=session_id,
                    timestamp=event_time.isoformat()
                ))
        
        return events
    
    @staticmethod
    def build_rapid_purchase_events(user_id: str, purchase_count: int = 8) -> List[Dict[str, Any]]:
        session_id = TestDataBuilder.generate_id("session")
        base_time = datetime.now(timezone.utc)
        
        events = []
        for i in range(purchase_count):
            event_time = base_time + timedelta(minutes=i)
            events.append(TestDataBuilder.build_behavior_event_dict(
                user_id=user_id,
                event_type="purchase",
                session_id=session_id,
                timestamp=event_time.isoformat(),
                event_data={"amount": 100.0 + i * 50, "currency": "CNY"}
            ))
        
        return events
    
    @staticmethod
    def build_failed_login_events(user_id: str, failed_count: int = 12) -> List[Dict[str, Any]]:
        events = []
        base_time = datetime.now(timezone.utc)
        
        for i in range(failed_count):
            event_time = base_time + timedelta(seconds=i * 10)
            events.append(TestDataBuilder.build_behavior_event_dict(
                user_id=user_id,
                event_type="login_failed",
                session_id=f"{user_id}_login_attempt",
                timestamp=event_time.isoformat()
            ))
        
        return events
    
    @staticmethod
    def build_abnormal_sequence_events(user_id: str) -> List[Dict[str, Any]]:
        session_id = TestDataBuilder.generate_id("session")
        base_time = datetime.now(timezone.utc)
        
        abnormal_sequence = [
            "page_view", "click", "export",
            "page_view", "click", "export",
            "page_view", "click", "export"
        ]
        
        events = []
        for idx, event_type in enumerate(abnormal_sequence):
            event_time = base_time + timedelta(seconds=idx * 5)
            events.append(TestDataBuilder.build_behavior_event_dict(
                user_id=user_id,
                event_type=event_type,
                session_id=session_id,
                timestamp=event_time.isoformat()
            ))
        
        return events
    
    @staticmethod
    def build_data_export_events(user_id: str, export_count: int = 8) -> List[Dict[str, Any]]:
        session_id = TestDataBuilder.generate_id("session")
        base_time = datetime.now(timezone.utc)
        
        events = []
        for i in range(export_count):
            event_time = base_time + timedelta(minutes=i * 5)
            events.append(TestDataBuilder.build_behavior_event_dict(
                user_id=user_id,
                event_type="export",
                session_id=session_id,
                timestamp=event_time.isoformat(),
                event_data={"format": "csv", "size_mb": 50 + i * 10}
            ))
        
        return events
    
    @staticmethod
    def build_rules_config() -> Dict[str, Any]:
        return {
            "rules": [
                {
                    "rule_id": "custom_frequency",
                    "rule_name": "自定义频率检测",
                    "rule_type": "frequency",
                    "enabled": True,
                    "threshold": {"max_events_per_minute": 50},
                    "description": "检测超过50次/分钟的高频事件",
                    "severity": "high",
                    "action": "block"
                },
                {
                    "rule_id": "custom_temporal",
                    "rule_name": "工作时间检测",
                    "rule_type": "temporal",
                    "enabled": True,
                    "threshold": {"normal_hours_start": 9, "normal_hours_end": 18},
                    "description": "检测工作日非工作时间活动",
                    "severity": "low",
                    "action": "monitor"
                }
            ]
        }
    
    @staticmethod
    def build_user_profile_with_behavior_context(user_id: str) -> Dict[str, Any]:
        return {
            "user_id": user_id,
            "demographics": {
                "age_group": "25-34",
                "gender": "male",
                "country": "CN",
                "city": "北京"
            },
            "behavior": {
                "total_events": 500,
                "active_days": 30,
                "normal_locations": [
                    {"latitude": 39.9, "longitude": 116.4, "city": "北京"},
                    {"latitude": 31.2, "longitude": 121.5, "city": "上海"}
                ],
                "used_devices": [
                    {"type": "mobile", "os": "ios"},
                    {"type": "desktop", "os": "windows"}
                ],
                "most_active_hours": [10, 11, 14, 15, 16]
            },
            "tags": [
                {"tag": "active_user", "category": "engagement", "confidence": 0.9},
                {"tag": "mobile_user", "category": "behavior", "confidence": 0.85}
            ]
        }


class MockStorageHelper:
    @staticmethod
    def create_mock_event_query_results(events: List[BehaviorEvent]):
        class MockCursor:
            def __init__(self, data):
                self._data = data
                self._index = 0
                self._sort_key = None
                self._sort_order = None
                self._limit = None
            
            def sort(self, key, order):
                self._sort_key = key
                self._sort_order = order
                return self
            
            def skip(self, count):
                self._index = count
                return self
            
            def limit(self, count):
                self._limit = count
                return self
            
            def __iter__(self):
                data = self._data[self._index:]
                if self._limit:
                    data = data[:self._limit]
                return iter(data)
        
        return MockCursor([e.to_dict() for e in events])
