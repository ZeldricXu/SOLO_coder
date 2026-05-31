import asyncio
import time
import uuid
import heapq
from datetime import datetime, timedelta
from typing import Dict, List, Optional, Any, Tuple
from collections import defaultdict, deque
from threading import Lock
from app.logging_module import get_logger
from app.config import settings
from .models import (
    NotificationRequest, NotificationResponse,
    NotificationPriority, NotificationChannel, SuppressionRule
)
from .persistence import NotificationPersistenceStore


logger = get_logger(__name__)


class SlidingWindowRateLimiter:
    __slots__ = ('window_seconds', 'max_count', '_buckets', '_bucket_size', '_lock')
    
    def __init__(self, window_seconds: int = 60, max_count: int = 100):
        self.window_seconds = window_seconds
        self.max_count = max_count
        self._bucket_size = 1
        self._buckets: Dict[str, deque] = {}
        self._lock = Lock()
    
    def check_and_record(self, key: str) -> bool:
        now = time.time()
        cutoff = now - self.window_seconds
        
        with self._lock:
            if key not in self._buckets:
                self._buckets[key] = deque()
            
            bucket = self._buckets[key]
            
            while bucket and bucket[0][0] < cutoff:
                bucket.popleft()
            
            total = sum(count for _, count in bucket)
            if total >= self.max_count:
                return False
            
            if bucket and bucket[-1][0] >= now - self._bucket_size:
                bucket[-1] = (bucket[-1][0], bucket[-1][1] + 1)
            else:
                bucket.append((now, 1))
            
            return True


class TTLDeduplicationStore:
    __slots__ = ('ttl_seconds', '_keys', '_lock')
    
    def __init__(self, ttl_seconds: int = 300):
        self.ttl_seconds = ttl_seconds
        self._keys: Dict[str, float] = {}
        self._lock = Lock()
    
    def is_duplicate(self, key: str) -> bool:
        with self._lock:
            timestamp = self._keys.get(key)
            if timestamp is None:
                return False
            
            if time.time() - timestamp > self.ttl_seconds:
                del self._keys[key]
                return False
            
            return True
    
    def record(self, key: str):
        with self._lock:
            self._keys[key] = time.time()


class FastPriorityQueue:
    __slots__ = ('_heap', '_task_map', '_lock')
    
    def __init__(self):
        self._heap: List[Tuple[int, int, str, Any]] = []
        self._task_map: Dict[str, Tuple[int, int, str, Any]] = {}
        self._lock = Lock()
        self._counter = 0
    
    def put_nowait(self, priority: int, task_id: str, task: Any):
        with self._lock:
            self._counter += 1
            entry = (priority, self._counter, task_id, task)
            heapq.heappush(self._heap, entry)
            self._task_map[task_id] = entry
    
    def get_nowait(self) -> Optional[Tuple[int, str, Any]]:
        with self._lock:
            while self._heap:
                priority, counter, task_id, task = heapq.heappop(self._heap)
                
                if task_id in self._task_map:
                    del self._task_map[task_id]
                    return (priority, task_id, task)
            
            return None
    
    def qsize(self) -> int:
        with self._lock:
            return len(self._task_map)
    
    def get_priority_counts(self, max_priority: int) -> Dict[int, int]:
        counts = defaultdict(int)
        with self._lock:
            for priority, _, _, _ in self._heap:
                actual = max_priority - priority
                if actual > 0:
                    counts[actual] += 1
        return dict(counts)


class NotificationError(Exception):
    def __init__(self, code: str, message: str, details: Any = None):
        self.code = code
        self.message = message
        self.details = details
        super().__init__(message)


class ChannelHandlerRegistry:
    __slots__ = ('_handlers',)
    
    def __init__(self):
        self._handlers: Dict[str, Any] = {}
        self._register_defaults()
    
    def _register_defaults(self):
        self._handlers["email"] = self._noop_handler
        self._handlers["slack"] = self._noop_handler
        self._handlers["webhook"] = self._noop_handler
        self._handlers["sms"] = self._noop_handler
        self._handlers["push"] = self._noop_handler
    
    async def _noop_handler(self, request: NotificationRequest):
        await asyncio.sleep(0.01)
    
    def get_handler(self, channel: str) -> Optional[Any]:
        return self._handlers.get(channel)
    
    def register(self, channel: str, handler: Any):
        self._handlers[channel] = handler
    
    def has_channel(self, channel: str) -> bool:
        return channel in self._handlers


