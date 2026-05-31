import asyncio
import gc
import threading
import tracemalloc
from collections import defaultdict
from dataclasses import dataclass, field
from datetime import datetime, timezone
from typing import Any, Callable, Dict, List, Optional

try:
    import psutil
    HAS_PSUTIL = True
except ImportError:
    HAS_PSUTIL = False

from ..core.exceptions import ProfilingError


@dataclass
class SampleRecord:
    timestamp: datetime
    value: float
    metadata: Dict[str, Any] = field(default_factory=dict)


@dataclass
class ProfileSnapshot:
    snapshot_id: str
    started_at: datetime
    ended_at: datetime
    cpu_samples: List[SampleRecord]
    memory_samples: List[SampleRecord]
    cpu_usage_avg: float = 0.0
    cpu_usage_max: float = 0.0
    memory_usage_avg: float = 0.0
    memory_usage_max: float = 0.0
    memory_peak: int = 0
    call_stack_samples: List[Dict[str, Any]] = field(default_factory=list)
    labels: Dict[str, str] = field(default_factory=dict)

    def to_dict(self) -> Dict[str, Any]:
        return {
            "snapshot_id": self.snapshot_id,
            "started_at": self.started_at.isoformat(),
            "ended_at": self.ended_at.isoformat(),
            "cpu_samples": [
                {"timestamp": s.timestamp.isoformat(), "value": s.value, "metadata": s.metadata}
                for s in self.cpu_samples
            ],
            "memory_samples": [
                {"timestamp": s.timestamp.isoformat(), "value": s.value, "metadata": s.metadata}
                for s in self.memory_samples
            ],
            "cpu_usage_avg": self.cpu_usage_avg,
            "cpu_usage_max": self.cpu_usage_max,
            "memory_usage_avg": self.memory_usage_avg,
            "memory_usage_max": self.memory_usage_max,
            "memory_peak": self.memory_peak,
            "call_stack_samples": self.call_stack_samples,
            "labels": self.labels,
        }


class CPUSampler:
    MIN_INTERVAL = 0.001
    MAX_INTERVAL = 60.0

    def __init__(self, interval_seconds: float = 0.1):
        if interval_seconds <= 0:
            raise ValueError(f"interval_seconds must be positive, got {interval_seconds}")
        if interval_seconds < self.MIN_INTERVAL:
            raise ValueError(
                f"interval_seconds must be at least {self.MIN_INTERVAL}, got {interval_seconds}"
            )
        if interval_seconds > self.MAX_INTERVAL:
            raise ValueError(
                f"interval_seconds must be at most {self.MAX_INTERVAL}, got {interval_seconds}"
            )
        self._interval = interval_seconds
        self._process = None
        self._samples: List[SampleRecord] = []
        if HAS_PSUTIL:
            self._process = psutil.Process()

    async def sample(self) -> SampleRecord:
        if not HAS_PSUTIL:
            return SampleRecord(
                timestamp=datetime.now(timezone.utc),
                value=0.0,
                metadata={"error": "psutil not installed"},
            )
        cpu_percent = self._process.cpu_percent(interval=self._interval)
        sample = SampleRecord(
            timestamp=datetime.now(timezone.utc),
            value=float(cpu_percent),
            metadata={"process_id": self._process.pid},
        )
        self._samples.append(sample)
        return sample

    def get_samples(self) -> List[SampleRecord]:
        return list(self._samples)

    def clear(self) -> None:
        self._samples.clear()

    def get_stats(self) -> Dict[str, float]:
        if not self._samples:
            return {"avg": 0.0, "max": 0.0, "min": 0.0}
        values = [s.value for s in self._samples]
        return {
            "avg": sum(values) / len(values),
            "max": max(values),
            "min": min(values),
        }


