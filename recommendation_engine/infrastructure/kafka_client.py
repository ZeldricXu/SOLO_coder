from typing import Optional, List, Dict, Any, Callable, Awaitable
import asyncio
import json
from datetime import datetime
from loguru import logger

try:
    from aiokafka import AIOKafkaProducer, AIOKafkaConsumer
    from aiokafka.admin import AIOKafkaAdminClient, NewTopic
    KAFKA_AVAILABLE = True
except ImportError:
    AIOKafkaProducer = None
    AIOKafkaConsumer = None
    AIOKafkaAdminClient = None
    NewTopic = None
    KAFKA_AVAILABLE = False

from config import settings


class KafkaProducerClient:
    _instance: Optional["KafkaProducerClient"] = None
    _producer: Optional[AIOKafkaProducer] = None

    def __new__(cls) -> "KafkaProducerClient":
        if cls._instance is None:
            cls._instance = super().__new__(cls)
        return cls._instance

    async def initialize(self) -> None:
        if self._producer is not None:
            return
        if not KAFKA_AVAILABLE:
            raise ImportError("aiokafka is not installed. Please install it with: pip install aiokafka")
        try:
            await self._create_topics_if_not_exists()

            producer_config = {
                "bootstrap_servers": settings.kafka_bootstrap_servers,
                "acks": settings.kafka_producer_acks,
                "batch_size": settings.kafka_batch_size,
                "linger_ms": settings.kafka_linger_ms,
                "compression_type": "snappy",
                "value_serializer": lambda v: json.dumps(
                    v, ensure_ascii=False, default=self._json_serializer
                ).encode("utf-8"),
                "key_serializer": lambda k: k.encode("utf-8") if k else b"",
            }
            self._producer = AIOKafkaProducer(**producer_config)
            await self._producer.start()
            logger.info(
                f"Kafka producer connected to {settings.kafka_bootstrap_servers}"
            )
        except Exception as e:
            logger.error(f"Failed to connect Kafka producer: {e}")
            raise

    async def _create_topics_if_not_exists(self) -> None:
        try:
            admin = AIOKafkaAdminClient(
                bootstrap_servers=settings.kafka_bootstrap_servers
            )
            await admin.start()
            try:
                existing_topics = await admin.list_topics()
                topics_to_create = []

                feedback_topic = settings.kafka_feedback_topic
                if feedback_topic not in existing_topics:
                    topics_to_create.append(
                        NewTopic(
                            name=feedback_topic,
                            num_partitions=settings.kafka_feedback_partitions,
                            replication_factor=1,
                        )
                    )

                if topics_to_create:
                    await admin.create_topics(topics_to_create)
                    logger.info(f"Created Kafka topics: {[t.name for t in topics_to_create]}")
            finally:
                await admin.close()
        except Exception as e:
            logger.warning(f"Kafka topic creation skipped: {e}")

    def _json_serializer(self, obj: Any) -> Any:
        if isinstance(obj, datetime):
            return obj.isoformat()
        if hasattr(obj, "model_dump"):
            return obj.model_dump()
        if hasattr(obj, "__dict__"):
            return obj.__dict__
        return str(obj)

    async def close(self) -> None:
        if self._producer is not None:
            await self._producer.stop()
            self._producer = None
        logger.info("Kafka producer closed")

    def _get_producer(self) -> AIOKafkaProducer:
        if self._producer is None:
            raise RuntimeError("Kafka producer not initialized")
        return self._producer

    async def send(
        self,
        topic: str,
        value: Any,
        key: Optional[str] = None,
        partition: Optional[int] = None,
    ) -> Optional[int]:
        producer = self._get_producer()
        try:
            result = await producer.send_and_wait(
                topic=topic, value=value, key=key, partition=partition
            )
            return result.partition
        except Exception as e:
            logger.error(f"Failed to send Kafka message to {topic}: {e}")
            return None

    async def send_batch(
        self, topic: str, messages: List[Dict[str, Any]], key_field: Optional[str] = None
    ) -> int:
        success_count = 0
        for msg in messages:
            key = msg.get(key_field) if key_field else None
            if await self.send(topic, msg, key=key) is not None:
                success_count += 1
        return success_count

    async def health_check(self) -> bool:
        try:
            producer = self._get_producer()
            await producer.send_and_wait(
                topic=settings.kafka_feedback_topic,
                value={"type": "health_check", "timestamp": datetime.utcnow().isoformat()},
            )
            return True
        except Exception:
            return False


