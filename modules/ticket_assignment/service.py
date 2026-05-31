import asyncio
from dataclasses import dataclass
from typing import Any, Dict, List, Optional, Tuple

from sqlalchemy import select, and_
from sqlalchemy.ext.asyncio import AsyncSession

from core.exceptions import ValidationError, NotFoundError, ConflictError
from core.utils import validate_params, utc_now
from .models import (
    Ticket,
    TicketCreate,
    TicketResponse,
    TicketStatus,
    Agent,
    AgentCreate,
    AgentResponse,
    AgentStatus,
    AssignmentResult,
    AssignmentResultResponse,
    AssignmentStrategy,
)


@dataclass
class AssignmentScorer:
    skill_weight: float = 0.6
    load_weight: float = 0.3
    efficiency_weight: float = 0.1

    def calculate_skill_match(
        self, required_skills: Dict[str, float], agent_skills: Dict[str, float]
    ) -> float:
        if not required_skills:
            return 1.0

        total_score = 0.0
        total_weight = 0.0

        for skill, required_level in required_skills.items():
            agent_level = agent_skills.get(skill, 0.0)
            if agent_level >= required_level:
                score = 1.0
            else:
                score = agent_level / required_level if required_level > 0 else 0.0
            total_score += score * required_level
            total_weight += required_level

        return total_score / total_weight if total_weight > 0 else 0.0

    def calculate_load_score(self, current_tickets: int, max_concurrent_tickets: int) -> float:
        if max_concurrent_tickets <= 0:
            return 0.0
        load_ratio = current_tickets / max_concurrent_tickets
        return max(0.0, 1.0 - load_ratio)

    def calculate_final_score(
        self,
        skill_match: float,
        load_score: float,
        efficiency_score: float,
        strategy: AssignmentStrategy = AssignmentStrategy.HYBRID,
    ) -> float:
        if strategy == AssignmentStrategy.SKILL_MATCH:
            return skill_match
        elif strategy == AssignmentStrategy.LEAST_LOADED:
            return load_score
        elif strategy == AssignmentStrategy.ROUND_ROBIN:
            return 1.0
        else:
            return (
                skill_match * self.skill_weight
                + load_score * self.load_weight
                + efficiency_score * self.efficiency_weight
            )