class SuppressionRuleEngine:
    __slots__ = ('_rules', '_enabled_rules_cache', '_lock', '_dirty')
    
    def __init__(self):
        self._rules: Dict[str, SuppressionRule] = {}
        self._enabled_rules_cache: List[SuppressionRule] = []
        self._lock = Lock()
        self._dirty = True
    
    def add(self, rule: SuppressionRule):
        with self._lock:
            self._rules[rule.rule_id] = rule
            self._dirty = True
    
    def remove(self, rule_id: str) -> bool:
        with self._lock:
            if rule_id in self._rules:
                del self._rules[rule_id]
                self._dirty = True
                return True
            return False
    
    def get_all(self) -> Dict[str, SuppressionRule]:
        with self._lock:
            return self._rules.copy()
    
    def check(self, request: NotificationRequest, rate_limiter: SlidingWindowRateLimiter) -> Tuple[bool, Optional[str]]:
        if self._dirty:
            with self._lock:
                if self._dirty:
                    self._enabled_rules_cache = [
                        r for r in self._rules.values() if r.enabled
                    ]
                    self._dirty = False
        
        for rule in self._enabled_rules_cache:
            if rule.priority_threshold is not None and request.priority <= rule.priority_threshold:
                continue
            
            if rule.channel is not None and request.channel != rule.channel:
                continue
            
            if rule.pattern is not None:
                content_check = rule.pattern in request.title
                if not content_check:
                    content_check = rule.pattern in request.content
                if not content_check:
                    continue
            
            rule_key = f"r:{rule.rule_id}:{request.channel}"
            if not rate_limiter.check_and_record(rule_key):
                return True, f"suppression_rule:{rule.name}"
        
        return False, None


