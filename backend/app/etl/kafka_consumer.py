import json
import logging
from datetime import datetime
from typing import Dict, List, Optional
from confluent_kafka import Consumer, Producer, KafkaError

from app.config import settings

logger = logging.getLogger(__name__)


class KafkaConsumerManager:
    def __init__(self):
        self.consumer = None
        self.producer = None
        self._running = False

    def _get_consumer_config(self):
        return {
            'bootstrap.servers': settings.KAFKA_BOOTSTRAP_SERVERS,
            'group.id': settings.KAFKA_GROUP_ID,
            'auto.offset.reset': 'earliest',
            'enable.auto.commit': True,
            'auto.commit.interval.ms': 5000,
            'fetch.min.bytes': 1024 * 1024,
            'fetch.max.wait.ms': 500,
        }

    def _get_producer_config(self):
        return {
            'bootstrap.servers': settings.KAFKA_BOOTSTRAP_SERVERS,
            'acks': 'all',
            'retries': 3,
            'linger.ms': 5,
            'batch.size': 16384,
        }

    def start_consumer(self, topics: List[str] = None):
        if topics is None:
            topics = [settings.KAFKA_TRAFFIC_TOPIC]

        try:
            self.consumer = Consumer(self._get_consumer_config())
            self.consumer.subscribe(topics)
            self._running = True
            logger.info(f"Kafka consumer started, subscribed to topics: {topics}")
        except Exception as e:
            logger.error(f"Failed to start Kafka consumer: {e}")
            raise

    def consume_messages(self, callback, batch_size: int = 100, timeout: float = 1.0):
        if not self.consumer:
            raise RuntimeError("Consumer not started")

        messages = []
        try:
            while self._running:
                msg = self.consumer.poll(timeout)
                if msg is None:
                    if messages:
                        callback(messages)
                        messages = []
                    continue

                if msg.error():
                    if msg.error().code() == KafkaError._PARTITION_EOF:
                        logger.info(f"Reached end of partition: {msg.partition()}")
                    else:
                        logger.error(f"Kafka consumer error: {msg.error()}")
                    continue

                try:
                    value = json.loads(msg.value().decode('utf-8'))
                    messages.append(value)

                    if len(messages) >= batch_size:
                        callback(messages)
                        messages = []
                except json.JSONDecodeError as e:
                    logger.error(f"Failed to decode message: {e}")
                    continue

        except Exception as e:
            logger.error(f"Error in consume loop: {e}")
            raise

    def stop_consumer(self):
        self._running = False
        if self.consumer:
            self.consumer.close()
            logger.info("Kafka consumer stopped")

    def start_producer(self):
        try:
            self.producer = Producer(self._get_producer_config())
            logger.info("Kafka producer started")
        except Exception as e:
            logger.error(f"Failed to start Kafka producer: {e}")
            raise

    def produce_message(self, topic: str, value: dict, key: str = None):
        if not self.producer:
            self.start_producer()

        try:
            value_str = json.dumps(value, ensure_ascii=False).encode('utf-8')
            self.producer.produce(
                topic=topic,
                key=key,
                value=value_str,
                callback=self._delivery_report
            )
            self.producer.poll(0)
        except Exception as e:
            logger.error(f"Failed to produce message: {e}")
            raise

    def produce_batch(self, topic: str, messages: List[dict]):
        if not self.producer:
            self.start_producer()

        for msg in messages:
            value_str = json.dumps(msg, ensure_ascii=False).encode('utf-8')
            self.producer.produce(topic=topic, value=value_str)

        self.producer.flush()

    def _delivery_report(self, err, msg):
        if err:
            logger.error(f"Message delivery failed: {err}")
        else:
            logger.debug(f"Message delivered to {msg.topic()} [{msg.partition()}]")

    def flush_producer(self):
        if self.producer:
            self.producer.flush()

    def stop_producer(self):
        if self.producer:
            self.producer.flush()
            logger.info("Kafka producer stopped")


kafka_manager = KafkaConsumerManager()
