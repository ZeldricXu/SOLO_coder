from typing import Dict, Any, List, Optional, Callable
from datetime import datetime
import asyncio
import logging

from influxdb_client import InfluxDBClient, Point, WritePrecision
from influxdb_client.client.write_api import SYNCHRONOUS, WriteOptions

from app.core.config import settings
from app.core.models import MetricResult, CleanedDataEvent

logger = logging.getLogger(__name__)


class InfluxDBStore:
    def __init__(
        self,
        url: str = None,
        token: str = None,
        org: str = None,
        bucket: str = None,
        batch_interval: int = None,
        max_batch_size: int = None
    ):
        self._url = url or settings.INFLUXDB_URL
        self._token = token or settings.INFLUXDB_TOKEN
        self._org = org or settings.INFLUXDB_ORG
        self._bucket = bucket or settings.INFLUXDB_BUCKET
        self._batch_interval = batch_interval or settings.BATCH_WRITE_INTERVAL
        self._max_batch_size = max_batch_size or settings.MAX_BATCH_SIZE

        self._client: Optional[InfluxDBClient] = None
        self._write_api = None
        self._query_api = None
        self._is_connected = False

        self._buffer: List[Point] = []
        self._buffer_lock = asyncio.Lock()
        self._flush_task: Optional[asyncio.Task] = None
        self._is_running = False

        self._on_write_callback: Optional[Callable[[List[MetricResult]], None]] = None

    def set_write_callback(self, callback: Callable[[List[MetricResult]], None]):
        self._on_write_callback = callback

    async def connect(self) -> bool:
        try:
            loop = asyncio.get_running_loop()

            def create_client():
                return InfluxDBClient(
                    url=self._url,
                    token=self._token,
                    org=self._org
                )

            self._client = await loop.run_in_executor(None, create_client)
            self._write_api = self._client.write_api(
                write_options=WriteOptions(
                    batch_size=self._max_batch_size,
                    flush_interval=self._batch_interval * 1000
                )
            )
            self._query_api = self._client.query_api()
            self._is_connected = True

            logger.info(f"Connected to InfluxDB: {self._url}")
            return True

        except Exception as e:
            logger.error(f"Failed to connect to InfluxDB: {e}")
            self._is_connected = False
            return False

    async def disconnect(self):
        await self.stop()

        if self._write_api:
            try:
                loop = asyncio.get_running_loop()
                await loop.run_in_executor(None, self._write_api.close)
            except Exception as e:
                logger.warning(f"Error closing write API: {e}")
            self._write_api = None

        if self._client:
            try:
                self._client.close()
            except Exception as e:
                logger.warning(f"Error closing InfluxDB client: {e}")
            self._client = None

        self._is_connected = False
        logger.info("Disconnected from InfluxDB")

    async def start(self):
        self._is_running = True
        self._flush_task = asyncio.create_task(self._flush_loop())
        logger.info("Started InfluxDB store")

    async def stop(self):
        self._is_running = False

        if self._buffer:
            await self._flush_buffer()

        if self._flush_task and not self._flush_task.done():
            self._flush_task.cancel()
            try:
                await self._flush_task
            except asyncio.CancelledError:
                pass

    async def _flush_loop(self):
        while self._is_running:
            await asyncio.sleep(self._batch_interval)
            if self._buffer:
                await self._flush_buffer()

    async def _flush_buffer(self):
        if not self._buffer:
            return

        if not self._is_connected or not self._write_api:
            logger.warning("InfluxDB not connected, cannot flush buffer")
            return

        try:
            async with self._buffer_lock:
                points_to_write = self._buffer.copy()
                self._buffer.clear()

            loop = asyncio.get_running_loop()

            def do_write():
                self._write_api.write(
                    bucket=self._bucket,
                    org=self._org,
                    record=points_to_write
                )

            await loop.run_in_executor(None, do_write)
            logger.debug(f"Flushed {len(points_to_write)} points to InfluxDB")

        except Exception as e:
            logger.error(f"Failed to flush buffer to InfluxDB: {e}")

    def _metric_to_point(self, result: MetricResult) -> Point:
        point = Point("metrics")

        point.field("value", result.value)

        point.tag("metric_id", result.metric_id)

        for key, value in result.group_key.items():
            if value is not None:
                point.tag(key, str(value))

        point.time(result.timestamp, WritePrecision.NS)

        return point

    def _cleaned_event_to_point(self, event: CleanedDataEvent) -> Point:
        point = Point("raw_data")

        point.tag("source", event.source)
        point.tag("quality_score", event.quality_score)

        for key, value in event.data.items():
            if isinstance(value, (int, float)):
                point.field(key, value)
            elif isinstance(value, str) and len(value) < 256:
                point.tag(key, value)

        point.time(event.timestamp, WritePrecision.NS)

        return point

    async def write_metric(self, result: MetricResult):
        if not self._is_running:
            return

        try:
            point = self._metric_to_point(result)

            async with self._buffer_lock:
                self._buffer.append(point)

                if len(self._buffer) >= self._max_batch_size:
                    await self._flush_buffer()

        except Exception as e:
            logger.error(f"Failed to queue metric for writing: {e}")

    async def write_metrics_batch(self, results: List[MetricResult]):
        if not self._is_running:
            return

        try:
            points = [self._metric_to_point(r) for r in results]

            async with self._buffer_lock:
                self._buffer.extend(points)

                if len(self._buffer) >= self._max_batch_size:
                    await self._flush_buffer()

        except Exception as e:
            logger.error(f"Failed to queue metrics batch for writing: {e}")

    async def write_cleaned_event(self, event: CleanedDataEvent):
        if not self._is_running:
            return

        try:
            point = self._cleaned_event_to_point(event)

            async with self._buffer_lock:
                self._buffer.append(point)

                if len(self._buffer) >= self._max_batch_size:
                    await self._flush_buffer()

        except Exception as e:
            logger.error(f"Failed to queue cleaned event for writing: {e}")

    async def query_metric(
        self,
        metric_id: str,
        start_time: datetime,
        end_time: datetime = None,
        group_key: Dict[str, Any] = None
    ) -> List[Dict[str, Any]]:
        if not self._is_connected or not self._query_api:
            logger.warning("InfluxDB not connected for query")
            return []

        try:
            if end_time is None:
                end_time = datetime.utcnow()

            flux_query = f'''
            from(bucket: "{self._bucket}")
                |> range(start: {start_time.isoformat()}Z, stop: {end_time.isoformat()}Z)
                |> filter(fn: (r) => r._measurement == "metrics" and r.metric_id == "{metric_id}")
            '''

            if group_key:
                for key, value in group_key.items():
                    if value is not None:
                        flux_query += f' |> filter(fn: (r) => r.{key} == "{value}")'

            flux_query += '''
                |> keep(columns: ["_time", "_value", "metric_id"])
                |> yield(name: "results")
            '''

            loop = asyncio.get_running_loop()

            def do_query():
                return self._query_api.query(flux_query, org=self._org)

            tables = await loop.run_in_executor(None, do_query)

            results = []
            for table in tables:
                for record in table.records:
                    results.append({
                        "time": record.get_time(),
                        "value": record.get_value(),
                        "metric_id": record.values.get("metric_id")
                    })

            return results

        except Exception as e:
            logger.error(f"Failed to query InfluxDB: {e}")
            return []

    def get_status(self) -> Dict[str, Any]:
        return {
            "connected": self._is_connected,
            "url": self._url,
            "org": self._org,
            "bucket": self._bucket,
            "buffer_size": len(self._buffer),
            "max_batch_size": self._max_batch_size,
            "batch_interval": self._batch_interval
        }


influxdb_store = InfluxDBStore()
