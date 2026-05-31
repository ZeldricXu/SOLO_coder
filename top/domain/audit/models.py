from __future__ import annotations

from datetime import datetime, timezone
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional


def utc_now() -> datetime:
    return datetime.now(timezone.utc)


@dataclass
class ComplianceReport:
    report_id: str
    period_start: datetime
    period_end: datetime
    total_commands: int
    total_audit_logs: int
    command_breakdown: Dict[str, int] = field(default_factory=dict)
    action_breakdown: Dict[str, int] = field(default_factory=dict)
    actor_breakdown: Dict[str, int] = field(default_factory=dict)
    uncorrelated_count: int = 0
    generated_at: datetime = field(default_factory=utc_now)

    def to_dict(self) -> Dict[str, Any]:
        return {
            "report_id": self.report_id,
            "period_start": self.period_start.isoformat(),
            "period_end": self.period_end.isoformat(),
            "total_commands": self.total_commands,
            "total_audit_logs": self.total_audit_logs,
            "command_breakdown": self.command_breakdown,
            "action_breakdown": self.action_breakdown,
            "actor_breakdown": self.actor_breakdown,
            "uncorrelated_count": self.uncorrelated_count,
            "generated_at": self.generated_at.isoformat(),
        }

    @property
    def correlation_rate(self) -> float:
        if self.total_commands == 0:
            return 1.0
        return (self.total_commands - self.uncorrelated_count) / self.total_commands

    @property
    def is_compliant(self) -> bool:
        return self.correlation_rate >= 0.95


@dataclass
class CommandQueryResult:
    commands: List[Any]
    audit_logs: List[Any]
    correlation_id: Optional[str] = None
    command_id: Optional[str] = None

    @property
    def total_count(self) -> int:
        return len(self.commands) + len(self.audit_logs)

    @property
    def has_linked_audit(self) -> bool:
        command_ids = {getattr(cmd, 'command_id', None) for cmd in self.commands}
        return any(
            getattr(log, 'command_id', None) in command_ids
            for log in self.audit_logs
        )
