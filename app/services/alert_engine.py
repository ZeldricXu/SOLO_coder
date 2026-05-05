import json
import logging
import threading
from dataclasses import dataclass, field, asdict
from datetime import datetime, timedelta
from enum import Enum
from typing import Dict, List, Optional, Any, Callable, Union
from collections import defaultdict
from concurrent.futures import ThreadPoolExecutor
from queue import Queue, Empty

try:
    import redis
    REDIS_AVAILABLE = True
except ImportError:
    REDIS_AVAILABLE = False

from app.models.metric import Metric
from app.models.alert import AlertRule, AlertEvent, AlertStatus, AlertSeverity
from app.services.alert_rules import AlertRuleManager
from app.services.alert_history import AlertHistoryManager
from app import config

logger = logging.getLogger(__name__)


class SilenceType(str, Enum):
    RULE_SERVER = "rule_server"
    RULE = "rule"
    SERVER = "server"
    GLOBAL = "global"


@dataclass
class SilenceEntry:
    silence_id: str
    silence_type: SilenceType
    rule_id: Optional[str] = None
    server_id: Optional[str] = None
    start_time: datetime = field(default_factory=datetime.utcnow)
    end_time: datetime = None
    reason: str = ""
    created_by: str = "system"
    is_active: bool = True
    severity: Optional[str] = None
    
    def is_expired(self) -> bool:
        if not self.is_active:
            return True
        if self.end_time is None:
            return False
        return datetime.utcnow() > self.end_time
    
    def matches(
        self,
        rule_id: Optional[str] = None,
        server_id: Optional[str] = None,
        severity: Optional[str] = None
    ) -> bool:
        if self.is_expired():
            return False
        
        if self.severity and severity and self.severity != severity:
            return False
        
        if self.silence_type == SilenceType.GLOBAL:
            return True
        
        if self.silence_type == SilenceType.RULE_SERVER:
            return self.rule_id == rule_id and self.server_id == server_id
        
        if self.silence_type == SilenceType.RULE:
            return self.rule_id == rule_id
        
        if self.silence_type == SilenceType.SERVER:
            return self.server_id == server_id
        
        return False
    
    def to_dict(self) -> Dict[str, Any]:
        return {
            "silence_id": self.silence_id,
            "silence_type": self.silence_type.value if isinstance(self.silence_type, Enum) else self.silence_type,
            "rule_id": self.rule_id,
            "server_id": self.server_id,
            "start_time": self.start_time.isoformat() if self.start_time else None,
            "end_time": self.end_time.isoformat() if self.end_time else None,
            "reason": self.reason,
            "created_by": self.created_by,
            "is_active": self.is_active,
            "severity": self.severity
        }
    
    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> 'SilenceEntry':
        return cls(
            silence_id=data["silence_id"],
            silence_type=SilenceType(data["silence_type"]),
            rule_id=data.get("rule_id"),
            server_id=data.get("server_id"),
            start_time=datetime.fromisoformat(data["start_time"]) if data.get("start_time") else datetime.utcnow(),
            end_time=datetime.fromisoformat(data["end_time"]) if data.get("end_time") else None,
            reason=data.get("reason", ""),
            created_by=data.get("created_by", "system"),
            is_active=data.get("is_active", True),
            severity=data.get("severity")
        )


class SilenceDurationConfig:
    def __init__(self, alert_config: Dict[str, Any]):
        self._default_seconds = alert_config.get('silence_default_seconds', 300)
        self._silence_by_severity = alert_config.get('silence_by_severity', {
            'critical': 60,
            'warning': 600,
            'info': 1800
        })
        
        logger.info(f"Silence duration config initialized: default={self._default_seconds}s, by_severity={self._silence_by_severity}")
    
    def get_duration(
        self,
        severity: Optional[Union[str, AlertSeverity]] = None,
        rule_silence_period: Optional[int] = None
    ) -> int:
        if rule_silence_period is not None and rule_silence_period > 0:
            return rule_silence_period
        
        if severity is None:
            return self._default_seconds
        
        if isinstance(severity, AlertSeverity):
            severity_str = severity.value
        else:
            severity_str = str(severity).lower()
        
        return self._silence_by_severity.get(severity_str, self._default_seconds)
    
    def get_duration_for_rule(self, rule: AlertRule) -> int:
        if hasattr(rule, 'silence_period') and rule.silence_period is not None:
            return rule.silence_period
        
        return self.get_duration(rule.severity)


