"""
API网关领域模型 - 数据一致性
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional


@dataclass
class ConsistencyCheckResult:
    request_id: str
    policy: str
    consistent: bool = True
    violations: List[str] = field(default_factory=list)
    idempotency_key: Optional[str] = None
    checksum: Optional[str] = None

    def add_violation(self, violation: str) -> None:
        self.violations.append(violation)
        self.consistent = False
