import json
import logging
from typing import Any, Callable, Dict, Iterator, Optional

from src.infrastructure.config.settings import KafkaConfig

logger = logging.getLogger(__name__)


class KafkaConsumer:
    def __init__(self, config: KafkaConfig, topics: Optional[list] = None):
        self._config = config
        self._topics = topics or []
        self._consumer = None

    def _get_consumer(self):
        if self._consumer is None:
            try:
                from kafka import KafkaConsumer as _KafkaConsumer
                self._consumer = _KafkaConsumer(
                    *self._topics,
                    bootstrap_servers=self._config.bootstrap_servers,
                    group_id=self._config.group_id,
                    auto_offset_reset=self._config.auto_offset_reset,
                    enable_auto_commit=self._config.enable_auto_commit,
                    value_deserializer=lambda m: json.loads(m.decode("utf-8")),
                    key_deserializer=lambda m: m.decode("utf-8") if m else None,
                    session_timeout_ms=30000,
                    heartbeat_interval_ms=10000,
                    max_poll_records=500,
                    max_poll_interval_ms=300000,
                )
            except Exception as e:
                logger.error(f"Failed to create Kafka consumer: {e}")
                raise
        return self._consumer

    def subscribe(self, topics: list) -> None:
        self._topics = topics
        consumer = self._get_consumer()
        consumer.subscribe(topics)

    def consume(self, timeout_ms: int = 1000, max_records: int = 100) -> list:
        consumer = self._get_consumer()
        messages = []
        try:
            records = consumer.poll(timeout_ms=timeout_ms, max_records=max_records)
            for tp, msgs in records.items():
                for msg in msgs:
                    messages.append({
                        "topic": tp.topic,
                        "partition": tp.partition,
                        "offset": msg.offset,
                        "key": msg.key,
                        "value": msg.value,
                        "timestamp": msg.timestamp,
                    })
        except Exception as e:
            logger.error(f"Failed to consume messages: {e}")
        return messages

    def consume_iter(self, timeout_ms: int = 1000) -> Iterator[Dict[str, Any]]:
        consumer = self._get_consumer()
        try:
            for msg in consumer:
                yield {
                    "topic": msg.topic,
                    "partition": msg.partition,
                    "offset": msg.offset,
                    "key": msg.key,
                    "value": msg.value,
                    "timestamp": msg.timestamp,
                }
        except Exception as e:
            logger.error(f"Consumer iteration error: {e}")

    def commit(self, offsets: Optional[Dict] = None) -> None:
        consumer = self._get_consumer()
        try:
            if offsets:
                consumer.commit(offsets=offsets)
            else:
                consumer.commit()
        except Exception as e:
            logger.error(f"Failed to commit offsets: {e}")

    def seek(self, topic: str, partition: int, offset: int) -> None:
        consumer = self._get_consumer()
        from kafka.structs import TopicPartition
        tp = TopicPartition(topic, partition)
        consumer.seek(tp, offset)

    def close(self) -> None:
        if self._consumer is not None:
            self._consumer.close()
            self._consumer = None
