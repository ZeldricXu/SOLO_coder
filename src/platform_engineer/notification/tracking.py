from dataclasses import dataclass, field
from datetime import datetime, timezone
from enum import Enum
from typing import Any, Dict, List, Optional
from uuid import uuid4


class DeliveryStatus(Enum):
    PENDING = "pending"
    SCHEDULED = "scheduled"
    IN_PROGRESS = "in_progress"
    DELIVERED = "delivered"
    FAILED = "failed"
    EXPIRED = "expired"
    CANCELLED = "cancelled"


@dataclass
class DeliveryAttempt:
    attempt_number: int
    status: DeliveryStatus
    started_at: datetime
    completed_at: Optional[datetime] = None
    error: Optional[str] = None
    metadata: Dict[str, Any] = field(default_factory=dict)


@dataclass
class DeliveryRecord:
    delivery_id: str
    notification_id: str
    channel_id: str
    recipient: str
    status: DeliveryStatus
    created_at: datetime = field(default_factory=lambda: datetime.now(timezone.utc))
    last_attempt_at: Optional[datetime] = None
    attempts: List[DeliveryAttempt] = field(default_factory=list)
    message_id: Optional[str] = None
    error: Optional[str] = None
    expires_at: Optional[datetime] = None

    def add_attempt(self, status: DeliveryStatus, error: Optional[str] = None, **metadata) -> DeliveryAttempt:
        attempt = DeliveryAttempt(
            attempt_number=len(self.attempts) + 1,
            status=status,
            started_at=datetime.now(timezone.utc),
            error=error,
            metadata=metadata,
        )
        self.attempts.append(attempt)
        self.last_attempt_at = attempt.started_at
        self.status = status
        if error:
            self.error = error
        return attempt

    def complete_attempt(self, status: DeliveryStatus, error: Optional[str] = None, **metadata) -> None:
        if self.attempts:
            last = self.attempts[-1]
            last.completed_at = datetime.now(timezone.utc)
            last.status = status
            if error:
                last.error = error
            last.metadata.update(metadata)
            self.status = status
            if error:
                self.error = error
            self.last_attempt_at = last.completed_at

    def is_expired(self) -> bool:
        if self.expires_at is None:
            return False
        return datetime.now(timezone.utc) >= self.expires_at

    def to_dict(self) -> Dict[str, Any]:
        return {
            "delivery_id": self.delivery_id,
            "notification_id": self.notification_id,
            "channel_id": self.channel_id,
            "recipient": self.recipient,
            "status": self.status.value,
            "created_at": self.created_at.isoformat(),
            "last_attempt_at": self.last_attempt_at.isoformat() if self.last_attempt_at else None,
            "attempts": [
                {
                    "attempt_number": a.attempt_number,
                    "status": a.status.value,
                    "started_at": a.started_at.isoformat(),
                    "completed_at": a.completed_at.isoformat() if a.completed_at else None,
                    "error": a.error,
                    "metadata": a.metadata,
                }
                for a in self.attempts
            ],
            "message_id": self.message_id,
            "error": self.error,
            "expires_at": self.expires_at.isoformat() if self.expires_at else None,
        }


class DeliveryTracker:
    def __init__(self, max_records: int = 10000, logger=None):
        self._records: Dict[str, DeliveryRecord] = {}
        self._max_records = max_records
        self._logger = logger

    def create_record(
        self,
        notification_id: str,
        channel_id: str,
        recipient: str,
        expires_seconds: Optional[int] = None,
    ) -> DeliveryRecord:
        delivery_id = f"dlv_{uuid4().hex[:12]}"
        record = DeliveryRecord(
            delivery_id=delivery_id,
            notification_id=notification_id,
            channel_id=channel_id,
            recipient=recipient,
            status=DeliveryStatus.PENDING,
        )
        if expires_seconds:
            record.expires_at = datetime.now(timezone.utc).replace(tzinfo=timezone.utc) + __import__("datetime").timedelta(seconds=expires_seconds)
        self._records[delivery_id] = record
        self._cleanup_if_needed()
        return record

    def get_record(self, delivery_id: str) -> Optional[DeliveryRecord]:
        return self._records.get(delivery_id)

    def get_records_by_notification(self, notification_id: str) -> List[DeliveryRecord]:
        return [r for r in self._records.values() if r.notification_id == notification_id]

    def get_records_by_status(self, status: DeliveryStatus) -> List[DeliveryRecord]:
        return [r for r in self._records.values() if r.status == status]

    def update_status(
        self,
        delivery_id: str,
        status: DeliveryStatus,
        error: Optional[str] = None,
        message_id: Optional[str] = None,
    ) -> Optional[DeliveryRecord]:
        record = self._records.get(delivery_id)
        if record:
            record.status = status
            if error:
                record.error = error
            if message_id:
                record.message_id = message_id
            record.last_attempt_at = datetime.now(timezone.utc)
        return record

    def mark_delivered(self, delivery_id: str, message_id: str) -> Optional[DeliveryRecord]:
        return self.update_status(delivery_id, DeliveryStatus.DELIVERED, message_id=message_id)

    def mark_failed(self, delivery_id: str, error: str) -> Optional[DeliveryRecord]:
        return self.update_status(delivery_id, DeliveryStatus.FAILED, error=error)

    def get_stats(self) -> Dict[str, Any]:
        total = len(self._records)
        by_status: Dict[str, int] = {}
        for status in DeliveryStatus:
            by_status[status.value] = 0
        for record in self._records.values():
            by_status[record.status.value] += 1
        return {
            "total": total,
            "by_status": by_status,
            "max_records": self._max_records,
        }

    def _cleanup_if_needed(self) -> None:
        if len(self._records) <= self._max_records:
            return
        sorted_records = sorted(self._records.values(), key=lambda r: r.created_at)
        remove_count = len(self._records) - self._max_records + int(self._max_records * 0.1)
        for record in sorted_records[:remove_count]:
            del self._records[record.delivery_id]
        if self._logger:
            self._logger.info(f"Cleaned up {remove_count} delivery records")

    def list_records(
        self,
        limit: int = 100,
        offset: int = 0,
        status: Optional[DeliveryStatus] = None,
    ) -> List[DeliveryRecord]:
        records = list(self._records.values())
        if status:
            records = [r for r in records if r.status == status]
        records.sort(key=lambda r: r.created_at, reverse=True)
        return records[offset:offset + limit]

    def delete_record(self, delivery_id: str) -> bool:
        if delivery_id in self._records:
            del self._records[delivery_id]
            return True
        return False
