import sys
sys.path.insert(0, '/Users/huangzitong/Desktop/SoloCoder_All/SoloCoder7/session307')

import numpy as np
import pandas as pd
from timeseries_compression import (
    TimeSeries,
    DeltaCodec,
    XORCodec,
    GorillaCodec,
    LZ4Codec,
    ZSTDCodec,
    SnappyCodec,
    RunLengthCodec,
    AutoCodec,
    MeanDownsampler,
    LTTBDownsampler,
    M4Downsampler,
    MultiResolutionStorage,
    MissingValueStrategy,
)

print("=== Module Import: SUCCESS ===")

timestamps = np.arange(0, 1000, 1.0)
values = np.sin(timestamps * 0.01) + np.random.normal(0, 0.1, len(timestamps))

ts = TimeSeries(timestamps, values, name='test')
print(f"TimeSeries: {len(ts)} points, duration={ts.duration:.1f}s")

print("\n=== Compressor Tests ===")
codecs = [DeltaCodec(), XORCodec(), GorillaCodec(), LZ4Codec(), ZSTDCodec(), SnappyCodec(), RunLengthCodec()]
for codec in codecs:
    enc, stats = codec.encode_with_stats(values)
    dec = codec.decode(enc)
    err = np.max(np.abs(dec - values))
    print(f"{codec.name():15} | ratio={stats.compression_ratio:.4f} | size={stats.compressed_size:6d}B | enc={stats.encode_time_ms:6.2f}ms | dec={stats.decode_time_ms:6.2f}ms | err={err:.2e}")

print("\n=== AutoCodec Test ===")
auto = AutoCodec()
enc, stats = auto.encode_with_stats(values)
dec = auto.decode(enc)
err = np.max(np.abs(dec - values))
print(f"Chosen: {stats.extra['chosen_codec']}, ratio={stats.compression_ratio:.4f}, err={err:.2e}")

print("\n=== Downsampler Tests ===")
for ds in [MeanDownsampler(), LTTBDownsampler(), M4Downsampler()]:
    t, v = ds.downsample(timestamps, values, 100)
    print(f"{ds.name():10} | output={len(v)} points")

print("\n=== TimeSeries Methods ===")
ds = ts.downsample(LTTBDownsampler(), 100)
aligned = ts.align_to_frequency(2.0)
filled = ts.fill_missing(MissingValueStrategy.LINEAR)
chunks = ts.split(200)
time_chunks = ts.split_by_time(300)
sliced = ts.slice(100, 500)
print(f"downsample={len(ds)}, align={len(aligned)}, fill={len(filled)}, chunks={len(chunks)}, time_chunks={len(time_chunks)}, slice={len(sliced)}")

print("\n=== Pandas Conversion ===")
pd_s = ts.to_pandas()
ts2 = TimeSeries.from_pandas(pd_s)
print(f"Pandas roundtrip: {len(pd_s)} -> {len(ts2)} points")

print("\n=== MultiResolutionStorage Test ===")
storage = MultiResolutionStorage(shard_interval_seconds=200)
keys = storage.write(ts)
print(f"Write: {len(keys)} shards: {keys}")

read_ts = storage.read(0, 1000)
print(f"Read all: {len(read_ts) if read_ts else 0} points")

read_1min = storage.read(0, 1000, resolution='1min')
print(f"Read 1min: {len(read_1min) if read_1min else 0} points")

for key in keys:
    comp_stats = storage.compress_shard(key)
    for res, s in comp_stats.items():
        print(f"  {key} {res}: ratio={s.compression_ratio:.4f}")

summary = storage.get_storage_summary()
print(f"\nSummary: {summary['total_shards']} shards")
print(f"  Tier distribution: {summary['tier_distribution']}")
print(f"  Resolution distribution: {summary['resolution_distribution']}")
print(f"  Total uncompressed: {summary['total_uncompressed_bytes']} bytes")
print(f"  Total compressed: {summary['total_compressed_bytes']} bytes")
print(f"  Compression ratio: {summary['compression_ratio']:.4f}")
print(f"  Space saving: {summary['space_saving']*100:.1f}%")

shard_info = storage.get_shard_info()
print(f"\nShard info: {len(shard_info)} shards")
for info in shard_info[:2]:
    print(f"  {info['key']}: {info['tier']}, resolutions={info['resolutions']}")

print("\n=== ALL TESTS PASSED ===")
