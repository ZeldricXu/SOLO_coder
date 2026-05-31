"""
链路追踪领域模型
"""

from __future__ import annotations

import enum
import json
import time
from dataclasses import dataclass, field
from typing import Any, Dict, Optional
from uuid import uuid4


class LogLevel(str, enum.Enum):
    DEBUG = "debug"
    INFO = "info"
    WARNING = "warning"
    ERROR = "error"
    CRITICAL = "critical"


@dataclass
class LogEntry:
    timestamp: float = field(default_factory=time.time)
    level: LogLevel = LogLevel.INFO
    message: str = ""
    service_name: str = ""
    trace_id: str = ""
    span_id: str = ""
    extra: Dict[str, Any] = field(default_factory=dict)

    def to_dict(self) -> Dict[str, Any]:
        return {
            "timestamp": self.timestamp,
            "level": self.level.value,
            "message": self.message,
            "service_name": self.service_name,
            "trace_id": self.trace_id,
            "span_id": self.span_id,
            **self.extra,
        }

    def to_json(self) -> str:
        return json.dumps(self.to_dict(), ensure_ascii=False)


@dataclass
class TraceSpan:
    span_id: str = field(default_factory=lambda: str(uuid4()))
    parent_span_id: Optional[str] = None
    service_name: str = ""
    operation_name: str = ""
    start_time: float = field(default_factory=time.time)
    end_time: Optional[float] = None
    status: str = "pending"
    tags: Dict[str, Any] = field(default_factory=dict)

    def finish(self, status: str = "success") -> None:
        self.end_time = time.time()
        self.status = status

    def duration(self) -> float:
        if self.end_time is None:
            return 0.0
        return self.end_time - self.start_time
