from .ticket_assignment import TicketAssignmentModule
from .metering_billing import MeteringBillingModule
from .sla_monitor import SLAMonitorModule
from .skill_graph import SkillGraphModule
from .multitenant import MultiTenantModule
from .workflow_designer import WorkflowDesignerModule
from .document_diff import DocumentDiffModule
from .approval_engine import ApprovalEngineModule

__all__ = [
    "TicketAssignmentModule",
    "MeteringBillingModule",
    "SLAMonitorModule",
    "SkillGraphModule",
    "MultiTenantModule",
    "WorkflowDesignerModule",
    "DocumentDiffModule",
    "ApprovalEngineModule",
]

MODULES = [
    TicketAssignmentModule,
    MeteringBillingModule,
    SLAMonitorModule,
    SkillGraphModule,
    MultiTenantModule,
    WorkflowDesignerModule,
    DocumentDiffModule,
    ApprovalEngineModule,
]
