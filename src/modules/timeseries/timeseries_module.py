"""Time series module orchestrator."""
from __future__ import annotations

from datetime import datetime
from typing import Any, Dict, List, Optional

from ...domain.models.common import (
    EventMessage,
    ProcessingResult,
    ProcessingStatus,
    TimeSeriesDataPoint,
)
from ...domain.errors.common import ValidationError
from ...infrastructure.cache.redis_cache import RedisCache
from ...infrastructure.config.settings import Settings
from ...infrastructure.logging.structured_logger import LogManager
from .compression import CompressionAlgorithm, TimeSeriesCompressor
from .downsampling import DownsamplingEngine, DownsamplingMethod
from .multires import MultiResolutionStorage


class TimeSeriesModule:
    def __init__(
        self,
        settings: Settings,
        cache: Optional[RedisCache] = None,
    ) -> None:
        self._settings = settings
        self._compressor = TimeSeriesCompressor()
        self._downsampler = DownsamplingEngine()
        self._multires_storage = MultiResolutionStorage(
            settings=settings,
            compressor=self._compressor,
            downsampler=self._downsampler,
            cache=cache,
        )
        self._logger = LogManager().get_logger(__name__)
        self._logger.info("Time series module initialized")

    @property
    def compressor(self) -> TimeSeriesCompressor:
        return self._compressor

    @property
    def downsampler(self) -> DownsamplingEngine:
        return self._downsampler

    @property
    def multires_storage(self) -> MultiResolutionStorage:
        return self._multires_storage

    async def process_event(self, event: EventMessage) -> ProcessingResult:
        result = ProcessingResult(
            started_at=datetime.utcnow(),
            status=ProcessingStatus.PROCESSING,
        )

        try:
            event_type = event.event_type
            payload = event.payload

            if event_type == "timeseries.ingest":
                ingest_result = await self._handle_ingest(payload)
                result.results = [ingest_result]
                result.status = ProcessingStatus.SUCCESS
                result.message = "Time series data ingested successfully"

            elif event_type == "timeseries.compress":
                compress_result = await self._handle_compress(payload)
                result.results = [compress_result]
                result.status = ProcessingStatus.SUCCESS
                result.message = "Time series data compressed successfully"

            elif event_type == "timeseries.downsample":
                downsample_result = await self._handle_downsample(payload)
                result.results = [downsample_result]
                result.status = ProcessingStatus.SUCCESS
                result.message = "Time series data downsampled successfully"

            elif event_type == "timeseries.query":
                query_result = await self._handle_query(payload)
                result.results = [query_result]
                result.status = ProcessingStatus.SUCCESS
                result.message = "Time series query completed successfully"

            else:
                raise ValidationError(
                    message=f"Unknown event type: {event_type}",
                    suggestion="Check the event type and try again.",
                )

        except Exception as e:
            result.status = ProcessingStatus.FAILED
            result.message = f"Time series event processing failed: {str(e)}"
            result.errors.append({"error": str(e)})

            self._logger.error(
                "Time series event processing failed",
                event_type=event.event_type,
                error=str(e),
            )

        result.completed_at = datetime.utcnow()
        result.calculate_duration()

        return result

    async def _handle_ingest(self, payload: Dict[str, Any]) -> Dict[str, Any]:
        timestamps = payload.get("timestamps", [])
        values = payload.get("values", [])
        tags = payload.get("tags", {})
        metric_name = payload.get("metric_name", "default")

        if len(timestamps) != len(values):
            raise ValidationError(
                message="timestamps and values must have the same length",
                suggestion="Ensure timestamps and values arrays have equal length.",
            )

        data_points = [
            TimeSeriesDataPoint(
                timestamp=datetime.fromtimestamp(ts) if isinstance(ts, (int, float)) else ts,
                value=val,
                tags=tags,
            )
            for ts, val in zip(timestamps, values)
        ]

        await self._multires_storage.add_data(metric_name, data_points)

        return {
            "metric_name": metric_name,
            "points_ingested": len(data_points),
            "resolutions": ["raw", "1m", "5m", "1h", "1d"],
        }

    async def _handle_compress(self, payload: Dict[str, Any]) -> Dict[str, Any]:
        timestamps = payload.get("timestamps", [])
        values = payload.get("values", [])
        algorithm = payload.get("algorithm", "zstd")
        tags = payload.get("tags", {})

        if not timestamps or not values:
            raise ValidationError(
                message="timestamps and values are required",
                suggestion="Provide 'timestamps' and 'values' in the payload.",
            )

        data_points = [
            TimeSeriesDataPoint(
                timestamp=datetime.fromtimestamp(ts) if isinstance(ts, (int, float)) else ts,
                value=val,
                tags=tags,
            )
            for ts, val in zip(timestamps, values)
        ]

        algo = CompressionAlgorithm(algorithm)
        result = await self._compressor.compress(data_points, algo)

        return {
            "algorithm": result.algorithm.value,
            "original_size": result.original_size,
            "compressed_size": result.compressed_size,
            "compression_ratio": round(result.compression_ratio, 2),
            "saved_bytes": result.original_size - result.compressed_size,
        }

    async def _handle_downsample(self, payload: Dict[str, Any]) -> Dict[str, Any]:
        timestamps = payload.get("timestamps", [])
        values = payload.get("values", [])
        target_points = payload.get("target_points", 100)
        method = payload.get("method", "average")
        tags = payload.get("tags", {})

        if not timestamps or not values:
            raise ValidationError(
                message="timestamps and values are required",
                suggestion="Provide 'timestamps' and 'values' in the payload.",
            )

        data_points = [
            TimeSeriesDataPoint(
                timestamp=datetime.fromtimestamp(ts) if isinstance(ts, (int, float)) else ts,
                value=val,
                tags=tags,
            )
            for ts, val in zip(timestamps, values)
        ]

        downsample_method = DownsamplingMethod(method)
        result = await self._downsampler.downsample(data_points, target_points, downsample_method)

        return {
            "method": downsample_method.value,
            "original_points": len(data_points),
            "downsampled_points": len(result),
            "reduction_ratio": round(len(data_points) / len(result), 2) if result else 1,
            "timestamps": [dp.timestamp.timestamp() for dp in result],
            "values": [dp.value for dp in result],
        }

    async def _handle_query(self, payload: Dict[str, Any]) -> Dict[str, Any]:
        metric_name = payload.get("metric_name", "default")
        start_time = payload.get("start_time")
        end_time = payload.get("end_time")
        resolution = payload.get("resolution", "raw")

        data = await self._multires_storage.query(
            metric_name=metric_name,
            start_time=datetime.fromtimestamp(start_time) if start_time else None,
            end_time=datetime.fromtimestamp(end_time) if end_time else None,
            resolution=resolution,
        )

        return {
            "metric_name": metric_name,
            "resolution": resolution,
            "points_returned": len(data),
            "timestamps": [dp.timestamp.timestamp() for dp in data],
            "values": [dp.value for dp in data],
        }

    async def start(self) -> None:
        self._logger.info("Starting time series module")

    async def stop(self) -> None:
        self._logger.info("Stopping time series module")

    async def get_status(self) -> dict:
        metrics = await self._multires_storage.list_metrics()
        return {
            "module": "timeseries",
            "status": "running",
            "metrics_count": len(metrics),
        }
