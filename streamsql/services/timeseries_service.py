from __future__ import annotations

from typing import Any, Optional

from streamsql.core.config import ConfigManager
from streamsql.core.context import ProcessingContext
from streamsql.core.events import EventBus
from streamsql.modules.timeseries_compression.compression import TimeSeriesCompressor
from streamsql.modules.timeseries_compression.multi_resolution import ResolutionLevel


class TimeSeriesService:
    def __init__(self, config_manager: Optional[ConfigManager] = None):
        self.config_manager = config_manager or ConfigManager()
        self.event_bus = EventBus()
        self.compressor = TimeSeriesCompressor()
        self._series: dict[str, Any] = {}

    def create_series(self, name: str) -> dict[str, Any]:
        series = self.compressor.create_series(name)
        self._series[name] = series
        return {
            "series_id": series.series_id,
            "name": name,
            "multi_resolution_enabled": series.multi_resolution is not None,
        }

    def add_data_point(
        self,
        series_name: str,
        timestamp: int,
        value: float,
    ) -> dict[str, Any]:
        if series_name not in self._series:
            self.create_series(series_name)

        series = self._series[series_name]
        series.add(timestamp, value)

        return {
            "series": series_name,
            "timestamp": timestamp,
            "value": value,
            "total_points": series.count(),
        }

    def add_data_batch(
        self,
        series_name: str,
        timestamps: list[int],
        values: list[float],
    ) -> dict[str, Any]:
        if series_name not in self._series:
            self.create_series(series_name)

        series = self._series[series_name]
        series.add_batch(timestamps, values)

        return {
            "series": series_name,
            "added_count": len(timestamps),
            "total_points": series.count(),
        }

    def compress(
        self,
        series_name: str,
        encoder_type: str = "gorilla",
    ) -> dict[str, Any]:
        if series_name not in self._series:
            raise ValueError(f"Series {series_name} not found")

        series = self._series[series_name]
        encoded = self.compressor.compress(series, encoder_type)

        return {
            "series": series_name,
            "encoder": encoder_type,
            "original_size_bytes": encoded.original_size,
            "compressed_size_bytes": encoded.compressed_size,
            "compression_ratio": encoded.compression_ratio,
            "saved_bytes": encoded.original_size - encoded.compressed_size,
        }

    def decompress(
        self,
        series_name: str,
    ) -> dict[str, Any]:
        if series_name not in self._series:
            raise ValueError(f"Series {series_name} not found")

        series = self._series[series_name]
        if not series.encoded_data:
            return {
                "series": series_name,
                "timestamps": series.timestamps,
                "values": series.values,
                "count": series.count(),
            }

        timestamps, values = self.compressor.decompress(series.encoded_data)
        return {
            "series": series_name,
            "timestamps": timestamps,
            "values": values,
            "count": len(timestamps),
        }

    def downsample(
        self,
        series_name: str,
        target_count: int,
        method: str = "lttb",
    ) -> dict[str, Any]:
        if series_name not in self._series:
            raise ValueError(f"Series {series_name} not found")

        series = self._series[series_name]
        result = self.compressor.downsample(series, target_count, method)

        return {
            "series": series_name,
            "method": method,
            "original_count": result.original_count,
            "downsampled_count": result.downsampled_count,
            "ratio": result.ratio,
            "timestamps": result.timestamps,
            "values": result.values,
        }

    def query(
        self,
        series_name: str,
        start_time: Optional[int] = None,
        end_time: Optional[int] = None,
        resolution: str = "raw",
    ) -> dict[str, Any]:
        if series_name not in self._series:
            raise ValueError(f"Series {series_name} not found")

        series = self._series[series_name]
        res_level = ResolutionLevel(resolution) if resolution != "raw" else None

        result = self.compressor.query_series(series, start_time, end_time, res_level)
        return result

    def get_optimal_encoder(self, series_name: str) -> dict[str, Any]:
        if series_name not in self._series:
            raise ValueError(f"Series {series_name} not found")

        series = self._series[series_name]
        optimal = self.compressor.get_optimal_encoder(series)
        return {
            "series": series_name,
            "optimal_encoder": optimal,
            "available_encoders": self.compressor.get_available_encoders(),
        }

    def compact(
        self,
        series_name: str,
        current_time_ms: Optional[int] = None,
    ) -> dict[str, Any]:
        import time

        if series_name not in self._series:
            raise ValueError(f"Series {series_name} not found")

        series = self._series[series_name]
        current = current_time_ms or int(time.time() * 1000)
        result = self.compressor.compact_series(series, current)
        return result

    def get_series_info(self, series_name: str) -> dict[str, Any]:
        if series_name not in self._series:
            raise ValueError(f"Series {series_name} not found")

        series = self._series[series_name]
        return series.to_dict()

    def list_series(self) -> list[str]:
        return list(self._series.keys())

    def get_available_encoders(self) -> list[str]:
        return self.compressor.get_available_encoders()

    def get_available_downsamplers(self) -> list[str]:
        return self.compressor.get_available_downsamplers()
