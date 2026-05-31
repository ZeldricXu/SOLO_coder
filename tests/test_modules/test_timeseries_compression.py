import pytest
import time
from streamsql.modules.timeseries_compression.encoder import (
    DeltaEncoder,
    GorillaEncoder,
    Simple8bEncoder,
)
from streamsql.modules.timeseries_compression.downsampler import (
    MeanDownsampler,
    MinMaxDownsampler,
    LTTBDownsampler,
)
from streamsql.modules.timeseries_compression.multi_resolution import (
    MultiResolutionStorage,
    ResolutionLevel,
)
from streamsql.modules.timeseries_compression.compression import TimeSeriesCompressor


def test_delta_encoder_encode_decode():
    encoder = DeltaEncoder()
    values = [100, 102, 105, 107, 110]
    encoded = encoder.encode(values)
    assert "original" in encoded
    assert "deltas" in encoded
    decoded = encoder.decode(encoded)
    assert decoded == values


def test_delta_encoder_compression_ratio():
    encoder = DeltaEncoder()
    values = list(range(1000, 2000))
    encoded = encoder.encode(values)
    ratio = encoder.compression_ratio(values, encoded)
    assert ratio > 0


def test_gorilla_encoder_encode_decode():
    encoder = GorillaEncoder()
    values = [10.5, 11.2, 12.8, 13.1, 14.5, 15.3, 16.7]
    encoded = encoder.encode(values)
    decoded = encoder.decode(encoded)
    assert len(decoded) == len(values)
    for original, decoded_val in zip(values, decoded):
        assert abs(original - decoded_val) < 0.001


def test_simple8b_encoder_encode_decode():
    encoder = Simple8bEncoder()
    values = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10]
    encoded = encoder.encode(values)
    decoded = encoder.decode(encoded)
    assert decoded == values


def test_mean_downsampler():
    downsampler = MeanDownsampler()
    timestamps = [
        1704067200 + i * 60 for i in range(10)
    ]
    values = [1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0, 10.0]
    data = [{"timestamp": t, "value": v} for t, v in zip(timestamps, values)]

    downsampled = downsampler.downsample(data, interval_seconds=300)
    assert len(downsampled) == 2
    assert downsampled[0]["value"] == 3.0
    assert downsampled[1]["value"] == 8.0


def test_minmax_downsampler():
    downsampler = MinMaxDownsampler()
    timestamps = [
        1704067200 + i * 60 for i in range(10)
    ]
    values = [1.0, 5.0, 3.0, 8.0, 2.0, 7.0, 4.0, 9.0, 6.0, 10.0]
    data = [{"timestamp": t, "value": v} for t, v in zip(timestamps, values)]

    downsampled = downsampler.downsample(data, interval_seconds=300)
    assert len(downsampled) == 2
    assert "min" in downsampled[0]
    assert "max" in downsampled[0]
    assert downsampled[0]["min"] == 1.0
    assert downsampled[0]["max"] == 8.0


def test_lttb_downsampler():
    downsampler = LTTBDownsampler()
    timestamps = [
        1704067200 + i * 60 for i in range(100)
    ]
    values = [i * 0.1 for i in range(100)]
    data = [{"timestamp": t, "value": v} for t, v in zip(timestamps, values)]

    downsampled = downsampler.downsample(data, target_points=20)
    assert len(downsampled) == 20
    assert downsampled[0]["value"] == 0.0
    assert abs(downsampled[-1]["value"] - 9.9) < 0.001


def test_resolution_level():
    level = ResolutionLevel(
        name="raw",
        resolution_seconds=60,
        retention_seconds=86400,
    )
    assert level.name == "raw"
    assert level.resolution_seconds == 60
    assert level.retention_seconds == 86400


def test_multi_resolution_storage():
    storage = MultiResolutionStorage()
    storage.add_resolution_level("raw", 60, 86400)
    storage.add_resolution_level("hourly", 3600, 86400 * 7)
    storage.add_resolution_level("daily", 86400, 86400 * 365)

    assert len(storage.resolution_levels) == 3
    assert storage.resolution_levels[0].name == "raw"

    levels = storage.get_levels_for_time_range(
        start_time=time.time() - 86400 * 10,
        end_time=time.time(),
    )
    assert len(levels) >= 1


def test_multi_resolution_store_and_retrieve():
    storage = MultiResolutionStorage()
    storage.add_resolution_level("raw", 60, 86400)
    storage.add_resolution_level("hourly", 3600, 86400 * 7)

    now = int(time.time())
    data = [
        {"timestamp": now - 3600 + i * 60, "value": float(i)}
        for i in range(60)
    ]

    stored = storage.store(data)
    assert "raw" in stored
    assert "hourly" in stored

    retrieved = storage.retrieve(
        start_time=now - 3600,
        end_time=now,
        resolution="raw",
    )
    assert len(retrieved) == 60


def test_timeseries_compressor_compress():
    compressor = TimeSeriesCompressor(
        encoder_type="delta",
        downsampler_type="mean",
    )
    now = int(time.time())
    data = [
        {"timestamp": now - 3600 + i * 60, "value": float(i)}
        for i in range(60)
    ]

    result = compressor.compress(data, compression_ratio=0.5)
    assert "compressed" in result
    assert "original_size" in result
    assert "compressed_size" in result
    assert result["ratio"] <= 1.0


def test_timeseries_compressor_decompress():
    compressor = TimeSeriesCompressor(encoder_type="delta")
    values = [1.0, 2.0, 3.0, 4.0, 5.0]
    data = [
        {"timestamp": 1704067200 + i * 60, "value": v}
        for i, v in enumerate(values)
    ]

    compressed = compressor.compress(data)
    decompressed = compressor.decompress(compressed["compressed"])
    assert len(decompressed) == len(data)
    for original, decomp in zip(data, decompressed):
        assert original["value"] == decomp["value"]


def test_timeseries_compressor_multi_resolution():
    compressor = TimeSeriesCompressor(
        encoder_type="gorilla",
        downsampler_type="lttb",
    )
    now = int(time.time())
    data = [
        {"timestamp": now - 86400 + i * 60, "value": float(i % 100)}
        for i in range(1440)
    ]

    result = compressor.compress_multi_resolution(
        data,
        resolutions=[
            ("raw", 60, 86400),
            ("hourly", 3600, 86400 * 7),
        ],
    )
    assert "raw" in result
    assert "hourly" in result
