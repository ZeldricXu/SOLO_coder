import json
import logging
import os
from collections import defaultdict, Counter
from dataclasses import dataclass, field
from datetime import datetime, timedelta
from typing import Any, Dict, List, Optional, Callable
import threading

from ..models import AbnormalBehavior
from ..config import settings
from .redis_client import redis_manager


logger = logging.getLogger(__name__)


@dataclass
class DetectionRule:
    rule_id: str
    rule_name: str
    rule_type: str
    enabled: bool = True
    threshold: Any = None
    description: str = ""
    severity: str = "medium"
    action: str = "flag"
    
    def to_dict(self) -> Dict[str, Any]:
        return {
            "rule_id": self.rule_id,
            "rule_name": self.rule_name,
            "rule_type": self.rule_type,
            "enabled": self.enabled,
            "threshold": self.threshold,
            "description": self.description,
            "severity": self.severity,
            "action": self.action
        }
    
    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> "DetectionRule":
        return cls(
            rule_id=data.get("rule_id", ""),
            rule_name=data.get("rule_name", ""),
            rule_type=data.get("rule_type", ""),
            enabled=data.get("enabled", True),
            threshold=data.get("threshold"),
            description=data.get("description", ""),
            severity=data.get("severity", "medium"),
            action=data.get("action", "flag")
        )


DEFAULT_RULES_CONFIG = {
    "rules": [
        {
            "rule_id": "frequent_events",
            "rule_name": "高频事件检测",
            "rule_type": "frequency",
            "enabled": True,
            "threshold": {"max_events_per_minute": 100},
            "description": "检测短时间内高频次事件，可能为爬虫或自动化工具",
            "severity": "high",
            "action": "flag"
        },
        {
            "rule_id": "rapid_session_creation",
            "rule_name": "快速会话创建检测",
            "rule_type": "frequency",
            "enabled": True,
            "threshold": {"max_sessions_per_hour": 10},
            "description": "检测用户短时间内创建大量会话",
            "severity": "medium",
            "action": "flag"
        },
        {
            "rule_id": "unusual_hours",
            "rule_name": "异常时段活动检测",
            "rule_type": "temporal",
            "enabled": True,
            "threshold": {"normal_hours_start": 6, "normal_hours_end": 22},
            "description": "检测用户在非活跃时段的异常活动",
            "severity": "low",
            "action": "monitor"
        },
        {
            "rule_id": "geographic_anomaly",
            "rule_name": "地理位置异常检测",
            "rule_type": "geographic",
            "enabled": True,
            "threshold": {"max_distance_km": 500},
            "description": "检测用户在短时间内从远距离地点登录",
            "severity": "high",
            "action": "block"
        },
        {
            "rule_id": "device_switching",
            "rule_name": "设备频繁切换检测",
            "rule_type": "device",
            "enabled": True,
            "threshold": {"max_devices_per_hour": 3},
            "description": "检测用户短时间内频繁切换设备",
            "severity": "medium",
            "action": "flag"
        },
        {
            "rule_id": "sequence_anomaly",
            "rule_name": "行为序列异常检测",
            "rule_type": "sequence",
            "enabled": True,
            "threshold": {"min_confidence": 0.7},
            "description": "检测不符合正常行为模式的事件序列",
            "severity": "medium",
            "action": "flag"
        },
        {
            "rule_id": "suspicious_pattern",
            "rule_name": "可疑行为模式检测",
            "rule_type": "pattern",
            "enabled": True,
            "threshold": {"suspicious_patterns": ["view_item", "add_to_cart", "remove_from_cart", "view_item", "add_to_cart"]},
            "description": "检测重复的可疑行为模式",
            "severity": "high",
            "action": "flag"
        },
        {
            "rule_id": "rapid_purchases",
            "rule_name": "快速购买检测",
            "rule_type": "transaction",
            "enabled": True,
            "threshold": {"max_purchases_per_hour": 5, "min_interval_seconds": 60},
            "description": "检测用户在短时间内频繁进行购买操作",
            "severity": "high",
            "action": "block"
        },
        {
            "rule_id": "credential_stuffing",
            "rule_name": "凭证填充检测",
            "rule_type": "auth",
            "enabled": True,
            "threshold": {"max_failed_logins": 10, "time_window_seconds": 300},
            "description": "检测大量失败的登录尝试",
            "severity": "critical",
            "action": "block"
        },
        {
            "rule_id": "data_exfiltration",
            "rule_name": "数据导出异常检测",
            "rule_type": "data",
            "enabled": True,
            "threshold": {"max_export_size_mb": 100, "max_exports_per_hour": 5},
            "description": "检测异常大量的数据导出操作",
            "severity": "high",
            "action": "block"
        }
    ]
}


