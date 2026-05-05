from typing import Dict, Any, Optional, List, Set, Tuple
from datetime import datetime
import asyncio
import json
import logging

from kafka import KafkaConsumer, KafkaProducer, TopicPartition
from kafka.errors import KafkaError, NoBrokersAvailable, CommitFailedError

from app.connectors.base import BaseConnector
from app.connectors.message_tracker import message_tracker, TrackedMessage
from app.core.config import settings
from app.core.models import DataSourceConfig, DataSourceType, KafkaOffsetRecord, RawDataEvent

logger = logging.getLogger(__name__)


class KafkaConnector(BaseConnector):
    def __init__(self, config: DataSourceConfig):
        super().__init__(config)
        self.source_type = DataSourceType.KAFKA

        self._enable_auto_commit = config.config.get(
            'enable_auto_commit',
            not settings.KAFKA_ENABLE_AUTO_COMMIT
        )

        self._consumer_config = {
            'bootstrap_servers': config.config.get(
                'bootstrap_servers',
                'localhost:9092'
            ),
            'group_id': config.config.get(
                'group_id',
                'dataflow-consumer'
            ),
            'auto_offset_reset': config.config.get(
                'auto_offset_reset',
                'latest'
            ),
            'enable_auto_commit': self._enable_auto_commit,
            'value_deserializer': lambda m: json.loads(
                m.decode('utf-8') if m else '{}'
            ),
            'key_deserializer': lambda m: m.decode('utf-8') if m else None,
        }

        self._topics: List[str] = config.config.get('topics', [])
        self._consumer: Optional[KafkaConsumer] = None
        self._producer: Optional[KafkaProducer] = None
        self._consume_task: Optional[asyncio.Task] = None

        self._pending_offsets: Dict[TopicPartition, int] = {}
        self._offset_lock = asyncio.Lock()
        self._commit_interval = config.config.get(
            'manual_commit_interval',
            settings.KAFKA_MANUAL_COMMIT_INTERVAL
        ) / 1000.0
        self._last_commit_time: datetime = datetime.utcnow()

        self._enable_offset_persistence = config.config.get(
            'enable_offset_persistence',
            settings.KAFKA_ENABLE_OFFSET_PERSISTENCE
        )
        self._offset_history: List[KafkaOffsetRecord] = []
        self._max_offset_history = 1000

        self._message_tracker = message_tracker
        self._use_message_tracking = True

    async def connect(self) -> bool:
        try:
            loop = asyncio.get_running_loop()

            self._consumer = await loop.run_in_executor(
                None,
                lambda: KafkaConsumer(
                    *self._topics,
                    **{k: v for k, v in self._consumer_config.items()
                       if k not in ['topics']}
                )
            )

            self._producer = await loop.run_in_executor(
                None,
                lambda: KafkaProducer(
                    bootstrap_servers=self._consumer_config['bootstrap_servers'],
                    value_serializer=lambda v: json.dumps(v).encode('utf-8'),
                    key_serializer=lambda k: k.encode('utf-8') if k else None
                )
            )

            self.is_connected = True
            self._reconnect_attempts = 0
            logger.info(
                f"Connected to Kafka: {self.source_id}, "
                f"topics: {self._topics}, "
                f"auto_commit: {self._enable_auto_commit}"
            )
            return True

        except NoBrokersAvailable as e:
            logger.error(f"No Kafka brokers available for {self.source_id}: {e}")
            self.is_connected = False
            return await self._reconnect()
        except Exception as e:
            logger.error(f"Failed to connect to Kafka {self.source_id}: {e}")
            self.is_connected = False
            return await self._reconnect()

    async def disconnect(self):
        await self.stop_listening()

        if self._consumer:
            try:
                loop = asyncio.get_running_loop()
                await loop.run_in_executor(None, self._consumer.close)
            except Exception as e:
                logger.warning(f"Error closing Kafka consumer: {e}")
            self._consumer = None

        if self._producer:
            try:
                loop = asyncio.get_running_loop()
                await loop.run_in_executor(None, self._producer.flush)
                await loop.run_in_executor(None, self._producer.close)
            except Exception as e:
                logger.warning(f"Error closing Kafka producer: {e}")
            self._producer = None

        self.is_connected = False
        logger.info(f"Disconnected from Kafka: {self.source_id}")

    async def start_listening(self):
        if not self.is_connected:
            await self.connect()

        self.is_running = True
        self._consume_task = asyncio.create_task(self._consume_loop())
        logger.info(f"Started Kafka consumer for: {self.source_id}")

    async def stop_listening(self):
        self.is_running = False

        if not self._enable_auto_commit and self._use_message_tracking:
            await self._commit_tracked_offsets()
        elif not self._enable_auto_commit:
            await self._commit_pending_offsets()

        if self._consume_task and not self._consume_task.done():
            self._consume_task.cancel()
            try:
                await self._consume_task
            except asyncio.CancelledError:
                pass

        logger.info(f"Stopped Kafka consumer for: {self.source_id}")

    async def _consume_loop(self):
        loop = asyncio.get_running_loop()

        while self.is_running:
            try:
                if not self._consumer:
                    await asyncio.sleep(1)
                    continue

                records = await loop.run_in_executor(
                    None,
                    lambda: self._consumer.poll(timeout_ms=1000)
                )

                if records:
                    for topic_partition, consumer_records in records.items():
                        if not consumer_records:
                            continue

                        for record in consumer_records:
                            try:
                                message_value = record.value
                                if isinstance(message_value, str):
                                    message_value = json.loads(message_value)

                                event_type = "insert"
                                if isinstance(message_value, dict):
                                    event_type = message_value.get('event_type', 'insert')
                                    data = message_value.get('data', message_value)
                                else:
                                    data = message_value

                                if self._use_message_tracking:
                                    tracked = await self._message_tracker.register_message(
                                        topic=topic_partition.topic,
                                        partition=topic_partition.partition,
                                        offset=record.offset
                                    )

                                    event = RawDataEvent(
                                        source=self.source_id,
                                        data=data,
                                        timestamp=datetime.utcnow(),
                                        event_type=event_type,
                                        message_id=tracked.message_id,
                                        kafka_topic=topic_partition.topic,
                                        kafka_partition=topic_partition.partition,
                                        kafka_offset=record.offset
                                    )
                                else:
                                    event = RawDataEvent(
                                        source=self.source_id,
                                        data=data,
                                        timestamp=datetime.utcnow(),
                                        event_type=event_type
                                    )

                                self._emit_raw_event(event)

                            except json.JSONDecodeError as e:
                                logger.warning(
                                    f"Failed to decode Kafka message: {e}, "
                                    f"raw message: {record.value}"
                                )
                            except Exception as e:
                                logger.error(f"Error processing Kafka message: {e}")

                        if not self._enable_auto_commit and not self._use_message_tracking:
                            max_offset = max(r.offset for r in consumer_records)
                            async with self._offset_lock:
                                self._pending_offsets[topic_partition] = max_offset + 1

                                if self._enable_offset_persistence:
                                    offset_record = KafkaOffsetRecord(
                                        topic=topic_partition.topic,
                                        partition=topic_partition.partition,
                                        offset=max_offset + 1,
                                        group_id=self._consumer_config['group_id']
                                    )
                                    self._offset_history.append(offset_record)
                                    if len(self._offset_history) > self._max_offset_history:
                                        self._offset_history = self._offset_history[-self._max_offset_history:]

                if self._use_message_tracking:
                    await self._check_and_commit_tracked_offsets()
                elif not self._enable_auto_commit:
                    await self._check_and_commit_offsets()

            except asyncio.CancelledError:
                if self._use_message_tracking:
                    await self._commit_tracked_offsets()
                else:
                    await self._commit_pending_offsets()
                break
            except KafkaError as e:
                logger.error(f"Kafka error in consume loop: {e}")
                if not self.is_connected:
                    await self._reconnect()
            except Exception as e:
                logger.error(f"Unexpected error in Kafka consume loop: {e}")
                await asyncio.sleep(1)

    def _emit_raw_event(self, event: RawDataEvent):
        if self._on_data_callback:
            try:
                self._on_data_callback(event)
            except Exception as e:
                logger.error(f"Error emitting data from {self.source_id}: {e}")
                if event.has_kafka_metadata() and event.message_id:
                    import asyncio
                    loop = asyncio.get_event_loop()
                    if loop.is_running():
                        asyncio.create_task(
                            self._message_tracker.acknowledge_failure(
                                event.message_id,
                                str(e)
                            )
                        )

    async def acknowledge_message_success(self, message_id: str) -> bool:
        if not self._use_message_tracking:
            return False

        success = await self._message_tracker.acknowledge_success(message_id)
        if success:
            logger.debug(f"Message {message_id} acknowledged successfully")
        return success

    async def acknowledge_message_failure(self, message_id: str, error: str) -> bool:
        if not self._use_message_tracking:
            return False

        success = await self._message_tracker.acknowledge_failure(message_id, error)
        if success:
            logger.warning(f"Message {message_id} acknowledged as failed: {error}")
        return success

    async def _check_and_commit_tracked_offsets(self):
        now = datetime.utcnow()
        elapsed = (now - self._last_commit_time).total_seconds()

        if elapsed >= self._commit_interval:
            await self._commit_tracked_offsets()

    async def _commit_tracked_offsets(self):
        if not self._consumer:
            return

        commit_offsets = await self._message_tracker.get_commit_offsets()

        if not commit_offsets:
            return

        async with self._offset_lock:
            try:
                loop = asyncio.get_running_loop()

                offsets_to_commit = {
                    TopicPartition(topic, partition): offset
                    for (topic, partition), offset in commit_offsets.items()
                }

                if offsets_to_commit:
                    await loop.run_in_executor(
                        None,
                        lambda: self._consumer.commit(offsets=offsets_to_commit)
                    )

                    for (topic, partition), offset in commit_offsets.items():
                        await self._message_tracker.remove_committed_offsets(
                            topic=topic,
                            partition=partition,
                            committed_offset=offset
                        )

                        if self._enable_offset_persistence:
                            offset_record = KafkaOffsetRecord(
                                topic=topic,
                                partition=partition,
                                offset=offset,
                                group_id=self._consumer_config['group_id'],
                                committed=True
                            )
                            self._offset_history.append(offset_record)
                            if len(self._offset_history) > self._max_offset_history:
                                self._offset_history = self._offset_history[-self._max_offset_history:]

                    logger.debug(
                        f"Committed {len(offsets_to_commit)} tracked offsets "
                        f"for {self.source_id}"
                    )

                    self._last_commit_time = datetime.utcnow()

            except CommitFailedError as e:
                logger.error(f"Commit failed for {self.source_id}: {e}")
            except Exception as e:
                logger.error(f"Error committing tracked offsets: {e}")

    async def _check_and_commit_offsets(self):
        now = datetime.utcnow()
        elapsed = (now - self._last_commit_time).total_seconds()

        if elapsed >= self._commit_interval:
            await self._commit_pending_offsets()

    async def _commit_pending_offsets(self):
        if not self._consumer or not self._pending_offsets:
            return

        async with self._offset_lock:
            try:
                loop = asyncio.get_running_loop()

                offsets_to_commit = {
                    tp: offset
                    for tp, offset in self._pending_offsets.items()
                }

                if offsets_to_commit:
                    await loop.run_in_executor(
                        None,
                        lambda: self._consumer.commit(offsets=offsets_to_commit)
                    )

                    logger.debug(
                        f"Committed {len(offsets_to_commit)} offsets "
                        f"for {self.source_id}"
                    )

                    self._pending_offsets.clear()
                    self._last_commit_time = datetime.utcnow()

            except CommitFailedError as e:
                logger.error(f"Commit failed for {self.source_id}: {e}")
            except Exception as e:
                logger.error(f"Error committing offsets: {e}")

    async def commit_offsets_sync(self):
        if self._use_message_tracking:
            await self._commit_tracked_offsets()
        else:
            await self._commit_pending_offsets()

    async def send_message(self, topic: str, message: Dict[str, Any], key: str = None):
        if not self._producer:
            logger.error("Kafka producer not initialized")
            return

        try:
            loop = asyncio.get_running_loop()
            future = self._producer.send(topic, value=message, key=key)
            await loop.run_in_executor(None, lambda: future.get(timeout=10))
            logger.debug(f"Sent message to Kafka topic {topic}")
        except Exception as e:
            logger.error(f"Failed to send message to Kafka: {e}")

    def subscribe_topics(self, topics: List[str]):
        if self._consumer:
            self._consumer.subscribe(topics)
            self._topics = topics
            logger.info(f"Subscribed to topics: {topics}")

    def get_status(self) -> Dict[str, Any]:
        base_status = super().get_status()

        offset_info = {}
        for tp, offset in self._pending_offsets.items():
            offset_info[f"{tp.topic}:{tp.partition}"] = offset

        tracker_stats = self._message_tracker.get_stats() if self._use_message_tracking else {}

        base_status.update({
            "topics": self._topics,
            "enable_auto_commit": self._enable_auto_commit,
            "use_message_tracking": self._use_message_tracking,
            "pending_offsets": offset_info,
            "pending_offsets_count": len(self._pending_offsets),
            "offset_history_count": len(self._offset_history),
            "last_commit_time": self._last_commit_time.isoformat() + "Z" if self._last_commit_time else None,
            "commit_interval_seconds": self._commit_interval,
            "tracker_stats": tracker_stats
        })

        return base_status

    def get_latest_offset(self, topic: str, partition: int) -> Optional[int]:
        tp = TopicPartition(topic, partition)
        return self._pending_offsets.get(tp)
