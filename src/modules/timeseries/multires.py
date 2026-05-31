"""Multi-resolution storage for time series data."""
from __future__ import annotations

from datetime import datetime, timedelta
from typing import Any, Dict, List, Optional, Tuple
from uuid import UUID, uuid4

import numpy as np

from ...domain.models.common import TimeSeriesData, TimeSeriesDataPoint
from ...infrastructure.cache.redis_cache import RedisCache
from ...infrastructure.config.settings import Settings
from ...infrastructure.logging.structured_logger import LogManager
from .compression import CompressionAlgorithm, TimeSeriesCompressor
from .downsampling import DownsamplingEngine, DownsamplingMethod


class ResolutionLevel(str):
    RAW = "raw"
    MINUTE = "1m"
    FIVE_MINUTES = "5m"
    FIFTEEN_MINUTES = "15m"
    HOUR = "1h"
    FOUR_HOURS = "4h"
    DAY = "1d"
    WEEK = "1w"
    MONTH = "1mo"


RESOLUTION_INTERVALS: Dict[str, int] = {
    ResolutionLevel.RAW: 1,
    ResolutionLevel.MINUTE: 60,
    ResolutionLevel.FIVE_MINUTES: 300,
    ResolutionLevel.FIFTEEN_MINUTES: 900,
    ResolutionLevel.HOUR: 3600,
    ResolutionLevel.FOUR_HOURS: 14400,
    ResolutionLevel.DAY: 86400,
    ResolutionLevel.WEEK: 604800,
    ResolutionLevel.MONTH: 2592000,
}


