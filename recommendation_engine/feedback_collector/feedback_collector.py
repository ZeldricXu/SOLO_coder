from typing import Optional, List, Dict, Any
import asyncio
import json
import uuid
from datetime import datetime
from loguru import logger

from config import settings
from recommendation_engine.models.schemas import FeedbackEvent
from recommendation_engine.infrastructure.kafka_client import (
    get_kafka_producer,
    KafkaProducerClient,
    KafkaConsumerClient,
    close_kafka_producer,
)
from recommendation_engine.feedback_collector.iceberg_writer import IcebergWriter


class FeedbackCollector:
    def __init__(self):
        self._max_queue_size = settings.feedback_collector_max_queue_size
        self._worker_count = settings.feedback_collector_worker_count
        self._batch_size = settings.feedback_collector_batch_size
        self._topic = settings.kafka_feedback_topic

        self._event_queue: asyncio.Queue[FeedbackEvent] = asyncio.Queue(
            maxsize=self._max_queue_size
        )
        self._producer: Optional[KafkaProducerClient] = None
        self._iceberg_writer = IcebergWriter()
        self._consumer: Optional[KafkaConsumerClient] = None

        self._producer_tasks: List[asyncio.Task] = []
        self._consumer_task: Optional[asyncio.Task] = None
        self._running = False

        self._stats = {
            "events_received": 0,
            "events_sent_to_kafka": 0,
            "events_written_to_iceberg": 0,
            "events_dropped": 0,
            "kafka_send_failures": 0,
            "iceberg_write_failures": 0,
        }

    async def initialize(self) -> None:
        if self._running:
            return

        try:
            self._producer = await get_kafka_producer()
            self._running = True

            for i in range(self._worker_count):
                task = asyncio.create_task(self._producer_worker(i))
                self._producer_tasks.append(task)

            self._consumer_task = asyncio.create_task(self._consumer_worker())

            logger.info(
                f"FeedbackCollector initialized with {self._worker_count} producer workers"
            )
        except Exception as e:
            logger.error(f"Failed to initialize FeedbackCollector: {e}")
            self._running = False
            raise

    async def collect(self, event: FeedbackEvent) -> bool:
        if not self._running:
            logger.warning("FeedbackCollector not running, event dropped")
            self._stats["events_dropped"] += 1
            return False

        try:
            self._event_queue.put_nowait(event)
            self._stats["events_received"] += 1
            logger.debug(f"Event {event.event_id} queued")
            return True
        except asyncio.QueueFull:
            logger.warning(f"Event queue full, dropping event {event.event_id}")
            self._stats["events_dropped"] += 1
            return False

    async def collect_batch(self, events: List[FeedbackEvent]) -> int:
        success_count = 0
        for event in events:
            if await self.collect(event):
                success_count += 1
        return success_count

    async def collect_raw(self, event_data: Dict[str, Any]) -> bool:
        try:
            if "event_id" not in event_data:
                event_data["event_id"] = str(uuid.uuid4())
            if "timestamp" not in event_data:
                event_data["timestamp"] = datetime.utcnow()

            event = FeedbackEvent(**event_data)
            return await self.collect(event)
        except Exception as e:
            logger.error(f"Invalid event data: {e}")
            self._stats["events_dropped"] += 1
            return False

    async def _producer_worker(self, worker_id: int) -> None:
        logger.info(f"Producer worker {worker_id} started")
        batch: List[FeedbackEvent] = []
        last_flush = asyncio.get_event_loop().time()

        while self._running:
            try:
                event = await asyncio.wait_for(self._event_queue.get(), timeout=0.5)
                batch.append(event)
                self._event_queue.task_done()

                if len(batch) >= self._batch_size or (
                    asyncio.get_event_loop().time() - last_flush > 1.0 and batch
                ):
                    await self._flush_batch(batch, worker_id)
                    batch = []
                    last_flush = asyncio.get_event_loop().time()

            except asyncio.TimeoutError:
                if batch:
                    await self._flush_batch(batch, worker_id)
                    batch = []
                    last_flush = asyncio.get_event_loop().time()
            except asyncio.CancelledError:
                logger.info(f"Producer worker {worker_id} cancelled")
                break
            except Exception as e:
                logger.error(f"Producer worker {worker_id} error: {e}")
                await asyncio.sleep(0.1)

        if batch:
            await self._flush_batch(batch, worker_id)
        logger.info(f"Producer worker {worker_id} stopped")

    async def _flush_batch(self, batch: List[FeedbackEvent], worker_id: int) -> None:
        if not self._producer or not batch:
            return

        try:
            messages = []
            for event in batch:
                event_dict = event.model_dump()
                if isinstance(event_dict["timestamp"], datetime):
                    event_dict["timestamp"] = event_dict["timestamp"].isoformat()
                messages.append(event_dict)

            success_count = await self._producer.send_batch(
                self._topic, messages, key_field="user_id"
            )

            self._stats["events_sent_to_kafka"] += success_count
            if success_count < len(batch):
                self._stats["kafka_send_failures"] += len(batch) - success_count
                logger.warning(
                    f"Worker {worker_id}: {len(batch) - success_count}/{len(batch)} messages failed"
                )

            logger.debug(
                f"Worker {worker_id}: Flushed {success_count}/{len(batch)} events to Kafka"
            )
        except Exception as e:
            logger.error(f"Worker {worker_id}: Failed to flush batch: {e}")
            self._stats["kafka_send_failures"] += len(batch)

    async def _consumer_worker(self) -> None:
        logger.info("Consumer worker started")
        self._consumer = KafkaConsumerClient(
            topics=[self._topic],
            group_id=f"{settings.kafka_consumer_group_id}-iceberg",
            auto_offset_reset="earliest",
        )

        try:
            await self._consumer.initialize()
            await self._consumer.consume(
                handler=self._handle_kafka_message,
                batch_size=self._batch_size * 2,
                batch_timeout_ms=5000,
            )
        except Exception as e:
            logger.error(f"Consumer worker error: {e}")
        finally:
            if self._consumer:
                await self._consumer.close()
            logger.info("Consumer worker stopped")

    async def _handle_kafka_message(
        self, key: Any, value: Any, partition: int, offset: int
    ) -> None:
        if not value:
            return

        try:
            if isinstance(value, str):
                event = json.loads(value)
            else:
                event = value

            if "timestamp" in event and isinstance(event["timestamp"], str):
                try:
                    event["timestamp"] = datetime.fromisoformat(event["timestamp"])
                except (ValueError, TypeError):
                    pass

            success = self._iceberg_writer.write_events([event])
            if success:
                self._stats["events_written_to_iceberg"] += 1
            else:
                self._stats["iceberg_write_failures"] += 1

        except Exception as e:
            logger.error(f"Failed to handle Kafka message: {e}")
            self._stats["iceberg_write_failures"] += 1

    async def load_fallback_data(self, date_str: Optional[str] = None) -> int:
        count = self._iceberg_writer.load_fallback_to_iceberg(date_str)
        self._stats["events_written_to_iceberg"] += count
        return count

    def get_stats(self) -> Dict[str, Any]:
        stats = self._stats.copy()
        stats["queue_size"] = self._event_queue.qsize()
        stats["running"] = self._running
        stats["worker_count"] = len(
            [t for t in self._producer_tasks if not t.done()]
        )
        stats["iceberg"] = self._iceberg_writer.get_stats()
        return stats

    async def wait_until_empty(self, timeout: float = 30.0) -> bool:
        try:
            await asyncio.wait_for(self._event_queue.join(), timeout=timeout)
            return True
        except asyncio.TimeoutError:
            logger.warning("Timeout waiting for queue to empty")
            return False

    async def close(self) -> None:
        if not self._running:
            return

        logger.info("Closing FeedbackCollector...")
        self._running = False

        for task in self._producer_tasks:
            if not task.done():
                task.cancel()
                try:
                    await task
                except asyncio.CancelledError:
                    pass

        if self._consumer_task and not self._consumer_task.done():
            self._consumer_task.cancel()
            try:
                await self._consumer_task
            except asyncio.CancelledError:
                pass

        if self._consumer:
            await self._consumer.close()

        await close_kafka_producer()
        self._iceberg_writer.close()

        logger.info("FeedbackCollector closed")
        logger.info(f"Final stats: {json.dumps(self._stats, ensure_ascii=False)}")


_feedback_collector: Optional[FeedbackCollector] = None


async def get_feedback_collector() -> FeedbackCollector:
    global _feedback_collector
    if _feedback_collector is None:
        _feedback_collector = FeedbackCollector()
        await _feedback_collector.initialize()
    return _feedback_collector


async def close_feedback_collector() -> None:
    global _feedback_collector
    if _feedback_collector is not None:
        await _feedback_collector.close()
        _feedback_collector = None
