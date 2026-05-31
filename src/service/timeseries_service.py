import logging
from typing import Any, Dict, List, Optional

from src.domain.timeseries.compression import TimeseriesCompressor, CompressedBlock
from src.domain.timeseries.downsampling import DownsamplingEngine, DownsampledSeries, AggregationType
from src.domain.timeseries.multires import MultiResolutionStore
from src.infrastructure.config.settings import (
    TimeseriesCompressionConfig,
    DownsamplingConfig,
    MultiresConfig,
)
from src.infrastructure.db.timeseries_store import TimeseriesStore

logger = logging.getLogger(__name__)


class TimeseriesService:
    def __init__(
        self,
        timeseries_store: Optional[TimeseriesStore] = None,
        compression_config: Optional[TimeseriesCompressionConfig] = None,
        downsampling_config: Optional[DownsamplingConfig] = None,
        multires_config: Optional[MultiresConfig] = None,
    ):
        self._store = timeseries_store
        self._compression_config = compression_config or TimeseriesCompressionConfig()
        self._downsampling_config = downsampling_config or DownsamplingConfig()
        self._multires_config = multires_config or MultiresConfig()

        self._compressor = TimeseriesCompressor(self._compression_config)
        self._downsampler = DownsamplingEngine(self._downsampling_config)
        self._multires_store = MultiResolutionStore(
            timeseries_store=timeseries_store,
            compressor=self._compressor,
            downsampler=self._downsampler,
            multires_config=self._multires_config,
        )

    def ingest(
        self,
        metric_name: str,
        timestamps: List[int],
        values: List[float],
        tags: Optional[Dict[str, str]] = None,
    ) -> Dict[str, Any]:
        result = self._multires_store.ingest(metric_name, timestamps, values, tags)

        resolution_info = {}
        for interval, series in result.resolutions.items():
            resolution_info[interval] = {
                "point_count": len(series.points),
                "interval": series.interval,
                "aggregation": series.aggregation,
            }

        compressed_info = {}
        for interval, block in result.compressed_blocks.items():
            compressed_info[interval] = {
                "original_size": block.original_size,
                "compressed_size": block.compressed_size,
                "compression_ratio": round(block.compression_ratio, 2),
                "algorithm": block.algorithm,
            }

        return {
            "metric_name": metric_name,
            "ingested_points": len(timestamps),
            "resolutions": resolution_info,
            "compressed_blocks": compressed_info,
        }

    def compress(
        self,
        metric_name: str,
        timestamps: List[int],
        values: List[float],
    ) -> Dict[str, Any]:
        block = self._compressor.compress(metric_name, timestamps, values)
        return {
            "metric_name": block.metric_name,
            "start_timestamp": block.start_timestamp,
            "end_timestamp": block.end_timestamp,
            "algorithm": block.algorithm,
            "original_size": block.original_size,
            "compressed_size": block.compressed_size,
            "compression_ratio": round(block.compression_ratio, 2),
            "point_count": block.point_count,
        }

    def decompress(self, compressed_data: bytes, algorithm: str, start_ts: int, end_ts: int) -> Dict[str, Any]:
        block = CompressedBlock(
            metric_name="",
            start_timestamp=start_ts,
            end_timestamp=end_ts,
            algorithm=algorithm,
            data=compressed_data,
            original_size=0,
            compressed_size=len(compressed_data),
            point_count=0,
        )
        timestamps, values = self._compressor.decompress(block)
        return {
            "timestamps": timestamps,
            "values": values,
            "point_count": len(timestamps),
        }

    def downsample(
        self,
        metric_name: str,
        timestamps: List[int],
        values: List[float],
        interval: str,
        aggregation: Optional[str] = None,
    ) -> Dict[str, Any]:
        series = self._downsampler.downsample(metric_name, timestamps, values, interval, aggregation)
        return series.to_dict()

    def query(
        self,
        metric_name: str,
        start_ts: int,
        end_ts: int,
        resolution: Optional[str] = None,
        tags: Optional[Dict[str, str]] = None,
    ) -> List[Dict[str, Any]]:
        return self._multires_store.query(metric_name, start_ts, end_ts, resolution, tags)

    def write_point(
        self,
        metric_name: str,
        timestamp: int,
        value: float,
        tags: Optional[Dict[str, str]] = None,
    ) -> None:
        if self._store:
            self._store.write_point(metric_name, timestamp, value, tags)

    def write_points(self, points: List[Dict[str, Any]]) -> None:
        if self._store:
            self._store.write_points(points)

    def get_retention_info(self) -> List[Dict[str, Any]]:
        return self._multires_store.get_retention_info()

    def cleanup_expired(self, current_timestamp: int) -> Dict[str, int]:
        return self._multires_store.cleanup_expired(current_timestamp)

    def get_compression_stats(self) -> Dict[str, Any]:
        return self._multires_store.get_compression_stats()
