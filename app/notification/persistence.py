import asyncio
import time
import uuid
from datetime import datetime, timedelta
from typing import Dict, List, Optional, Any
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy import select, update, delete, and_, or_
from sqlalchemy.sql import func
from app.logging_module import get_logger
from .models import NotificationRequest, NotificationResponse, SuppressionRule
from app.data_access.models import NotificationQueueItem, NotificationSuppressionRule


logger = get_logger(__name__)


class NotificationPersistenceStore:
    def __init__(self, session_factory):
        self._session_factory = session_factory
        self._max_retry = 3
        self._retry_backoff = 60
    
    async def enqueue_notification(self, request: NotificationRequest, notification_id: str) -> bool:
        try:
            async with self._session_factory() as session:
                item = NotificationQueueItem(
                    id=notification_id,
                    priority=request.priority,
                    title=request.title,
                    content=request.content,
                    channel=request.channel,
                    recipient=request.recipient,
                    deduplication_key=request.deduplication_key,
                    ttl_seconds=request.ttl_seconds,
                    metadata=request.metadata or {},
                    status="pending",
                    retry_count=0
                )
                session.add(item)
                await session.commit()
                logger.info(f"Notification persisted to queue", notification_id=notification_id)
                return True
        except Exception as e:
            logger.error(f"Failed to persist notification", error=str(e))
            return False
    
    async def get_pending_notifications(self, limit: int = 100) -> List[Dict[str, Any]]:
        try:
            async with self._session_factory() as session:
                now = datetime.utcnow()
                
                stmt = select(NotificationQueueItem).where(
                    and_(
                        NotificationQueueItem.status == "pending",
                        or_(
                            NotificationQueueItem.next_retry_at == None,
                            NotificationQueueItem.next_retry_at <= now
                        )
                    )
                ).order_by(
                    NotificationQueueItem.priority.desc(),
                    NotificationQueueItem.queued_at.asc()
                ).limit(limit)
                
                result = await session.execute(stmt)
                items = result.scalars().all()
                
                return [
                    {
                        "id": item.id,
                        "priority": item.priority,
                        "title": item.title,
                        "content": item.content,
                        "channel": item.channel,
                        "recipient": item.recipient,
                        "deduplication_key": item.deduplication_key,
                        "ttl_seconds": item.ttl_seconds,
                        "metadata": item.metadata or {},
                        "retry_count": item.retry_count
                    }
                    for item in items
                ]
        except Exception as e:
            logger.error(f"Failed to get pending notifications", error=str(e))
            return []
    
    async def update_notification_status(
        self,
        notification_id: str,
        status: str,
        error_message: Optional[str] = None,
        increment_retry: bool = False
    ) -> bool:
        try:
            async with self._session_factory() as session:
                stmt = select(NotificationQueueItem).where(
                    NotificationQueueItem.id == notification_id
                )
                result = await session.execute(stmt)
                item = result.scalar_one_or_none()
                
                if not item:
                    return False
                
                item.status = status
                
                if status == "sent":
                    item.sent_at = datetime.utcnow()
                elif status == "failed" and error_message:
                    item.error_message = error_message
                
                if increment_retry:
                    item.retry_count = (item.retry_count or 0) + 1
                    if item.retry_count < self._max_retry:
                        item.next_retry_at = datetime.utcnow() + timedelta(
                            seconds=self._retry_backoff * (2 ** item.retry_count)
                        )
                        item.status = "pending"
                
                await session.commit()
                logger.info(
                    f"Notification status updated",
                    notification_id=notification_id,
                    status=status
                )
                return True
        except Exception as e:
            logger.error(f"Failed to update notification status", error=str(e))
            return False
    
    async def delete_notification(self, notification_id: str) -> bool:
        try:
            async with self._session_factory() as session:
                stmt = delete(NotificationQueueItem).where(
                    NotificationQueueItem.id == notification_id
                )
                await session.execute(stmt)
                await session.commit()
                return True
        except Exception as e:
            logger.error(f"Failed to delete notification", error=str(e))
            return False
    
    async def get_notification(self, notification_id: str) -> Optional[Dict[str, Any]]:
        try:
            async with self._session_factory() as session:
                stmt = select(NotificationQueueItem).where(
                    NotificationQueueItem.id == notification_id
                )
                result = await session.execute(stmt)
                item = result.scalar_one_or_none()
                
                if not item:
                    return None
                
                return {
                    "id": item.id,
                    "priority": item.priority,
                    "title": item.title,
                    "content": item.content,
                    "channel": item.channel,
                    "recipient": item.recipient,
                    "status": item.status,
                    "retry_count": item.retry_count,
                    "error_message": item.error_message,
                    "queued_at": item.queued_at,
                    "sent_at": item.sent_at
                }
        except Exception as e:
            logger.error(f"Failed to get notification", error=str(e))
            return None
    
    async def get_suppression_rules(self) -> List[SuppressionRule]:
        try:
            async with self._session_factory() as session:
                stmt = select(NotificationSuppressionRule).where(
                    NotificationSuppressionRule.enabled == True
                )
                result = await session.execute(stmt)
                items = result.scalars().all()
                
                return [
                    SuppressionRule(
                        rule_id=item.rule_id,
                        name=item.name,
                        enabled=item.enabled,
                        priority_threshold=item.priority_threshold,
                        channel=item.channel,
                        time_window_seconds=item.time_window_seconds,
                        max_count=item.max_count,
                        pattern=item.pattern
                    )
                    for item in items
                ]
        except Exception as e:
            logger.error(f"Failed to load suppression rules", error=str(e))
            return []
    
    async def save_suppression_rule(self, rule: SuppressionRule) -> bool:
        try:
            async with self._session_factory() as session:
                stmt = select(NotificationSuppressionRule).where(
                    NotificationSuppressionRule.rule_id == rule.rule_id
                )
                result = await session.execute(stmt)
                existing = result.scalar_one_or_none()
                
                if existing:
                    existing.name = rule.name
                    existing.enabled = rule.enabled
                    existing.priority_threshold = rule.priority_threshold
                    existing.channel = rule.channel
                    existing.time_window_seconds = rule.time_window_seconds
                    existing.max_count = rule.max_count
                    existing.pattern = rule.pattern
                else:
                    item = NotificationSuppressionRule(
                        rule_id=rule.rule_id,
                        name=rule.name,
                        enabled=rule.enabled,
                        priority_threshold=rule.priority_threshold,
                        channel=rule.channel,
                        time_window_seconds=rule.time_window_seconds,
                        max_count=rule.max_count,
                        pattern=rule.pattern
                    )
                    session.add(item)
                
                await session.commit()
                logger.info(f"Suppression rule saved", rule_id=rule.rule_id)
                return True
        except Exception as e:
            logger.error(f"Failed to save suppression rule", error=str(e))
            return False
    
    async def delete_suppression_rule(self, rule_id: str) -> bool:
        try:
            async with self._session_factory() as session:
                stmt = delete(NotificationSuppressionRule).where(
                    NotificationSuppressionRule.rule_id == rule_id
                )
                await session.execute(stmt)
                await session.commit()
                return True
        except Exception as e:
            logger.error(f"Failed to delete suppression rule", error=str(e))
            return False
    
    async def get_statistics(self) -> Dict[str, Any]:
        try:
            async with self._session_factory() as session:
                stats = {}
                
                for status in ["pending", "processing", "sent", "failed"]:
                    stmt = select(func.count(NotificationQueueItem.id)).where(
                        NotificationQueueItem.status == status
                    )
                    result = await session.execute(stmt)
                    stats[status] = result.scalar() or 0
                
                return stats
        except Exception as e:
            logger.error(f"Failed to get notification stats", error=str(e))
            return {}
    
    async def cleanup_expired(self, retention_days: int = 7) -> int:
        try:
            async with self._session_factory() as session:
                cutoff = datetime.utcnow() - timedelta(days=retention_days)
                
                stmt = delete(NotificationQueueItem).where(
                    and_(
                        NotificationQueueItem.status.in_(["sent", "failed"]),
                        or_(
                            NotificationQueueItem.sent_at < cutoff,
                            and_(
                                NotificationQueueItem.sent_at == None,
                                NotificationQueueItem.queued_at < cutoff
                            )
                        )
                    )
                )
                result = await session.execute(stmt)
                await session.commit()
                
                deleted = result.rowcount or 0
                logger.info(f"Cleaned up {deleted} expired notifications")
                return deleted
        except Exception as e:
            logger.error(f"Failed to cleanup expired notifications", error=str(e))
            return 0
