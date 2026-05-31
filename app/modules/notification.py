from datetime import datetime, timedelta
from typing import Any, Dict, List, Optional
from sqlalchemy import select, and_
from sqlalchemy.ext.asyncio import AsyncSession
from app.models import Notification
from app.logger import logger


class NotificationManager:
    def __init__(self, db: AsyncSession):
        self.db = db
        self._suppression_rules: Dict[str, Dict[str, Any]] = {}
    
    def register_suppression_rule(
        self,
        category: str,
        window_minutes: int = 60,
        max_notifications: int = 5,
        min_interval_seconds: int = 300
    ):
        self._suppression_rules[category] = {
            "window_minutes": window_minutes,
            "max_notifications": max_notifications,
            "min_interval_seconds": min_interval_seconds,
            "last_sent": {}
        }
        logger.info("Registered suppression rule", category=category)
    
    async def create_notification(
        self,
        title: str,
        content: str,
        user_id: str = None,
        priority: int = 0,
        category: str = None
    ) -> Optional[Notification]:
        if not await self._should_send_notification(category, user_id):
            logger.info("Notification suppressed", category=category, user_id=user_id)
            return None
        
        notification = Notification(
            user_id=user_id,
            title=title,
            content=content,
            priority=priority,
            category=category
        )
        self.db.add(notification)
        await self.db.flush()
        
        if category in self._suppression_rules:
            rule = self._suppression_rules[category]
            key = user_id or "global"
            if key not in rule["last_sent"]:
                rule["last_sent"][key] = []
            rule["last_sent"][key].append(datetime.utcnow())
        
        logger.info("Notification created", notification_id=notification.id, category=category)
        return notification
    
    async def _should_send_notification(self, category: str, user_id: str = None) -> bool:
        if not category or category not in self._suppression_rules:
            return True
        
        rule = self._suppression_rules[category]
        key = user_id or "global"
        
        if key not in rule["last_sent"]:
            return True
        
        now = datetime.utcnow()
        window_start = now - timedelta(minutes=rule["window_minutes"])
        
        recent_notifications = [
            ts for ts in rule["last_sent"][key]
            if ts >= window_start
        ]
        rule["last_sent"][key] = recent_notifications
        
        if len(recent_notifications) >= rule["max_notifications"]:
            return False
        
        if recent_notifications:
            last_time = recent_notifications[-1]
            elapsed = (now - last_time).total_seconds()
            if elapsed < rule["min_interval_seconds"]:
                return False
        
        return True
    
    async def get_user_notifications(
        self,
        user_id: str,
        include_read: bool = False,
        limit: int = 100
    ) -> List[Dict[str, Any]]:
        conditions = [Notification.user_id == user_id]
        if not include_read:
            conditions.append(Notification.is_read == False)
        
        stmt = select(Notification).where(
            and_(*conditions)
        ).order_by(Notification.priority.desc(), Notification.created_at.desc()).limit(limit)
        
        result = await self.db.execute(stmt)
        notifications = result.scalars().all()
        
        return [self._notification_to_dict(n) for n in notifications]
    
    async def mark_as_read(self, notification_id: str, user_id: str = None) -> bool:
        stmt = select(Notification).where(Notification.id == notification_id)
        result = await self.db.execute(stmt)
        notification = result.scalar_one_or_none()
        
        if not notification:
            return False
        
        if user_id and notification.user_id != user_id:
            return False
        
        notification.is_read = True
        await self.db.flush()
        return True
    
    async def mark_all_as_read(self, user_id: str) -> int:
        stmt = select(Notification).where(
            and_(
                Notification.user_id == user_id,
                Notification.is_read == False
            )
        )
        result = await self.db.execute(stmt)
        notifications = result.scalars().all()
        
        for n in notifications:
            n.is_read = True
        
        await self.db.flush()
        return len(notifications)
    
    async def get_unread_count(self, user_id: str) -> int:
        stmt = select(Notification).where(
            and_(
                Notification.user_id == user_id,
                Notification.is_read == False
            )
        )
        result = await self.db.execute(stmt)
        notifications = result.scalars().all()
        return len(notifications)
    
    async def suppress_notifications(
        self,
        user_id: str,
        category: str = None,
        duration_minutes: int = 60
    ):
        end_time = datetime.utcnow() + timedelta(minutes=duration_minutes)
        
        conditions = [Notification.user_id == user_id]
        if category:
            conditions.append(Notification.category == category)
        
        stmt = select(Notification).where(and_(*conditions))
        result = await self.db.execute(stmt)
        notifications = result.scalars().all()
        
        for n in notifications:
            if not n.is_read:
                n.suppressed_until = end_time
        
        await self.db.flush()
        logger.info("Suppressed notifications", user_id=user_id, category=category, duration_minutes=duration_minutes)
    
    async def broadcast_notification(
        self,
        title: str,
        content: str,
        priority: int = 0,
        category: str = None
    ) -> List[Notification]:
        notification = Notification(
            user_id=None,
            title=title,
            content=content,
            priority=priority,
            category=category
        )
        self.db.add(notification)
        await self.db.flush()
        
        logger.info("Broadcast notification created", notification_id=notification.id, category=category)
        return [notification]
    
    async def delete_notification(self, notification_id: str, user_id: str = None) -> bool:
        stmt = select(Notification).where(Notification.id == notification_id)
        result = await self.db.execute(stmt)
        notification = result.scalar_one_or_none()
        
        if not notification:
            return False
        
        if user_id and notification.user_id != user_id:
            return False
        
        await self.db.delete(notification)
        await self.db.flush()
        return True
    
    def _notification_to_dict(self, notification: Notification) -> Dict[str, Any]:
        return {
            "id": notification.id,
            "user_id": notification.user_id,
            "title": notification.title,
            "content": notification.content,
            "priority": notification.priority,
            "category": notification.category,
            "is_read": notification.is_read,
            "suppressed_until": notification.suppressed_until.isoformat() if notification.suppressed_until else None,
            "created_at": notification.created_at.isoformat() if notification.created_at else None
        }