class NotificationManager:
    __slots__ = (
        '_rate_limiter', '_dedup_store', '_rule_engine',
        '_queue', '_handler_registry', '_persistence_store',
        '_enable_persistence', '_processing_task', '_cleanup_task',
        '_running', '_session_factory'
    )
    
    def __init__(self, session_factory=None, enable_persistence: bool = True):
        self._session_factory = session_factory
        self._enable_persistence = enable_persistence
        
        self._rate_limiter = SlidingWindowRateLimiter(
            window_seconds=settings.NOTIFICATION_SUPPRESSION_WINDOW,
            max_count=settings.NOTIFICATION_RATE_LIMIT
        )
        
        self._dedup_store = TTLDeduplicationStore()
        self._rule_engine = SuppressionRuleEngine()
        self._queue = FastPriorityQueue()
        self._handler_registry = ChannelHandlerRegistry()
        
        self._persistence_store: Optional[NotificationPersistenceStore] = None
        if enable_persistence and session_factory:
            self._persistence_store = NotificationPersistenceStore(session_factory)
        
        self._processing_task: Optional[asyncio.Task] = None
        self._cleanup_task: Optional[asyncio.Task] = None
        self._running = False
    
    async def start(self):
        if self._running:
            return
        
        self._running = True
        
        if self._persistence_store:
            await self._load_persisted_state_optimized()
        
        self._processing_task = asyncio.create_task(self._process_queue_optimized())
        self._cleanup_task = asyncio.create_task(self._cleanup_loop_optimized())
        
        logger.info(
            "Notification manager started",
            persistence_enabled=self._enable_persistence,
            handler_count=len(self._handler_registry._handlers)
        )
    
    async def stop(self):
        self._running = False
        
        for task in [self._processing_task, self._cleanup_task]:
            if task:
                task.cancel()
                try:
                    await task
                except asyncio.CancelledError:
                    pass
        
        logger.info("Notification manager stopped")
    
    async def _load_persisted_state_optimized(self):
        if not self._persistence_store:
            return
        
        logger.info("Loading persisted notification state...")
        
        rules = await self._persistence_store.get_suppression_rules()
        for rule in rules:
            self._rule_engine.add(rule)
        logger.info(f"Loaded {len(rules)} suppression rules")
        
        pending_items = await self._persistence_store.get_pending_notifications(limit=500)
        restored_count = 0
        
        for item in pending_items:
            try:
                request = NotificationRequest(
                    title=item["title"],
                    content=item["content"],
                    priority=item["priority"],
                    channel=item["channel"],
                    recipient=item["recipient"],
                    deduplication_key=item.get("deduplication_key"),
                    ttl_seconds=item.get("ttl_seconds"),
                    metadata=item.get("metadata") or {}
                )
                
                priority = settings.MAX_NOTIFICATION_PRIORITY - item["priority"]
                self._queue.put_nowait(priority, item["id"], request)
                restored_count += 1
            except Exception as e:
                logger.error(f"Failed to restore notification", id=item.get("id"), error=str(e))
        
        logger.info(f"Restored {restored_count} pending notifications")
    
    async def add_suppression_rule(self, rule: SuppressionRule) -> bool:
        self._rule_engine.add(rule)
        
        if self._persistence_store:
            await self._persistence_store.save_suppression_rule(rule)
        
        logger.info(f"Added suppression rule", rule_id=rule.rule_id)
        return True
    
    async def remove_suppression_rule(self, rule_id: str) -> bool:
        success = self._rule_engine.remove(rule_id)
        
        if success and self._persistence_store:
            await self._persistence_store.delete_suppression_rule(rule_id)
        
        if success:
            logger.info(f"Removed suppression rule", rule_id=rule_id)
        
        return success
    
    async def send(self, request: NotificationRequest) -> NotificationResponse:
        notification_id = f"notif_{uuid.uuid4().hex[:12]}"
        
        try:
            suppressed, reason = await self._check_suppression_fast(request)
            if suppressed:
                logger.warning(f"Notification suppressed", notification_id=notification_id, reason=reason)
                return NotificationResponse(
                    notification_id=notification_id,
                    status="suppressed",
                    suppressed=True,
                    suppression_reason=reason
                )
            
            if self._persistence_store:
                persisted = await self._persistence_store.enqueue_notification(request, notification_id)
                if not persisted:
                    logger.warning(f"Persistence failed, using memory only", notification_id=notification_id)
            
            priority = settings.MAX_NOTIFICATION_PRIORITY - request.priority
            self._queue.put_nowait(priority, notification_id, request)
            
            if request.deduplication_key:
                self._dedup_store.record(request.deduplication_key)
            
            logger.debug(f"Notification queued", notification_id=notification_id, priority=request.priority)
            
            return NotificationResponse(
                notification_id=notification_id,
                status="queued"
            )
        
        except NotificationError as e:
            logger.error(f"Notification send error", code=e.code, message=e.message)
            return NotificationResponse(
                notification_id=notification_id,
                status="failed",
                message=e.message
            )
        except Exception as e:
            logger.error(f"Unexpected error sending notification", error=str(e))
            return NotificationResponse(
                notification_id=notification_id,
                status="failed",
                message=str(e)
            )
    
    async def _check_suppression_fast(self, request: NotificationRequest) -> Tuple[bool, Optional[str]]:
        if request.deduplication_key:
            if self._dedup_store.is_duplicate(request.deduplication_key):
                return True, "deduplication"
        
        rate_key = f"{request.channel}:{request.recipient or 'global'}"
        if not self._rate_limiter.check_and_record(rate_key):
            return True, "rate_limit_exceeded"
        
        return self._rule_engine.check(request, self._rate_limiter)
    
    async def _process_queue_optimized(self):
        batch_size = 10
        
        while self._running:
            try:
                processed = 0
                while processed < batch_size:
                    item = self._queue.get_nowait()
                    if item is None:
                        break
                    
                    priority, notification_id, request = item
                    await self._process_single(notification_id, request)
                    processed += 1
                
                if processed == 0:
                    await asyncio.sleep(0.05)
                
            except asyncio.CancelledError:
                raise
            except Exception as e:
                logger.error(f"Queue processing error", error=str(e))
                await asyncio.sleep(0.1)
    
    async def _process_single(self, notification_id: str, request: NotificationRequest):
        if self._persistence_store:
            await self._persistence_store.update_notification_status(
                notification_id, "processing"
            )
        
        handler = self._handler_registry.get_handler(request.channel)
        success = False
        error_message = None
        
        if handler:
            try:
                await handler(request)
                success = True
                logger.debug(f"Notification sent", notification_id=notification_id)
            except Exception as e:
                error_message = str(e)
                logger.error(f"Handler failed", notification_id=notification_id, error=error_message)
        else:
            error_message = f"Unknown channel: {request.channel}"
            logger.warning(f"Unknown channel", channel=request.channel)
        
        if self._persistence_store:
            if success:
                await self._persistence_store.update_notification_status(
                    notification_id, "sent"
                )
            else:
                await self._persistence_store.update_notification_status(
                    notification_id, "failed", error_message, increment_retry=True
                )
    
    async def _cleanup_loop_optimized(self):
        while self._running:
            try:
                await asyncio.sleep(300)
                
                if self._persistence_store:
                    await self._persistence_store.cleanup_expired(retention_days=7)
                
            except asyncio.CancelledError:
                raise
            except Exception as e:
                logger.error(f"Cleanup loop error", error=str(e))
    
    def get_queue_size(self) -> int:
        return self._queue.qsize()
    
    def get_pending_count(self) -> Dict[int, int]:
        return self._queue.get_priority_counts(settings.MAX_NOTIFICATION_PRIORITY)
    
    async def get_persistence_statistics(self) -> Dict[str, Any]:
        if not self._persistence_store:
            return {"persistence_enabled": False}
        
        stats = await self._persistence_store.get_statistics()
        stats["persistence_enabled"] = True
        return stats
    
    async def get_notification_status(self, notification_id: str) -> Optional[Dict[str, Any]]:
        if not self._persistence_store:
            return None
        
        return await self._persistence_store.get_notification(notification_id)
    
    def register_channel_handler(self, channel: str, handler: Any):
        self._handler_registry.register(channel, handler)
        logger.info(f"Registered handler for channel", channel=channel)
