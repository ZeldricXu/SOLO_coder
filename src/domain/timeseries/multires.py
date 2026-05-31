import logging
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional, Tuple

from src.domain.timeseries.compression import TimeseriesCompressor, CompressedBlock
from src.domain.timeseries.downsampling import DownsamplingEngine, DownsampledSeries
from src.infrastructure.config.settings import MultiresConfig, TimeseriesCompressionConfig, DownsamplingConfig
from src.infrastructure.db.timeseries_store import TimeseriesStore

logger = logging.getLogger(__name__)


@dataclass
class ResolutionLevel:
    interval: str
    retention: str
    aggregation: str = "avg"
    compression: str = "gorilla"


@dataclass
class MultiResolutionData:
    metric_name: str
    resolutions: Dict[str, DownsampledSeries] = field(default_factory=dict)
    compressed_blocks: Dict[str, CompressedBlock] = field(default_factory=dict)


def _parse_retention(retention_str: str) -> int:
    multipliers = {"s": 1, "m": 60, "h": 3600, "d": 86400, "w": 604800}
    retention_str = retention_str.strip().lower()
    for suffix, mult in sorted(multipliers.items(), key=lambda x: -len(x[0])):
        if retention_str.endswith(suffix):
            try:
                return int(retention_str[:-len(suffix)]) * mult
            except ValueError:
                break
    try:
        return int(retention_str)
    except ValueError:
        return 86400


class MultiResolutionStore:
    def __init__(
        self,
        timeseries_store: Optional[TimeseriesStore] = None,
        compressor: Optional[TimeseriesCompressor] = None,
        downsampler: Optional[DownsamplingEngine] = None,
        multires_config: Optional[MultiresConfig] = None,
        compression_config: Optional[TimeseriesCompressionConfig] = None,
        downsampling_config: Optional[DownsamplingConfig] = None,
    ):
        self._store = timeseries_store
        self._compressor = compressor or TimeseriesCompressor(compression_config or TimeseriesCompressionConfig())
        self._downsampler = downsampler or DownsamplingEngine(downsampling_config or DownsamplingConfig())
        self._resolutions: List[ResolutionLevel] = []
        self._config = multires_config or MultiresConfig()
        self._setup_resolutions()

    def _setup_resolutions(self) -> None:
        for res in self._config.resolutions:
            interval = res.get("interval", "1m")
            retention = res.get("retention", "30d")
            aggregation = res.get("aggregation", "avg")
            compression = res.get("compression", "gorilla")
            self._resolutions.append(ResolutionLevel(
                interval=interval,
                retention=retention,
                aggregation=aggregation,
                compression=compression,
            ))

        if not self._resolutions:
            self._resolutions = [
                ResolutionLevel(interval="1m", retention="7d", aggregation="avg"),
                ResolutionLevel(interval="5m", retention="30d", aggregation="avg"),
                ResolutionLevel(interval="1h", retention="90d", aggregation="avg"),
                ResolutionLevel(interval="1d", retention="365d", aggregation="avg"),
            ]

    def ingest(
        self,
        metric_name: str,
        timestamps: List[int],
        values: List[float],
        tags: Optional[Dict[str, str]] = None,
    ) -> MultiResolutionData:
        result = MultiResolutionData(metric_name=metric_name)

        if self._store:
            for ts, val in zip(timestamps, values):
                self._store.write_point(metric_name, ts, val, tags, "raw")

        for level in self._resolutions:
            series = self._downsampler.downsample(
                metric_name, timestamps, values,
                level.interval, level.aggregation,
            )
            result.resolutions[level.interval] = series

            if self._store:
                for point in series.points:
                    self._store.write_point(
                        metric_name, point.timestamp, point.value,
                        tags, level.interval,
                    )

            if level.compression and len(timestamps) > 10:
                compressed = self._compressor.compress(
                    metric_name,
                    [p.timestamp for p in series.points],
                    [p.value for p in series.points],
                )
                result.compressed_blocks[level.interval] = compressed

                if self._store:
                    self._store.save_compressed_block(
                        metric_name=metric_name,
                        resolution=level.interval,
                        start_timestamp=compressed.start_timestamp,
                        end_timestamp=compressed.end_timestamp,
                        compression_algo=compressed.algorithm,
                        data=compressed.data,
                        point_count=compressed.point_count,
                    )

        return result

    def query(
        self,
        metric_name: str,
        start_ts: int,
        end_ts: int,
        target_resolution: Optional[str] = None,
        tags: Optional[Dict[str, str]] = None,
    ) -> List[Dict[str, Any]]:
        resolution = target_resolution or self._select_resolution(start_ts, end_ts)

        if self._store:
            raw_data = self._store.query_range(
                metric_name, start_ts, end_ts, resolution, tags,
            )
            if raw_data:
                return raw_data

        compressed_blocks = []
        if self._store:
            compressed_blocks = self._store.get_compressed_blocks(
                metric_name, resolution, start_ts, end_ts,
            )

        all_points = []
        for block_info in compressed_blocks:
            try:
                block = CompressedBlock(
                    metric_name=metric_name,
                    start_timestamp=block_info["start_timestamp"],
                    end_timestamp=block_info["end_timestamp"],
                    algorithm=block_info["compression_algo"],
                    data=block_info["data"],
                    original_size=0,
                    compressed_size=len(block_info["data"]),
                    point_count=block_info["point_count"],
                )
                timestamps, values = self._compressor.decompress(block)
                for ts, val in zip(timestamps, values):
                    if start_ts <= ts <= end_ts:
                        all_points.append({
                            "metric_name": metric_name,
                            "timestamp": ts,
                            "value": val,
                            "resolution": resolution,
                        })
            except Exception as e:
                logger.error(f"Failed to decompress block: {e}")

        return sorted(all_points, key=lambda p: p["timestamp"])

    def _select_resolution(self, start_ts: int, end_ts: int) -> str:
        time_range = end_ts - start_ts
        if time_range <= 3600:
            return self._resolutions[0].interval if self._resolutions else "1m"
        elif time_range <= 86400:
            return self._resolutions[min(1, len(self._resolutions) - 1)].interval if len(self._resolutions) > 1 else "5m"
        elif time_range <= 604800:
            return self._resolutions[min(2, len(self._resolutions) - 1)].interval if len(self._resolutions) > 2 else "1h"
        else:
            return self._resolutions[-1].interval if self._resolutions else "1d"

    def get_retention_info(self) -> List[Dict[str, Any]]:
        return [
            {
                "interval": level.interval,
                "retention": level.retention,
                "retention_seconds": _parse_retention(level.retention),
                "aggregation": level.aggregation,
                "compression": level.compression,
            }
            for level in self._resolutions
        ]

    def cleanup_expired(self, current_timestamp: int) -> Dict[str, int]:
        cleanup_stats = {}

        for level in self._resolutions:
            retention_seconds = _parse_retention(level.retention)
            cutoff_ts = current_timestamp - retention_seconds

            if self._store:
                deleted = self._store.delete_raw_data(
                    "", cutoff_ts, level.interval,
                )
                cleanup_stats[level.interval] = deleted
            else:
                cleanup_stats[level.interval] = 0

        return cleanup_stats

    def get_compression_stats(self) -> Dict[str, Any]:
        stats = {
            "resolutions": len(self._resolutions),
            "levels": [],
        }
        for level in self._resolutions:
            stats["levels"].append({
                "interval": level.interval,
                "retention": level.retention,
                "aggregation": level.aggregation,
            })
        return stats
