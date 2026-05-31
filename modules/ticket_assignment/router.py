from typing import Any, Dict, List, Optional

from fastapi import APIRouter, Depends, Query
from sqlalchemy.ext.asyncio import AsyncSession

from core.database import get_db
from core.utils import generate_id
from .models import (
    TicketCreate,
    TicketResponse,
    TicketStatus,
    AgentCreate,
    AgentResponse,
    AssignmentResultResponse,
)
from .service import TicketAssignmentService

router = APIRouter(prefix="/tickets", tags=["工单智能分配"])


@router.post("", response_model=Dict[str, Any], status_code=201)
async def create_ticket(
    ticket_data: TicketCreate,
    db: AsyncSession = Depends(get_db),
):
    service = TicketAssignmentService(db)
    ticket = await service.create_ticket(ticket_data)
    return {
        "code": 201,
        "data": ticket.model_dump(),
        "message": "工单创建成功",
    }


@router.get("/{ticket_id}", response_model=Dict[str, Any])
async def get_ticket(
    ticket_id: str,
    tenant_id: Optional[str] = Query(None),
    db: AsyncSession = Depends(get_db),
):
    service = TicketAssignmentService(db)
    ticket = await service.get_ticket(ticket_id, tenant_id)
    return {
        "code": 200,
        "data": ticket.model_dump(),
        "message": "查询成功",
    }


@router.get("", response_model=Dict[str, Any])
async def list_tickets(
    tenant_id: Optional[str] = Query(None),
    status: Optional[TicketStatus] = Query(None),
    agent_id: Optional[str] = Query(None),
    limit: int = Query(50, ge=1, le=200),
    offset: int = Query(0, ge=0),
    db: AsyncSession = Depends(get_db),
):
    service = TicketAssignmentService(db)
    tickets = await service.list_tickets(tenant_id, status, agent_id, limit, offset)
    return {
        "code": 200,
        "data": [t.model_dump() for t in tickets],
        "total": len(tickets),
        "message": "查询成功",
    }


@router.post("/{ticket_id}/assign", response_model=Dict[str, Any])
async def assign_ticket(
    ticket_id: str,
    tenant_id: Optional[str] = Query(None),
    db: AsyncSession = Depends(get_db),
):
    service = TicketAssignmentService(db)
    ticket = await service.get_ticket(ticket_id, tenant_id)
    assignment = await service.assign_ticket(ticket)
    return {
        "code": 200,
        "data": assignment.model_dump() if assignment else None,
        "message": "工单分配成功",
    }


@router.patch("/{ticket_id}/status", response_model=Dict[str, Any])
async def update_ticket_status(
    ticket_id: str,
    status: TicketStatus,
    tenant_id: Optional[str] = Query(None),
    db: AsyncSession = Depends(get_db),
):
    service = TicketAssignmentService(db)
    ticket = await service.update_ticket_status(ticket_id, status, tenant_id)
    return {
        "code": 200,
        "data": ticket.model_dump(),
        "message": "状态更新成功",
    }


@router.get("/{ticket_id}/assignment-history", response_model=Dict[str, Any])
async def get_assignment_history(
    ticket_id: str,
    tenant_id: Optional[str] = Query(None),
    db: AsyncSession = Depends(get_db),
):
    service = TicketAssignmentService(db)
    history = await service.get_assignment_history(ticket_id, tenant_id)
    return {
        "code": 200,
        "data": [h.model_dump() for h in history],
        "message": "查询成功",
    }


@router.post("/agents", response_model=Dict[str, Any], status_code=201)
async def create_agent(
    agent_data: AgentCreate,
    db: AsyncSession = Depends(get_db),
):
    service = TicketAssignmentService(db)
    agent = await service.create_agent(agent_data)
    return {
        "code": 201,
        "data": agent.model_dump(),
        "message": "客服创建成功",
    }


@router.get("/agents/available", response_model=Dict[str, Any])
async def get_available_agents(
    tenant_id: Optional[str] = Query(None),
    db: AsyncSession = Depends(get_db),
):
    service = TicketAssignmentService(db)
    agents = await service.get_available_agents(tenant_id)
    return {
        "code": 200,
        "data": [a.model_dump() for a in agents],
        "message": "查询成功",
    }
