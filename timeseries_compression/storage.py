from dataclasses import dataclass, field
from typing import Optional, Dict, List, Tuple, Any, Callable
from enum import Enum
import time
from collections import defaultdict
import numpy as np

from .series import TimeSeries
from .codec import Codec, AutoCodec, CompressionStats
from .downsampler import (
    Downsampler,
    MeanDownsampler,
    LTTBDownsampler,
    M4Downsampler,
)


class StorageTier(str, Enum):
    HOT = "hot"
    WARM = "warm"
    COLD = "cold"
    ARCHIVE = "archive"


@dataclass
class ResolutionLevel:
    name: str
    target_interval: float
    downsampler: Downsampler = field(default_factory=MeanDownsampler)
    retention_seconds: float = float("inf")
    codec: Codec = field(default_factory=AutoCodec)


@dataclass
class TimeShard:
    start_time: float
    end_time: float
    series_by_resolution: Dict[str, TimeSeries] = field(default_factory=dict)
    compressed_data: Dict[str, bytes] = field(default_factory=dict)
    stats: Dict[str, CompressionStats] = field(default_factory=dict)
    tier: StorageTier = StorageTier.HOT
    created_at: float = field(default_factory=time.time)
    last_accessed: float = field(default_factory=time.time)

    @property
    def duration(self) -> float:
        return self.end_time - self.start_time

    @property
    def total_size_bytes(self) -> int:
        return sum(len(data) for data in self.compressed_data.values())


