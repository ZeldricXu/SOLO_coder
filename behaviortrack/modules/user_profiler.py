import logging
from collections import Counter
from datetime import datetime, timezone, date, timedelta
from typing import Any, Dict, List, Optional

from dateutil.parser import parse as parse_date

from ..config import settings
from ..storage import MongoStorage
from ..models import UserProfile, BehaviorEvent


logger = logging.getLogger(__name__)


class UserProfiler:
    def __init__(self) -> None:
        self.storage = MongoStorage()
        
        self._incremental_profiles: Dict[str, Dict[str, Any]] = {}
    
    def build_profile(self, user_id: str) -> Dict[str, Any]:
        try:
            events = self.storage.find_events(
                {"user_id": user_id},
                limit=10000
            )
            
            if not events:
                return {
                    "success": False,
                    "error": "No events found for user"
                }
            
            basic_attributes = self._calculate_basic_attributes(events)
            behavior_attributes = self._calculate_behavior_attributes(events)
            profile_tags = self._generate_profile_tags(basic_attributes, behavior_attributes, events)
            
            profile = UserProfile(
                user_id=user_id,
                basic_attributes=basic_attributes,
                behavior_attributes=behavior_attributes,
                profile_tags=profile_tags
            )
            
            self.storage.upsert_profile(profile)
            
            return {
                "success": True,
                "profile": profile.to_dict()
            }
            
        except Exception as e:
            logger.exception(f"Error building profile for user {user_id}: {str(e)}")
            return {
                "success": False,
                "error": str(e)
            }
    
    def update_profile_incrementally(
        self,
        user_id: str,
        new_events: List[BehaviorEvent]
    ) -> Dict[str, Any]:
        try:
            if not new_events:
                return {
                    "success": False,
                    "error": "No new events provided"
                }
            
            existing_profile = self.storage.find_profile_by_user_id(user_id)
            
            if existing_profile is None:
                return self.build_profile(user_id)
            
            delta_basic = self._calculate_delta_basic_attributes(new_events)
            delta_behavior = self._calculate_delta_behavior_attributes(new_events)
            
            merged_basic = self._merge_attributes(
                existing_profile.basic_attributes,
                delta_basic
            )
            
            merged_behavior = self._merge_behavior_attributes(
                existing_profile.behavior_attributes,
                delta_behavior
            )
            
            existing_events = self._get_existing_events_for_incremental(
                user_id,
                existing_profile.behavior_attributes
            )
            
            merged_tags = self._generate_profile_tags(
                merged_basic,
                merged_behavior,
                existing_events + new_events
            )
            
            updated_profile = UserProfile(
                user_id=user_id,
                basic_attributes=merged_basic,
                behavior_attributes=merged_behavior,
                profile_tags=merged_tags
            )
            updated_profile.profile_id = existing_profile.profile_id
            
            self.storage.upsert_profile(updated_profile)
            
            return {
                "success": True,
                "profile": updated_profile.to_dict(),
                "update_type": "incremental",
                "events_processed": len(new_events)
            }
            
        except Exception as e:
            logger.exception(f"Error updating profile incrementally for user {user_id}: {str(e)}")
            return {
                "success": False,
                "error": str(e)
            }
    
    def queue_incremental_update(
        self,
        user_id: str,
        event: BehaviorEvent
    ) -> Dict[str, Any]:
        if user_id not in self._incremental_profiles:
            self._incremental_profiles[user_id] = {
                "events": [],
                "last_update": None
            }
        
        self._incremental_profiles[user_id]["events"].append(event)
        
        event_count = len(self._incremental_profiles[user_id]["events"])
        logger.debug(
            f"Queued incremental update for user {user_id}, "
            f"pending events: {event_count}"
        )
        
        return {
            "success": True,
            "queued": True,
            "pending_events": event_count
        }
    
    def flush_incremental_updates(
        self,
        min_events_threshold: int = 10
    ) -> Dict[str, Any]:
        if not self._incremental_profiles:
            return {
                "success": True,
                "processed_count": 0,
                "message": "No pending incremental updates"
            }
        
        processed_count = 0
        errors = []
        
        for user_id, data in list(self._incremental_profiles.items()):
            events = data["events"]
            
            if len(events) >= min_events_threshold:
                try:
                    result = self.update_profile_incrementally(user_id, events)
                    if result.get("success"):
                        processed_count += 1
                        del self._incremental_profiles[user_id]
                    else:
                        errors.append({
                            "user_id": user_id,
                            "error": result.get("error", "Unknown error")
                        })
                except Exception as e:
                    logger.exception(f"Error flushing incremental update for user {user_id}: {str(e)}")
                    errors.append({
                        "user_id": user_id,
                        "error": str(e)
                    })
        
        return {
            "success": len(errors) == 0,
            "processed_count": processed_count,
            "remaining_count": len(self._incremental_profiles),
            "errors": errors
        }
    
    def get_incremental_queue_stats(self) -> Dict[str, Any]:
        total_events = sum(
            len(data["events"]) for data in self._incremental_profiles.values()
        )
        
        return {
            "pending_users": len(self._incremental_profiles),
            "total_pending_events": total_events,
            "users": [
                {
                    "user_id": user_id,
                    "pending_events": len(data["events"])
                }
                for user_id, data in self._incremental_profiles.items()
            ]
        }
    
    def clear_incremental_queue(self) -> None:
        self._incremental_profiles.clear()
        logger.info("Cleared incremental profile update queue")
    
    def _calculate_delta_basic_attributes(
        self,
        events: List[BehaviorEvent]
    ) -> Dict[str, Any]:
        attributes: Dict[str, Any] = {}
        
        device_types = Counter()
        os_types = Counter()
        cities = Counter()
        countries = Counter()
        
        for event in events:
            device = event.device or {}
            location = event.location or {}
            
            device_type = device.get("type", "unknown")
            device_types[device_type] += 1
            
            os_type = device.get("os", "unknown")
            os_types[os_type] += 1
            
            if location:
                city = location.get("city", "")
                country = location.get("country", "")
                if city:
                    cities[city] += 1
                if country:
                    countries[country] += 1
        
        if device_types:
            attributes["device_distribution_delta"] = dict(device_types)
        
        if os_types:
            attributes["os_distribution_delta"] = dict(os_types)
        
        if cities:
            attributes["city_distribution_delta"] = dict(cities)
        
        if countries:
            attributes["country_distribution_delta"] = dict(countries)
        
        return attributes
    
    def _calculate_delta_behavior_attributes(
        self,
        events: List[BehaviorEvent]
    ) -> Dict[str, Any]:
        attributes: Dict[str, Any] = {}
        
        event_types = Counter()
        session_ids = set()
        active_dates = set()
        hour_counts = Counter()
        session_durations = []
        last_event_times: Dict[str, datetime] = {}
        
        for event in events:
            event_types[event.event_type] += 1
            
            if event.session_id:
                session_ids.add(event.session_id)
            
            try:
                event_ts = parse_date(event.timestamp.replace("Z", "+00:00"))
                active_dates.add(event_ts.date())
                hour_counts[event_ts.hour] += 1
                
                if event.session_id:
                    if event.session_id not in last_event_times:
                        last_event_times[event.session_id] = event_ts
                    else:
                        duration = (event_ts - last_event_times[event.session_id]).total_seconds()
                        session_durations.append(duration)
                        last_event_times[event.session_id] = event_ts
            except Exception:
                pass
        
        attributes["new_events_count"] = len(events)
        attributes["new_sessions_count"] = len(session_ids)
        attributes["new_active_days"] = len(active_dates)
        
        if event_types:
            attributes["event_types_delta"] = dict(event_types)
        
        if hour_counts:
            attributes["hour_distribution_delta"] = dict(hour_counts)
        
        if session_durations:
            attributes["new_avg_session_duration"] = round(
                sum(session_durations) / len(session_durations),
                2
            )
        
        return attributes
    
    def _merge_attributes(
        self,
        existing: Dict[str, Any],
        delta: Dict[str, Any]
    ) -> Dict[str, Any]:
        merged = dict(existing)
        
        device_delta = delta.get("device_distribution_delta", {})
        if device_delta:
            existing_devices = merged.get("device_distribution", {})
            for device_type, count in device_delta.items():
                existing_devices[device_type] = existing_devices.get(device_type, 0) + count
            merged["device_distribution"] = existing_devices
            
            most_common_device = max(
                existing_devices.items(),
                key=lambda x: x[1]
            )[0] if existing_devices else None
            if most_common_device:
                merged["preferred_device"] = most_common_device
        
        os_delta = delta.get("os_distribution_delta", {})
        if os_delta:
            existing_os = merged.get("os_distribution", {})
            for os_type, count in os_delta.items():
                existing_os[os_type] = existing_os.get(os_type, 0) + count
            merged["os_distribution"] = existing_os
        
        city_delta = delta.get("city_distribution_delta", {})
        if city_delta:
            existing_cities = merged.get("city_distribution", {})
            for city, count in city_delta.items():
                existing_cities[city] = existing_cities.get(city, 0) + count
            merged["city_distribution"] = existing_cities
            
            most_common_city = max(
                existing_cities.items(),
                key=lambda x: x[1]
            )[0] if existing_cities else None
            if most_common_city:
                merged["most_common_city"] = most_common_city
        
        country_delta = delta.get("country_distribution_delta", {})
        if country_delta:
            existing_countries = merged.get("country_distribution", {})
            for country, count in country_delta.items():
                existing_countries[country] = existing_countries.get(country, 0) + count
            merged["country_distribution"] = existing_countries
        
        return merged
    
    def _merge_behavior_attributes(
        self,
        existing: Dict[str, Any],
        delta: Dict[str, Any]
    ) -> Dict[str, Any]:
        merged = dict(existing)
        
        merged["total_events"] = existing.get("total_events", 0) + delta.get("new_events_count", 0)
        merged["unique_sessions"] = existing.get("unique_sessions", 0) + delta.get("new_sessions_count", 0)
        merged["active_days"] = existing.get("active_days", 0) + delta.get("new_active_days", 0)
        
        event_delta = delta.get("event_types_delta", {})
        if event_delta:
            existing_events = merged.get("event_type_distribution", {})
            for event_type, count in event_delta.items():
                existing_events[event_type] = existing_events.get(event_type, 0) + count
            merged["event_type_distribution"] = existing_events
            
            sorted_events = sorted(
                existing_events.items(),
                key=lambda x: x[1],
                reverse=True
            )
            merged["favorite_events"] = [e[0] for e in sorted_events[:5]]
            merged["unique_event_types"] = len(existing_events)
        
        hour_delta = delta.get("hour_distribution_delta", {})
        if hour_delta:
            existing_hours = merged.get("hour_distribution", {})
            for hour, count in hour_delta.items():
                existing_hours[hour] = existing_hours.get(hour, 0) + count
            merged["hour_distribution"] = existing_hours
            
            most_active_hour = max(
                existing_hours.items(),
                key=lambda x: x[1]
            )[0] if existing_hours else None
            if most_active_hour is not None:
                merged["most_active_hour"] = most_active_hour
        
        new_avg_duration = delta.get("new_avg_session_duration")
        if new_avg_duration is not None:
            existing_avg = existing.get("avg_session_duration", 0)
            existing_count = existing.get("total_events", 1)
            delta_count = delta.get("new_events_count", 1)
            
            weighted_avg = (
                existing_avg * existing_count + new_avg_duration * delta_count
            ) / (existing_count + delta_count)
            merged["avg_session_duration"] = round(weighted_avg, 2)
        
        return merged
    
    def _get_existing_events_for_incremental(
        self,
        user_id: str,
        behavior_attributes: Dict[str, Any]
    ) -> List[BehaviorEvent]:
        existing_events = self.storage.find_events(
            {"user_id": user_id},
            limit=min(1000, behavior_attributes.get("total_events", 0))
        )
        return existing_events
    
    def _calculate_basic_attributes(
        self,
        events: List[Any]
    ) -> Dict[str, Any]:
        attributes: Dict[str, Any] = {}
        
        device_types = Counter()
        os_types = Counter()
        locations = Counter()
        cities = Counter()
        countries = Counter()
        
        for event in events:
            device = event.device or {}
            location = event.location or {}
            
            device_type = device.get("type", "unknown")
            device_types[device_type] += 1
            
            os_type = device.get("os", "unknown")
            os_types[os_type] += 1
            
            if location:
                city = location.get("city", "")
                country = location.get("country", "")
                if city:
                    cities[city] += 1
                if country:
                    countries[country] += 1
        
        if device_types:
            most_common_device = device_types.most_common(1)[0][0]
            attributes["preferred_device"] = most_common_device
            attributes["device_distribution"] = dict(device_types)
        
        if os_types:
            attributes["os_distribution"] = dict(os_types)
        
        if cities:
            most_common_city = cities.most_common(1)[0][0]
            attributes["most_common_city"] = most_common_city
            attributes["city_distribution"] = dict(cities)
        
        if countries:
            attributes["country_distribution"] = dict(countries)
        
        return attributes
    
    def _calculate_behavior_attributes(
        self,
        events: List[Any]
    ) -> Dict[str, Any]:
        attributes: Dict[str, Any] = {}
        
        event_types = Counter()
        event_names = Counter()
        session_ids = set()
        active_dates = set()
        hour_counts = Counter()
        session_durations = []
        last_event_times: Dict[str, datetime] = {}
        
        for event in events:
            event_types[event.event_type] += 1
            if event.event_name:
                event_names[event.event_name] += 1
            
            if event.session_id:
                session_ids.add(event.session_id)
            
            try:
                event_ts = parse_date(event.timestamp.replace("Z", "+00:00"))
                active_dates.add(event_ts.date())
                hour_counts[event_ts.hour] += 1
                
                if event.session_id:
                    if event.session_id not in last_event_times:
                        last_event_times[event.session_id] = event_ts
                    else:
                        duration = (event_ts - last_event_times[event.session_id]).total_seconds()
                        session_durations.append(duration)
                        last_event_times[event.session_id] = event_ts
            except Exception:
                pass
        
        attributes["total_events"] = len(events)
        attributes["unique_event_types"] = len(event_types)
        attributes["unique_sessions"] = len(session_ids)
        attributes["active_days"] = len(active_dates)
        
        if event_types:
            top_events = [e for e, c in event_types.most_common(5)]
            attributes["favorite_events"] = top_events
            attributes["event_type_distribution"] = dict(event_types)
        
        if hour_counts:
            most_active_hour = hour_counts.most_common(1)[0][0]
            attributes["most_active_hour"] = most_active_hour
            attributes["hour_distribution"] = dict(hour_counts)
        
        if session_durations:
            avg_duration = sum(session_durations) / len(session_durations)
            attributes["avg_session_duration"] = round(avg_duration, 2)
        
        return attributes
    
    def _generate_profile_tags(
        self,
        basic_attributes: Dict[str, Any],
        behavior_attributes: Dict[str, Any],
        events: List[Any]
    ) -> List[str]:
        tags: List[str] = []
        
        active_days = behavior_attributes.get("active_days", 0)
        if active_days >= settings.ACTIVE_DAYS_THRESHOLD:
            tags.append("活跃用户")
        elif active_days >= 3:
            tags.append("一般活跃用户")
        else:
            tags.append("低活跃用户")
        
        device_distribution = basic_attributes.get("device_distribution", {})
        total_devices = sum(device_distribution.values())
        mobile_count = device_distribution.get("mobile", 0)
        
        if total_devices > 0 and mobile_count / total_devices >= settings.MOBILE_RATIO_THRESHOLD:
            tags.append("移动端用户")
        
        preferred_device = basic_attributes.get("preferred_device", "")
        if preferred_device == "desktop":
            tags.append("桌面端用户")
        elif preferred_device == "tablet":
            tags.append("平板用户")
        
        total_events = behavior_attributes.get("total_events", 0)
        if total_events >= settings.EVENT_FREQUENCY_THRESHOLD:
            tags.append("高频用户")
        elif total_events >= 50:
            tags.append("中频用户")
        else:
            tags.append("低频用户")
        
        avg_duration = behavior_attributes.get("avg_session_duration", 0)
        if avg_duration >= 300:
            tags.append("长会话用户")
        elif avg_duration >= 60:
            tags.append("中等会话用户")
        
        favorite_events = behavior_attributes.get("favorite_events", [])
        if "page_view" in favorite_events[:3]:
            tags.append("浏览型用户")
        if "click" in favorite_events[:3]:
            tags.append("交互型用户")
        if "purchase" in favorite_events:
            tags.append("消费型用户")
        
        return tags
    
    def get_profile(self, user_id: str) -> Dict[str, Any]:
        try:
            profile = self.storage.find_profile_by_user_id(user_id)
            
            if profile:
                return {
                    "success": True,
                    "profile": profile.to_dict()
                }
            
            build_result = self.build_profile(user_id)
            if build_result.get("success"):
                return build_result
            
            return {
                "success": False,
                "error": "Profile not found and could not be built"
            }
            
        except Exception as e:
            logger.exception(f"Error getting profile for user {user_id}: {str(e)}")
            return {
                "success": False,
                "error": str(e)
            }
    
    def update_profile(self, user_id: str, updates: Dict[str, Any]) -> Dict[str, Any]:
        try:
            profile = self.storage.find_profile_by_user_id(user_id)
            
            if not profile:
                return {
                    "success": False,
                    "error": "Profile not found"
                }
            
            if "basic_attributes" in updates:
                profile.basic_attributes.update(updates["basic_attributes"])
            
            if "behavior_attributes" in updates:
                profile.behavior_attributes.update(updates["behavior_attributes"])
            
            if "profile_tags" in updates:
                profile.profile_tags = updates["profile_tags"]
            
            profile.updated_at = datetime.now(timezone.utc).isoformat()
            
            self.storage.upsert_profile(profile)
            
            return {
                "success": True,
                "profile": profile.to_dict()
            }
            
        except Exception as e:
            logger.exception(f"Error updating profile for user {user_id}: {str(e)}")
            return {
                "success": False,
                "error": str(e)
            }
    
    def search_profiles_by_tags(
        self,
        tags: List[str],
        limit: int = 100
    ) -> Dict[str, Any]:
        try:
            query = {"profile_tags": {"$all": tags}}
            profiles = self.storage.find_profiles(query, limit=limit)
            
            return {
                "success": True,
                "profiles": [p.to_dict() for p in profiles],
                "count": len(profiles)
            }
            
        except Exception as e:
            logger.exception(f"Error searching profiles: {str(e)}")
            return {
                "success": False,
                "error": str(e)
            }
    
    def get_tag_distribution(self) -> Dict[str, Any]:
        try:
            pipeline = [
                {"$unwind": "$profile_tags"},
                {"$group": {"_id": "$profile_tags", "count": {"$sum": 1}}},
                {"$sort": {"count": -1}}
            ]
            
            results = list(self.storage.profiles_collection.aggregate(pipeline))
            
            distribution = []
            total = sum(r["count"] for r in results)
            
            for result in results:
                distribution.append({
                    "tag": result["_id"],
                    "count": result["count"],
                    "percentage": round(result["count"] / total * 100, 2) if total > 0 else 0
                })
            
            return {
                "success": True,
                "distribution": distribution
            }
            
        except Exception as e:
            logger.exception(f"Error getting tag distribution: {str(e)}")
            return {
                "success": False,
                "error": str(e)
            }
