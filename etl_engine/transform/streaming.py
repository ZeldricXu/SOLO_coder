from __future__ import annotations

import asyncio
import json
import logging
from datetime import datetime, timezone
from typing import Any, Literal

import faust
import pandas as pd
from pydantic import BaseModel, Field

from etl_engine.exceptions import (
    SinkWriteError,
    StreamingPipelineError,
    TransformStepError,
    WindowAggregationError,
)
from etl_engine.transform.engine import TransformEngine

logger = logging.getLogger(__name__)

StreamingMode = Literal["batch", "streaming"]


class WindowConfig(BaseModel):
    type: Literal["tumbling", "hopping", "sliding", "session"]
    size_seconds: int
    advance_seconds: int | None = None
    grace_seconds: int = 60


class StreamingConfig(BaseModel):
    topic: str
    consumer_group: str
    bootstrap_servers: str
    window: WindowConfig | None = None
    transformations: list[dict] = Field(default_factory=list)
    checkpoint_interval: int = 5000
    sink_type: Literal["clickhouse", "redis", "kafka"] = "clickhouse"
    sink_config: dict = Field(default_factory=dict)


class StreamSink:
    def __init__(self, sink_type: str, config: dict):
        self.sink_type = sink_type
        self.config = config
        self._client: Any = None

    async def _get_client(self) -> Any:
        if self._client is not None:
            return self._client

        if self.sink_type == "clickhouse":
            from clickhouse_driver import Client as ClickHouseClient

            self._client = ClickHouseClient(
                host=self.config.get("host", "localhost"),
                port=self.config.get("port", 9000),
                user=self.config.get("user", "default"),
                password=self.config.get("password", ""),
                database=self.config.get("database", "default"),
            )
        elif self.sink_type == "redis":
            import redis.asyncio as redis

            self._client = redis.Redis(
                host=self.config.get("host", "localhost"),
                port=self.config.get("port", 6379),
                db=self.config.get("db", 0),
                password=self.config.get("password", None),
                decode_responses=True,
            )
        elif self.sink_type == "kafka":
            from confluent_kafka import Producer

            self._client = Producer({
                "bootstrap.servers": self.config.get("bootstrap_servers", "localhost:9092"),
                "acks": self.config.get("acks", "all"),
                "retries": self.config.get("retries", 3),
            })
        else:
            raise ValueError(f"Unsupported sink type: {self.sink_type}")

        return self._client

    async def write(self, data: dict | list[dict]) -> None:
        records = [data] if isinstance(data, dict) else data
        if not records:
            return

        try:
            client = await self._get_client()

            if self.sink_type == "clickhouse":
                table = self.config.get("table", "events")
                await self._write_clickhouse(client, records, table)
            elif self.sink_type == "redis":
                await self._write_redis(client, records)
            elif self.sink_type == "kafka":
                topic = self.config.get("topic", "sink_topic")
                await self._write_kafka(client, records, topic)

            logger.info("Written %d records to %s sink", len(records), self.sink_type)
        except Exception as e:
            raise SinkWriteError(
                sink_type=self.sink_type,
                operation="write",
                record_count=len(records),
                cause=e,
            ) from e

    async def _write_clickhouse(self, client: Any, records: list[dict], table: str) -> None:
        loop = asyncio.get_event_loop()
        await loop.run_in_executor(None, client.execute, f"INSERT INTO {table} VALUES", records)

    async def _write_redis(self, client: Any, records: list[dict]) -> None:
        key_prefix = self.config.get("key_prefix", "etl:")
        operation = self.config.get("redis_operation", "set")

        async with client.pipeline(transaction=True) as pipe:
            for record in records:
                key = f"{key_prefix}{record.get('id', datetime.now().timestamp())}"
                value = json.dumps(record) if operation != "incr" else record.get("value", 1)

                if operation == "set":
                    await pipe.set(key, value)
                elif operation == "incr":
                    await pipe.incrby(key, int(value))
                elif operation == "lpush":
                    await pipe.lpush(key, value)
                else:
                    await pipe.set(key, value)

            await pipe.execute()

    async def _write_kafka(self, client: Any, records: list[dict], topic: str) -> None:
        def delivery_report(err: Any, msg: Any) -> None:
            if err is not None:
                logger.error("Kafka delivery failed: %s", err)
            else:
                logger.debug("Kafka message delivered to %s [%d]", msg.topic(), msg.partition())

        for record in records:
            key = str(record.get("key", "")).encode() if "key" in record else None
            value = json.dumps(record).encode()
            client.produce(topic, key=key, value=value, on_delivery=delivery_report)

        client.flush(10.0)

    async def close(self) -> None:
        if self._client is not None:
            try:
                if self.sink_type == "redis":
                    await self._client.close()
                elif self.sink_type == "clickhouse":
                    self._client.disconnect()
                elif self.sink_type == "kafka":
                    self._client.flush()
            except Exception:
                pass
            self._client = None


