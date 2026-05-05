from typing import Dict, Any, Optional, Tuple, Set
from datetime import datetime
from dataclasses import dataclass, field
import logging
import asyncio
import uuid

from app.core.models import MessageStatus

logger = logging.getLogger(__name__)


@dataclass
class TrackedMessage:
    message_id: str
    topic: str
    partition: int
    offset: int
    status: MessageStatus = MessageStatus.PENDING
    created_at: datetime = field(default_factory=datetime.utcnow)
    processed_at: Optional[datetime] = None
    error_message: Optional[str] = None
    retry_count: int = 0
    max_retries: int = 3

    def can_retry(self) -> bool:
        return self.retry_count < self.max_retries

    def increment_retry(self):
        self.retry_count += 1

    def mark_success(self):
        self.status = MessageStatus.SUCCESS
        self.processed_at = datetime.utcnow()

    def mark_failed(self, error: str):
        self.status = MessageStatus.FAILED
        self.error_message = error
        self.processed_at = datetime.utcnow()

    def mark_acknowledged(self):
        self.status = MessageStatus.ACKNOWLEDGED
        self.processed_at = datetime.utcnow()


class MessageTracker:
    def __init__(self, max_messages: int = 10000):
        self._messages: Dict[str, TrackedMessage] = {}
        self._partition_offsets: Dict[Tuple[str, int], Dict[int, TrackedMessage]] = {}
        self._max_messages = max_messages
        self._lock = asyncio.Lock()

    def generate_message_id(self) -> str:
        return f"msg_{uuid.uuid4().hex[:16]}"

    async def register_message(
        self,
        topic: str,
        partition: int,
        offset: int,
        message_id: str = None
    ) -> TrackedMessage:
        async with self._lock:
            msg_id = message_id or self.generate_message_id()

            tracked = TrackedMessage(
                message_id=msg_id,
                topic=topic,
                partition=partition,
                offset=offset
            )

            self._messages[msg_id] = tracked

            partition_key = (topic, partition)
            if partition_key not in self._partition_offsets:
                self._partition_offsets[partition_key] = {}
            self._partition_offsets[partition_key][offset] = tracked

            self._cleanup_if_needed()

            return tracked

    async def get_message(self, message_id: str) -> Optional[TrackedMessage]:
        async with self._lock:
            return self._messages.get(message_id)

    async def get_messages_by_partition(
        self,
        topic: str,
        partition: int
    ) -> Dict[int, TrackedMessage]:
        async with self._lock:
            partition_key = (topic, partition)
            return self._partition_offsets.get(partition_key, {}).copy()

    async def acknowledge_success(self, message_id: str) -> bool:
        async with self._lock:
            tracked = self._messages.get(message_id)
            if not tracked:
                logger.warning(f"Message not found for ack: {message_id}")
                return False

            tracked.mark_success()
            logger.debug(f"Message {message_id} marked as success")
            return True

    async def acknowledge_failure(self, message_id: str, error: str) -> bool:
        async with self._lock:
            tracked = self._messages.get(message_id)
            if not tracked:
                logger.warning(f"Message not found for nack: {message_id}")
                return False

            tracked.mark_failed(error)
            logger.warning(f"Message {message_id} marked as failed: {error}")
            return True

    async def get_commit_offsets(self) -> Dict[Tuple[str, int], int]:
        async with self._lock:
            commit_offsets: Dict[Tuple[str, int], int] = {}

            for (topic, partition), offsets in self._partition_offsets.items():
                if not offsets:
                    continue

                sorted_offsets = sorted(offsets.keys())

                continuous_committable = 0
                for offset in sorted_offsets:
                    tracked = offsets[offset]
                    if tracked.status == MessageStatus.SUCCESS:
                        continuous_committable = offset + 1
                    else:
                        break

                if continuous_committable > 0:
                    commit_offsets[(topic, partition)] = continuous_committable

            return commit_offsets

    async def remove_committed_offsets(
        self,
        topic: str,
        partition: int,
        committed_offset: int
    ):
        async with self._lock:
            partition_key = (topic, partition)
            offsets = self._partition_offsets.get(partition_key, {})

            to_remove = [offset for offset in offsets.keys() if offset < committed_offset]

            for offset in to_remove:
                tracked = offsets.pop(offset, None)
                if tracked:
                    tracked.mark_acknowledged()
                    self._messages.pop(tracked.message_id, None)

            if not offsets and partition_key in self._partition_offsets:
                del self._partition_offsets[partition_key]

    def _cleanup_if_needed(self):
        if len(self._messages) > self._max_messages:
            logger.warning(
                f"Message tracker exceeded max messages ({self._max_messages}), "
                f"current count: {len(self._messages)}"
            )

            sorted_messages = sorted(
                self._messages.values(),
                key=lambda m: m.created_at
            )

            to_remove = sorted_messages[:int(len(sorted_messages) * 0.2)]

            for tracked in to_remove:
                del self._messages[tracked.message_id]

                partition_key = (tracked.topic, tracked.partition)
                if partition_key in self._partition_offsets:
                    self._partition_offsets[partition_key].pop(tracked.offset, None)
                    if not self._partition_offsets[partition_key]:
                        del self._partition_offsets[partition_key]

            logger.info(
                f"Cleaned up {len(to_remove)} old messages, "
                f"remaining: {len(self._messages)}"
            )

    def get_stats(self) -> Dict[str, Any]:
        pending_count = sum(
            1 for m in self._messages.values()
            if m.status == MessageStatus.PENDING
        )
        success_count = sum(
            1 for m in self._messages.values()
            if m.status == MessageStatus.SUCCESS
        )
        failed_count = sum(
            1 for m in self._messages.values()
            if m.status == MessageStatus.FAILED
        )

        return {
            "total_messages": len(self._messages),
            "pending_messages": pending_count,
            "success_messages": success_count,
            "failed_messages": failed_count,
            "active_partitions": len(self._partition_offsets)
        }


message_tracker = MessageTracker()
