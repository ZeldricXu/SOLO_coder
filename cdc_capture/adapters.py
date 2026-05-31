import json
import time
import threading
from abc import ABC, abstractmethod
from typing import Any, Dict, List, Optional, Callable
from dataclasses import dataclass, field
from queue import Queue, Empty

from .events import CDCEvent
from .serializer import Serializer, JSONSerializer


class OutputAdapter(ABC):
    def __init__(self, serializer: Optional[Serializer] = None):
        self.serializer = serializer or JSONSerializer()
        self.is_connected = False
        self._stats: Dict[str, Any] = {
            "messages_sent": 0,
            "messages_failed": 0,
            "bytes_sent": 0,
            "last_send_time": 0,
        }

    @abstractmethod
    def connect(self) -> None:
        pass

    @abstractmethod
    def disconnect(self) -> None:
        pass

    @abstractmethod
    def send(self, event: CDCEvent, **kwargs) -> bool:
        pass

    def send_batch(self, events: List[CDCEvent], **kwargs) -> Dict[str, int]:
        results = {"success": 0, "failed": 0}
        for event in events:
            if self.send(event, **kwargs):
                results["success"] += 1
            else:
                results["failed"] += 1
        return results

    def get_stats(self) -> Dict[str, Any]:
        return self._stats.copy()

    def reset_stats(self) -> None:
        self._stats = {
            "messages_sent": 0,
            "messages_failed": 0,
            "bytes_sent": 0,
            "last_send_time": 0,
        }

    def _update_stats_success(self, bytes_sent: int) -> None:
        self._stats["messages_sent"] += 1
        self._stats["bytes_sent"] += bytes_sent
        self._stats["last_send_time"] = time.time()

    def _update_stats_failed(self) -> None:
        self._stats["messages_failed"] += 1


class ConsoleAdapter(OutputAdapter):
    def __init__(
        self,
        serializer: Optional[Serializer] = None,
        pretty_print: bool = True,
        output_file: Optional[str] = None,
    ):
        super().__init__(serializer)
        self.pretty_print = pretty_print
        self.output_file = output_file
        self._file_handle = None

    def connect(self) -> None:
        if self.output_file:
            self._file_handle = open(self.output_file, "a", encoding="utf-8")
        self.is_connected = True

    def disconnect(self) -> None:
        if self._file_handle:
            self._file_handle.close()
            self._file_handle = None
        self.is_connected = False

    def send(self, event: CDCEvent, **kwargs) -> bool:
        if not self.is_connected:
            self.connect()

        try:
            if self.pretty_print:
                if isinstance(self.serializer, JSONSerializer):
                    data = json.dumps(event.to_dict(), indent=2, ensure_ascii=False, default=str)
                else:
                    data = self.serializer.serialize(event).decode("utf-8", errors="replace")
            else:
                data = self.serializer.serialize(event).decode("utf-8", errors="replace")

            if self._file_handle:
                self._file_handle.write(data + "\n")
                self._file_handle.flush()
            else:
                print(data)

            self._update_stats_success(len(data.encode("utf-8")))
            return True
        except Exception as e:
            print(f"ConsoleAdapter error: {e}")
            self._update_stats_failed()
            return False


class FileOutputAdapter(OutputAdapter):
    def __init__(
        self,
        file_path: str,
        serializer: Optional[Serializer] = None,
        mode: str = "a",
        rotate_on_size: int = 0,
        rotate_on_interval: int = 0,
        max_files: int = 10,
    ):
        super().__init__(serializer)
        self.file_path = file_path
        self.mode = mode
        self.rotate_on_size = rotate_on_size
        self.rotate_on_interval = rotate_on_interval
        self.max_files = max_files
        self._file_handle = None
        self._current_size = 0
        self._last_rotate_time = time.time()
        self._file_index = 0

    def connect(self) -> None:
        self._open_file()
        self.is_connected = True

    def disconnect(self) -> None:
        if self._file_handle:
            self._file_handle.close()
            self._file_handle = None
        self.is_connected = False

    def _open_file(self) -> None:
        if self._file_handle:
            self._file_handle.close()

        file_path = self._get_file_path()
        self._file_handle = open(file_path, self.mode, encoding="utf-8")
        self._current_size = 0
        self._last_rotate_time = time.time()

    def _get_file_path(self) -> str:
        if self.rotate_on_size > 0 or self.rotate_on_interval > 0:
            import os
            base, ext = os.path.splitext(self.file_path)
            return f"{base}_{self._file_index:03d}{ext}"
        return self.file_path

    def _check_rotation(self) -> None:
        need_rotate = False

        if self.rotate_on_size > 0 and self._current_size >= self.rotate_on_size:
            need_rotate = True

        if self.rotate_on_interval > 0:
            if time.time() - self._last_rotate_time >= self.rotate_on_interval:
                need_rotate = True

        if need_rotate:
            self._file_index = (self._file_index + 1) % self.max_files
            self._open_file()

    def send(self, event: CDCEvent, **kwargs) -> bool:
        if not self.is_connected:
            self.connect()

        try:
            self._check_rotation()

            data = self.serializer.serialize(event)
            if isinstance(data, bytes):
                line = data + b"\n"
                self._file_handle.buffer.write(line)
            else:
                line = data + "\n"
                self._file_handle.write(line)

            self._file_handle.flush()
            self._current_size += len(line)

            self._update_stats_success(len(line))
            return True
        except Exception as e:
            print(f"FileOutputAdapter error: {e}")
            self._update_stats_failed()
            return False