class MemorySampler:
    MIN_INTERVAL = 0.001
    MAX_INTERVAL = 300.0

    def __init__(self, interval_seconds: float = 0.5, enable_tracemalloc: bool = True):
        if interval_seconds <= 0:
            raise ValueError(f"interval_seconds must be positive, got {interval_seconds}")
        if interval_seconds < self.MIN_INTERVAL:
            raise ValueError(
                f"interval_seconds must be at least {self.MIN_INTERVAL}, got {interval_seconds}"
            )
        if interval_seconds > self.MAX_INTERVAL:
            raise ValueError(
                f"interval_seconds must be at most {self.MAX_INTERVAL}, got {interval_seconds}"
            )
        self._interval = interval_seconds
        self._samples: List[SampleRecord] = []
        self._peak_memory = 0
        self._enable_tracemalloc = bool(enable_tracemalloc)
        self._tracemalloc_started = False
        self._process = None
        if HAS_PSUTIL:
            self._process = psutil.Process()

    def start(self) -> None:
        if self._enable_tracemalloc and not self._tracemalloc_started:
            tracemalloc.start()
            self._tracemalloc_started = True

    def stop(self) -> None:
        if self._tracemalloc_started:
            tracemalloc.stop()
            self._tracemalloc_started = False

    async def sample(self) -> SampleRecord:
        memory_info = {}
        current_memory = 0

        if HAS_PSUTIL and self._process:
            mem_info = self._process.memory_info()
            current_memory = mem_info.rss
            memory_info = {
                "rss": mem_info.rss,
                "vms": mem_info.vms,
                "shared": getattr(mem_info, "shared", 0),
                "process_id": self._process.pid,
            }
        else:
            current_memory = gc.get_objects().__sizeof__()

        if self._tracemalloc_started:
            current, peak = tracemalloc.get_traced_memory()
            current_memory = current
            self._peak_memory = max(self._peak_memory, peak)
            memory_info["tracemalloc_peak"] = peak
            memory_info["tracemalloc_current"] = current

        if current_memory > self._peak_memory:
            self._peak_memory = current_memory

        sample = SampleRecord(
            timestamp=datetime.now(timezone.utc),
            value=float(current_memory),
            metadata=memory_info,
        )
        self._samples.append(sample)
        return sample

    def get_samples(self) -> List[SampleRecord]:
        return list(self._samples)

    def clear(self) -> None:
        self._samples.clear()

    def get_peak_memory(self) -> int:
        return self._peak_memory

    def get_stats(self) -> Dict[str, float]:
        if not self._samples:
            return {"avg": 0.0, "max": 0.0, "min": 0.0, "peak": float(self._peak_memory)}
        values = [s.value for s in self._samples]
        return {
            "avg": sum(values) / len(values),
            "max": max(values),
            "min": min(values),
            "peak": float(self._peak_memory),
        }

    def get_snapshot(self, top_n: int = 10) -> List[Dict[str, Any]]:
        if not self._tracemalloc_started:
            return []
        if top_n <= 0:
            return []
        if top_n > 1000:
            top_n = 1000
        snapshot = tracemalloc.take_snapshot()
        top_stats = snapshot.statistics("lineno")
        result = []
        for stat in top_stats[:top_n]:
            filename = "unknown"
            lineno = 0
            if stat.traceback and len(stat.traceback) > 0:
                frame = stat.traceback[0]
                if frame:
                    filename = str(getattr(frame, "filename", "unknown"))
                    lineno = getattr(frame, "lineno", 0)
            result.append({
                "file": filename,
                "line": lineno,
                "size_bytes": stat.size,
                "count": stat.count,
                "size_mb": stat.size / (1024 * 1024),
            })
        return result


