"""Kafka message producer implementation."""
from __future__ import annotations

import json
from typing import Dict, Optional

try:
    from kafka import KafkaProducer
    KAFKA_AVAILABLE = True
except ImportError:
    KAFKA_AVAILABLE = False

from ...domain.contracts.messaging import IMessagePublisher
from ...domain.models.common import EventMessage
from ...infrastructure.logging.structured_logger import LogManager


class KafkaMessagePublisher(IMessagePublisher):
    def __init__(self, bootstrap_servers: str) -> None:
        self._bootstrap_servers = bootstrap_servers
        self._producer: Optional[KafkaProducer] = None
        self._logger = LogManager().get_logger(__name__)
        self._initialize()

    def _initialize(self) -> None:
        if KAFKA_AVAILABLE:
            try:
                self._producer = KafkaProducer(
                    bootstrap_servers=self._bootstrap_servers,
                    value_serializer=lambda v: json.dumps(v).encode("utf-8"),
                )
                self._logger.info("Kafka producer initialized successfully")
            except Exception as e:
                self._logger.warning(f"Failed to connect to Kafka: {e}. Using in-memory mode.")
                self._producer = None
        else:
            self._logger.warning("Kafka library not available. Using in-memory mode.")
            self._producer = None

    async def publish(
        self,
        topic: str,
        message: EventMessage,
        headers: Optional[Dict[str, str]] = None,
    ) -> bool:
        try:
            message_dict = message.model_dump()
            message_dict["timestamp"] = message_dict["timestamp"].isoformat()
            message_dict["event_id"] = str(message_dict["event_id"])

            if self._producer is not None:
                future = self._producer.send(topic, message_dict)
                future.get(timeout=10)
            else:
                self._logger.debug(
                    f"Publishing message to topic '{topic}' (in-memory)",
                    event_type=message.event_type,
                    event_id=str(message.event_id),
                )
            return True
        except Exception as e:
            self._logger.error(
                f"Failed to publish message to topic '{topic}'",
                error=str(e),
                event_type=message.event_type,
            )
            return False

    async def publish_batch(
        self,
        topic: str,
        messages: list[EventMessage],
        headers: Optional[Dict[str, str]] = None,
    ) -> int:
        success_count = 0
        for message in messages:
            if await self.publish(topic, message, headers):
                success_count += 1
        return success_count

    async def close(self) -> None:
        if self._producer is not None:
            self._producer.flush()
            self._producer.close()
            self._logger.info("Kafka producer closed")
