import logging
from typing import Any, Callable, Dict, List, Optional

from src.domain.cdc.event_serializer import CDCEvent, CDCEventSerializer, SerializationFormat
from src.infrastructure.messaging.kafka_producer import KafkaProducer
from src.infrastructure.config.settings import KafkaConfig

logger = logging.getLogger(__name__)


class OutputDestination:
    KAFKA = "kafka"
    FILE = "file"
    HTTP = "http"
    CONSOLE = "console"


class CDCOutputAdapter:
    def __init__(
        self,
        kafka_producer: Optional[KafkaProducer] = None,
        serializer: Optional[CDCEventSerializer] = None,
    ):
        self._kafka_producer = kafka_producer
        self._serializer = serializer or CDCEventSerializer(SerializationFormat.DEBEZIUM_JSON)
        self._destinations: Dict[str, Dict[str, Any]] = {}
        self._transformers: List[Callable[[CDCEvent], CDCEvent]] = []
        self._filters: List[Callable[[CDCEvent], bool]] = []
        self._error_handlers: List[Callable[[CDCEvent, Exception], None]] = []

    def add_destination(self, name: str, dest_type: str, config: Dict[str, Any]) -> None:
        self._destinations[name] = {
            "type": dest_type,
            "config": config,
        }

    def remove_destination(self, name: str) -> None:
        self._destinations.pop(name, None)

    def add_transformer(self, transformer: Callable[[CDCEvent], CDCEvent]) -> None:
        self._transformers.append(transformer)

    def add_filter(self, filter_fn: Callable[[CDCEvent], bool]) -> None:
        self._filters.append(filter_fn)

    def add_error_handler(self, handler: Callable[[CDCEvent, Exception], None]) -> None:
        self._error_handlers.append(handler)

    def emit(self, event: CDCEvent) -> Dict[str, bool]:
        results = {}

        for filter_fn in self._filters:
            if not filter_fn(event):
                return {name: False for name in self._destinations}

        for transformer in self._transformers:
            event = transformer(event)

        serialized = self._serializer.serialize(event)

        for name, dest in self._destinations.items():
            try:
                success = self._send_to_destination(name, dest, event, serialized)
                results[name] = success
            except Exception as e:
                results[name] = False
                logger.error(f"Failed to emit event to destination '{name}': {e}")
                for handler in self._error_handlers:
                    handler(event, e)

        return results

    def emit_batch(self, events: List[CDCEvent]) -> List[Dict[str, bool]]:
        return [self.emit(e) for e in events]

    def _send_to_destination(
        self,
        name: str,
        dest: Dict[str, Any],
        event: CDCEvent,
        serialized: bytes,
    ) -> bool:
        dest_type = dest["type"]
        config = dest["config"]

        if dest_type == OutputDestination.KAFKA:
            return self._send_to_kafka(config, event, serialized)
        elif dest_type == OutputDestination.FILE:
            return self._send_to_file(config, event, serialized)
        elif dest_type == OutputDestination.HTTP:
            return self._send_to_http(config, event, serialized)
        elif dest_type == OutputDestination.CONSOLE:
            return self._send_to_console(event, serialized)
        else:
            logger.warning(f"Unknown destination type: {dest_type}")
            return False

    def _send_to_kafka(self, config: Dict[str, Any], event: CDCEvent, serialized: bytes) -> bool:
        if self._kafka_producer is None:
            logger.warning("Kafka producer not configured")
            return False

        topic = config.get("topic", f"streamsql.cdc.{event.source_database}.{event.source_table}")
        key = config.get("key_field")
        key_value = None
        if key and key in event.after:
            key_value = str(event.after[key])
        elif key and key in event.before:
            key_value = str(event.before[key])

        try:
            import json
            self._kafka_producer.send(topic, json.loads(serialized.decode("utf-8")), key=key_value)
            return True
        except Exception as e:
            logger.error(f"Kafka send failed: {e}")
            return False

    def _send_to_file(self, config: Dict[str, Any], event: CDCEvent, serialized: bytes) -> bool:
        file_path = config.get("path", "/tmp/cdc_events.jsonl")
        try:
            with open(file_path, "ab") as f:
                f.write(serialized)
                f.write(b"\n")
            return True
        except Exception as e:
            logger.error(f"File write failed: {e}")
            return False

    def _send_to_http(self, config: Dict[str, Any], event: CDCEvent, serialized: bytes) -> bool:
        url = config.get("url", "")
        if not url:
            return False
        try:
            import aiohttp
            import asyncio
            async def _post():
                async with aiohttp.ClientSession() as session:
                    async with session.post(url, data=serialized, headers={"Content-Type": "application/json"}) as resp:
                        return resp.status < 400
            try:
                loop = asyncio.get_event_loop()
                if loop.is_running():
                    return True
                return loop.run_until_complete(_post())
            except RuntimeError:
                return asyncio.run(_post())
        except Exception as e:
            logger.error(f"HTTP POST failed: {e}")
            return False

    def _send_to_console(self, event: CDCEvent, serialized: bytes) -> bool:
        logger.info(f"CDC Event: {serialized.decode('utf-8')}")
        return True

    def get_destinations(self) -> Dict[str, Dict[str, Any]]:
        return dict(self._destinations)

    def set_serializer_format(self, format_type: SerializationFormat) -> None:
        self._serializer = CDCEventSerializer(format_type)
