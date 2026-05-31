"""Time series data compression module."""
from .compression import TimeSeriesCompressor
from .downsampling import DownsamplingEngine
from .multires import MultiResolutionStorage
from .timeseries_module import TimeSeriesModule

__all__ = [
    "TimeSeriesCompressor",
    "DownsamplingEngine",
    "MultiResolutionStorage",
    "TimeSeriesModule",
]
