from __future__ import annotations

import asyncio
import json
from abc import ABC, abstractmethod
from collections import deque
from typing import Any

from streamsql.modules.cdc_capture.binlog_parser import CDCEvent
from streamsql.modules.cdc_capture.event_serializer import EventSerializer, JSONEventSerializer


class OutputAdapter(ABC):
    def __init__(self, serializer: EventSerializer | None = None):
        self.serializer = serializer or JSONEventSerializer()
        self._buffered_events: deque[CDCEvent] = deque()

    @abstractmethod
    async def send(self, event: CDCEvent) -> None: ...

    @abstractmethod
    async def send_batch(self, events: list[CDCEvent]) -> None: ...

    def buffer(self, event: CDCEvent) -> None:
        self._buffered_events.append(event)

    async def flush(self) -> None:
        if self._buffered_events:
            events = list(self._buffered_events)
            self._buffered_events.clear()
            await self.send_batch(events)

    def get_buffer_size(self) -> int:
        return len(self._buffered_events)


class ConsoleOutputAdapter(OutputAdapter):
    async def send(self, event: CDCEvent) -> None:
        print(f"[CDC] {event.timestamp} [{event.operation.value}] {event.database}.{event.table}")

    async def send_batch(self, events: list[CDCEvent]) -> None:
        for event in events:
            await self.send(event)


class KafkaOutputAdapter(OutputAdapter):
    def __init__(
        self,
        bootstrap_servers: str,
        topic: str,
        serializer: EventSerializer | None = None,
    ):
        super().__init__(serializer)
        self.bootstrap_servers = bootstrap_servers
        self.topic = topic
        self._producer: Any = None

    async def _get_producer(self) -> Any:
        if self._producer is None:
            try:
                from aiokafka import AIOKafkaProducer
                self._producer = AIOKafkaProducer(bootstrap_servers=self.bootstrap_servers)
                await self._producer.start()
            except ImportError:
                self._producer = MockKafkaProducer()
        return self._producer

    async def send(self, event: CDCEvent) -> None:
        producer = await self._get_producer()
        key = f"{event.database}.{event.table}".encode()
        value = self.serializer.serialize(event)
        await producer.send_and_wait(self.topic, value=value, key=key)

    async def send_batch(self, events: list[CDCEvent]) -> None:
        producer = await self._get_producer()
        for event in events:
            key = f"{event.database}.{event.table}".encode()
            value = self.serializer.serialize(event)
            await producer.send(self.topic, value=value, key=key)

    async def close(self) -> None:
        if self._producer is not None:
            try:
                await self._producer.stop()
            except Exception:
                pass
            self._producer = None


class MockKafkaProducer:
    async def start(self) -> None:
        pass

    async def stop(self) -> None:
        pass

    async def send_and_wait(self, topic: str, value: bytes, key: bytes | None = None) -> None:
        await asyncio.sleep(0.001)

    async def send(self, topic: str, value: bytes, key: bytes | None = None) -> None:
        pass


class RedisStreamOutputAdapter(OutputAdapter):
    def __init__(
        self,
        redis_url: str,
        stream_name: str,
        serializer: EventSerializer | None = None,
    ):
        super().__init__(serializer)
        self.redis_url = redis_url
        self.stream_name = stream_name
        self._client: Any = None

    async def _get_client(self) -> Any:
        if self._client is None:
            try:
                import redis.asyncio as redis
                self._client = redis.from_url(self.redis_url)
            except ImportError:
                self._client = MockRedisClient()
        return self._client

    async def send(self, event: CDCEvent) -> None:
        client = await self._get_client()
        data = {
            "event_id": event.event_id,
            "operation": event.operation.value,
            "database": event.database,
            "table": event.table,
            "timestamp": event.timestamp.isoformat(),
            "payload": self.serializer.serialize(event).decode("latin-1"),
        }
        await client.xadd(self.stream_name, data)

    async def send_batch(self, events: list[CDCEvent]) -> None:
        client = await self._get_client()
        pipe = client.pipeline()
        for event in events:
            data = {
                "event_id": event.event_id,
                "operation": event.operation.value,
                "database": event.database,
                "table": event.table,
                "timestamp": event.timestamp.isoformat(),
                "payload": self.serializer.serialize(event).decode("latin-1"),
            }
            pipe.xadd(self.stream_name, data)
        await pipe.execute()

    async def close(self) -> None:
        if self._client is not None:
            try:
                await self._client.close()
            except Exception:
                pass
            self._client = None