class SilenceManager:
    def __init__(self, duration_config: Optional[SilenceDurationConfig] = None):
        self._silences: Dict[str, SilenceEntry] = {}
        self._lock = threading.RLock()
        self._duration_config = duration_config or SilenceDurationConfig({})
        logger.info("SilenceManager initialized")
    
    def set_duration_config(self, config: SilenceDurationConfig):
        with self._lock:
            self._duration_config = config
            logger.info("Silence duration config updated")
    
    def create_silence(
        self,
        silence_type: SilenceType,
        rule_id: Optional[str] = None,
        server_id: Optional[str] = None,
        duration_seconds: Optional[int] = None,
        severity: Optional[str] = None,
        reason: str = "",
        created_by: str = "system"
    ) -> SilenceEntry:
        with self._lock:
            from uuid import uuid4
            silence_id = f"silence_{uuid4().hex[:8]}"
            
            actual_duration = duration_seconds if duration_seconds is not None else self._duration_config.get_duration(severity)
            end_time = datetime.utcnow() + timedelta(seconds=actual_duration) if actual_duration > 0 else None
            
            entry = SilenceEntry(
                silence_id=silence_id,
                silence_type=silence_type,
                rule_id=rule_id,
                server_id=server_id,
                end_time=end_time,
                reason=reason,
                created_by=created_by,
                is_active=True,
                severity=severity
            )
            
            self._silences[silence_id] = entry
            logger.info(f"Created silence: {silence_id} ({silence_type.value}), duration: {actual_duration}s, severity: {severity}")
            return entry
    
    def create_auto_silence_for_alert(
        self,
        event: AlertEvent,
        rule: AlertRule
    ) -> Optional[SilenceEntry]:
        with self._lock:
            duration_seconds = self._duration_config.get_duration_for_rule(rule)
            
            existing_silence = self._find_existing_silence(
                rule_id=rule.rule_id,
                server_id=event.server_id
            )
            
            if existing_silence:
                logger.debug(f"Silence already exists for {rule.rule_id}:{event.server_id}, skipping")
                return existing_silence
            
            severity_str = None
            if hasattr(rule, 'severity'):
                if isinstance(rule.severity, AlertSeverity):
                    severity_str = rule.severity.value
                else:
                    severity_str = str(rule.severity)
            
            return self.create_silence(
                silence_type=SilenceType.RULE_SERVER,
                rule_id=rule.rule_id,
                server_id=event.server_id,
                duration_seconds=duration_seconds,
                severity=severity_str,
                reason=f"Auto-silence for alert {event.alert_id}",
                created_by="system"
            )
    
    def _find_existing_silence(
        self,
        rule_id: Optional[str] = None,
        server_id: Optional[str] = None
    ) -> Optional[SilenceEntry]:
        for silence in self._silences.values():
            if not silence.is_active or silence.is_expired():
                continue
            if silence.matches(rule_id, server_id):
                return silence
        return None
    
    def check_silenced(
        self,
        rule_id: Optional[str] = None,
        server_id: Optional[str] = None,
        severity: Optional[str] = None
    ) -> bool:
        with self._lock:
            for silence in self._silences.values():
                if silence.matches(rule_id, server_id, severity):
                    return True
            return False
    
    def get_active_silences(
        self,
        rule_id: Optional[str] = None,
        server_id: Optional[str] = None
    ) -> List[SilenceEntry]:
        with self._lock:
            self._cleanup_expired()
            
            results = []
            for silence in self._silences.values():
                if not silence.is_active or silence.is_expired():
                    continue
                
                if rule_id is None and server_id is None:
                    results.append(silence)
                elif silence.matches(rule_id, server_id):
                    results.append(silence)
            
            return results
    
    def cancel_silence(self, silence_id: str) -> bool:
        with self._lock:
            if silence_id in self._silences:
                self._silences[silence_id].is_active = False
                logger.info(f"Cancelled silence: {silence_id}")
                return True
            return False
    
    def get_silence(self, silence_id: str) -> Optional[SilenceEntry]:
        with self._lock:
            return self._silences.get(silence_id)
    
    def list_all_silences(self, include_expired: bool = False) -> List[SilenceEntry]:
        with self._lock:
            self._cleanup_expired()
            
            if include_expired:
                return list(self._silences.values())
            
            return [s for s in self._silences.values() if s.is_active and not s.is_expired()]
    
    def _cleanup_expired(self):
        expired_ids = [
            sid for sid, s in self._silences.items()
            if s.is_expired() and s.is_active
        ]
        for sid in expired_ids:
            logger.debug(f"Removing expired silence: {sid}")
            del self._silences[sid]


@dataclass
class NotificationTask:
    task_id: str
    event_dict: Dict[str, Any]
    is_resolved: bool
    channels: List[str]
    created_at: datetime = field(default_factory=datetime.utcnow)
    retry_count: int = 0
    max_retries: int = 3
    
    @property
    def alert_id(self) -> str:
        return self.event_dict.get("alert_id", "")
    
    def to_dict(self) -> Dict[str, Any]:
        return {
            "task_id": self.task_id,
            "event_dict": self.event_dict,
            "is_resolved": self.is_resolved,
            "channels": self.channels,
            "created_at": self.created_at.isoformat(),
            "retry_count": self.retry_count,
            "max_retries": self.max_retries
        }
    
    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> 'NotificationTask':
        return cls(
            task_id=data["task_id"],
            event_dict=data["event_dict"],
            is_resolved=data["is_resolved"],
            channels=data.get("channels", ["email", "dingtalk"]),
            created_at=datetime.fromisoformat(data["created_at"]) if data.get("created_at") else datetime.utcnow(),
            retry_count=data.get("retry_count", 0),
            max_retries=data.get("max_retries", 3)
        )


class BaseNotificationQueue:
    def __init__(self, max_queue_size: int = 1000):
        self._lock = threading.RLock()
        self._processing = False
        self._workers: List[threading.Thread] = []
        self._shutdown_event = threading.Event()
        self._max_queue_size = max_queue_size
        self._notification_service = None
        self._executor = None
    
    def start_workers(self, num_workers: int = 2, notification_service=None):
        raise NotImplementedError
    
    def stop_workers(self, wait: bool = True):
        raise NotImplementedError
    
    def enqueue(self, event: AlertEvent, is_resolved: bool = False) -> str:
        raise NotImplementedError
    
    def get_queue_size(self) -> int:
        raise NotImplementedError
    
    def get_pending_tasks(self) -> int:
        return self.get_queue_size()
    
    def get_failed_tasks(self) -> List[NotificationTask]:
        raise NotImplementedError
    
    def clear_failed_tasks(self):
        raise NotImplementedError
    
    def retry_failed_tasks(self) -> int:
        raise NotImplementedError
    
    def _process_task(self, task: NotificationTask):
        raise NotImplementedError


