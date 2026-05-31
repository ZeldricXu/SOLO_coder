from top.domain.audit import (
    CommandBus,
    CommandStore,
    InMemoryCommandStore,
    AuditLogStore,
    InMemoryAuditLogStore,
    AuditCorrelator,
    ComplianceReporter,
    ComplianceReport,
    CommandQueryResult,
)

from top.domain.audit.bus import (
    get_command_bus,
    CommandHandler,
)

from top.domain.audit.models import (
    utc_now,
)


def generate_id(prefix: str) -> str:
    from uuid import uuid4
    return f"{prefix}_{uuid4().hex[:12]}"


CQRSBus = CommandBus
AuditStore = AuditLogStore
InMemoryAuditStore = InMemoryAuditLogStore


__all__ = [
    "CommandBus",
    "CQRSBus",
    "CommandStore",
    "InMemoryCommandStore",
    "AuditLogStore",
    "AuditStore",
    "InMemoryAuditLogStore",
    "InMemoryAuditStore",
    "AuditCorrelator",
    "ComplianceReporter",
    "ComplianceReport",
    "CommandQueryResult",
    "CommandHandler",
    "get_command_bus",
    "utc_now",
    "generate_id",
]