class KafkaAdapter(OutputAdapter):
    def __init__(
        self,
        bootstrap_servers: str,
        topic: str,
        serializer: Optional[Serializer] = None,
        producer_config: Optional[Dict[str, Any]] = None,
        key_generator: Optional[Callable[[CDCEvent], str]] = None,
    ):
        super().__init__(serializer)
        self.bootstrap_servers = bootstrap_servers
        self.topic = topic
        self.producer_config = producer_config or {}
        self.key_generator = key_generator or self._default_key_generator
        self._producer = None
        self._delivery_reports: Queue = Queue()
        self._delivery_thread = None

    def _default_key_generator(self, event: CDCEvent) -> str:
        metadata = event.metadata
        key_parts = []
        if metadata.database:
            key_parts.append(metadata.database)
        if metadata.table:
            key_parts.append(metadata.table)
        if hasattr(event, "old_data") and event.old_data:
            for key in ["id", "uuid", "primary_key"]:
                if key in event.old_data:
                    key_parts.append(str(event.old_data[key]))
                    break
        elif hasattr(event, "new_data") and event.new_data:
            for key in ["id", "uuid", "primary_key"]:
                if key in event.new_data:
                    key_parts.append(str(event.new_data[key]))
                    break

        return "|".join(key_parts) if key_parts else metadata.event_id

    def connect(self) -> None:
        try:
            from kafka import KafkaProducer

            config = {
                "bootstrap_servers": self.bootstrap_servers,
                "acks": "all",
                "retries": 3,
                "linger_ms": 5,
                "batch_size": 16384,
                "compression_type": "none",
                **self.producer_config,
            }

            self._producer = KafkaProducer(**config)
            self._start_delivery_report_thread()
            self.is_connected = True
        except ImportError:
            print("kafka-python not installed, using mock mode")
            self._producer = None
            self.is_connected = True

    def _start_delivery_report_thread(self) -> None:
        def delivery_report_thread():
            while self.is_connected:
                try:
                    report = self._delivery_reports.get(timeout=0.1)
                    if report["error"]:
                        print(f"Message delivery failed: {report['error']}")
                        self._update_stats_failed()
                    else:
                        self._update_stats_success(report["size"])
                except Empty:
                    continue

        self._delivery_thread = threading.Thread(target=delivery_report_thread, daemon=True)
        self._delivery_thread.start()

    def disconnect(self) -> None:
        self.is_connected = False
        if self._producer:
            self._producer.flush(timeout=10)
            self._producer.close()
            self._producer = None

    def send(self, event: CDCEvent, **kwargs) -> bool:
        if not self.is_connected:
            self.connect()

        try:
            key = self.key_generator(event)
            value = self.serializer.serialize(event)
            key_bytes = key.encode("utf-8") if isinstance(key, str) else key

            headers = kwargs.get("headers", {})
            kafka_headers = [(k, str(v).encode("utf-8")) for k, v in headers.items()]

            if self._producer:
                def on_delivery(err, msg):
                    self._delivery_reports.put({
                        "error": err,
                        "size": len(value) if not err else 0,
                        "topic": msg.topic() if msg else self.topic,
                        "partition": msg.partition() if msg else -1,
                        "offset": msg.offset() if msg else -1,
                    })

                self._producer.send(
                    topic=self.topic,
                    value=value,
                    key=key_bytes,
                    headers=kafka_headers if kafka_headers else None,
                    on_delivery=on_delivery,
                )
                return True
            else:
                print(f"[Mock Kafka] Send to {self.topic}: key={key}, size={len(value)} bytes")
                self._update_stats_success(len(value))
                return True

        except Exception as e:
            print(f"KafkaAdapter error: {e}")
            self._update_stats_failed()
            return False

    def send_batch(self, events: List[CDCEvent], **kwargs) -> Dict[str, int]:
        results = {"success": 0, "failed": 0}

        if not self.is_connected:
            self.connect()

        for event in events:
            if self.send(event, **kwargs):
                results["success"] += 1
            else:
                results["failed"] += 1

        if self._producer:
            self._producer.flush()

        return results


