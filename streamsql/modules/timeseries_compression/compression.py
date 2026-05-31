from __future__ import annotations

import json
from dataclasses import dataclass, field
from typing import Any, Optional

from streamsql.core.context import ProcessingContext
from streamsql.core.events import Event, EventBus, EventType
from streamsql.core.models import generate_id
from streamsql.modules.timeseries_compression.downsampler import (
    DownsamplerFactory,
    TimeSeriesDownsampler,
)
from streamsql.modules.timeseries_compression.encoder import (
    EncodedData,
    EncoderFactory,
    TimeSeriesEncoder,
)
from streamsql.modules.timeseries_compression.multi_resolution import (
    MultiResolutionStorage,
    ResolutionLevel,
)


@dataclass
class CompressedTimeSeries:
    series_id: str = field(default_factory=lambda: generate_id("ts"))
    name: str = ""
    timestamps: list[int] = field(default_factory=list)
    values: list[float] = field(default_factory=list)
    encoded_data: Optional[EncodedData] = None
    multi_resolution: Optional[MultiResolutionStorage] = None
    metadata: dict[str, Any] = field(default_factory=dict)

    def add(self, timestamp: int, value: float) -> None:
        self.timestamps.append(timestamp)
        self.values.append(value)
        if self.multi_resolution:
            self.multi_resolution.add(timestamp, value)

    def add_batch(self, timestamps: list[int], values: list[float]) -> None:
        self.timestamps.extend(timestamps)
        self.values.extend(values)
        if self.multi_resolution:
            self.multi_resolution.add_batch(timestamps, values)

    def count(self) -> int:
        return len(self.timestamps)

    def get_original_size(self) -> int:
        return len(self.timestamps) * 16

    def get_compressed_size(self) -> int:
        if self.encoded_data:
            return self.encoded_data.compressed_size
        return self.get_original_size()

    def get_compression_ratio(self) -> float:
        if self.encoded_data and self.encoded_data.compressed_size > 0:
            return self.encoded_data.compression_ratio
        return 1.0

    def to_dict(self) -> dict[str, Any]:
        return {
            "series_id": self.series_id,
            "name": self.name,
            "count": self.count(),
            "original_size": self.get_original_size(),
            "compressed_size": self.get_compressed_size(),
            "compression_ratio": self.get_compression_ratio(),
            "encoding": self.encoded_data.to_dict() if self.encoded_data else None,
            "multi_resolution": self.multi_resolution.get_summary() if self.multi_resolution else None,
            "time_range": {
                "start": self.timestamps[0] if self.timestamps else None,
                "end": self.timestamps[-1] if self.timestamps else None,
            },
        }


class TimeSeriesCompressor:
    def __init__(
        self,
        context: Optional[ProcessingContext] = None,
        default_encoder: str = "gorilla",
        default_downsampler: str = "lttb",
        enable_multi_resolution: bool = True,
    ):
        self.context = context or ProcessingContext(trace_id=generate_id("trace"))
        self.event_bus = EventBus()
        self.default_encoder = default_encoder
        self.default_downsampler = default_downsampler
        self.enable_multi_resolution = enable_multi_resolution

    def create_series(self, name: str) -> CompressedTimeSeries:
        series = CompressedTimeSeries(name=name)
        if self.enable_multi_resolution:
            series.multi_resolution = MultiResolutionStorage()
        return series

    def compress(
        self,
        series: CompressedTimeSeries,
        encoder_type: Optional[str] = None,
    ) -> EncodedData:
        self.event_bus.emit(
            Event(
                EventType.COMPRESSION_STARTED,
                {"series": series.name, "count": series.count()},
            )
        )

        try:
            encoder = EncoderFactory.create(encoder_type or self.default_encoder)
            encoded = encoder.encode(series.timestamps, series.values)
            series.encoded_data = encoded

            self.event_bus.emit(
                Event(
                    EventType.COMPRESSION_COMPLETED,
                    {
                        "series": series.name,
                        "original_size": encoded.original_size,
                        "compressed_size": encoded.compressed_size,
                        "ratio": encoded.compression_ratio,
                    },
                )
            )

            return encoded

        except Exception as e:
            self.event_bus.emit(
                Event(
                    EventType.COMPRESSION_FAILED,
                    {"series": series.name, "error": str(e)},
                )
            )
            raise

    def decompress(self, encoded: EncodedData) -> tuple[list[int], list[float]]:
        encoder = EncoderFactory.create(encoded.encoding_type)
        return encoder.decode(encoded)

    def downsample(
        self,
        series: CompressedTimeSeries,
        target_count: int,
        downsampler_type: Optional[str] = None,
    ) -> Any:
        downsampler = DownsamplerFactory.create(
            downsampler_type or self.default_downsampler
        )
        return downsampler.downsample(series.timestamps, series.values, target_count)

    def get_available_encoders(self) -> list[str]:
        return EncoderFactory.get_available_encoders()

    def get_available_downsamplers(self) -> list[str]:
        return DownsamplerFactory.get_available_downsamplers()

    def get_optimal_encoder(self, series: CompressedTimeSeries) -> str:
        best_ratio = 0.0
        best_encoder = self.default_encoder

        for encoder_name in self.get_available_encoders():
            try:
                encoder = EncoderFactory.create(encoder_name)
                encoded = encoder.encode(
                    series.timestamps[: min(1000, len(series.timestamps))],
                    series.values[: min(1000, len(series.values))],
                )
                if encoded.compression_ratio > best_ratio:
                    best_ratio = encoded.compression_ratio
                    best_encoder = encoder_name
            except Exception:
                continue

        return best_encoder

    def compact_series(self, series: CompressedTimeSeries, current_time_ms: int) -> dict[str, Any]:
        if series.multi_resolution:
            return series.multi_resolution.compact(current_time_ms)
        return {"removed": {}}

    def query_series(
        self,
        series: CompressedTimeSeries,
        start_time: Optional[int] = None,
        end_time: Optional[int] = None,
        resolution: Optional[ResolutionLevel] = None,
    ) -> dict[str, Any]:
        if series.multi_resolution and resolution:
            data = series.multi_resolution.query(start_time, end_time, resolution)
            return {
                "resolution": resolution.value,
                "timestamps": data.timestamps,
                "values": data.values,
                "count": data.count(),
            }

        timestamps = series.timestamps
        values = series.values

        if start_time is not None:
            indices = [i for i, ts in enumerate(timestamps) if ts >= start_time]
            if indices:
                start_idx = indices[0]
                timestamps = timestamps[start_idx:]
                values = values[start_idx:]

        if end_time is not None:
            indices = [i for i, ts in enumerate(timestamps) if ts <= end_time]
            if indices:
                end_idx = indices[-1] + 1
                timestamps = timestamps[:end_idx]
                values = values[:end_idx]

        return {
            "resolution": "raw",
            "timestamps": timestamps,
            "values": values,
            "count": len(timestamps),
        }