class MemoryNotificationQueue(BaseNotificationQueue):
    def __init__(self, max_queue_size: int = 1000):
        super().__init__(max_queue_size)
        self._queue: Queue = Queue(maxsize=max_queue_size)
        self._completed_tasks: Dict[str, NotificationTask] = {}
        self._failed_tasks: Dict[str, NotificationTask] = {}
        logger.info("MemoryNotificationQueue initialized")
    
    def enqueue(self, event: AlertEvent, is_resolved: bool = False) -> str:
        from uuid import uuid4
        task_id = f"notify_{uuid4().hex[:8]}"
        
        channels = event.notify_channels if event.notify_channels else ['email', 'dingtalk']
        
        event_dict = {
            "alert_id": event.alert_id,
            "rule_id": event.rule_id,
            "server_id": event.server_id,
            "metric_type": event.metric_type,
            "metric_value": event.metric_value,
            "threshold": event.threshold,
            "severity": event.severity.value if isinstance(event.severity, AlertSeverity) else str(event.severity),
            "status": event.status.value if isinstance(event.status, AlertStatus) else str(event.status),
            "message": event.message,
            "details": event.details,
            "notify_status": event.notify_status,
            "notify_channels": event.notify_channels,
            "notify_error": event.notify_error,
            "triggered_at": event.triggered_at.isoformat() if event.triggered_at else None,
            "resolved_at": event.resolved_at.isoformat() if event.resolved_at else None
        }
        
        task = NotificationTask(
            task_id=task_id,
            event_dict=event_dict,
            is_resolved=is_resolved,
            channels=channels
        )
        
        try:
            self._queue.put_nowait(task)
            with self._lock:
                self._completed_tasks[task_id] = task
            logger.debug(f"Enqueued notification task: {task_id}")
            return task_id
        except Exception as e:
            logger.error(f"Failed to enqueue notification: {e}")
            raise
    
    def start_workers(self, num_workers: int = 2, notification_service=None):
        with self._lock:
            if self._processing:
                logger.warning("Workers already started")
                return
            
            self._processing = True
            self._shutdown_event.clear()
            self._notification_service = notification_service
            
            for i in range(num_workers):
                worker = threading.Thread(
                    target=self._worker_loop,
                    name=f"MemoryNotificationWorker-{i+1}",
                    daemon=True
                )
                worker.start()
                self._workers.append(worker)
            
            logger.info(f"Started {num_workers} memory notification workers")
    
    def stop_workers(self, wait: bool = True):
        with self._lock:
            if not self._processing:
                return
            
            self._shutdown_event.set()
            self._processing = False
        
        if wait:
            for worker in self._workers:
                worker.join(timeout=10)
        
        self._workers.clear()
        logger.info("Memory notification workers stopped")
    
    def _worker_loop(self):
        while not self._shutdown_event.is_set():
            try:
                task = self._queue.get(timeout=1.0)
            except Empty:
                continue
            
            try:
                self._process_task(task)
            except Exception as e:
                logger.error(f"Error processing task {task.task_id}: {e}")
            finally:
                self._queue.task_done()
    
    def _process_task(self, task: NotificationTask):
        if self._notification_service is None:
            logger.error("No notification service available")
            return
        
        results = {}
        errors = {}
        
        from app.models.alert import AlertEvent, AlertSeverity, AlertStatus
        
        severity_value = task.event_dict.get("severity", "warning")
        try:
            severity = AlertSeverity(severity_value)
        except (ValueError, TypeError):
            severity = AlertSeverity.WARNING
        
        status_value = task.event_dict.get("status", "triggered")
        try:
            status = AlertStatus(status_value)
        except (ValueError, TypeError):
            status = AlertStatus.TRIGGERED
        
        event_from_dict = AlertEvent(
            alert_id=task.event_dict.get("alert_id", ""),
            rule_id=task.event_dict.get("rule_id", ""),
            server_id=task.event_dict.get("server_id", ""),
            metric_type=task.event_dict.get("metric_type", ""),
            metric_value=task.event_dict.get("metric_value", 0.0),
            threshold=task.event_dict.get("threshold", 0.0),
            severity=severity,
            status=status,
            message=task.event_dict.get("message", ""),
            details=task.event_dict.get("details", {}),
            notify_status=task.event_dict.get("notify_status"),
            notify_channels=task.event_dict.get("notify_channels"),
            notify_error=task.event_dict.get("notify_error")
        )
        
        for channel in task.channels:
            try:
                notifier = self._notification_service.notifiers.get(channel)
                if notifier:
                    success = notifier.send(event_from_dict, task.is_resolved)
                    results[channel] = success
                    if success:
                        logger.info(f"Notification sent via {channel} for task {task.task_id}")
                    else:
                        errors[channel] = "Send failed"
                else:
                    logger.warning(f"Unknown channel not found: {channel}")
                    errors[channel] = "Channel not found"
            except Exception as e:
                logger.error(f"Failed to send via {channel}: {e}")
                errors[channel] = str(e)
                results[channel] = False
        
        all_success = all(results.values()) if results else False
        
        with self._lock:
            if all_success:
                if task.task_id in self._completed_tasks:
                    del self._completed_tasks[task.task_id]
            else:
                task.retry_count += 1
                if task.retry_count < task.max_retries:
                    logger.info(f"Retrying task {task.task_id} (attempt {task.retry_count})")
                    try:
                        self._queue.put_nowait(task)
                    except Exception:
                        self._failed_tasks[task.task_id] = task
                else:
                    self._failed_tasks[task.task_id] = task
    
    def get_queue_size(self) -> int:
        return self._queue.qsize()
    
    def get_failed_tasks(self) -> List[NotificationTask]:
        with self._lock:
            return list(self._failed_tasks.values())
    
    def clear_failed_tasks(self):
        with self._lock:
            count = len(self._failed_tasks)
            self._failed_tasks.clear()
            return count
    
    def retry_failed_tasks(self) -> int:
        with self._lock:
            failed_tasks = list(self._failed_tasks.values())
            retried = 0
            
            for task in failed_tasks:
                try:
                    task.retry_count = 0
                    self._queue.put_nowait(task)
                    del self._failed_tasks[task.task_id]
                    retried += 1
                except Exception as e:
                    logger.error(f"Failed to requeue task {task.task_id}: {e}")
            
            return retried