class RabbitMQAdapter(OutputAdapter):
    def __init__(
        self,
        host: str,
        port: int,
        username: str,
        password: str,
        exchange: str,
        routing_key: str = "",
        queue_name: Optional[str] = None,
        serializer: Optional[Serializer] = None,
        exchange_type: str = "topic",
        durable: bool = True,
    ):
        super().__init__(serializer)
        self.host = host
        self.port = port
        self.username = username
        self.password = password
        self.exchange = exchange
        self.routing_key = routing_key
        self.queue_name = queue_name
        self.exchange_type = exchange_type
        self.durable = durable
        self._connection = None
        self._channel = None

    def connect(self) -> None:
        try:
            import pika

            credentials = pika.PlainCredentials(self.username, self.password)
            parameters = pika.ConnectionParameters(
                host=self.host,
                port=self.port,
                credentials=credentials,
                heartbeat=600,
                blocked_connection_timeout=300,
            )

            self._connection = pika.BlockingConnection(parameters)
            self._channel = self._connection.channel()

            self._channel.exchange_declare(
                exchange=self.exchange,
                exchange_type=self.exchange_type,
                durable=self.durable,
            )

            if self.queue_name:
                self._channel.queue_declare(queue=self.queue_name, durable=self.durable)
                self._channel.queue_bind(
                    queue=self.queue_name,
                    exchange=self.exchange,
                    routing_key=self.routing_key,
                )

            self.is_connected = True
        except ImportError:
            print("pika not installed, using mock mode")
            self._connection = None
            self._channel = None
            self.is_connected = True
        except Exception as e:
            print(f"RabbitMQ connection error: {e}")
            self._connection = None
            self._channel = None
            self.is_connected = True

    def disconnect(self) -> None:
        if self._connection and not self._connection.is_closed:
            self._connection.close()
        self._connection = None
        self._channel = None
        self.is_connected = False

    def _get_routing_key(self, event: CDCEvent) -> str:
        routing_key = self.routing_key
        if "{database}" in routing_key:
            routing_key = routing_key.replace("{database}", event.metadata.database)
        if "{table}" in routing_key:
            routing_key = routing_key.replace("{table}", event.metadata.table)
        if "{event_type}" in routing_key:
            routing_key = routing_key.replace("{event_type}", event.event_type.value)
        return routing_key

    def send(self, event: CDCEvent, **kwargs) -> bool:
        if not self.is_connected:
            self.connect()

        try:
            value = self.serializer.serialize(event)
            routing_key = self._get_routing_key(event)

            properties = kwargs.get("properties", {})
            if self._channel and self._connection and not self._connection.is_closed:
                import pika

                basic_properties = pika.BasicProperties(
                    delivery_mode=2 if self.durable else 1,
                    content_type="application/octet-stream",
                    headers=properties.get("headers", {}),
                    **{k: v for k, v in properties.items() if k != "headers"},
                )

                self._channel.basic_publish(
                    exchange=self.exchange,
                    routing_key=routing_key,
                    body=value,
                    properties=basic_properties,
                )
            else:
                print(
                    f"[Mock RabbitMQ] Send to exchange={self.exchange}, "
                    f"routing_key={routing_key}, size={len(value)} bytes"
                )

            self._update_stats_success(len(value))
            return True

        except Exception as e:
            print(f"RabbitMQAdapter error: {e}")
            self._update_stats_failed()
            return False