class KafkaConsumerClient:
    def __init__(
        self,
        topics: List[str],
        group_id: Optional[str] = None,
        auto_offset_reset: str = "latest",
    ):
        self._topics = topics
        self._group_id = group_id or settings.kafka_consumer_group_id
        self._auto_offset_reset = auto_offset_reset
        self._consumer: Optional[AIOKafkaConsumer] = None
        self._running = False

    async def initialize(self) -> None:
        if self._consumer is not None:
            return
        if not KAFKA_AVAILABLE:
            raise ImportError("aiokafka is not installed. Please install it with: pip install aiokafka")
        try:
            consumer_config = {
                "bootstrap_servers": settings.kafka_bootstrap_servers,
                "group_id": self._group_id,
                "auto_offset_reset": self._auto_offset_reset,
                "enable_auto_commit": False,
                "value_deserializer": self._deserialize_json,
                "key_deserializer": lambda k: k.decode("utf-8") if k else None,
                "max_poll_records": 500,
                "session_timeout_ms": 30000,
                "heartbeat_interval_ms": 10000,
            }
            self._consumer = AIOKafkaConsumer(*self._topics, **consumer_config)
            await self._consumer.start()
            logger.info(
                f"Kafka consumer started for topics {self._topics}, group {self._group_id}"
            )
        except Exception as e:
            logger.error(f"Failed to start Kafka consumer: {e}")
            raise

    def _deserialize_json(self, data: bytes) -> Any:
        try:
            return json.loads(data.decode("utf-8"))
        except (json.JSONDecodeError, UnicodeDecodeError):
            return None

    async def close(self) -> None:
        self._running = False
        if self._consumer is not None:
            await self._consumer.stop()
            self._consumer = None
        logger.info("Kafka consumer closed")

    async def consume(
        self,
        handler: Callable[[Any, Any, int, int], Awaitable[None]],
        batch_size: int = 100,
        batch_timeout_ms: int = 1000,
    ) -> None:
        if self._consumer is None:
            raise RuntimeError("Kafka consumer not initialized")

        self._running = True
        while self._running:
            try:
                batch = await self._consumer.getmany(
                    timeout_ms=batch_timeout_ms, max_records=batch_size
                )
                if not batch:
                    await asyncio.sleep(0.1)
                    continue

                tasks = []
                for tp, messages in batch.items():
                    for msg in messages:
                        if msg.value is not None:
                            task = handler(
                                msg.key, msg.value, tp.partition, msg.offset
                            )
                            tasks.append(task)

                if tasks:
                    await asyncio.gather(*tasks, return_exceptions=True)

                await self._consumer.commit()

            except asyncio.CancelledError:
                logger.info("Kafka consumer cancelled")
                break
            except Exception as e:
                logger.error(f"Kafka consumer error: {e}")
                await asyncio.sleep(1)

    async def commit(self) -> None:
        if self._consumer is not None:
            await self._consumer.commit()


_kafka_producer: Optional[KafkaProducerClient] = None


async def get_kafka_producer() -> KafkaProducerClient:
    global _kafka_producer
    if _kafka_producer is None:
        _kafka_producer = KafkaProducerClient()
        await _kafka_producer.initialize()
    return _kafka_producer


async def close_kafka_producer() -> None:
    global _kafka_producer
    if _kafka_producer is not None:
        await _kafka_producer.close()
        _kafka_producer = None