class RuleConfigManager:
    def __init__(
        self, 
        config_path: Optional[str] = None,
        use_redis: Optional[bool] = None
    ):
        self._config_path = config_path or settings.DETECTION_RULES_CONFIG_PATH
        self._use_redis = use_redis if use_redis is not None else settings.USE_REDIS_QUEUE
        self._redis_key = "behaviortrack:detection_rules"
        self._lock = threading.Lock()
    
    def _load_from_file(self) -> Dict[str, Any]:
        if not os.path.exists(self._config_path):
            logger.warning(f"Rules config file not found: {self._config_path}, using defaults")
            return DEFAULT_RULES_CONFIG
        
        try:
            with open(self._config_path, 'r', encoding='utf-8') as f:
                return json.load(f)
        except Exception as e:
            logger.exception(f"Failed to load rules from file: {str(e)}")
            return DEFAULT_RULES_CONFIG
    
    def _load_from_redis(self) -> Optional[Dict[str, Any]]:
        if not self._use_redis or not redis_manager.is_connected():
            return None
        
        try:
            data = redis_manager.get_cache(self._redis_key)
            if data:
                return data
        except Exception as e:
            logger.exception(f"Failed to load rules from Redis: {str(e)}")
        
        return None
    
    def _save_to_redis(self, rules_data: Dict[str, Any]) -> bool:
        if not self._use_redis or not redis_manager.is_connected():
            return False
        
        try:
            return redis_manager.set_cache(self._redis_key, rules_data)
        except Exception as e:
            logger.exception(f"Failed to save rules to Redis: {str(e)}")
            return False
    
    def _save_to_file(self, rules_data: Dict[str, Any]) -> bool:
        try:
            os.makedirs(os.path.dirname(self._config_path), exist_ok=True)
            with open(self._config_path, 'w', encoding='utf-8') as f:
                json.dump(rules_data, f, ensure_ascii=False, indent=2)
            return True
        except Exception as e:
            logger.exception(f"Failed to save rules to file: {str(e)}")
            return False
    
    def load_rules(self) -> List[DetectionRule]:
        with self._lock:
            rules_data = self._load_from_redis()
            if rules_data is None:
                rules_data = self._load_from_file()
                if self._use_redis and redis_manager.is_connected():
                    self._save_to_redis(rules_data)
            
            rules = []
            for rule_data in rules_data.get("rules", []):
                try:
                    rule = DetectionRule.from_dict(rule_data)
                    if rule.rule_id:
                        rules.append(rule)
                except Exception as e:
                    logger.warning(f"Failed to parse rule: {str(e)}")
            
            logger.info(f"Loaded {len(rules)} detection rules")
            return rules
    
    def save_rules(self, rules: List[DetectionRule]) -> bool:
        with self._lock:
            rules_data = {
                "version": "1.0",
                "updated_at": datetime.now().isoformat(),
                "rules": [rule.to_dict() for rule in rules]
            }
            
            file_success = self._save_to_file(rules_data)
            redis_success = self._save_to_redis(rules_data)
            
            return file_success or redis_success
    
    def get_config_path(self) -> str:
        return self._config_path


