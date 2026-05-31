import json
import logging
from typing import Any, Optional

from src.infrastructure.config.settings import KafkaConfig

logger = logging.getLogger(__name__)


class KafkaProducer:
    def __init__(self, config: KafkaConfig):
        self._config = config
        self._producer = None

    def _get_producer(self):
        if self._producer is None:
            try:
                from kafka import KafkaProducer as _KafkaProducer
                self._producer = _KafkaProducer(
                    bootstrap_servers=self._config.bootstrap_servers,
                    value_serializer=lambda v: json.dumps(v, ensure_ascii=False).encode("utf-8"),
                    key_serializer=lambda k: k.encode("utf-8") if k else None,
                    acks="all",
                    retries=3,
                    retry_backoff_ms=1000,
                    max_in_flight_requests_per_connection=5,
                    linger_ms=10,
                    batch_size=16384,
                )
            except Exception as e:
                logger.error(f"Failed to create Kafka producer: {e}")
                raise
        return self._producer

    def send(self, topic: str, value: Any, key: Optional[str] = None, headers: Optional[dict] = None) -> None:
        producer = self._get_producer()
        try:
            kwargs = {"value": value}
            if key is not None:
                kwargs["key"] = key
            if headers:
                kwargs["headers"] = [(k, str(v).encode()) for k, v in headers.items()]
            future = producer.send(topic, **kwargs)
            future.get(timeout=10)
        except Exception as e:
            logger.error(f"Failed to send message to topic '{topic}': {e}")
            raise

    def send_batch(self, topic: str, messages: list) -> None:
        producer = self._get_producer()
        try:
            for msg in messages:
                key = msg.get("key")
                value = msg.get("value")
                producer.send(topic, key=key, value=value)
            producer.flush(timeout=30)
        except Exception as e:
            logger.error(f"Failed to send batch to topic '{topic}': {e}")
            raise

    def flush(self, timeout: float = 30) -> None:
        if self._producer is not None:
            self._producer.flush(timeout=timeout)

    def close(self) -> None:
        if self._producer is not None:
            self._producer.flush()
            self._producer.close()
            self._producer = None