class StreamingEngine:
    def __init__(self, config: StreamingConfig, app_name: str = "etl_streaming"):
        self.config = config
        self.app_name = app_name
        self.app = faust.App(
            app_name,
            broker=config.bootstrap_servers,
            consumer_group_prefix=config.consumer_group,
            autodiscover=False,
        )
        self.topic = self.app.topic(config.topic, value_type=bytes)
        self.transform_engine = TransformEngine(use_dask=False)
        self.sink = StreamSink(config.sink_type, config.sink_config)
        self._processed_count = 0
        self._error_count = 0
        self._start_time = datetime.now(timezone.utc)
        self._window_watermark: datetime | None = None
        self._agent: Any = None
        self._build_pipeline()

    def _build_pipeline(self) -> None:
        @self.app.agent(self.topic)
        async def process_stream(stream: Any) -> None:
            try:
                if self.config.window is not None:
                    processed = (
                        stream
                        .add_timestamp(lambda event, timestamp: timestamp or datetime.now(timezone.utc).timestamp())
                        .group_by(lambda event: event.get("group_key", "default"))
                    )

                    window_config = self.config.window
                    if window_config.type == "tumbling":
                        windowed = processed.tumbling(
                            size=window_config.size_seconds,
                            expires=window_config.grace_seconds,
                        )
                    elif window_config.type == "hopping":
                        advance = window_config.advance_seconds or window_config.size_seconds // 2
                        windowed = processed.hopping(
                            size=window_config.size_seconds,
                            step=advance,
                            expires=window_config.grace_seconds,
                        )
                    elif window_config.type == "sliding":
                        windowed = processed.sliding(
                            size=window_config.size_seconds,
                            expires=window_config.grace_seconds,
                        )
                    elif window_config.type == "session":
                        windowed = processed.session(
                            timeout=window_config.size_seconds,
                            expires=window_config.grace_seconds,
                        )
                    else:
                        raise ValueError(f"Unsupported window type: {window_config.type}")

                    async for key, window in windowed:
                        events = [json.loads(event) async for event in window]
                        if events:
                            try:
                                aggregated = self.aggregate_window(events)
                                aggregated["window_key"] = key
                                aggregated["window_start"] = window.start.isoformat() if hasattr(window, "start") else None
                                aggregated["window_end"] = window.end.isoformat() if hasattr(window, "end") else None
                                await self.sink.write(aggregated)
                                self._processed_count += len(events)
                            except WindowAggregationError:
                                raise
                            except Exception as e:
                                raise StreamingPipelineError(
                                    pipeline_name=self.app_name,
                                    topic=self.config.topic,
                                    stage="aggregate",
                                    cause=e,
                                ) from e
                else:
                    async for raw_event in stream:
                        try:
                            event = json.loads(raw_event)
                            transformed = self.apply_streaming_transformations(event)
                            await self.sink.write(transformed)
                            self._processed_count += 1
                        except TransformStepError:
                            self._error_count += 1
                            raise
                        except Exception as e:
                            self._error_count += 1
                            raise StreamingPipelineError(
                                pipeline_name=self.app_name,
                                topic=self.config.topic,
                                stage="transform",
                                cause=e,
                            ) from e

            except StreamingPipelineError:
                raise
            except Exception as e:
                raise StreamingPipelineError(
                    pipeline_name=self.app_name,
                    topic=self.config.topic,
                    stage="consume",
                    cause=e,
                ) from e

        self._agent = process_stream

    def build_streaming_pipeline(self) -> Any:
        return self._agent

    def apply_streaming_transformations(self, event: dict) -> dict:
        if not self.config.transformations:
            return event

        df = pd.DataFrame([event])
        transformed_df = self.transform_engine.apply(df, self.config.transformations)

        if transformed_df.empty:
            return event

        result = transformed_df.iloc[0].to_dict()
        for k, v in result.items():
            if pd.isna(v):
                result[k] = None
        return result

    def aggregate_window(self, events: list[dict]) -> dict:
        if not events:
            return {}

        window_config = self.config.window
        if window_config is None:
            return {"count": len(events), "events": events}

        try:
            df = pd.DataFrame(events)
            numeric_cols = df.select_dtypes(include="number").columns

            result: dict = {
                "count": len(events),
                "window_type": window_config.type,
                "window_size_seconds": window_config.size_seconds,
                "aggregation_time": datetime.now(timezone.utc).isoformat(),
            }

            for col in numeric_cols:
                col_data = df[col].dropna()
                if len(col_data) > 0:
                    result[f"{col}_count"] = int(col_data.count())
                    result[f"{col}_sum"] = float(col_data.sum())
                    result[f"{col}_avg"] = float(col_data.mean())
                    result[f"{col}_min"] = float(col_data.min())
                    result[f"{col}_max"] = float(col_data.max())

            self._window_watermark = datetime.now(timezone.utc)
            return result

        except Exception as e:
            raise WindowAggregationError(
                window_type=window_config.type,
                window_size=window_config.size_seconds,
                cause=e,
            ) from e

    def start(self) -> None:
        logger.info("Starting streaming engine: %s", self.app_name)
        self._start_time = datetime.now(timezone.utc)
        self.app.main()

    def stop(self) -> None:
        logger.info("Stopping streaming engine: %s", self.app_name)
        if self.app is not None:
            asyncio.create_task(self.sink.close())

    def get_status(self) -> dict:
        uptime = (datetime.now(timezone.utc) - self._start_time).total_seconds()
        throughput = self._processed_count / uptime if uptime > 0 else 0

        return {
            "app_name": self.app_name,
            "topic": self.config.topic,
            "status": "running" if self._agent is not None else "stopped",
            "processed_count": self._processed_count,
            "error_count": self._error_count,
            "uptime_seconds": uptime,
            "throughput_per_second": round(throughput, 2),
            "window_watermark": self._window_watermark.isoformat() if self._window_watermark else None,
            "sink_type": self.config.sink_type,
            "has_window": self.config.window is not None,
            "window_config": self.config.window.model_dump() if self.config.window else None,
        }

    def get_prometheus_metrics(self) -> dict:
        uptime = (datetime.now(timezone.utc) - self._start_time).total_seconds()
        throughput = self._processed_count / uptime if uptime > 0 else 0

        return {
            "etl_streaming_processed_total": self._processed_count,
            "etl_streaming_errors_total": self._error_count,
            "etl_streaming_uptime_seconds": uptime,
            "etl_streaming_throughput_per_second": round(throughput, 2),
            "etl_streaming_lag": self._calculate_lag(),
            "etl_streaming_window_count": 1 if self.config.window else 0,
        }

    def _calculate_lag(self) -> int:
        try:
            if self.app.monitor is not None:
                return getattr(self.app.monitor, "consumer_lag", 0)
        except Exception:
            pass
        return 0
