"""
领域模型定义 - 所有模块共享的数据结构
"""

from __future__ import annotations

import enum
import json
import time
from dataclasses import dataclass, field, asdict
from typing import Any, Dict, List, Optional
from uuid import UUID, uuid4


class LogLevel(str, enum.Enum):
    DEBUG = "debug"
    INFO = "info"
    WARNING = "warning"
    ERROR = "error"
    CRITICAL = "critical"


class NotificationPriority(str, enum.Enum):
    LOW = "low"
    NORMAL = "normal"
    HIGH = "high"
    URGENT = "urgent"


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
class ServiceMetadata:
    id: str = field(default_factory=lambda: str(uuid4()))
    name: str = ""
    type: str = ""
    description: str = ""
    version: str = ""
    language: str = ""
    owner: str = ""
    repository: str = ""
    endpoints: List[Dict[str, Any]] = field(default_factory=list)
    dependencies: List[str] = field(default_factory=list)
    tags: List[str] = field(default_factory=list)
    created_at: float = field(default_factory=time.time)
    updated_at: float = field(default_factory=time.time)


@dataclass
class DocumentMetadata:
    id: str = field(default_factory=lambda: str(uuid4()))
    title: str = ""
    source: str = ""
    type: str = ""
    url: str = ""
    content_hash: str = ""
    permissions: List[str] = field(default_factory=list)
    tags: List[str] = field(default_factory=list)
    created_at: float = field(default_factory=time.time)
    updated_at: float = field(default_factory=time.time)


@dataclass
class QualityIssue:
    file: str
    line: int
    column: int
    severity: str
    rule_id: str
    message: str
    language: str


@dataclass
class QualityReport:
    project_name: str
    total_files: int = 0
    issues: List[QualityIssue] = field(default_factory=list)
    score: int = 100
    passed: bool = True
    generated_at: float = field(default_factory=time.time)

    def add_issue(self, issue: QualityIssue) -> None:
        self.issues.append(issue)
        self._recalculate_score()

    def _recalculate_score(self) -> None:
        critical = sum(1 for i in self.issues if i.severity == "critical")
        major = sum(1 for i in self.issues if i.severity == "major")
        minor = sum(1 for i in self.issues if i.severity == "minor")
        score = 100 - (critical * 10 + major * 5 + minor * 1)
        self.score = max(0, score)
        self.passed = self.score >= 80


@dataclass
class ScaffoldConfig:
    project_name: str
    project_type: str
    language: str
    author: str
    template: str
    parameters: Dict[str, Any] = field(default_factory=dict)
    output_dir: str = ""


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