class RedisNotificationQueue(BaseNotificationQueue):
    def __init__(
        self,
        redis_config: Dict[str, Any],
        max_queue_size: int = 1000,
        max_retries: int = 3,
        poll_interval_seconds: int = 2
    ):
        super().__init__(max_queue_size)
        
        self._redis_config = redis_config
        self._queue_key = redis_config.get("notification_queue_key", "metric_monitor:notifications")
        self._failed_key = redis_config.get("failed_notifications_key", "metric_monitor:failed_notifications")
        self._lock_prefix = redis_config.get("notification_lock_prefix", "metric_monitor:lock:")
        self._poll_interval = poll_interval_seconds
        self._max_retries = max_retries
        self._redis_client: Optional[redis.Redis] = None
        self._recovered_tasks = False
        
        logger.info(f"RedisNotificationQueue initialized, queue_key={self._queue_key}")
    
    def _get_redis_client(self) -> Optional[redis.Redis]:
        if self._redis_client is not None:
            return self._redis_client
        
        if not REDIS_AVAILABLE:
            logger.error("Redis package not installed, falling back to memory queue")
            return None
        
        try:
            self._redis_client = redis.Redis(
                host=self._redis_config.get("host", "localhost"),
                port=self._redis_config.get("port", 6379),
                db=self._redis_config.get("db", 0),
                password=self._redis_config.get("password"),
                decode_responses=True,
                socket_timeout=self._redis_config.get("socket_timeout", 5),
                socket_connect_timeout=self._redis_config.get("socket_connect_timeout", 5),
                health_check_interval=30
            )
            self._redis_client.ping()
            logger.info("Redis connection established")
            return self._redis_client
        except Exception as e:
            logger.error(f"Failed to connect to Redis: {e}")
            return None
    
    def _recover_pending_tasks(self):
        if self._recovered_tasks:
            return
        
        redis_client = self._get_redis_client()
        if not redis_client:
            return
        
        try:
            pending_keys = redis_client.keys(f"{self._lock_prefix}*")
            for lock_key in pending_keys:
                task_id = lock_key.replace(self._lock_prefix, "")
                try:
                    task_data = redis_client.hget(self._queue_key, task_id)
                    if task_data:
                        task_dict = json.loads(task_data)
                        task = NotificationTask.from_dict(task_dict)
                        task.retry_count = task.retry_count + 1 if task.retry_count < self._max_retries else 0
                        
                        redis_client.hset(self._queue_key, task.task_id, json.dumps(task.to_dict()))
                        redis_client.delete(lock_key)
                        
                        logger.info(f"Recovered task from lock: {task_id}")
                except Exception as e:
                    logger.error(f"Failed to recover task {task_id}: {e}")
            
            self._recovered_tasks = True
            pending_count = self.get_queue_size()
            if pending_count > 0:
                logger.info(f"Recovered {pending_count} pending tasks from Redis on startup")
        except Exception as e:
            logger.error(f"Failed to recover pending tasks: {e}")
    
    def enqueue(self, event: AlertEvent, is_resolved: bool = False) -> str:
        from uuid import uuid4
        task_id = f"notify_{uuid4().hex[:8]}"
        
        channels = event.notify_channels if event.notify_channels else ['email', 'dingtalk']
        
        event_dict = {
            "alert_id": event.alert_id,
            "rule_id": event.rule_id,
            "server_id": event.server_id,
            "metric_type": event.metric_type,
            "metric_value": event.metric_value,
            "threshold": event.threshold,
            "severity": event.severity.value if isinstance(event.severity, AlertSeverity) else str(event.severity),
            "status": event.status.value if isinstance(event.status, AlertStatus) else str(event.status),
            "message": event.message,
            "details": event.details,
            "notify_status": event.notify_status,
            "notify_channels": event.notify_channels,
            "notify_error": event.notify_error,
            "triggered_at": event.triggered_at.isoformat() if event.triggered_at else None,
            "resolved_at": event.resolved_at.isoformat() if event.resolved_at else None
        }
        
        task = NotificationTask(
            task_id=task_id,
            event_dict=event_dict,
            is_resolved=is_resolved,
            channels=channels,
            max_retries=self._max_retries
        )
        
        redis_client = self._get_redis_client()
        if redis_client:
            try:
                redis_client.hset(
                    self._queue_key,
                    task_id,
                    json.dumps(task.to_dict())
                )
                logger.debug(f"Enqueued notification task to Redis: {task_id}")
                return task_id
            except Exception as e:
                logger.error(f"Failed to enqueue to Redis, falling back: {e}")
                raise
        else:
            raise RuntimeError("Redis not available")
    
    def start_workers(self, num_workers: int = 2, notification_service=None):
        with self._lock:
            if self._processing:
                logger.warning("Workers already started")
                return
            
            self._get_redis_client()
            self._recover_pending_tasks()
            
            self._processing = True
            self._shutdown_event.clear()
            self._notification_service = notification_service
            
            for i in range(num_workers):
                worker = threading.Thread(
                    target=self._worker_loop,
                    name=f"RedisNotificationWorker-{i+1}",
                    daemon=True
                )
                worker.start()
                self._workers.append(worker)
            
            logger.info(f"Started {num_workers} Redis notification workers")
    
    def stop_workers(self, wait: bool = True):
        with self._lock:
            if not self._processing:
                return
            
            self._shutdown_event.set()
            self._processing = False
        
        if wait:
            for worker in self._workers:
                worker.join(timeout=10)
        
        self._workers.clear()
        
        if self._redis_client:
            try:
                self._redis_client.close()
            except Exception:
                pass
        
        logger.info("Redis notification workers stopped")
    
    def _worker_loop(self):
        while not self._shutdown_event.is_set():
            task = self._fetch_next_task()
            
            if task:
                try:
                    self._process_task(task)
                except Exception as e:
                    logger.error(f"Error processing task {task.task_id}: {e}")
            else:
                self._shutdown_event.wait(self._poll_interval)
    
    def _fetch_next_task(self) -> Optional[NotificationTask]:
        redis_client = self._get_redis_client()
        if not redis_client:
            self._shutdown_event.wait(self._poll_interval)
            return None
        
        try:
            task_ids = redis_client.hkeys(self._queue_key)
            
            for task_id in task_ids:
                lock_key = f"{self._lock_prefix}{task_id}"
                
                if redis_client.set(lock_key, "locked", nx=True, ex=30):
                    try:
                        task_data = redis_client.hget(self._queue_key, task_id)
                        if task_data:
                            task_dict = json.loads(task_data)
                            task = NotificationTask.from_dict(task_dict)
                            return task
                        else:
                            redis_client.delete(lock_key)
                    except Exception as e:
                        logger.error(f"Failed to fetch task {task_id}: {e}")
                        redis_client.delete(lock_key)
            
            return None
            
        except Exception as e:
            logger.error(f"Error fetching task: {e}")
            self._shutdown_event.wait(self._poll_interval)
            return None
    
    def _process_task(self, task: NotificationTask):
        if self._notification_service is None:
            logger.error("No notification service available")
            self._release_task_lock(task, success=False)
            return
        
        redis_client = self._get_redis_client()
        
        results = {}
        errors = {}
        
        from app.models.alert import AlertEvent, AlertSeverity, AlertStatus
        
        severity_value = task.event_dict.get("severity", "warning")
        try:
            severity = AlertSeverity(severity_value)
        except (ValueError, TypeError):
            severity = AlertSeverity.WARNING
        
        status_value = task.event_dict.get("status", "triggered")
        try:
            status = AlertStatus(status_value)
        except (ValueError, TypeError):
            status = AlertStatus.TRIGGERED
        
        event_from_dict = AlertEvent(
            alert_id=task.event_dict.get("alert_id", ""),
            rule_id=task.event_dict.get("rule_id", ""),
            server_id=task.event_dict.get("server_id", ""),
            metric_type=task.event_dict.get("metric_type", ""),
            metric_value=task.event_dict.get("metric_value", 0.0),
            threshold=task.event_dict.get("threshold", 0.0),
            severity=severity,
            status=status,
            message=task.event_dict.get("message", ""),
            details=task.event_dict.get("details", {}),
            notify_status=task.event_dict.get("notify_status"),
            notify_channels=task.event_dict.get("notify_channels"),
            notify_error=task.event_dict.get("notify_error")
        )
        
        for channel in task.channels:
            try:
                notifier = self._notification_service.notifiers.get(channel)
                if notifier:
                    success = notifier.send(event_from_dict, task.is_resolved)
                    results[channel] = success
                    if success:
                        logger.info(f"Notification sent via {channel} for task {task.task_id}")
                    else:
                        errors[channel] = "Send failed"
                else:
                    logger.warning(f"Unknown channel not found: {channel}")
                    errors[channel] = "Channel not found"
            except Exception as e:
                logger.error(f"Failed to send via {channel}: {e}")
                errors[channel] = str(e)
                results[channel] = False
        
        all_success = all(results.values()) if results else False
        
        if all_success:
            self._release_task_lock(task, success=True)
        else:
            task.retry_count += 1
            if task.retry_count < task.max_retries:
                logger.info(f"Retrying task {task.task_id} (attempt {task.retry_count})")
                
                if redis_client:
                    try:
                        redis_client.hset(self._queue_key, task.task_id, json.dumps(task.to_dict()))
                        redis_client.delete(f"{self._lock_prefix}{task.task_id}")
                    except Exception as e:
                        logger.error(f"Failed to update retry task: {e}")
            else:
                logger.error(f"Task {task.task_id} failed after {task.max_retries} retries")
                self._move_to_failed(task, errors)
    
    def _release_task_lock(self, task: NotificationTask, success: bool):
        redis_client = self._get_redis_client()
        if not redis_client:
            return
        
        lock_key = f"{self._lock_prefix}{task.task_id}"
        
        try:
            if success:
                redis_client.hdel(self._queue_key, task.task_id)
            redis_client.delete(lock_key)
        except Exception as e:
            logger.error(f"Failed to release task lock: {e}")
    
    def _move_to_failed(self, task: NotificationTask, errors: Dict[str, str]):
        redis_client = self._get_redis_client()
        if not redis_client:
            return
        
        lock_key = f"{self._lock_prefix}{task.task_id}"
        
        try:
            failed_data = task.to_dict()
            failed_data["error_details"] = errors
            failed_data["failed_at"] = datetime.utcnow().isoformat()
            
            redis_client.hset(self._failed_key, task.task_id, json.dumps(failed_data))
            redis_client.hdel(self._queue_key, task.task_id)
            redis_client.delete(lock_key)
            
            logger.info(f"Moved task {task.task_id} to failed queue")
        except Exception as e:
            logger.error(f"Failed to move task to failed queue: {e}")
    
    def get_queue_size(self) -> int:
        redis_client = self._get_redis_client()
        if redis_client:
            try:
                return redis_client.hlen(self._queue_key)
            except Exception:
                pass
        return 0
    
    def get_failed_tasks(self) -> List[NotificationTask]:
        redis_client = self._get_redis_client()
        if not redis_client:
            return []
        
        try:
            failed_data = redis_client.hgetall(self._failed_key)
            tasks = []
            for task_id, data_str in failed_data.items():
                try:
                    data = json.loads(data_str)
                    tasks.append(NotificationTask.from_dict(data))
                except Exception:
                    pass
            return tasks
        except Exception:
            return []
    
    def clear_failed_tasks(self):
        redis_client = self._get_redis_client()
        if not redis_client:
            return 0
        
        try:
            count = redis_client.hlen(self._failed_key)
            redis_client.delete(self._failed_key)
            return count
        except Exception:
            return 0
    
    def retry_failed_tasks(self) -> int:
        redis_client = self._get_redis_client()
        if not redis_client:
            return 0
        
        try:
            failed_data = redis_client.hgetall(self._failed_key)
            retried = 0
            
            for task_id, data_str in failed_data.items():
                try:
                    data = json.loads(data_str)
                    task = NotificationTask.from_dict(data)
                    task.retry_count = 0
                    
                    redis_client.hset(self._queue_key, task_id, json.dumps(task.to_dict()))
                    redis_client.hdel(self._failed_key, task_id)
                    retried += 1
                    
                    logger.info(f"Retrying failed task: {task_id}")
                except Exception as e:
                    logger.error(f"Failed to retry task {task_id}: {e}")
            
            return retried
        except Exception:
            return 0


