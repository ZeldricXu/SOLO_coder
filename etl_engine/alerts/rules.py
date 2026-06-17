from __future__ import annotations

from typing import Literal

from pydantic import BaseModel, Field

SEVERITY_LEVELS: dict[str, int] = {
    "info": 0,
    "warning": 1,
    "error": 2,
    "critical": 3,
}


class AlertRule(BaseModel):
    alert_type: Literal["task_failure", "quality_degradation", "sla_timeout"]
    channels: list[str]
    min_severity: str = "warning"
    cooldown_minutes: int = 15
    enabled: bool = True

    def severity_met(self, severity: str) -> bool:
        return SEVERITY_LEVELS.get(severity, 0) >= SEVERITY_LEVELS.get(self.min_severity, 1)
