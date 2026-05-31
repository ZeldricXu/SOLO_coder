"""Kafka message consumer implementation."""
from __future__ import annotations

import json
from datetime import datetime
from typing import Callable, Dict, Optional
from uuid import UUID

try:
    from kafka import KafkaConsumer
    KAFKA_AVAILABLE = True
except ImportError:
    KAFKA_AVAILABLE = False

from ...domain.contracts.messaging import IMessageConsumer
from ...domain.models.common import EventMessage
from ...infrastructure.logging.structured_logger import LogManager


class KafkaMessageConsumer(IMessageConsumer):
    def __init__(self, bootstrap_servers: str, group_id: Optional[str] = None) -> None:
        self._bootstrap_servers = bootstrap_servers
        self._group_id = group_id or "default-group"
        self._consumer: Optional[KafkaConsumer] = None
        self._handlers: Dict[str, Callable] = {}
        self._logger = LogManager().get_logger(__name__)
        self._running = False

    def _initialize(self) -> None:
        if KAFKA_AVAILABLE:
            try:
                self._consumer = KafkaConsumer(
                    bootstrap_servers=self._bootstrap_servers,
                    group_id=self._group_id,
                    value_deserializer=lambda m: json.loads(m.decode("utf-8")),
                    auto_offset_reset="earliest",
                    enable_auto_commit=False,
                )
                self._logger.info("Kafka consumer initialized successfully")
            except Exception as e:
                self._logger.warning(f"Failed to connect to Kafka: {e}")
                self._consumer = None
        else:
            self._logger.warning("Kafka library not available")
            self._consumer = None

    async def subscribe(
        self,
        topic: str,
        handler: Callable[[EventMessage], None],
        group_id: Optional[str] = None,
    ) -> None:
        if group_id:
            self._group_id = group_id
            self._initialize()

        self._handlers[topic] = handler

        if self._consumer is not None:
            self._consumer.subscribe([topic])
        self._logger.info(f"Subscribed to topic: {topic}")

    async def start(self) -> None:
        if self._running:
            return

        self._running = True
        self._logger.info("Kafka consumer started")

        if self._consumer is None:
            self._logger.warning("Kafka not available, running in simulation mode")
            return

        try:
            while self._running:
                messages = self._consumer.poll(timeout_ms=1000)
                for topic_partition, records in messages.items():
                    for record in records:
                        try:
                            message_dict = record.value
                            if isinstance(message_dict.get("event_id"), str):
                                message_dict["event_id"] = UUID(message_dict["event_id"])
                            if isinstance(message_dict.get("timestamp"), str):
                                message_dict["timestamp"] = datetime.fromisoformat(
                                    message_dict["timestamp"]
                                )
                            message = EventMessage.model_validate(message_dict)

                            if topic_partition.topic in self._handlers:
                                self._handlers[topic_partition.topic](message)
                        except Exception as e:
                            self._logger.error(f"Error processing message: {e}", record=record)
        except Exception as e:
            self._logger.error(f"Consumer error: {e}")
        finally:
            self._running = False

    async def stop(self) -> None:
        self._running = False
        if self._consumer is not None:
            self._consumer.close()
        self._logger.info("Kafka consumer stopped")

    async def poll(self, timeout_ms: int = 1000) -> Optional[EventMessage]:
        if self._consumer is None:
            return None

        messages = self._consumer.poll(timeout_ms=timeout_ms)
        for _, records in messages.items():
            for record in records:
                try:
                    message_dict = record.value
                    if isinstance(message_dict.get("event_id"), str):
                        message_dict["event_id"] = UUID(message_dict["event_id"])
                    if isinstance(message_dict.get("timestamp"), str):
                        message_dict["timestamp"] = datetime.fromisoformat(
                            message_dict["timestamp"]
                        )
                    return EventMessage.model_validate(message_dict)
                except Exception as e:
                    self._logger.error(f"Error polling message: {e}")
        return None

    async def commit(self) -> None:
        if self._consumer is not None:
            self._consumer.commit()
