from .models import (
    Ticket,
    Agent,
    AssignmentResult,
    TicketPriority,
    TicketStatus,
    TicketChannel,
    AssignmentStrategy,
    TicketCreate,
    TicketResponse,
    AgentCreate,
    AgentResponse,
    AssignmentResultResponse,
)
from .service import TicketAssignmentService, AssignmentScorer
from .router import router

__all__ = [
    "Ticket",
    "Agent",
    "AssignmentResult",
    "TicketPriority",
    "TicketStatus",
    "TicketChannel",
    "AssignmentStrategy",
    "TicketCreate",
    "TicketResponse",
    "AgentCreate",
    "AgentResponse",
    "AssignmentResultResponse",
    "TicketAssignmentService",
    "AssignmentScorer",
    "router",
]


class TicketAssignmentModule:
    name = "ticket_assignment"
    description = "基于技能匹配与负载均衡的工单路由分配模块"
    router = router

    def __init__(self):
        pass