class AlertEngine:
    def __init__(
        self,
        rule_manager: AlertRuleManager = None,
        history_manager: AlertHistoryManager = None,
        notification_service=None,
        use_async_notification: bool = True,
        num_notification_workers: int = 2,
        use_redis_persistence: bool = None
    ):
        if rule_manager is None:
            rule_manager = AlertRuleManager()
        if history_manager is None:
            history_manager = AlertHistoryManager()
        
        self.rule_manager = rule_manager
        self.history_manager = history_manager
        self.notification_service = notification_service
        
        self._metric_value_buffer: Dict[str, List[Dict[str, Any]]] = defaultdict(list)
        self._active_alerts: Dict[str, AlertEvent] = {}
        
        alert_config = config.get('alert', {})
        self._silence_duration_config = SilenceDurationConfig(alert_config)
        self.silence_manager = SilenceManager(self._silence_duration_config)
        
        notification_config = config.get('notification', {})
        redis_config = config.get('redis', {})
        
        if use_redis_persistence is None:
            use_redis_persistence = notification_config.get('use_redis_persistence', True)
        
        if use_redis_persistence and REDIS_AVAILABLE:
            self.notification_queue = RedisNotificationQueue(
                redis_config=redis_config,
                max_queue_size=notification_config.get('max_queue_size', 1000),
                max_retries=notification_config.get('max_retries', 3),
                poll_interval_seconds=notification_config.get('redis_poll_interval_seconds', 2)
            )
            logger.info("Using Redis-backed notification queue for persistence")
        else:
            self.notification_queue = MemoryNotificationQueue(
                max_queue_size=notification_config.get('max_queue_size', 1000)
            )
            logger.info("Using in-memory notification queue (non-persistent)")
        
        self._use_async_notification = use_async_notification
        self._num_notification_workers = num_notification_workers
        
        self._notification_workers_started = False
        
        self.on_alert_triggered: Optional[Callable[[AlertEvent], None]] = None
        self.on_alert_resolved: Optional[Callable[[AlertEvent], None]] = None
        self.on_notification_queued: Optional[Callable[[str, AlertEvent], None]] = None
        
        self._lock = threading.RLock()
        
        logger.info(f"AlertEngine initialized (async_notification={use_async_notification})")
    
    def start(self):
        if self._use_async_notification and not self._notification_workers_started:
            if self.notification_service is None:
                from app.services.notifier import NotificationService
                self.notification_service = NotificationService(config.get('notification', {}))
            
            self.notification_queue.start_workers(
                num_workers=self._num_notification_workers,
                notification_service=self.notification_service
            )
            self._notification_workers_started = True
            logger.info("AlertEngine started - notification workers running")
    
    def stop(self):
        if self._notification_workers_started:
            self.notification_queue.stop_workers()
            self._notification_workers_started = False
        logger.info("AlertEngine stopped")
    
    def process_metric(self, metric: Metric) -> List[AlertEvent]:
        events = []
        
        rules = self.rule_manager.get_rules_for_metric(
            metric_type=metric.metric_type,
            server_id=metric.server_id
        )
        
        if not rules:
            return events
        
        buffer_key = f"{metric.server_id}:{metric.metric_type}"
        self._buffer_metric(buffer_key, metric)
        
        for rule in rules:
            event = self._evaluate_rule(metric, rule, buffer_key)
            if event:
                events.append(event)
        
        return events
    
    def process_metrics_batch(self, metrics: List[Metric]) -> List[AlertEvent]:
        all_events = []
        for metric in metrics:
            events = self.process_metric(metric)
            all_events.extend(events)
        return all_events
    
    def _buffer_metric(self, buffer_key: str, metric: Metric):
        self._metric_value_buffer[buffer_key].append({
            'value': metric.value,
            'time': metric.collected_at or datetime.utcnow()
        })
        
        max_age_seconds = 300
        cutoff_time = datetime.utcnow() - timedelta(seconds=max_age_seconds)
        
        self._metric_value_buffer[buffer_key] = [
            v for v in self._metric_value_buffer[buffer_key]
            if v['time'] >= cutoff_time
        ]
    
    def _evaluate_rule(
        self,
        metric: Metric,
        rule: AlertRule,
        buffer_key: str
    ) -> Optional[AlertEvent]:
        alert_key = f"{rule.rule_id}:{metric.server_id}"
        
        duration_seconds = rule.duration
        if duration_seconds > 0:
            if not self._check_duration_condition(buffer_key, rule, duration_seconds):
                return None
        
        is_violation = rule.evaluate(metric.value)
        
        if is_violation:
            return self._handle_violation(metric, rule, alert_key)
        else:
            return self._handle_recovery(metric, rule, alert_key)
    
    def _check_duration_condition(
        self,
        buffer_key: str,
        rule: AlertRule,
        duration_seconds: int
    ) -> bool:
        values = self._metric_value_buffer.get(buffer_key, [])
        if not values:
            return False
        
        cutoff_time = datetime.utcnow() - timedelta(seconds=duration_seconds)
        
        recent_values = [
            v for v in values
            if v['time'] >= cutoff_time
        ]
        
        if not recent_values:
            return False
        
        for v in recent_values:
            if not rule.evaluate(v['value']):
                return False
        
        return True
    
    def _handle_violation(
        self,
        metric: Metric,
        rule: AlertRule,
        alert_key: str
    ) -> Optional[AlertEvent]:
        with self._lock:
            if alert_key in self._active_alerts:
                return None
            
            severity_str = None
            if hasattr(rule, 'severity'):
                if isinstance(rule.severity, AlertSeverity):
                    severity_str = rule.severity.value
                else:
                    severity_str = str(rule.severity)
            
            is_silenced = self.silence_manager.check_silenced(
                rule_id=rule.rule_id,
                server_id=metric.server_id,
                severity=severity_str
            )
            
            message = self._build_alert_message(metric, rule, is_resolved=False)
            event = AlertEvent.create_from_metric(metric, rule, message)
            
            if is_silenced:
                event.notify_status = "silenced"
                event.status = AlertStatus.SILENCED
                
                active_silences = self.silence_manager.get_active_silences(
                    rule_id=rule.rule_id,
                    server_id=metric.server_id
                )
                if active_silences:
                    event.details["silence_reason"] = active_silences[0].reason
                    event.details["silence_id"] = active_silences[0].silence_id
                
                logger.info(f"Alert {event.alert_id} silenced - {event.message}")
                
                self._active_alerts[alert_key] = event
                self.history_manager.save_alert(event)
                
                return event
            
            self.silence_manager.create_auto_silence_for_alert(event, rule)
            
            if self._use_async_notification:
                event.notify_status = "queued"
                
                try:
                    task_id = self.notification_queue.enqueue(event, is_resolved=False)
                    event.details["notification_task_id"] = task_id
                    
                    if self.on_notification_queued:
                        self.on_notification_queued(task_id, event)
                    
                    logger.debug(f"Notification queued for alert {event.alert_id}, task: {task_id}")
                except Exception as e:
                    logger.error(f"Failed to enqueue notification: {e}")
                    event.notify_status = "failed"
                    event.notify_error = str(e)
            else:
                try:
                    if self.notification_service is None:
                        from app.services.notifier import NotificationService
                        self.notification_service = NotificationService(config.get('notification', {}))
                    
                    results = self.notification_service.send_alert(event, is_resolved=False)
                    all_success = all(results.values()) if results else False
                    event.notify_status = "sent" if all_success else "failed"
                except Exception as e:
                    logger.error(f"Failed to send notification for {event.alert_id}: {e}")
                    event.notify_status = "failed"
                    event.notify_error = str(e)
            
            self._active_alerts[alert_key] = event
            self.history_manager.save_alert(event)
            
            if self.on_alert_triggered:
                self.on_alert_triggered(event)
            
            logger.warning(f"Alert triggered: {event.alert_id} - {event.message}")
            return event
    
    def _handle_recovery(
        self,
        metric: Metric,
        rule: AlertRule,
        alert_key: str
    ) -> Optional[AlertEvent]:
        with self._lock:
            if alert_key not in self._active_alerts:
                return None
            
            active_event = self._active_alerts[alert_key]
            
            if active_event.status == AlertStatus.SILENCED:
                logger.info(f"Silenced alert recovered: {active_event.alert_id}")
                del self._active_alerts[alert_key]
                return None
            
            if active_event.status != AlertStatus.TRIGGERED:
                return None
            
            message = self._build_alert_message(metric, rule, is_resolved=True)
            
            active_event.status = AlertStatus.RESOLVED
            active_event.resolved_at = datetime.utcnow()
            active_event.message = message
            active_event.details["resolved_value"] = metric.value
            
            if self._use_async_notification:
                try:
                    task_id = self.notification_queue.enqueue(active_event, is_resolved=True)
                    active_event.details["recovery_task_id"] = task_id
                    logger.debug(f"Recovery notification queued for alert {active_event.alert_id}")
                except Exception as e:
                    logger.error(f"Failed to enqueue recovery notification: {e}")
            else:
                try:
                    if self.notification_service is None:
                        from app.services.notifier import NotificationService
                        self.notification_service = NotificationService(config.get('notification', {}))
                    
                    self.notification_service.send_alert(active_event, is_resolved=True)
                except Exception as e:
                    logger.error(f"Failed to send recovery notification: {e}")
            
            del self._active_alerts[alert_key]
            self.history_manager.update_alert(active_event)
            
            if self.on_alert_resolved:
                self.on_alert_resolved(active_event)
            
            logger.info(f"Alert resolved: {active_event.alert_id}")
            return active_event
    
    def silence_rule_server(
        self,
        rule_id: str,
        server_id: str,
        duration_seconds: Optional[int] = None,
        severity: Optional[str] = None,
        reason: str = "",
        created_by: str = "api"
    ) -> SilenceEntry:
        return self.silence_manager.create_silence(
            silence_type=SilenceType.RULE_SERVER,
            rule_id=rule_id,
            server_id=server_id,
            duration_seconds=duration_seconds,
            severity=severity,
            reason=reason,
            created_by=created_by
        )
    
    def silence_rule(
        self,
        rule_id: str,
        duration_seconds: Optional[int] = None,
        severity: Optional[str] = None,
        reason: str = "",
        created_by: str = "api"
    ) -> SilenceEntry:
        return self.silence_manager.create_silence(
            silence_type=SilenceType.RULE,
            rule_id=rule_id,
            duration_seconds=duration_seconds,
            severity=severity,
            reason=reason,
            created_by=created_by
        )
    
    def silence_server(
        self,
        server_id: str,
        duration_seconds: Optional[int] = None,
        severity: Optional[str] = None,
        reason: str = "",
        created_by: str = "api"
    ) -> SilenceEntry:
        return self.silence_manager.create_silence(
            silence_type=SilenceType.SERVER,
            server_id=server_id,
            duration_seconds=duration_seconds,
            severity=severity,
            reason=reason,
            created_by=created_by
        )
    
    def silence_global(
        self,
        duration_seconds: Optional[int] = None,
        severity: Optional[str] = None,
        reason: str = "",
        created_by: str = "api"
    ) -> SilenceEntry:
        return self.silence_manager.create_silence(
            silence_type=SilenceType.GLOBAL,
            duration_seconds=duration_seconds,
            severity=severity,
            reason=reason,
            created_by=created_by
        )
    
    def cancel_silence(self, silence_id: str) -> bool:
        return self.silence_manager.cancel_silence(silence_id)
    
    def list_active_silences(
        self,
        rule_id: Optional[str] = None,
        server_id: Optional[str] = None
    ) -> List[SilenceEntry]:
        return self.silence_manager.get_active_silences(rule_id, server_id)
    
    def get_notification_queue_status(self) -> Dict[str, Any]:
        is_redis = isinstance(self.notification_queue, RedisNotificationQueue)
        return {
            "queue_type": "redis" if is_redis else "memory",
            "queue_size": self.notification_queue.get_queue_size(),
            "failed_tasks_count": len(self.notification_queue.get_failed_tasks()),
            "workers_running": self._notification_workers_started
        }
    
    def retry_failed_notifications(self) -> int:
        return self.notification_queue.retry_failed_tasks()
    
    def _build_alert_message(
        self,
        metric: Metric,
        rule: AlertRule,
        is_resolved: bool
    ) -> str:
        op_desc = {
            'greater_than': '超过',
            'less_than': '低于',
            'greater_or_equal': '达到或超过',
            'less_or_equal': '达到或低于',
            'equal': '等于',
            'not_equal': '不等于'
        }
        
        op = rule.operator.value if hasattr(rule.operator, 'value') else str(rule.operator)
        operator_desc = op_desc.get(op, op)
        
        metric_names = {
            'cpu_usage': 'CPU使用率',
            'memory_usage': '内存使用率',
            'disk_usage': '磁盘使用率',
            'network_in': '网络入流量',
            'network_out': '网络出流量'
        }
        
        metric_name = metric_names.get(metric.metric_type, metric.metric_type)
        
        if is_resolved:
            return (
                f"[恢复通知] 服务器 {metric.server_id} 的 {metric_name} 已恢复正常\n"
                f"当前值: {metric.value}{metric.unit}\n"
                f"阈值: {rule.threshold}{metric.unit}\n"
                f"规则: {rule.rule_id}"
            )
        else:
            severity_desc = {
                'info': '信息',
                'warning': '警告',
                'critical': '严重'
            }
            sev = rule.severity.value if hasattr(rule.severity, 'value') else str(rule.severity)
            severity_name = severity_desc.get(sev, sev)
            
            silence_duration = self._silence_duration_config.get_duration_for_rule(rule)
            silence_minutes = silence_duration // 60
            
            return (
                f"[告警通知 - {severity_name}] 服务器 {metric.server_id} 的 {metric_name} {operator_desc}阈值\n"
                f"当前值: {metric.value}{metric.unit}\n"
                f"阈值: {rule.threshold}{metric.unit}\n"
                f"规则: {rule.rule_id}\n"
                f"静默期: {silence_minutes} 分钟\n"
                f"触发时间: {datetime.utcnow().strftime('%Y-%m-%d %H:%M:%S UTC')}"
            )
    
    def get_active_alerts(
        self,
        server_id: Optional[str] = None,
        rule_id: Optional[str] = None
    ) -> List[AlertEvent]:
        with self._lock:
            alerts = list(self._active_alerts.values())
            
            if server_id:
                alerts = [a for a in alerts if a.server_id == server_id]
            if rule_id:
                alerts = [a for a in alerts if a.rule_id == rule_id]
            
            return alerts
    
    def clear_silence(self, alert_key: str) -> bool:
        parts = alert_key.split(':')
        if len(parts) == 2:
            rule_id, server_id = parts
            silences = self.silence_manager.get_active_silences(rule_id, server_id)
            for s in silences:
                self.silence_manager.cancel_silence(s.silence_id)
            return len(silences) > 0
        return False
