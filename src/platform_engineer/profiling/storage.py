import json
import os
from datetime import datetime
from typing import Any, Dict, List, Optional

from .profiler import ProfileSnapshot


class ProfileStorage:
    def __init__(self, storage_dir: str = "./profiles", max_files: int = 1000, logger=None):
        self._storage_dir = storage_dir
        self._max_files = max_files
        self._logger = logger
        self._cache: Dict[str, ProfileSnapshot] = {}
        os.makedirs(storage_dir, exist_ok=True)

    def save(self, snapshot: ProfileSnapshot) -> str:
        file_path = self._get_file_path(snapshot.snapshot_id)
        with open(file_path, "w", encoding="utf-8") as f:
            json.dump(snapshot.to_dict(), f, indent=2)
        self._cache[snapshot.snapshot_id] = snapshot
        self._cleanup_if_needed()
        if self._logger:
            self._logger.info(f"Saved profile snapshot: {snapshot.snapshot_id}")
        return file_path

    def load(self, snapshot_id: str) -> Optional[ProfileSnapshot]:
        if snapshot_id in self._cache:
            return self._cache[snapshot_id]
        file_path = self._get_file_path(snapshot_id)
        if not os.path.exists(file_path):
            return None
        with open(file_path, "r", encoding="utf-8") as f:
            data = json.load(f)
        snapshot = self._dict_to_snapshot(data)
        self._cache[snapshot_id] = snapshot
        return snapshot

    def _get_file_path(self, snapshot_id: str) -> str:
        return os.path.join(self._storage_dir, f"{snapshot_id}.json")

    def _dict_to_snapshot(self, data: Dict[str, Any]) -> ProfileSnapshot:
        from datetime import datetime as dt
        from .profiler import SampleRecord
        return ProfileSnapshot(
            snapshot_id=data["snapshot_id"],
            started_at=dt.fromisoformat(data["started_at"]),
            ended_at=dt.fromisoformat(data["ended_at"]),
            cpu_samples=[
                SampleRecord(
                    timestamp=dt.fromisoformat(s["timestamp"]),
                    value=s["value"],
                    metadata=s.get("metadata", {}),
                )
                for s in data.get("cpu_samples", [])
            ],
            memory_samples=[
                SampleRecord(
                    timestamp=dt.fromisoformat(s["timestamp"]),
                    value=s["value"],
                    metadata=s.get("metadata", {}),
                )
                for s in data.get("memory_samples", [])
            ],
            cpu_usage_avg=data.get("cpu_usage_avg", 0.0),
            cpu_usage_max=data.get("cpu_usage_max", 0.0),
            memory_usage_avg=data.get("memory_usage_avg", 0.0),
            memory_usage_max=data.get("memory_usage_max", 0.0),
            memory_peak=data.get("memory_peak", 0),
            call_stack_samples=data.get("call_stack_samples", []),
            labels=data.get("labels", {}),
        )

    def list_snapshots(self, limit: int = 100, start_time: Optional[datetime] = None) -> List[str]:
        files = sorted(os.listdir(self._storage_dir))
        snapshot_ids = [f.replace(".json", "") for f in files if f.endswith(".json")]
        snapshot_ids.sort(reverse=True)
        if start_time:
            filtered = []
            for sid in snapshot_ids:
                try:
                    ts = sid.replace("prof_", "")
                    file_time = datetime.strptime(ts, "%Y%m%d%H%M%S")
                    if file_time >= start_time:
                        filtered.append(sid)
                except Exception:
                    pass
            snapshot_ids = filtered
        return snapshot_ids[:limit]

    def delete(self, snapshot_id: str) -> bool:
        file_path = self._get_file_path(snapshot_id)
        if os.path.exists(file_path):
            os.remove(file_path)
            if snapshot_id in self._cache:
                del self._cache[snapshot_id]
            return True
        return False

    def clear_cache(self) -> None:
        self._cache.clear()

    def get_stats(self) -> Dict[str, Any]:
        files = os.listdir(self._storage_dir)
        total_size = sum(os.path.getsize(os.path.join(self._storage_dir, f)) for f in files if os.path.isfile(os.path.join(self._storage_dir, f)))
        return {
            "storage_dir": self._storage_dir,
            "file_count": len([f for f in files if f.endswith(".json")]),
            "total_size_bytes": total_size,
            "total_size_mb": total_size / (1024 * 1024),
            "cache_size": len(self._cache),
            "max_files": self._max_files,
        }

    def _cleanup_if_needed(self) -> None:
        files = sorted(
            [f for f in os.listdir(self._storage_dir) if f.endswith(".json")],
            key=lambda f: os.path.getmtime(os.path.join(self._storage_dir, f)),
        )
        if len(files) > self._max_files:
            remove_count = len(files) - self._max_files
            for f in files[:remove_count]:
                file_path = os.path.join(self._storage_dir, f)
                os.remove(file_path)
                snapshot_id = f.replace(".json", "")
                if snapshot_id in self._cache:
                    del self._cache[snapshot_id]
            if self._logger:
                self._logger.info(f"Cleaned up {remove_count} old profile files")
