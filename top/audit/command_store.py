from top.domain.audit.models import (
    ComplianceReport,
    CommandQueryResult,
    utc_now,
)
from top.domain.audit.stores import (
    CommandStore,
    AuditLogStore,
    InMemoryCommandStore,
    InMemoryAuditLogStore,
)
from top.domain.audit.services import (
    AuditCorrelator,
    ComplianceReporter,
)
from top.domain.audit.bus import (
    CommandBus,
    CommandHandler,
    get_command_bus,
)


def generate_id(prefix: str) -> str:
    from uuid import uuid4
    return f"{prefix}_{uuid4().hex[:12]}"


CQRSBus = CommandBus
AuditStore = AuditLogStore
InMemoryAuditStore = InMemoryAuditLogStore


__all__ = [
    "ComplianceReport",
    "CommandQueryResult",
    "CommandStore",
    "AuditLogStore",
    "AuditStore",
    "InMemoryCommandStore",
    "InMemoryAuditLogStore",
    "InMemoryAuditStore",
    "AuditCorrelator",
    "ComplianceReporter",
    "CommandBus",
    "CQRSBus",
    "CommandHandler",
    "get_command_bus",
    "utc_now",
    "generate_id",
]
