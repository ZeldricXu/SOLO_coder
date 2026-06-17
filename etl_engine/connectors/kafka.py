import asyncio
import json
import logging

import pandas as pd
from confluent_kafka import Consumer, KafkaError, KafkaException

from .base import BaseSource, register_source

logger = logging.getLogger(__name__)


@register_source("kafka")
class KafkaSource(BaseSource):
    def __init__(self, config: dict) -> None:
        super().__init__(config)
        self._consumer: Consumer | None = None

    def _get_consumer_config(self) -> dict:
        params = self.config.get("connection_params", {})
        return {
            "bootstrap.servers": params.get("bootstrap_servers", "localhost:9092"),
            "group.id": params.get("group_id", "etl_engine_consumer"),
            "auto.offset.reset": params.get("auto_offset_reset", "earliest"),
            "enable.auto.commit": False,
            "session.timeout.ms": params.get("session_timeout_ms", 30000),
        }

    async def connect(self) -> None:
        config = self._get_consumer_config()
        for attempt in range(3):
            try:
                self._consumer = Consumer(config)
                self._connected = True
                logger.info("Kafka consumer created successfully")
                return
            except KafkaException as e:
                wait_time = 2 ** attempt
                logger.warning(
                    "Kafka connect attempt %d/3 failed: %s. Retrying in %ds...",
                    attempt + 1, e, wait_time,
                )
                await asyncio.sleep(wait_time)
        raise ConnectionError("Failed to connect to Kafka after 3 attempts")

    async def disconnect(self) -> None:
        if self._consumer is not None:
            self._consumer.close()
            self._consumer = None
        self._connected = False
        logger.info("Kafka consumer closed")

    async def read(self, query: str | None = None, **kwargs) -> pd.DataFrame:
        if not query:
            raise ValueError("Topic name is required for KafkaSource.read()")
        if not self.is_connected:
            await self._reconnect()

        max_messages = kwargs.get("max_messages", 100)
        timeout = kwargs.get("timeout", 10.0)
        deserializer = kwargs.get("deserializer", "json")

        self._consumer.subscribe([query])
        messages: list[dict] = []
        remaining = max_messages

        try:
            while remaining > 0:
                msg = self._consumer.poll(timeout=timeout)
                if msg is None:
                    break
                if msg.error():
                    if msg.error().code() == KafkaError._PARTITION_EOF:
                        break
                    logger.error("Kafka consumer error: %s", msg.error())
                    continue

                value = msg.value()
                if deserializer == "json":
                    try:
                        record = json.loads(value.decode("utf-8"))
                    except (json.JSONDecodeError, UnicodeDecodeError) as e:
                        logger.warning("Failed to deserialize message: %s", e)
                        record = {"raw_value": value.decode("utf-8", errors="replace")}
                else:
                    record = {"raw_value": value.decode("utf-8", errors="replace")}

                record["_topic"] = msg.topic()
                record["_partition"] = msg.partition()
                record["_offset"] = msg.offset()
                messages.append(record)
                remaining -= 1

            self._consumer.commit(asynchronous=False)
            df = pd.DataFrame(messages)
            logger.info("Kafka read %d messages from topic '%s'", len(df), query)
            return df
        except KafkaException as e:
            logger.error("Kafka read failed: %s", e)
            self._connected = False
            raise

    async def test_connection(self) -> bool:
        try:
            metadata = self._consumer.list_topics(timeout=10)
            return True
        except Exception as e:
            logger.error("Kafka connection test failed: %s", e)
            return False

    async def _reconnect(self) -> None:
        logger.info("Attempting Kafka reconnection...")
        for attempt in range(3):
            try:
                await self.disconnect()
                await self.connect()
                return
            except Exception as e:
                wait_time = 2 ** attempt
                logger.warning(
                    "Reconnect attempt %d/3 failed: %s. Retrying in %ds...",
                    attempt + 1, e, wait_time,
                )
                await asyncio.sleep(wait_time)
        raise ConnectionError("Failed to reconnect to Kafka after 3 attempts")