class TicketAssignmentService:
    def __init__(self, db: AsyncSession, scorer: Optional[AssignmentScorer] = None):
        self.db = db
        self.scorer = scorer or AssignmentScorer()

    async def create_ticket(self, ticket_data: TicketCreate) -> TicketResponse:
        validation_rules = {
            "title": lambda x: x is not None and len(x.strip()) > 0,
            "requester_id": lambda x: x is not None and len(x) > 0,
        }
        validate_params(ticket_data.model_dump(), validation_rules)

        ticket = Ticket(**ticket_data.model_dump())
        ticket.status = TicketStatus.UNASSIGNED
        self.db.add(ticket)
        await self.db.flush()

        return TicketResponse.model_validate(ticket)

    async def get_ticket(self, ticket_id: str, tenant_id: Optional[str] = None) -> TicketResponse:
        query = select(Ticket).where(Ticket.ticket_id == ticket_id)
        if tenant_id:
            query = query.where(Ticket.tenant_id == tenant_id)

        result = await self.db.execute(query)
        ticket = result.scalar_one_or_none()

        if not ticket:
            raise NotFoundError(f"工单 {ticket_id} 不存在")

        return TicketResponse.model_validate(ticket)

    async def list_tickets(
        self,
        tenant_id: Optional[str] = None,
        status: Optional[TicketStatus] = None,
        agent_id: Optional[str] = None,
        limit: int = 50,
        offset: int = 0,
    ) -> List[TicketResponse]:
        query = select(Ticket)
        conditions = []

        if tenant_id:
            conditions.append(Ticket.tenant_id == tenant_id)
        if status:
            conditions.append(Ticket.status == status)
        if agent_id:
            conditions.append(Ticket.assigned_agent_id == agent_id)

        if conditions:
            query = query.where(and_(*conditions))

        query = query.order_by(Ticket.created_at.desc()).limit(limit).offset(offset)
        result = await self.db.execute(query)
        tickets = result.scalars().all()

        return [TicketResponse.model_validate(t) for t in tickets]

    async def create_agent(
        self,
        name: str,
        email: str,
        skills: Dict[str, float] = None,
        max_concurrent_tickets: int = 10,
        tenant_id: Optional[str] = None,
        current_tickets: int = 0,
        status: Optional[str] = None,
        department: Optional[str] = None,
    ) -> AgentResponse:
        validation_rules = {
            "name": lambda x: x is not None and len(x.strip()) > 0,
            "email": lambda x: x is not None and "@" in x,
        }
        validate_params(
            {"name": name, "email": email},
            validation_rules,
        )

        query = select(Agent).where(Agent.email == email)
        result = await self.db.execute(query)
        if result.scalar_one_or_none():
            raise ConflictError(f"邮箱 {email} 已被使用")

        agent_data = AgentCreate(
            name=name,
            email=email,
            department=department,
            skills=skills or {},
            current_tickets=current_tickets,
            max_concurrent_tickets=max_concurrent_tickets,
            status=status or "available",
            tenant_id=tenant_id,
        )
        agent = Agent(**agent_data.model_dump())
        self.db.add(agent)
        await self.db.flush()

        return AgentResponse.model_validate(agent)

    async def get_available_agents(
        self, tenant_id: Optional[str] = None
    ) -> List[AgentResponse]:
        query = select(Agent).where(Agent.status == "available")
        if tenant_id:
            query = query.where(Agent.tenant_id == tenant_id)

        result = await self.db.execute(query)
        agents = result.scalars().all()

        return [AgentResponse.model_validate(a) for a in agents]

    async def find_best_agent(
        self, ticket: Ticket, agents: List[Agent]
    ) -> Tuple[Optional[Agent], AssignmentResult]:
        best_agent = None
        best_score = -1.0
        best_skill_score = 0.0
        best_load_score = 0.0

        for agent in agents:
            if agent.current_tickets >= agent.max_concurrent_tickets:
                continue

            skill_match = self.scorer.calculate_skill_match(ticket.required_skills, agent.skills)
            load_score = self.scorer.calculate_load_score(agent.current_tickets, agent.max_concurrent_tickets)
            final_score = self.scorer.calculate_final_score(
                skill_match, load_score, agent.efficiency_score, ticket.assignment_strategy
            )

            if final_score > best_score:
                best_score = final_score
                best_agent = agent
                best_skill_score = skill_match
                best_load_score = load_score

        assignment_result = AssignmentResult(
            ticket_id=ticket.ticket_id,
            agent_id=best_agent.agent_id if best_agent else "",
            skill_match_score=best_skill_score,
            load_score=best_load_score,
            final_score=best_score,
            strategy=ticket.assignment_strategy,
            tenant_id=ticket.tenant_id,
            meta_data={
                "strategy_used": ticket.assignment_strategy,
                "candidates_count": len(agents),
            },
        )

        return best_agent, assignment_result

    async def assign_ticket(
        self,
        ticket_id: str,
        strategy: AssignmentStrategy = AssignmentStrategy.HYBRID,
        tenant_id: Optional[str] = None,
    ) -> Optional[AssignmentResultResponse]:
        query = select(Ticket).where(Ticket.ticket_id == ticket_id)
        if tenant_id:
            query = query.where(Ticket.tenant_id == tenant_id)

        result = await self.db.execute(query)
        ticket = result.scalar_one_or_none()

        if not ticket:
            raise NotFoundError(f"工单 {ticket_id} 不存在")

        if ticket.status not in [TicketStatus.NEW, TicketStatus.UNASSIGNED]:
            raise ConflictError(f"工单 {ticket.ticket_id} 状态不允许分配")

        ticket.assignment_strategy = strategy

        query = select(Agent).where(
            and_(
                Agent.status == AgentStatus.AVAILABLE,
                Agent.current_tickets < Agent.max_concurrent_tickets,
            )
        )
        if tenant_id:
            query = query.where(Agent.tenant_id == tenant_id)
        elif ticket.tenant_id:
            query = query.where(Agent.tenant_id == ticket.tenant_id)

        result = await self.db.execute(query)
        agents = result.scalars().all()

        if not agents:
            return None

        best_agent, assignment_result = await self.find_best_agent(ticket, agents)

        if not best_agent:
            return None

        ticket.assigned_agent_id = best_agent.agent_id
        ticket.status = TicketStatus.ASSIGNED
        ticket.assignment_score = assignment_result.final_score
        assignment_result.agent_id = best_agent.agent_id

        best_agent.current_tickets += 1

        self.db.add(ticket)
        self.db.add(best_agent)
        self.db.add(assignment_result)
        await self.db.flush()

        return AssignmentResultResponse(
            **assignment_result.__dict__,
            agent_name=best_agent.name,
        )

    async def get_assignment_history(
        self, ticket_id: str, tenant_id: Optional[str] = None
    ) -> List[AssignmentResultResponse]:
        query = select(AssignmentResult).where(AssignmentResult.ticket_id == ticket_id)
        if tenant_id:
            query = query.where(AssignmentResult.tenant_id == tenant_id)

        result = await self.db.execute(query)
        assignments = result.scalars().all()

        agent_ids = [a.agent_id for a in assignments if a.agent_id]
        if agent_ids:
            agent_query = select(Agent).where(Agent.agent_id.in_(agent_ids))
            agent_result = await self.db.execute(agent_query)
            agents = {a.agent_id: a.name for a in agent_result.scalars().all()}
        else:
            agents = {}

        return [
            AssignmentResultResponse(
                **a.__dict__,
                agent_name=agents.get(a.agent_id),
            )
            for a in assignments
        ]

    async def update_ticket_status(
        self, ticket_id: str, new_status: TicketStatus, tenant_id: Optional[str] = None
    ) -> TicketResponse:
        query = select(Ticket).where(Ticket.ticket_id == ticket_id)
        if tenant_id:
            query = query.where(Ticket.tenant_id == tenant_id)

        result = await self.db.execute(query)
        ticket = result.scalar_one_or_none()

        if not ticket:
            raise NotFoundError(f"工单 {ticket_id} 不存在")

        if ticket.status == TicketStatus.CLOSED and new_status != TicketStatus.CLOSED:
            raise ConflictError("已关闭的工单无法重新打开")

        if (
            ticket.status in [TicketStatus.ASSIGNED, TicketStatus.IN_PROGRESS]
            and new_status in [TicketStatus.RESOLVED, TicketStatus.CLOSED]
            and ticket.assigned_agent_id
        ):
            agent_query = select(Agent).where(Agent.agent_id == ticket.assigned_agent_id)
            agent_result = await self.db.execute(agent_query)
            agent = agent_result.scalar_one_or_none()
            if agent and agent.current_tickets > 0:
                agent.current_tickets -= 1
                self.db.add(agent)

        ticket.status = new_status
        self.db.add(ticket)
        await self.db.flush()

        return TicketResponse.model_validate(ticket)

    async def list_agents(
        self,
        tenant_id: Optional[str] = None,
        status: Optional[AgentStatus] = None,
        limit: int = 100,
        offset: int = 0,
    ) -> List[AgentResponse]:
        query = select(Agent)
        conditions = []

        if tenant_id:
            conditions.append(Agent.tenant_id == tenant_id)
        if status:
            conditions.append(Agent.status == status)

        if conditions:
            query = query.where(and_(*conditions))

        query = query.order_by(Agent.created_at.desc()).limit(limit).offset(offset)
        result = await self.db.execute(query)
        agents = result.scalars().all()

        return [AgentResponse.model_validate(a) for a in agents]

    async def update_agent_status(
        self,
        agent_id: str,
        new_status: AgentStatus,
        tenant_id: Optional[str] = None,
    ) -> AgentResponse:
        query = select(Agent).where(Agent.agent_id == agent_id)
        if tenant_id:
            query = query.where(Agent.tenant_id == tenant_id)

        result = await self.db.execute(query)
        agent = result.scalar_one_or_none()

        if not agent:
            raise NotFoundError(f"客服 {agent_id} 不存在")

        agent.status = new_status
        self.db.add(agent)
        await self.db.flush()

        return AgentResponse.model_validate(agent)

    async def get_assignment_history(
        self,
        ticket_id: Optional[str] = None,
        agent_id: Optional[str] = None,
        tenant_id: Optional[str] = None,
    ) -> List[AssignmentResultResponse]:
        query = select(AssignmentResult)
        conditions = []

        if ticket_id:
            conditions.append(AssignmentResult.ticket_id == ticket_id)
        if agent_id:
            conditions.append(AssignmentResult.agent_id == agent_id)
        if tenant_id:
            conditions.append(AssignmentResult.tenant_id == tenant_id)

        if conditions:
            query = query.where(and_(*conditions))

        query = query.order_by(AssignmentResult.assigned_at.desc())
        result = await self.db.execute(query)
        assignments = result.scalars().all()

        agent_ids = [a.agent_id for a in assignments if a.agent_id]
        if agent_ids:
            agent_query = select(Agent).where(Agent.agent_id.in_(agent_ids))
            agent_result = await self.db.execute(agent_query)
            agents = {a.agent_id: a.name for a in agent_result.scalars().all()}
        else:
            agents = {}

        return [
            AssignmentResultResponse(
                **a.__dict__,
                agent_name=agents.get(a.agent_id),
            )
            for a in assignments
        ]
