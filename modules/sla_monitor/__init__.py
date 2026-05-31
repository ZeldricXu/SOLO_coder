from .models import (
    SLAPolicy,
    SLATracker,
    SLAEvent,
    SLASeverity,
    SLATargetType,
    EscalationLevel,
    NotificationChannel,
    SLAPolicyCreate,
    SLAPolicyResponse,
    SLATrackerCreate,
    SLATrackerResponse,
    SLAEventResponse,
)
from .service import SLAPolicyService, SLATrackerService, SLAMonitorService
from .router import router

__all__ = [
    "SLAPolicy",
    "SLATracker",
    "SLAEvent",
    "SLASeverity",
    "SLATargetType",
    "EscalationLevel",
    "NotificationChannel",
    "SLAPolicyCreate",
    "SLAPolicyResponse",
    "SLATrackerCreate",
    "SLATrackerResponse",
    "SLAEventResponse",
    "SLAPolicyService",
    "SLATrackerService",
    "SLAMonitorService",
    "router",
]


class SLAMonitorModule:
    name = "sla_monitor"
    description = "工单/任务SLA倒计时、超时自动升级与通知模块"
    router = router

    def __init__(self):
        pass