class MultiResolutionStorage:
    def __init__(
        self,
        resolutions: Optional[List[ResolutionLevel]] = None,
        shard_interval_seconds: float = 3600,
        auto_downsample: bool = True,
        hot_threshold_seconds: float = 86400,
        warm_threshold_seconds: float = 604800,
        cold_threshold_seconds: float = 2592000,
    ):
        if resolutions is None:
            resolutions = [
                ResolutionLevel("raw", 1.0, MeanDownsampler(), float("inf"), AutoCodec()),
                ResolutionLevel("1min", 60.0, MeanDownsampler(), 86400 * 7, AutoCodec()),
                ResolutionLevel("5min", 300.0, LTTBDownsampler(), 86400 * 30, AutoCodec()),
                ResolutionLevel("1hour", 3600.0, M4Downsampler(), 86400 * 365, AutoCodec()),
                ResolutionLevel("1day", 86400.0, MeanDownsampler(), float("inf"), AutoCodec()),
            ]

        self.resolutions = {r.name: r for r in resolutions}
        self.shard_interval_seconds = shard_interval_seconds
        self.auto_downsample = auto_downsample
        self.hot_threshold = hot_threshold_seconds
        self.warm_threshold = warm_threshold_seconds
        self.cold_threshold = cold_threshold_seconds

        self.shards: Dict[str, TimeShard] = {}
        self._shard_order: List[str] = []

    def write(self, series: TimeSeries) -> List[str]:
        shard_keys = []

        chunks = series.split_by_time(self.shard_interval_seconds)

        for chunk in chunks:
            shard_key = self._get_shard_key(chunk.start_time)
            if shard_key not in self.shards:
                self._create_shard(shard_key, chunk.start_time, chunk.end_time)
                self._shard_order.append(shard_key)

            shard = self.shards[shard_key]
            shard.end_time = max(shard.end_time, chunk.end_time)
            shard.series_by_resolution["raw"] = self._merge_series(
                shard.series_by_resolution.get("raw"), chunk
            )
            shard.last_accessed = time.time()

            if self.auto_downsample:
                self._downsample_shard(shard)

            self._update_shard_tier(shard)
            shard_keys.append(shard_key)

        return shard_keys

    def read(
        self,
        start_time: Optional[float] = None,
        end_time: Optional[float] = None,
        resolution: Optional[str] = None,
    ) -> Optional[TimeSeries]:
        shards = self._get_shards_in_range(start_time, end_time)
        if not shards:
            return None

        if resolution is None:
            resolution = self._select_optimal_resolution(start_time, end_time)

        series_parts = []
        for shard in shards:
            shard.last_accessed = time.time()
            self._update_shard_tier(shard)

            if resolution not in shard.series_by_resolution:
                self._ensure_resolution(shard, resolution)

            series = shard.series_by_resolution.get(resolution)
            if series is not None:
                if start_time is not None or end_time is not None:
                    series = series.slice(start_time, end_time)
                if len(series) > 0:
                    series_parts.append(series)

        if not series_parts:
            return None

        return self._merge_series_list(series_parts)

    def compress_shard(self, shard_key: str, resolution: Optional[str] = None) -> Dict[str, CompressionStats]:
        shard = self.shards.get(shard_key)
        if shard is None:
            raise KeyError(f"Shard not found: {shard_key}")

        resolutions_to_compress = (
            [resolution] if resolution else list(shard.series_by_resolution.keys())
        )

        stats = {}
        for res_name in resolutions_to_compress:
            if res_name not in shard.series_by_resolution:
                continue

            series = shard.series_by_resolution[res_name]
            res_level = self.resolutions.get(res_name, self.resolutions["raw"])

            compressed, comp_stats = res_level.codec.encode_with_stats(series.values)
            shard.compressed_data[res_name] = compressed
            shard.stats[res_name] = comp_stats
            stats[res_name] = comp_stats

        return stats

    def decompress_shard(self, shard_key: str, resolution: str) -> Optional[np.ndarray]:
        shard = self.shards.get(shard_key)
        if shard is None:
            return None

        if resolution not in shard.compressed_data:
            return None

        res_level = self.resolutions.get(resolution, self.resolutions["raw"])
        return res_level.codec.decode(shard.compressed_data[resolution])

    def get_shard_info(self) -> List[Dict[str, Any]]:
        info = []
        for key in self._shard_order:
            shard = self.shards[key]
            info.append({
                "key": key,
                "start_time": shard.start_time,
                "end_time": shard.end_time,
                "duration": shard.duration,
                "tier": shard.tier.value,
                "resolutions": list(shard.series_by_resolution.keys()),
                "compressed_size_bytes": shard.total_size_bytes,
                "created_at": shard.created_at,
                "last_accessed": shard.last_accessed,
            })
        return info

    def cleanup_expired(self) -> List[str]:
        now = time.time()
        removed_keys = []

        for res_name, res_level in self.resolutions.items():
            if res_level.retention_seconds == float("inf"):
                continue

            for key in list(self._shard_order):
                shard = self.shards[key]
                age = now - shard.end_time

                if age > res_level.retention_seconds:
                    if res_name in shard.series_by_resolution:
                        del shard.series_by_resolution[res_name]
                    if res_name in shard.compressed_data:
                        del shard.compressed_data[res_name]
                    if res_name in shard.stats:
                        del shard.stats[res_name]

                    if not shard.series_by_resolution:
                        del self.shards[key]
                        self._shard_order.remove(key)
                        removed_keys.append(key)

        return removed_keys

    def _get_shard_key(self, timestamp: float) -> str:
        shard_start = int(timestamp // self.shard_interval_seconds) * self.shard_interval_seconds
        return f"shard_{int(shard_start)}"

    def _create_shard(self, key: str, start_time: float, end_time: float) -> TimeShard:
        shard = TimeShard(
            start_time=start_time,
            end_time=end_time,
            tier=self._determine_tier(end_time),
        )
        self.shards[key] = shard
        return shard

    def _get_shards_in_range(
        self, start_time: Optional[float], end_time: Optional[float]
    ) -> List[TimeShard]:
        shards = []
        for key in self._shard_order:
            shard = self.shards[key]
            if start_time is not None and shard.end_time < start_time:
                continue
            if end_time is not None and shard.start_time > end_time:
                continue
            shards.append(shard)
        return shards

    def _downsample_shard(self, shard: TimeShard) -> None:
        raw_series = shard.series_by_resolution.get("raw")
        if raw_series is None:
            return

        for res_name, res_level in self.resolutions.items():
            if res_name == "raw":
                continue

            target_size = max(10, int(shard.duration / res_level.target_interval))
            if len(raw_series) <= target_size:
                shard.series_by_resolution[res_name] = raw_series
                continue

            ds_series = raw_series.downsample(res_level.downsampler, target_size)
            shard.series_by_resolution[res_name] = ds_series

    def _ensure_resolution(self, shard: TimeShard, resolution: str) -> None:
        if resolution in shard.series_by_resolution:
            return

        if resolution not in self.resolutions:
            return

        res_level = self.resolutions[resolution]
        raw_series = shard.series_by_resolution.get("raw")

        if raw_series is None:
            return

        target_size = max(10, int(shard.duration / res_level.target_interval))
        ds_series = raw_series.downsample(res_level.downsampler, target_size)
        shard.series_by_resolution[resolution] = ds_series

    def _select_optimal_resolution(
        self, start_time: Optional[float], end_time: Optional[float]
    ) -> str:
        if start_time is None or end_time is None:
            return "raw"

        duration = end_time - start_time
        target_points = 1000
        desired_interval = duration / target_points

        best_res = "raw"
        best_interval = float("inf")

        for res_name, res_level in self.resolutions.items():
            if res_level.target_interval >= desired_interval and res_level.target_interval < best_interval:
                best_interval = res_level.target_interval
                best_res = res_name

        return best_res

    def _determine_tier(self, data_end_time: float) -> StorageTier:
        age = time.time() - data_end_time

        if age <= self.hot_threshold:
            return StorageTier.HOT
        elif age <= self.warm_threshold:
            return StorageTier.WARM
        elif age <= self.cold_threshold:
            return StorageTier.COLD
        else:
            return StorageTier.ARCHIVE

    def _update_shard_tier(self, shard: TimeShard) -> None:
        shard.tier = self._determine_tier(shard.end_time)

    def _merge_series(self, existing: Optional[TimeSeries], new: TimeSeries) -> TimeSeries:
        if existing is None or len(existing) == 0:
            return new

        combined_ts = np.concatenate([existing.timestamps, new.timestamps])
        combined_vals = np.concatenate([existing.values, new.values])

        sort_idx = np.argsort(combined_ts)
        combined_ts = combined_ts[sort_idx]
        combined_vals = combined_vals[sort_idx]

        _, unique_idx = np.unique(combined_ts, return_index=True)

        return TimeSeries(
            timestamps=combined_ts[unique_idx],
            values=combined_vals[unique_idx],
            name=existing.name or new.name,
            metadata={**existing.metadata, **new.metadata},
        )

    def _merge_series_list(self, series_list: List[TimeSeries]) -> TimeSeries:
        if len(series_list) == 1:
            return series_list[0]

        combined_ts = np.concatenate([s.timestamps for s in series_list])
        combined_vals = np.concatenate([s.values for s in series_list])

        sort_idx = np.argsort(combined_ts)
        combined_ts = combined_ts[sort_idx]
        combined_vals = combined_vals[sort_idx]

        _, unique_idx = np.unique(combined_ts, return_index=True)

        return TimeSeries(
            timestamps=combined_ts[unique_idx],
            values=combined_vals[unique_idx],
            name=series_list[0].name,
        )

    def get_storage_summary(self) -> Dict[str, Any]:
        tier_counts: Dict[str, int] = defaultdict(int)
        tier_sizes: Dict[str, int] = defaultdict(int)
        resolution_counts: Dict[str, int] = defaultdict(int)
        resolution_sizes: Dict[str, int] = defaultdict(int)

        total_uncompressed = 0
        total_compressed = 0

        for shard in self.shards.values():
            tier_counts[shard.tier.value] += 1
            tier_sizes[shard.tier.value] += shard.total_size_bytes

            for res_name, series in shard.series_by_resolution.items():
                resolution_counts[res_name] += 1
                total_uncompressed += series.values.nbytes

            for res_name, data in shard.compressed_data.items():
                resolution_sizes[res_name] += len(data)
                total_compressed += len(data)

        compression_ratio = (
            total_compressed / total_uncompressed if total_uncompressed > 0 else 1.0
        )

        return {
            "total_shards": len(self.shards),
            "tier_distribution": dict(tier_counts),
            "tier_sizes_bytes": dict(tier_sizes),
            "resolution_distribution": dict(resolution_counts),
            "resolution_sizes_bytes": dict(resolution_sizes),
            "total_uncompressed_bytes": total_uncompressed,
            "total_compressed_bytes": total_compressed,
            "compression_ratio": compression_ratio,
            "space_saving": 1.0 - compression_ratio if total_uncompressed > 0 else 0.0,
        }