class MockRedisClient:
    async def xadd(self, stream: str, data: dict[str, Any]) -> None:
        await asyncio.sleep(0.001)

    def pipeline(self) -> "MockRedisPipeline":
        return MockRedisPipeline()

    async def close(self) -> None:
        pass


class MockRedisPipeline:
    def __init__(self) -> None:
        self._commands: list[tuple[str, dict[str, Any]]] = []

    def xadd(self, stream: str, data: dict[str, Any]) -> None:
        self._commands.append((stream, data))

    async def execute(self) -> list[Any]:
        await asyncio.sleep(0.001 * len(self._commands))
        return [None] * len(self._commands)


class WebhookOutputAdapter(OutputAdapter):
    def __init__(
        self,
        webhook_url: str,
        serializer: EventSerializer | None = None,
        headers: dict[str, str] | None = None,
    ):
        super().__init__(serializer)
        self.webhook_url = webhook_url
        self.headers = headers or {"Content-Type": "application/json"}
        self._client: Any = None

    async def _get_client(self) -> Any:
        if self._client is None:
            try:
                import httpx
                self._client = httpx.AsyncClient()
            except ImportError:
                self._client = MockHttpClient()
        return self._client

    async def send(self, event: CDCEvent) -> None:
        client = await self._get_client()
        data = self.serializer.serialize(event).decode("utf-8")
        await client.post(self.webhook_url, content=data, headers=self.headers)

    async def send_batch(self, events: list[CDCEvent]) -> None:
        client = await self._get_client()
        data = self.serializer.serialize_batch(events).decode("utf-8")
        await client.post(self.webhook_url, content=data, headers=self.headers)

    async def close(self) -> None:
        if self._client is not None:
            try:
                await self._client.aclose()
            except Exception:
                pass
            self._client = None


class MockHttpClient:
    async def post(self, url: str, content: str, headers: dict[str, str]) -> None:
        await asyncio.sleep(0.01)

    async def aclose(self) -> None:
        pass


class FileOutputAdapter(OutputAdapter):
    def __init__(
        self,
        file_path: str,
        serializer: EventSerializer | None = None,
        append: bool = True,
    ):
        super().__init__(serializer)
        self.file_path = file_path
        self.mode = "ab" if append else "wb"

    async def send(self, event: CDCEvent) -> None:
        data = self.serializer.serialize(event)
        with open(self.file_path, self.mode) as f:
            f.write(data)
            f.write(b"\n")

    async def send_batch(self, events: list[CDCEvent]) -> None:
        data = self.serializer.serialize_batch(events)
        with open(self.file_path, self.mode) as f:
            f.write(data)
            f.write(b"\n")


class OutputRouter(OutputAdapter):
    def __init__(self, serializer: EventSerializer | None = None):
        super().__init__(serializer)
        self._adapters: list[OutputAdapter] = []
        self._rules: list[tuple[str, OutputAdapter]] = []

    def add_adapter(self, adapter: OutputAdapter) -> None:
        self._adapters.append(adapter)

    def add_route(self, table_pattern: str, adapter: OutputAdapter) -> None:
        self._rules.append((table_pattern, adapter))

    async def send(self, event: CDCEvent) -> None:
        for pattern, adapter in self._rules:
            if pattern == "*" or pattern in event.table:
                await adapter.send(event)

        for adapter in self._adapters:
            await adapter.send(event)

    async def send_batch(self, events: list[CDCEvent]) -> None:
        for event in events:
            await self.send(event)
