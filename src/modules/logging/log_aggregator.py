"""Log aggregator for logging module."""
from __future__ import annotations

import asyncio
import json
from collections import defaultdict, deque
from dataclasses import dataclass, field
from datetime import datetime, timedelta
from typing import Any, Deque, Dict, List, Optional
from uuid import UUID, uuid4

from ...domain.errors.common import ValidationError
from ...infrastructure.logging.structured_logger import LogManager


@dataclass
class LogEntry:
    id: UUID = field(default_factory=uuid4)
    timestamp: datetime = field(default_factory=datetime.utcnow)
    level: str = "INFO"
    logger_name: str = ""
    message: str = ""
    metadata: Dict[str, Any] = field(default_factory=dict)
    trace_id: Optional[str] = None
    span_id: Optional[str] = None

    def to_dict(self) -> Dict[str, Any]:
        return {
            "id": str(self.id),
            "timestamp": self.timestamp.isoformat(),
            "level": self.level,
            "logger_name": self.logger_name,
            "message": self.message,
            "metadata": self.metadata,
            "trace_id": self.trace_id,
            "span_id": self.span_id,
        }


class LogAggregator:
    def __init__(self, max_entries: int = 10000, retention_seconds: int = 3600) -> None:
        self._logger = LogManager().get_logger(__name__)
        self._entries: Deque[LogEntry] = deque(maxlen=max_entries)
        self._max_entries = max_entries
        self._retention_seconds = retention_seconds
        self._listeners: List = []
        self._level_counts: Dict[str, int] = defaultdict(int)
        self._logger_counts: Dict[str, int] = defaultdict(int)

    def add_entry(
        self,
        level: str,
        logger_name: str,
        message: str,
        metadata: Optional[Dict[str, Any]] = None,
        trace_id: Optional[str] = None,
        span_id: Optional[str] = None,
    ) -> LogEntry:
        entry = LogEntry(
            level=level.upper(),
            logger_name=logger_name,
            message=message,
            metadata=metadata or {},
            trace_id=trace_id,
            span_id=span_id,
        )

        self._entries.append(entry)
        self._level_counts[entry.level] += 1
        self._logger_counts[logger_name] += 1

        self._notify_listeners(entry)

        return entry

    def _notify_listeners(self, entry: LogEntry) -> None:
        for listener in self._listeners:
            try:
                if asyncio.iscoroutinefunction(listener):
                    asyncio.create_task(listener(entry))
                else:
                    listener(entry)
            except Exception as e:
                self._logger.error(f"Error in log listener: {e}")

    def add_listener(self, listener) -> None:
        self._listeners.append(listener)

    def remove_listener(self, listener) -> bool:
        if listener in self._listeners:
            self._listeners.remove(listener)
            return True
        return False

    def get_entries(
        self,
        level: Optional[str] = None,
        logger_name: Optional[str] = None,
        start_time: Optional[datetime] = None,
        end_time: Optional[datetime] = None,
        trace_id: Optional[str] = None,
        message_pattern: Optional[str] = None,
        limit: Optional[int] = None,
        metadata_filter: Optional[Dict[str, Any]] = None,
    ) -> List[LogEntry]:
        self._cleanup_old_entries()

        entries = list(self._entries)

        if level:
            entries = [e for e in entries if e.level == level.upper()]

        if logger_name:
            entries = [e for e in entries if e.logger_name.startswith(logger_name)]

        if start_time:
            entries = [e for e in entries if e.timestamp >= start_time]

        if end_time:
            entries = [e for e in entries if e.timestamp <= end_time]

        if trace_id:
            entries = [e for e in entries if e.trace_id == trace_id]

        if message_pattern:
            import re
            pattern = re.compile(message_pattern, re.IGNORECASE)
            entries = [e for e in entries if pattern.search(e.message)]

        if metadata_filter:
            entries = [
                e for e in entries
                if all(k in e.metadata and e.metadata[k] == v for k, v in metadata_filter.items())
            ]

        entries.sort(key=lambda e: e.timestamp, reverse=True)

        if limit:
            entries = entries[:limit]

        return entries

    def _cleanup_old_entries(self) -> None:
        cutoff_time = datetime.utcnow() - timedelta(seconds=self._retention_seconds)
        while self._entries and self._entries[0].timestamp < cutoff_time:
            removed = self._entries.popleft()
            self._level_counts[removed.level] = max(0, self._level_counts[removed.level] - 1)
            self._logger_counts[removed.logger_name] = max(0, self._logger_counts[removed.logger_name] - 1)

    def get_statistics(
        self,
        start_time: Optional[datetime] = None,
        end_time: Optional[datetime] = None,
    ) -> Dict[str, Any]:
        self._cleanup_old_entries()

        entries = list(self._entries)

        if start_time:
            entries = [e for e in entries if e.timestamp >= start_time]

        if end_time:
            entries = [e for e in entries if e.timestamp <= end_time]

        level_stats: Dict[str, int] = defaultdict(int)
        logger_stats: Dict[str, int] = defaultdict(int)

        for entry in entries:
            level_stats[entry.level] += 1
            logger_stats[entry.logger_name] += 1

        return {
            "total_entries": len(entries),
            "level_distribution": dict(level_stats),
            "logger_distribution": dict(sorted(logger_stats.items(), key=lambda x: x[1], reverse=True)[:20]),
            "first_entry_time": entries[0].timestamp.isoformat() if entries else None,
            "last_entry_time": entries[-1].timestamp.isoformat() if entries else None,
            "errors_per_minute": self._calculate_rate(entries, "ERROR", 60),
            "warnings_per_minute": self._calculate_rate(entries, "WARNING", 60),
        }

    def _calculate_rate(self, entries: List[LogEntry], level: str, seconds: int) -> float:
        if not entries:
            return 0.0

        cutoff = datetime.utcnow() - timedelta(seconds=seconds)
        count = sum(1 for e in entries if e.level == level and e.timestamp >= cutoff)
        return count / (seconds / 60)

    def get_trace_logs(self, trace_id: str) -> List[Dict[str, Any]]:
        entries = self.get_entries(trace_id=trace_id)
        return [e.to_dict() for e in entries]

    def get_error_summary(
        self,
        limit: int = 10,
        start_time: Optional[datetime] = None,
    ) -> List[Dict[str, Any]]:
        entries = self.get_entries(level="ERROR", start_time=start_time)

        error_groups: Dict[str, Dict[str, Any]] = defaultdict(lambda: {
            "message": "",
            "count": 0,
            "first_seen": None,
            "last_seen": None,
            "examples": [],
        })

        for entry in entries:
            key = entry.message[:100]
            group = error_groups[key]
            group["message"] = entry.message
            group["count"] += 1

            if group["first_seen"] is None or entry.timestamp < group["first_seen"]:
                group["first_seen"] = entry.timestamp

            if group["last_seen"] is None or entry.timestamp > group["last_seen"]:
                group["last_seen"] = entry.timestamp

            if len(group["examples"]) < 5:
                group["examples"].append(entry.to_dict())

        sorted_groups = sorted(
            error_groups.values(),
            key=lambda g: g["count"],
            reverse=True,
        )[:limit]

        return [
            {
                "message": g["message"],
                "count": g["count"],
                "first_seen": g["first_seen"].isoformat() if g["first_seen"] else None,
                "last_seen": g["last_seen"].isoformat() if g["last_seen"] else None,
                "examples": g["examples"],
            }
            for g in sorted_groups
        ]

    def export_logs(
        self,
        format: str = "json",
        **kwargs: Any,
    ) -> str:
        entries = self.get_entries(**kwargs)

        if format == "json":
            return json.dumps(
                [e.to_dict() for e in entries],
                indent=2,
                ensure_ascii=False,
            )
        elif format == "jsonl":
            return "\n".join(json.dumps(e.to_dict(), ensure_ascii=False) for e in entries)
        else:
            raise ValidationError(
                message=f"Unsupported format: {format}",
                suggestion="Use 'json' or 'jsonl' format.",
            )

    def clear(self) -> int:
        count = len(self._entries)
        self._entries.clear()
        self._level_counts.clear()
        self._logger_counts.clear()
        return count

    def search(
        self,
        query: str,
        limit: int = 100,
    ) -> List[Dict[str, Any]]:
        import re
        pattern = re.compile(query, re.IGNORECASE)

        results = []
        for entry in self._entries:
            if (pattern.search(entry.message) or
                any(pattern.search(str(v)) for v in entry.metadata.values())):
                results.append(entry.to_dict())
                if len(results) >= limit:
                    break

        return results

    def tail(
        self,
        n: int = 50,
        level: Optional[str] = None,
    ) -> List[Dict[str, Any]]:
        entries = self.get_entries(level=level, limit=n)
        return [e.to_dict() for e in entries]

    def get_logger_names(self) -> List[str]:
        return sorted(self._logger_counts.keys())

    def get_level_counts(self) -> Dict[str, int]:
        return dict(self._level_counts)