class MultiResolutionStorage:
    def __init__(
        self,
        settings: Settings,
        compressor: Optional[TimeSeriesCompressor] = None,
        downsampler: Optional[DownsamplingEngine] = None,
        cache: Optional[RedisCache] = None,
    ) -> None:
        self._settings = settings
        self._compressor = compressor or TimeSeriesCompressor()
        self._downsampler = downsampler or DownsamplingEngine()
        self._cache = cache
        self._logger = LogManager().get_logger(__name__)
        self._storage: Dict[str, Dict[str, TimeSeriesData]] = {}
        self._resolution_hierarchy = [
            ResolutionLevel.RAW,
            ResolutionLevel.MINUTE,
            ResolutionLevel.FIVE_MINUTES,
            ResolutionLevel.FIFTEEN_MINUTES,
            ResolutionLevel.HOUR,
            ResolutionLevel.FOUR_HOURS,
            ResolutionLevel.DAY,
            ResolutionLevel.WEEK,
            ResolutionLevel.MONTH,
        ]

    async def store(
        self,
        metric_name: str,
        data_points: List[TimeSeriesDataPoint],
        compress: bool = True,
    ) -> Dict[str, TimeSeriesData]:
        self._logger.info(
            f"Storing {len(data_points)} data points for metric: {metric_name}"
        )

        results: Dict[str, TimeSeriesData] = {}

        raw_data = TimeSeriesData(
            id=uuid4(),
            metric_name=metric_name,
            data_points=data_points.copy(),
            resolution=ResolutionLevel.RAW,
            original_size=len(data_points),
        )

        if compress:
            algo = await self._compressor.auto_select_algorithm(data_points)
            comp_result = await self._compressor.compress(data_points, algo)
            raw_data.compression_algorithm = algo.value
            raw_data.compressed_size = comp_result.compressed_size

        results[ResolutionLevel.RAW] = raw_data
        await self._store_in_cache(metric_name, ResolutionLevel.RAW, raw_data)

        for resolution in self._resolution_hierarchy[1:]:
            target_points = max(2, len(data_points) // RESOLUTION_INTERVALS[resolution])
            if target_points < 2:
                break

            downsampled = await self._downsampler.downsample(
                data_points,
                target_points,
                DownsamplingMethod.AVERAGE,
            )

            if len(downsampled) < 2:
                break

            downsampled_data = TimeSeriesData(
                id=uuid4(),
                metric_name=metric_name,
                data_points=downsampled,
                resolution=resolution,
                original_size=len(downsampled),
            )

            if compress:
                algo = await self._compressor.auto_select_algorithm(downsampled)
                comp_result = await self._compressor.compress(downsampled, algo)
                downsampled_data.compression_algorithm = algo.value
                downsampled_data.compressed_size = comp_result.compressed_size

            results[resolution] = downsampled_data
            await self._store_in_cache(metric_name, resolution, downsampled_data)

            data_points = downsampled

        if metric_name not in self._storage:
            self._storage[metric_name] = {}

        self._storage[metric_name].update(results)

        self._logger.info(
            f"Stored data for {metric_name} at {len(results)} resolution levels"
        )

        return results

    async def query(
        self,
        metric_name: str,
        start_time: Optional[datetime] = None,
        end_time: Optional[datetime] = None,
        resolution: Optional[str] = None,
    ) -> TimeSeriesData:
        self._logger.debug(
            f"Querying metric: {metric_name}",
            resolution=resolution,
            start_time=start_time,
            end_time=end_time,
        )

        if metric_name not in self._storage:
            return TimeSeriesData(
                id=uuid4(),
                metric_name=metric_name,
                data_points=[],
                resolution=resolution or ResolutionLevel.RAW,
            )

        if resolution is None:
            resolution = self._select_optimal_resolution(
                start_time, end_time, self._storage[metric_name].keys()
            )

        data = self._storage[metric_name].get(resolution)
        if data is None:
            available = list(self._storage[metric_name].keys())
            resolution = available[-1] if available else ResolutionLevel.RAW
            data = self._storage[metric_name].get(
                resolution,
                TimeSeriesData(
                    id=uuid4(),
                    metric_name=metric_name,
                    data_points=[],
                    resolution=resolution,
                ),
            )

        filtered_points = data.data_points
        if start_time:
            filtered_points = [dp for dp in filtered_points if dp.timestamp >= start_time]
        if end_time:
            filtered_points = [dp for dp in filtered_points if dp.timestamp <= end_time]

        return TimeSeriesData(
            id=data.id,
            metric_name=data.metric_name,
            data_points=filtered_points,
            resolution=data.resolution,
            compression_algorithm=data.compression_algorithm,
            original_size=data.original_size,
            compressed_size=data.compressed_size,
            created_at=data.created_at,
            updated_at=data.updated_at,
        )

    async def get_available_resolutions(self, metric_name: str) -> List[str]:
        if metric_name not in self._storage:
            return []
        return sorted(
            self._storage[metric_name].keys(),
            key=lambda r: RESOLUTION_INTERVALS.get(r, 0),
        )

    async def delete_metric(self, metric_name: str) -> bool:
        if metric_name in self._storage:
            del self._storage[metric_name]

            if self._cache is not None:
                pattern = f"timeseries:{metric_name}:*"
                await self._cache.clear_pattern(pattern)

            self._logger.info(f"Deleted metric: {metric_name}")
            return True

        return False

    async def list_metrics(self) -> List[str]:
        return list(self._storage.keys())

    async def get_metric_stats(self, metric_name: str) -> Dict[str, Any]:
        if metric_name not in self._storage:
            return {}

        stats: Dict[str, Any] = {
            "metric_name": metric_name,
            "resolutions": {},
            "total_data_points": 0,
            "total_original_size": 0,
            "total_compressed_size": 0,
        }

        for resolution, data in self._storage[metric_name].items():
            values = [dp.value for dp in data.data_points if isinstance(dp.value, (int, float))]

            if values:
                arr = np.array(values)
                resolution_stats = {
                    "data_points": len(data.data_points),
                    "time_range": {
                        "start": data.data_points[0].timestamp.isoformat() if data.data_points else None,
                        "end": data.data_points[-1].timestamp.isoformat() if data.data_points else None,
                    },
                    "min": float(np.min(arr)),
                    "max": float(np.max(arr)),
                    "avg": float(np.mean(arr)),
                    "std": float(np.std(arr)),
                    "original_size": data.original_size,
                    "compressed_size": data.compressed_size,
                    "compression_algorithm": data.compression_algorithm,
                }

                stats["resolutions"][resolution] = resolution_stats
                stats["total_data_points"] += len(data.data_points)
                stats["total_original_size"] += data.original_size
                stats["total_compressed_size"] += data.compressed_size

        if stats["total_original_size"] > 0:
            stats["overall_compression_ratio"] = (
                stats["total_original_size"] / stats["total_compressed_size"]
                if stats["total_compressed_size"] > 0
                else 1.0
            )

        return stats

    def _select_optimal_resolution(
        self,
        start_time: Optional[datetime],
        end_time: Optional[datetime],
        available_resolutions: List[str],
    ) -> str:
        if not start_time or not end_time:
            return ResolutionLevel.RAW

        time_range = (end_time - start_time).total_seconds()

        for resolution in self._resolution_hierarchy:
            if resolution in available_resolutions:
                interval = RESOLUTION_INTERVALS.get(resolution, 1)
                estimated_points = time_range / interval

                if estimated_points < 1000:
                    return resolution

        return available_resolutions[-1] if available_resolutions else ResolutionLevel.RAW

    async def _store_in_cache(
        self,
        metric_name: str,
        resolution: str,
        data: TimeSeriesData,
    ) -> None:
        if self._cache is None:
            return

        cache_key = f"timeseries:{metric_name}:{resolution}"
        try:
            await self._cache.set(
                cache_key,
                {
                    "metric_name": data.metric_name,
                    "resolution": data.resolution,
                    "data_points": [
                        {
                            "timestamp": dp.timestamp.isoformat(),
                            "value": dp.value,
                            "tags": dp.tags,
                        }
                        for dp in data.data_points
                    ],
                },
                ttl=3600,
            )
        except Exception as e:
            self._logger.warning(f"Failed to cache timeseries data: {e}")

    async def rollup(
        self,
        metric_name: str,
        source_resolution: str,
        target_resolution: str,
        method: DownsamplingMethod = DownsamplingMethod.AVERAGE,
    ) -> Optional[TimeSeriesData]:
        if metric_name not in self._storage:
            return None

        source_data = self._storage[metric_name].get(source_resolution)
        if source_data is None:
            return None

        target_points = max(
            2,
            len(source_data.data_points) * RESOLUTION_INTERVALS.get(source_resolution, 1)
            // RESOLUTION_INTERVALS.get(target_resolution, 1),
        )

        downsampled = await self._downsampler.downsample(
            source_data.data_points,
            target_points,
            method,
        )

        result = TimeSeriesData(
            id=uuid4(),
            metric_name=metric_name,
            data_points=downsampled,
            resolution=target_resolution,
            original_size=len(downsampled),
        )

        if metric_name not in self._storage:
            self._storage[metric_name] = {}

        self._storage[metric_name][target_resolution] = result
        await self._store_in_cache(metric_name, target_resolution, result)

        return result

    async def merge(
        self,
        metric_name: str,
        additional_points: List[TimeSeriesDataPoint],
    ) -> Dict[str, TimeSeriesData]:
        existing = await self.query(metric_name)

        merged = existing.data_points + additional_points
        merged.sort(key=lambda dp: dp.timestamp)

        return await self.store(metric_name, merged)