class HTTPWebhookAdapter(OutputAdapter):
    def __init__(
        self,
        url: str,
        serializer: Optional[Serializer] = None,
        method: str = "POST",
        headers: Optional[Dict[str, str]] = None,
        timeout: int = 30,
        retry_on_error: bool = True,
        max_retries: int = 3,
        verify_ssl: bool = True,
    ):
        super().__init__(serializer)
        self.url = url
        self.method = method
        self.headers = headers or {"Content-Type": "application/json"}
        self.timeout = timeout
        self.retry_on_error = retry_on_error
        self.max_retries = max_retries
        self.verify_ssl = verify_ssl

    def connect(self) -> None:
        self.is_connected = True

    def disconnect(self) -> None:
        self.is_connected = False

    def send(self, event: CDCEvent, **kwargs) -> bool:
        if not self.is_connected:
            self.connect()

        try:
            import urllib.request
            import urllib.error

            data = self.serializer.serialize(event)
            headers = {**self.headers, **kwargs.get("additional_headers", {})}

            last_error = None
            for attempt in range(self.max_retries if self.retry_on_error else 1):
                try:
                    req = urllib.request.Request(
                        url=self.url,
                        data=data,
                        headers=headers,
                        method=self.method,
                    )

                    context = None
                    if not self.verify_ssl:
                        import ssl
                        context = ssl.create_default_context()
                        context.check_hostname = False
                        context.verify_mode = ssl.CERT_NONE

                    with urllib.request.urlopen(req, timeout=self.timeout, context=context) as response:
                        status_code = response.getcode()
                        if 200 <= status_code < 300:
                            self._update_stats_success(len(data))
                            return True
                        else:
                            last_error = f"HTTP {status_code}"
                except urllib.error.URLError as e:
                    last_error = str(e)
                    if attempt < self.max_retries - 1:
                        time.sleep(2 ** attempt)

            if last_error:
                print(f"HTTPWebhookAdapter error: {last_error}")
            self._update_stats_failed()
            return False

        except Exception as e:
            print(f"HTTPWebhookAdapter error: {e}")
            self._update_stats_failed()
            return False

    def send_batch(self, events: List[CDCEvent], **kwargs) -> Dict[str, int]:
        results = {"success": 0, "failed": 0}

        if kwargs.get("batch_mode", False):
            batch_data = {
                "events": [event.to_dict() for event in events],
                "count": len(events),
                "timestamp": time.time(),
            }

            if not self.is_connected:
                self.connect()

            try:
                import urllib.request
                import urllib.error

                data = json.dumps(batch_data, ensure_ascii=False, default=str).encode("utf-8")
                headers = {**self.headers, **kwargs.get("additional_headers", {})}

                req = urllib.request.Request(
                    url=self.url,
                    data=data,
                    headers=headers,
                    method=self.method,
                )

                with urllib.request.urlopen(req, timeout=self.timeout) as response:
                    status_code = response.getcode()
                    if 200 <= status_code < 300:
                        self._update_stats_success(len(data))
                        results["success"] = len(events)
                    else:
                        results["failed"] = len(events)
                        self._update_stats_failed()
            except Exception as e:
                print(f"HTTPWebhookAdapter batch error: {e}")
                results["failed"] = len(events)
                self._update_stats_failed()
        else:
            for event in events:
                if self.send(event, **kwargs):
                    results["success"] += 1
                else:
                    results["failed"] += 1

        return results


class RedisStreamAdapter(OutputAdapter):
    def __init__(
        self,
        host: str,
        port: int,
        stream_name: str,
        serializer: Optional[Serializer] = None,
        password: Optional[str] = None,
        db: int = 0,
        maxlen: Optional[int] = None,
        approximate: bool = True,
    ):
        super().__init__(serializer)
        self.host = host
        self.port = port
        self.stream_name = stream_name
        self.password = password
        self.db = db
        self.maxlen = maxlen
        self.approximate = approximate
        self._redis = None

    def connect(self) -> None:
        try:
            import redis

            self._redis = redis.Redis(
                host=self.host,
                port=self.port,
                password=self.password,
                db=self.db,
                decode_responses=False,
            )
            self._redis.ping()
            self.is_connected = True
        except ImportError:
            print("redis-py not installed, using mock mode")
            self._redis = None
            self.is_connected = True
        except Exception as e:
            print(f"Redis connection error: {e}, using mock mode")
            self._redis = None
            self.is_connected = True

    def disconnect(self) -> None:
        if self._redis:
            self._redis.close()
            self._redis = None
        self.is_connected = False

    def send(self, event: CDCEvent, **kwargs) -> bool:
        if not self.is_connected:
            self.connect()

        try:
            data = self.serializer.serialize(event)

            fields = {
                b"event_type": event.event_type.value.encode("utf-8"),
                b"payload": data,
                b"event_id": event.metadata.event_id.encode("utf-8"),
                b"timestamp": str(event.metadata.timestamp).encode("utf-8"),
            }

            if event.metadata.database:
                fields[b"database"] = event.metadata.database.encode("utf-8")
            if event.metadata.table:
                fields[b"table"] = event.metadata.table.encode("utf-8")

            additional_fields = kwargs.get("fields", {})
            for k, v in additional_fields.items():
                if isinstance(k, str):
                    k = k.encode("utf-8")
                if isinstance(v, str):
                    v = v.encode("utf-8")
                fields[k] = v

            if self._redis:
                add_kwargs = {}
                if self.maxlen is not None:
                    add_kwargs["maxlen"] = self.maxlen
                    if self.approximate:
                        add_kwargs["approximate"] = True

                self._redis.xadd(self.stream_name, fields, **add_kwargs)
            else:
                print(
                    f"[Mock Redis Stream] Add to {self.stream_name}: "
                    f"event_type={event.event_type.value}, size={len(data)} bytes"
                )

            self._update_stats_success(len(data))
            return True

        except Exception as e:
            print(f"RedisStreamAdapter error: {e}")
            self._update_stats_failed()
            return False