class ContinuousProfiler:
    MIN_SNAPSHOT_DURATION = 1.0
    MAX_SNAPSHOT_DURATION = 86400.0
    MIN_SNAPSHOTS = 1
    MAX_SNAPSHOTS = 10000

    def __init__(
        self,
        cpu_interval: float = 0.1,
        memory_interval: float = 0.5,
        snapshot_duration: float = 60.0,
        max_snapshots: int = 100,
        logger=None,
    ):
        if snapshot_duration < self.MIN_SNAPSHOT_DURATION:
            raise ValueError(
                f"snapshot_duration must be at least {self.MIN_SNAPSHOT_DURATION}, got {snapshot_duration}"
            )
        if snapshot_duration > self.MAX_SNAPSHOT_DURATION:
            raise ValueError(
                f"snapshot_duration must be at most {self.MAX_SNAPSHOT_DURATION}, got {snapshot_duration}"
            )
        if max_snapshots < self.MIN_SNAPSHOTS:
            raise ValueError(
                f"max_snapshots must be at least {self.MIN_SNAPSHOTS}, got {max_snapshots}"
            )
        if max_snapshots > self.MAX_SNAPSHOTS:
            raise ValueError(
                f"max_snapshots must be at most {self.MAX_SNAPSHOTS}, got {max_snapshots}"
            )
        self._cpu_sampler = CPUSampler(interval_seconds=cpu_interval)
        self._memory_sampler = MemorySampler(interval_seconds=memory_interval)
        self._snapshot_duration = snapshot_duration
        self._max_snapshots = max_snapshots
        self._logger = logger
        self._running = False
        self._cpu_task: Optional[asyncio.Task] = None
        self._memory_task: Optional[asyncio.Task] = None
        self._snapshot_task: Optional[asyncio.Task] = None
        self._snapshots: List[ProfileSnapshot] = []
        self._callbacks: List[Callable[[ProfileSnapshot], Any]] = []
        self._snapshot_started_at: Optional[datetime] = None

    async def start(self) -> None:
        if self._running:
            return
        self._running = True
        self._memory_sampler.start()
        self._snapshot_started_at = datetime.now(timezone.utc)
        self._cpu_task = asyncio.create_task(self._cpu_loop())
        self._memory_task = asyncio.create_task(self._memory_loop())
        self._snapshot_task = asyncio.create_task(self._snapshot_loop())
        if self._logger:
            self._logger.info("Continuous profiler started")

    async def stop(self) -> None:
        if not self._running:
            return
        self._running = False
        for task in [self._cpu_task, self._memory_task, self._snapshot_task]:
            if task:
                task.cancel()
                try:
                    await task
                except asyncio.CancelledError:
                    pass
        self._memory_sampler.stop()
        if self._logger:
            self._logger.info("Continuous profiler stopped")

    async def _cpu_loop(self) -> None:
        while self._running:
            try:
                await self._cpu_sampler.sample()
            except Exception as e:
                if self._logger:
                    self._logger.error(f"CPU sampling error: {e}")
            await asyncio.sleep(0)

    async def _memory_loop(self) -> None:
        while self._running:
            try:
                await self._memory_sampler.sample()
            except Exception as e:
                if self._logger:
                    self._logger.error(f"Memory sampling error: {e}")
            await asyncio.sleep(self._memory_sampler._interval)

    async def _snapshot_loop(self) -> None:
        while self._running:
            await asyncio.sleep(self._snapshot_duration)
            try:
                snapshot = self._create_snapshot()
                self._snapshots.append(snapshot)
                if len(self._snapshots) > self._max_snapshots:
                    self._snapshots = self._snapshots[-self._max_snapshots:]
                for callback in self._callbacks:
                    try:
                        result = callback(snapshot)
                        if asyncio.iscoroutine(result):
                            await result
                    except Exception as e:
                        if self._logger:
                            self._logger.error(f"Snapshot callback error: {e}")
            except Exception as e:
                if self._logger:
                    self._logger.error(f"Snapshot creation error: {e}")

    def _create_snapshot(self, labels: Optional[Dict[str, str]] = None) -> ProfileSnapshot:
        cpu_samples = self._cpu_sampler.get_samples()
        memory_samples = self._memory_sampler.get_samples()
        cpu_stats = self._cpu_sampler.get_stats()
        memory_stats = self._memory_sampler.get_stats()
        call_stack_samples = self._memory_sampler.get_snapshot(top_n=20)
        ended_at = datetime.now(timezone.utc)
        snapshot = ProfileSnapshot(
            snapshot_id=f"prof_{datetime.now(timezone.utc).strftime('%Y%m%d%H%M%S')}",
            started_at=self._snapshot_started_at or ended_at,
            ended_at=ended_at,
            cpu_samples=cpu_samples,
            memory_samples=memory_samples,
            cpu_usage_avg=cpu_stats["avg"],
            cpu_usage_max=cpu_stats["max"],
            memory_usage_avg=memory_stats["avg"],
            memory_usage_max=memory_stats["max"],
            memory_peak=self._memory_sampler.get_peak_memory(),
            call_stack_samples=call_stack_samples,
            labels=labels or {},
        )
        self._cpu_sampler.clear()
        self._memory_sampler.clear()
        self._snapshot_started_at = datetime.now(timezone.utc)
        return snapshot

    def take_snapshot(self, labels: Optional[Dict[str, str]] = None) -> ProfileSnapshot:
        snapshot = self._create_snapshot(labels)
        self._snapshots.append(snapshot)
        if len(self._snapshots) > self._max_snapshots:
            self._snapshots = self._snapshots[-self._max_snapshots:]
        return snapshot

    def on_snapshot(self, callback: Callable[[ProfileSnapshot], Any]) -> None:
        if callback is None:
            raise ValueError("callback cannot be None")
        self._callbacks.append(callback)

    def get_snapshots(self, limit: int = 100) -> List[ProfileSnapshot]:
        if limit <= 0:
            return []
        if limit > len(self._snapshots):
            limit = len(self._snapshots)
        return list(self._snapshots[-limit:])

    def get_latest_snapshot(self) -> Optional[ProfileSnapshot]:
        if not self._snapshots:
            return None
        return self._snapshots[-1]

    def get_stats(self) -> Dict[str, Any]:
        return {
            "running": self._running,
            "snapshot_count": len(self._snapshots),
            "max_snapshots": self._max_snapshots,
            "snapshot_duration_seconds": self._snapshot_duration,
            "cpu_stats": self._cpu_sampler.get_stats(),
            "memory_stats": self._memory_sampler.get_stats(),
        }

    def is_running(self) -> bool:
        return self._running


_global_profiler: Optional[ContinuousProfiler] = None
_global_profiler_lock = threading.Lock()


def get_global_profiler() -> ContinuousProfiler:
    global _global_profiler, _global_profiler_lock
    if _global_profiler is None:
        with _global_profiler_lock:
            if _global_profiler is None:
                _global_profiler = ContinuousProfiler()
    return _global_profiler


def set_global_profiler(profiler: ContinuousProfiler) -> None:
    global _global_profiler, _global_profiler_lock
    if profiler is None:
        raise ValueError("profiler cannot be None")
    with _global_profiler_lock:
        _global_profiler = profiler