class AbnormalDetector:
    def __init__(self, rules_config_path: Optional[str] = None):
        self._rules: Dict[str, DetectionRule] = {}
        self._detection_history: List[Dict[str, Any]] = []
        self._user_behavior_stats: Dict[str, Dict[str, Any]] = defaultdict(lambda: {
            "event_counts": Counter(),
            "session_events": defaultdict(list),
            "hourly_activity": defaultdict(int),
            "last_event_time": None
        })
        self._lock = threading.Lock()
        
        self._rule_manager = RuleConfigManager(rules_config_path)
        
        self._load_initial_rules()
    
    def _load_initial_rules(self):
        rules = self._rule_manager.load_rules()
        for rule in rules:
            self._rules[rule.rule_id] = rule
    
    def reload_rules(self) -> Dict[str, Any]:
        with self._lock:
            rules = self._rule_manager.load_rules()
            old_count = len(self._rules)
            self._rules.clear()
            for rule in rules:
                self._rules[rule.rule_id] = rule
            new_count = len(self._rules)
        
        logger.info(f"Reloaded rules: {old_count} -> {new_count}")
        return {
            "success": True,
            "old_count": old_count,
            "new_count": new_count,
            "config_path": self._rule_manager.get_config_path()
        }
    
    def load_rules_from_file(self, config_path: Optional[str] = None) -> Dict[str, Any]:
        if config_path:
            self._rule_manager = RuleConfigManager(config_path)
        
        return self.reload_rules()
    
    def get_all_rules(self) -> List[Dict[str, Any]]:
        return [rule.to_dict() for rule in self._rules.values()]
    
    def get_rule(self, rule_id: str) -> Optional[Dict[str, Any]]:
        rule = self._rules.get(rule_id)
        return rule.to_dict() if rule else None
    
    def add_rule(self, rule: DetectionRule) -> Dict[str, Any]:
        with self._lock:
            if rule.rule_id in self._rules:
                return {
                    "success": False,
                    "error": f"Rule with id '{rule.rule_id}' already exists"
                }
            
            self._rules[rule.rule_id] = rule
            
            rules_list = list(self._rules.values())
            saved = self._rule_manager.save_rules(rules_list)
            
            return {
                "success": True,
                "rule_id": rule.rule_id,
                "persisted": saved
            }
    
    def remove_rule(self, rule_id: str) -> Dict[str, Any]:
        with self._lock:
            if rule_id not in self._rules:
                return {"success": False, "error": "Rule not found"}
            
            del self._rules[rule_id]
            
            rules_list = list(self._rules.values())
            saved = self._rule_manager.save_rules(rules_list)
            
            return {
                "success": True,
                "rule_id": rule_id,
                "persisted": saved
            }
    
    def enable_rule(self, rule_id: str) -> Dict[str, Any]:
        if rule_id not in self._rules:
            return {"success": False, "error": "Rule not found"}
        
        with self._lock:
            self._rules[rule_id].enabled = True
            rules_list = list(self._rules.values())
            self._rule_manager.save_rules(rules_list)
        
        return {"success": True, "rule_id": rule_id, "enabled": True}
    
    def disable_rule(self, rule_id: str) -> Dict[str, Any]:
        if rule_id not in self._rules:
            return {"success": False, "error": "Rule not found"}
        
        with self._lock:
            self._rules[rule_id].enabled = False
            rules_list = list(self._rules.values())
            self._rule_manager.save_rules(rules_list)
        
        return {"success": True, "rule_id": rule_id, "enabled": False}
    
    def update_rule(self, rule_id: str, updates: Dict[str, Any]) -> Dict[str, Any]:
        if rule_id not in self._rules:
            return {"success": False, "error": "Rule not found"}
        
        with self._lock:
            rule = self._rules[rule_id]
            for key, value in updates.items():
                if hasattr(rule, key):
                    setattr(rule, key, value)
            
            rules_list = list(self._rules.values())
            self._rule_manager.save_rules(rules_list)
        
        return {"success": True, "rule_id": rule_id}
    
    def detect(self, event: Dict[str, Any], user_profile: Optional[Dict[str, Any]] = None) -> List[Dict[str, Any]]:
        detections = []
        user_id = event.get("user_id", "unknown")
        
        with self._lock:
            self._update_user_behavior_stats(user_id, event)
        
        for rule_id, rule in self._rules.items():
            if not rule.enabled:
                continue
            
            detection_result = self._apply_rule(rule, event, user_id, user_profile)
            
            if detection_result and detection_result.get("is_abnormal", False):
                detections.append(detection_result)
                
                self._detection_history.append({
                    "user_id": user_id,
                    "rule_id": rule_id,
                    "rule_name": rule.rule_name,
                    "severity": rule.severity,
                    "detected_at": datetime.now().isoformat(),
                    "event_id": event.get("event_id"),
                    "detection_data": detection_result
                })
        
        return detections
    
    def _update_user_behavior_stats(self, user_id: str, event: Dict[str, Any]):
        stats = self._user_behavior_stats[user_id]
        
        stats["event_counts"][event.get("event_type", "unknown")] += 1
        
        session_id = event.get("session_id", "unknown")
        stats["session_events"][session_id].append({
            "event_type": event.get("event_type"),
            "timestamp": event.get("timestamp")
        })
        
        timestamp = event.get("timestamp")
        if timestamp:
            try:
                dt = datetime.fromisoformat(timestamp.replace("Z", "+00:00"))
                stats["hourly_activity"][dt.hour] += 1
                stats["last_event_time"] = dt
            except:
                pass
    
    def _apply_rule(self, rule: DetectionRule, event: Dict[str, Any], 
                    user_id: str, user_profile: Optional[Dict[str, Any]]) -> Optional[Dict[str, Any]]:
        rule_types = {
            "frequency": self._check_frequency_rule,
            "temporal": self._check_temporal_rule,
            "geographic": self._check_geographic_rule,
            "device": self._check_device_rule,
            "sequence": self._check_sequence_rule,
            "pattern": self._check_pattern_rule,
            "transaction": self._check_transaction_rule,
            "auth": self._check_auth_rule,
            "data": self._check_data_rule
        }
        
        checker = rule_types.get(rule.rule_type)
        if checker:
            result = checker(rule, event, user_id, user_profile)
            if result:
                result["rule_id"] = rule.rule_id
                result["rule_name"] = rule.rule_name
                result["severity"] = rule.severity
                result["action"] = rule.action
                return result
        
        return None
    
    def _check_frequency_rule(self, rule: DetectionRule, event: Dict[str, Any],
                              user_id: str, user_profile: Optional[Dict[str, Any]]) -> Optional[Dict[str, Any]]:
        stats = self._user_behavior_stats[user_id]
        threshold = rule.threshold or {}
        
        if rule.rule_id == "frequent_events":
            max_events = threshold.get("max_events_per_minute", 100)
            session_id = event.get("session_id")
            
            if session_id and len(stats["session_events"][session_id]) > max_events:
                return {
                    "is_abnormal": True,
                    "detection_type": "frequency",
                    "reason": f"超过{max_events}次/分钟的高频事件",
                    "actual_count": len(stats["session_events"][session_id]),
                    "threshold": max_events,
                    "confidence": 0.85
                }
        
        elif rule.rule_id == "rapid_session_creation":
            max_sessions = threshold.get("max_sessions_per_hour", 10)
            if len(stats["session_events"]) > max_sessions:
                return {
                    "is_abnormal": True,
                    "detection_type": "frequency",
                    "reason": f"1小时内创建超过{max_sessions}个会话",
                    "actual_sessions": len(stats["session_events"]),
                    "threshold": max_sessions,
                    "confidence": 0.7
                }
        
        return None
    
    def _check_temporal_rule(self, rule: DetectionRule, event: Dict[str, Any],
                             user_id: str, user_profile: Optional[Dict[str, Any]]) -> Optional[Dict[str, Any]]:
        timestamp = event.get("timestamp")
        if not timestamp:
            return None
        
        threshold = rule.threshold or {}
        normal_start = threshold.get("normal_hours_start", 6)
        normal_end = threshold.get("normal_hours_end", 22)
        
        try:
            dt = datetime.fromisoformat(timestamp.replace("Z", "+00:00"))
            hour = dt.hour
            
            if hour < normal_start or hour >= normal_end:
                stats = self._user_behavior_stats[user_id]
                normal_activity = sum(
                    count for h, count in stats["hourly_activity"].items()
                    if normal_start <= h < normal_end
                )
                total_activity = sum(stats["hourly_activity"].values())
                
                if total_activity > 0:
                    normal_ratio = normal_activity / total_activity
                    if normal_ratio < 0.5:
                        return {
                            "is_abnormal": True,
                            "detection_type": "temporal",
                            "reason": f"在非活跃时段{hour}:00进行活动",
                            "hour": hour,
                            "normal_hours": f"{normal_start}:00-{normal_end}:00",
                            "confidence": 0.6
                        }
        except:
            pass
        
        return None
    
    def _check_geographic_rule(self, rule: DetectionRule, event: Dict[str, Any],
                               user_id: str, user_profile: Optional[Dict[str, Any]]) -> Optional[Dict[str, Any]]:
        location = event.get("location", {})
        if not location:
            return None
        
        threshold = rule.threshold or {}
        max_distance = threshold.get("max_distance_km", 500)
        
        if user_profile:
            normal_locations = user_profile.get("behavior", {}).get("normal_locations", [])
            
            if normal_locations and location:
                lat = location.get("latitude")
                lng = location.get("longitude")
                
                if lat is not None and lng is not None:
                    for normal_loc in normal_locations:
                        distance = self._calculate_distance(
                            lat, lng,
                            normal_loc.get("latitude", 0),
                            normal_loc.get("longitude", 0)
                        )
                        
                        if distance > max_distance:
                            return {
                                "is_abnormal": True,
                                "detection_type": "geographic",
                                "reason": f"地理位置异常，距离常用地点{distance:.1f}km",
                                "current_location": {"lat": lat, "lng": lng},
                                "distance_km": round(distance, 2),
                                "threshold": max_distance,
                                "confidence": 0.9
                            }
        
        return None
    
    def _calculate_distance(self, lat1: float, lng1: float, lat2: float, lng2: float) -> float:
        import math
        R = 6371.0
        
        lat1_rad = math.radians(lat1)
        lat2_rad = math.radians(lat2)
        lng_diff = math.radians(lng2 - lng1)
        
        distance = R * math.acos(
            math.sin(lat1_rad) * math.sin(lat2_rad) +
            math.cos(lat1_rad) * math.cos(lat2_rad) * math.cos(lng_diff)
        )
        
        return distance
    
    def _check_device_rule(self, rule: DetectionRule, event: Dict[str, Any],
                           user_id: str, user_profile: Optional[Dict[str, Any]]) -> Optional[Dict[str, Any]]:
        device = event.get("device", {})
        if not device:
            return None
        
        threshold = rule.threshold or {}
        max_devices = threshold.get("max_devices_per_hour", 3)
        
        if user_profile:
            used_devices = user_profile.get("behavior", {}).get("used_devices", [])
            
            if len(used_devices) > max_devices:
                return {
                    "is_abnormal": True,
                    "detection_type": "device",
                    "reason": f"1小时内使用超过{max_devices}个不同设备",
                    "device_count": len(used_devices),
                    "threshold": max_devices,
                    "confidence": 0.75
                }
        
        return None
    
    def _check_sequence_rule(self, rule: DetectionRule, event: Dict[str, Any],
                             user_id: str, user_profile: Optional[Dict[str, Any]]) -> Optional[Dict[str, Any]]:
        stats = self._user_behavior_stats[user_id]
        session_id = event.get("session_id")
        
        if not session_id:
            return None
        
        session_events = stats["session_events"][session_id]
        if len(session_events) < 3:
            return None
        
        threshold = rule.threshold or {}
        min_confidence = threshold.get("min_confidence", 0.7)
        
        event_types = [e["event_type"] for e in session_events[-10:]]
        expected_sequences = {
            ("page_view", "click", "page_view"): 0.3,
            ("page_view", "scroll", "click"): 0.2,
            ("page_view", "click", "purchase"): 0.1
        }
        
        for i in range(len(event_types) - 2):
            sequence = tuple(event_types[i:i+3])
            if sequence not in expected_sequences:
                return {
                    "is_abnormal": True,
                    "detection_type": "sequence",
                    "reason": f"检测到异常行为序列: {'->'.join(sequence)}",
                    "sequence": list(sequence),
                    "confidence": 0.65
                }
        
        return None
    
    def _check_pattern_rule(self, rule: DetectionRule, event: Dict[str, Any],
                            user_id: str, user_profile: Optional[Dict[str, Any]]) -> Optional[Dict[str, Any]]:
        stats = self._user_behavior_stats[user_id]
        session_id = event.get("session_id")
        
        if not session_id:
            return None
        
        session_events = stats["session_events"][session_id]
        if len(session_events) < 5:
            return None
        
        event_types = [e["event_type"] for e in session_events]
        pattern_counts = Counter()
        
        for i in range(len(event_types) - 2):
            pattern = tuple(event_types[i:i+3])
            pattern_counts[pattern] += 1
        
        for pattern, count in pattern_counts.items():
            if count >= 3:
                return {
                    "is_abnormal": True,
                    "detection_type": "pattern",
                    "reason": f"检测到重复模式: {'->'.join(pattern)} (出现{count}次)",
                    "pattern": list(pattern),
                    "repeat_count": count,
                    "confidence": 0.8
                }
        
        return None
    
    def _check_transaction_rule(self, rule: DetectionRule, event: Dict[str, Any],
                                user_id: str, user_profile: Optional[Dict[str, Any]]) -> Optional[Dict[str, Any]]:
        if event.get("event_type") != "purchase":
            return None
        
        stats = self._user_behavior_stats[user_id]
        threshold = rule.threshold or {}
        
        max_purchases = threshold.get("max_purchases_per_hour", 5)
        min_interval = threshold.get("min_interval_seconds", 60)
        
        purchase_count = stats["event_counts"].get("purchase", 0)
        
        if purchase_count > max_purchases:
            return {
                "is_abnormal": True,
                "detection_type": "transaction",
                "reason": f"1小时内购买次数超过{max_purchases}次",
                "purchase_count": purchase_count,
                "threshold": max_purchases,
                "confidence": 0.85
            }
        
        return None
    
    def _check_auth_rule(self, rule: DetectionRule, event: Dict[str, Any],
                         user_id: str, user_profile: Optional[Dict[str, Any]]) -> Optional[Dict[str, Any]]:
        if event.get("event_type") not in ["login", "login_failed"]:
            return None
        
        stats = self._user_behavior_stats[user_id]
        threshold = rule.threshold or {}
        
        max_failed = threshold.get("max_failed_logins", 10)
        failed_count = stats["event_counts"].get("login_failed", 0)
        
        if failed_count >= max_failed:
            return {
                "is_abnormal": True,
                "detection_type": "auth",
                "reason": f"登录失败次数达到{max_failed}次",
                "failed_count": failed_count,
                "threshold": max_failed,
                "confidence": 0.95
            }
        
        return None
    
    def _check_data_rule(self, rule: DetectionRule, event: Dict[str, Any],
                         user_id: str, user_profile: Optional[Dict[str, Any]]) -> Optional[Dict[str, Any]]:
        if event.get("event_type") != "export":
            return None
        
        stats = self._user_behavior_stats[user_id]
        threshold = rule.threshold or {}
        
        max_exports = threshold.get("max_exports_per_hour", 5)
        export_count = stats["event_counts"].get("export", 0)
        
        if export_count > max_exports:
            return {
                "is_abnormal": True,
                "detection_type": "data",
                "reason": f"1小时内数据导出超过{max_exports}次",
                "export_count": export_count,
                "threshold": max_exports,
                "confidence": 0.8
            }
        
        return None
    
    def get_detection_history(self, user_id: Optional[str] = None, 
                              limit: int = 100) -> List[Dict[str, Any]]:
        history = self._detection_history
        
        if user_id:
            history = [h for h in history if h["user_id"] == user_id]
        
        return history[-limit:]
    
    def get_detection_stats(self) -> Dict[str, Any]:
        total_detections = len(self._detection_history)
        
        severity_counts = Counter()
        rule_counts = Counter()
        
        for detection in self._detection_history:
            severity_counts[detection["severity"]] += 1
            rule_counts[detection["rule_name"]] += 1
        
        return {
            "total_detections": total_detections,
            "severity_distribution": dict(severity_counts),
            "top_rules": rule_counts.most_common(5),
            "active_rules": sum(1 for r in self._rules.values() if r.enabled),
            "total_rules": len(self._rules),
            "config_path": self._rule_manager.get_config_path()
        }
    
    def evaluate_rules(self, normal_events: List[Dict[str, Any]],
                       abnormal_events: List[Dict[str, Any]]) -> Dict[str, Any]:
        true_positives = 0
        false_positives = 0
        true_negatives = 0
        false_negatives = 0
        
        for event in normal_events:
            detections = self.detect(event)
            if detections:
                false_positives += 1
            else:
                true_negatives += 1
        
        for event in abnormal_events:
            detections = self.detect(event)
            if detections:
                true_positives += 1
            else:
                false_negatives += 1
        
        total = len(normal_events) + len(abnormal_events)
        accuracy = (true_positives + true_negatives) / total if total > 0 else 0
        
        precision = true_positives / (true_positives + false_positives) if (true_positives + false_positives) > 0 else 0
        recall = true_positives / (true_positives + false_negatives) if (true_positives + false_negatives) > 0 else 0
        
        f1_score = 2 * precision * recall / (precision + recall) if (precision + recall) > 0 else 0
        
        false_positive_rate = false_positives / (false_positives + true_negatives) if (false_positives + true_negatives) > 0 else 0
        
        return {
            "true_positives": true_positives,
            "false_positives": false_positives,
            "true_negatives": true_negatives,
            "false_negatives": false_negatives,
            "accuracy": round(accuracy, 4),
            "precision": round(precision, 4),
            "recall": round(recall, 4),
            "f1_score": round(f1_score, 4),
            "false_positive_rate": round(false_positive_rate, 4),
            "misclassification_rate": round(1 - accuracy, 4)
        }
