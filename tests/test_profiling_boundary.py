import pytest

from platform_engineer.profiling import CPUSampler, MemorySampler, ContinuousProfiler


class TestCPUSamplerBoundary:
    def test_cpu_sampler_zero_interval(self):
        with pytest.raises(ValueError, match="must be positive"):
            CPUSampler(interval_seconds=0)

    def test_cpu_sampler_negative_interval(self):
        with pytest.raises(ValueError, match="must be positive"):
            CPUSampler(interval_seconds=-0.1)

    def test_cpu_sampler_too_small_interval(self):
        with pytest.raises(ValueError, match="must be at least"):
            CPUSampler(interval_seconds=0.0001)

    def test_cpu_sampler_too_large_interval(self):
        with pytest.raises(ValueError, match="must be at most"):
            CPUSampler(interval_seconds=1000)

    def test_cpu_sampler_valid_interval(self):
        sampler = CPUSampler(interval_seconds=0.1)
        assert sampler is not None

        sampler2 = CPUSampler(interval_seconds=1.0)
        assert sampler2 is not None

        sampler3 = CPUSampler(interval_seconds=59.0)
        assert sampler3 is not None


class TestMemorySamplerBoundary:
    def test_memory_sampler_zero_interval(self):
        with pytest.raises(ValueError, match="must be positive"):
            MemorySampler(interval_seconds=0)

    def test_memory_sampler_negative_interval(self):
        with pytest.raises(ValueError, match="must be positive"):
            MemorySampler(interval_seconds=-1.0)

    def test_memory_sampler_too_small_interval(self):
        with pytest.raises(ValueError, match="must be at least"):
            MemorySampler(interval_seconds=0.0001)

    def test_memory_sampler_too_large_interval(self):
        with pytest.raises(ValueError, match="must be at most"):
            MemorySampler(interval_seconds=1000)

    def test_memory_sampler_valid_interval(self):
        sampler = MemorySampler(interval_seconds=0.5)
        assert sampler is not None

        sampler2 = MemorySampler(interval_seconds=5.0)
        assert sampler2 is not None

    def test_memory_sampler_get_snapshot_before_start(self):
        sampler = MemorySampler()

        result = sampler.get_snapshot(top_n=10)
        assert result == []

    def test_memory_sampler_get_snapshot_negative_top_n(self):
        sampler = MemorySampler(enable_tracemalloc=True)
        sampler.start()

        try:
            result = sampler.get_snapshot(top_n=-1)
            assert result == []

            result2 = sampler.get_snapshot(top_n=0)
            assert result2 == []
        finally:
            sampler.stop()

    def test_memory_sampler_get_snapshot_large_top_n(self):
        sampler = MemorySampler(enable_tracemalloc=True)
        sampler.start()

        try:
            result = sampler.get_snapshot(top_n=10000)
            assert len(result) <= 1000
        finally:
            sampler.stop()


class TestContinuousProfilerBoundary:
    def test_profiler_too_small_snapshot_duration(self):
        with pytest.raises(ValueError, match="snapshot_duration must be at least"):
            ContinuousProfiler(snapshot_duration=0.5)

    def test_profiler_too_large_snapshot_duration(self):
        with pytest.raises(ValueError, match="snapshot_duration must be at most"):
            ContinuousProfiler(snapshot_duration=90000)

    def test_profiler_too_few_snapshots(self):
        with pytest.raises(ValueError, match="max_snapshots must be at least"):
            ContinuousProfiler(max_snapshots=0)

    def test_profiler_too_many_snapshots(self):
        with pytest.raises(ValueError, match="max_snapshots must be at most"):
            ContinuousProfiler(max_snapshots=100000)

    def test_profiler_valid_params(self):
        profiler = ContinuousProfiler(
            cpu_interval=0.1,
            memory_interval=0.5,
            snapshot_duration=60.0,
            max_snapshots=100,
        )
        assert profiler is not None
        assert profiler.is_running() is False

    def test_profiler_get_snapshots_negative_limit(self):
        profiler = ContinuousProfiler()

        result = profiler.get_snapshots(limit=-1)
        assert result == []

        result2 = profiler.get_snapshots(limit=0)
        assert result2 == []

    def test_profiler_get_snapshots_large_limit(self):
        profiler = ContinuousProfiler(max_snapshots=5)

        snapshot = profiler.take_snapshot()

        result = profiler.get_snapshots(limit=100)
        assert len(result) == 1

    def test_profiler_on_snapshot_none(self):
        profiler = ContinuousProfiler()

        with pytest.raises(ValueError, match="callback cannot be None"):
            profiler.on_snapshot(None)

    def test_profiler_set_global_none(self):
        from platform_engineer.profiling.profiler import set_global_profiler

        with pytest.raises(ValueError, match="profiler cannot be None"):
            set_global_profiler(None)

    def test_profiler_take_snapshot(self):
        profiler = ContinuousProfiler()

        snapshot = profiler.take_snapshot({"source": "test"})
        assert snapshot is not None
        assert snapshot.labels["source"] == "test"

        snapshots = profiler.get_snapshots()
        assert len(snapshots) == 1

    @pytest.mark.asyncio
    async def test_profiler_start_stop(self):
        profiler = ContinuousProfiler(snapshot_duration=1000)

        await profiler.start()
        assert profiler.is_running() is True

        await profiler.stop()
        assert profiler.is_running() is False

    @pytest.mark.asyncio
    async def test_profiler_double_start(self):
        profiler = ContinuousProfiler(snapshot_duration=1000)

        await profiler.start()
        await profiler.start()

        assert profiler.is_running() is True

        await profiler.stop()

    @pytest.mark.asyncio
    async def test_profiler_double_stop(self):
        profiler = ContinuousProfiler(snapshot_duration=1000)

        await profiler.start()
        await profiler.stop()
        await profiler.stop()

        assert profiler.is_running() is False

    def test_profiler_get_latest_snapshot_empty(self):
        profiler = ContinuousProfiler()

        latest = profiler.get_latest_snapshot()
        assert latest is None

    def test_profiler_stats(self):
        profiler = ContinuousProfiler()

        stats = profiler.get_stats()
        assert stats["running"] is False
        assert stats["snapshot_count"] == 0
        assert stats["max_snapshots"] == 100

        profiler.take_snapshot()

        stats2 = profiler.get_stats()
        assert stats2["snapshot_count"] == 1
