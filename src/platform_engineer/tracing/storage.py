import json
import os
from datetime import datetime, timezone
from typing import Any, Dict, List, Optional

from .collector import Trace, Span


class TraceStorage:
    def __init__(self, storage_dir: str = "./traces", max_files: int = 10000, logger=None):
        self._storage_dir = storage_dir
        self._max_files = max_files
        self._logger = logger
        os.makedirs(storage_dir, exist_ok=True)

    def save_trace(self, trace: Trace) -> str:
        date_str = trace.created_at.strftime("%Y%m%d")
        day_dir = os.path.join(self._storage_dir, date_str)
        os.makedirs(day_dir, exist_ok=True)
        filename = f"{trace.trace_id}.json"
        file_path = os.path.join(day_dir, filename)
        with open(file_path, "w", encoding="utf-8") as f:
            json.dump(trace.to_dict(), f, indent=2)
        if self._logger:
            self._logger.info(f"Saved trace: {trace.trace_id}")
        self._cleanup_if_needed()
        return file_path

    def save_span(self, span: Span) -> str:
        return ""

    def load_trace(self, trace_id: str) -> Optional[Dict[str, Any]]:
        for date_dir in os.listdir(self._storage_dir):
            day_path = os.path.join(self._storage_dir, date_dir)
            if not os.path.isdir(day_path):
                continue
            filename = f"{trace_id}.json"
            file_path = os.path.join(day_path, filename)
            if os.path.exists(file_path):
                with open(file_path, "r", encoding="utf-8") as f:
                    return json.load(f)
        return None

    def list_traces(
        self,
        service_name: Optional[str] = None,
        start_time: Optional[datetime] = None,
        end_time: Optional[datetime] = None,
        has_error: Optional[bool] = None,
        limit: int = 100,
    ) -> List[Dict[str, Any]]:
        traces = []
        for date_dir in sorted(os.listdir(self._storage_dir), reverse=True):
            day_path = os.path.join(self._storage_dir, date_dir)
            if not os.path.isdir(day_path):
                continue
            try:
                date_val = datetime.strptime(date_dir, "%Y%m%d")
                if start_time and date_val.date() < start_time.date():
                    continue
                if end_time and date_val.date() > end_time.date():
                    continue
            except ValueError:
                continue
            for filename in os.listdir(day_path):
                if not filename.endswith(".json"):
                    continue
                file_path = os.path.join(day_path, filename)
                with open(file_path, "r", encoding="utf-8") as f:
                    trace_data = json.load(f)
                if service_name:
                    spans = trace_data.get("spans", [])
                    has_service = any(s.get("service_name") == service_name for s in spans)
                    if not has_service:
                        continue
                if has_error is not None:
                    trace_has_error = trace_data.get("error_count", 0) > 0
                    if trace_has_error != has_error:
                        continue
                traces.append(trace_data)
                if len(traces) >= limit:
                    return traces
        return traces

    def get_stats(self) -> Dict[str, Any]:
        total_files = 0
        total_spans = 0
        for date_dir in os.listdir(self._storage_dir):
            day_path = os.path.join(self._storage_dir, date_dir)
            if os.path.isdir(day_path):
                files = [f for f in os.listdir(day_path) if f.endswith(".json")]
                total_files += len(files)
                for filename in files:
                    try:
                        file_path = os.path.join(day_path, filename)
                        with open(file_path, "r", encoding="utf-8") as f:
                            data = json.load(f)
                            total_spans += data.get("span_count", 0)
                    except Exception:
                        pass
        return {
            "storage_dir": self._storage_dir,
            "trace_files": total_files,
            "total_spans": total_spans,
            "max_files": self._max_files,
        }

    def _cleanup_if_needed(self) -> None:
        total_files = 0
        all_files = []
        for date_dir in sorted(os.listdir(self._storage_dir)):
            day_path = os.path.join(self._storage_dir, date_dir)
            if os.path.isdir(day_path):
                for filename in os.listdir(day_path):
                    if filename.endswith(".json"):
                        file_path = os.path.join(day_path, filename)
                        all_files.append((file_path, os.path.getmtime(file_path)))
                        total_files += 1
        if total_files > self._max_files:
            all_files.sort(key=lambda x: x[1])
            remove_count = total_files - self._max_files + int(self._max_files * 0.1)
            for file_path, _ in all_files[:remove_count]:
                os.remove(file_path)
            if self._logger:
                self._logger.info(f"Cleaned up {remove_count} old trace files")
