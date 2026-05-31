import pytest
import pytest_asyncio
from typing import Dict, Any

from sqlalchemy.ext.asyncio import AsyncSession

from modules.ticket_assignment.models import (
    Ticket,
    Agent,
    AssignmentResult,
    TicketCreate,
    AssignmentStrategy,
    TicketPriority,
    TicketStatus,
    AgentStatus,
)
from modules.ticket_assignment.service import TicketAssignmentService, AssignmentScorer


@pytest.mark.asyncio
async def test_assignment_scorer_skill_match():
    scorer = AssignmentScorer()

    required = {"python": 3.0, "django": 2.0}
    agent_skills = {"python": 4.0, "django": 3.0, "java": 2.0}

    score = scorer.calculate_skill_match(required, agent_skills)
    assert score > 0.0
    assert score <= 1.0


@pytest.mark.asyncio
async def test_assignment_scorer_load_score():
    scorer = AssignmentScorer()

    score = scorer.calculate_load_score(5, 10)
    assert score == 0.5

    score = scorer.calculate_load_score(0, 10)
    assert score == 1.0

    score = scorer.calculate_load_score(10, 10)
    assert score == 0.0


@pytest.mark.asyncio
async def test_assignment_scorer_final_score():
    scorer = AssignmentScorer()

    score = scorer.calculate_final_score(0.8, 0.7, 0.9, AssignmentStrategy.HYBRID)
    expected = 0.8 * 0.6 + 0.7 * 0.3 + 0.9 * 0.1
    assert abs(score - expected) < 0.001


@pytest.mark.asyncio
async def test_create_ticket(db_session: AsyncSession):
    service = TicketAssignmentService(db_session)

    ticket_data = TicketCreate(
        title="测试工单",
        description="测试工单描述",
        priority=TicketPriority.MEDIUM,
        required_skills={"python": 3.0},
        tenant_id="tnt_001",
        created_by="user_001",
    )

    ticket = await service.create_ticket(ticket_data)

    assert ticket.title == "测试工单"
    assert ticket.status == TicketStatus.UNASSIGNED
    assert ticket.required_skills == {"python": 3.0}


@pytest.mark.asyncio
async def test_create_agent(db_session: AsyncSession):
    service = TicketAssignmentService(db_session)

    agent = await service.create_agent(
        name="测试工程师",
        email="test@example.com",
        skills={"python": 4.0, "django": 3.0},
        max_concurrent_tickets=10,
        tenant_id="tnt_001",
    )

    assert agent.name == "测试工程师"
    assert agent.status == AgentStatus.AVAILABLE
    assert agent.skills == {"python": 4.0, "django": 3.0}


@pytest.mark.asyncio
async def test_assign_ticket_skill_match(db_session: AsyncSession):
    service = TicketAssignmentService(db_session)

    agent = await service.create_agent(
        name="测试工程师",
        email="test@example.com",
        skills={"python": 4.0, "django": 3.0},
        max_concurrent_tickets=10,
        tenant_id="tnt_001",
    )

    ticket_data = TicketCreate(
        title="测试工单",
        description="需要Python技能",
        priority=TicketPriority.HIGH,
        required_skills={"python": 3.0},
        tenant_id="tnt_001",
        created_by="user_001",
    )
    ticket = await service.create_ticket(ticket_data)

    result = await service.assign_ticket(ticket.ticket_id, AssignmentStrategy.SKILL_MATCH, "tnt_001")

    assert result is not None
    assert result.agent_id == agent.agent_id
    assert result.strategy == AssignmentStrategy.SKILL_MATCH
    assert result.match_score > 0.0


@pytest.mark.asyncio
async def test_assign_ticket_hybrid_strategy(db_session: AsyncSession):
    service = TicketAssignmentService(db_session)

    agent1 = await service.create_agent(
        name="工程师A",
        email="a@example.com",
        skills={"python": 4.0},
        current_tickets=5,
        max_concurrent_tickets=10,
        tenant_id="tnt_001",
    )

    agent2 = await service.create_agent(
        name="工程师B",
        email="b@example.com",
        skills={"python": 3.0},
        current_tickets=0,
        max_concurrent_tickets=10,
        tenant_id="tnt_001",
    )

    ticket_data = TicketCreate(
        title="测试工单",
        description="测试",
        priority=TicketPriority.MEDIUM,
        required_skills={"python": 3.0},
        tenant_id="tnt_001",
        created_by="user_001",
    )
    ticket = await service.create_ticket(ticket_data)

    result = await service.assign_ticket(ticket.ticket_id, AssignmentStrategy.HYBRID, "tnt_001")

    assert result is not None


@pytest.mark.asyncio
async def test_list_agents(db_session: AsyncSession):
    service = TicketAssignmentService(db_session)

    for i in range(5):
        await service.create_agent(
            name=f"工程师{i}",
            email=f"test{i}@example.com",
            skills={"python": 3.0 + i * 0.5},
            max_concurrent_tickets=10,
            tenant_id="tnt_001",
        )

    agents = await service.list_agents(tenant_id="tnt_001", status=AgentStatus.AVAILABLE)

    assert len(agents) == 5


@pytest.mark.asyncio
async def test_assign_ticket_no_available_agents(db_session: AsyncSession):
    service = TicketAssignmentService(db_session)

    await service.create_agent(
        name="忙碌工程师",
        email="busy@example.com",
        skills={"python": 4.0},
        current_tickets=10,
        max_concurrent_tickets=10,
        status=AgentStatus.BUSY,
        tenant_id="tnt_001",
    )

    ticket_data = TicketCreate(
        title="测试工单",
        description="测试",
        priority=TicketPriority.MEDIUM,
        required_skills={"python": 3.0},
        tenant_id="tnt_001",
        created_by="user_001",
    )
    ticket = await service.create_ticket(ticket_data)

    result = await service.assign_ticket(ticket.ticket_id, AssignmentStrategy.LEAST_LOADED, "tnt_001")

    assert result is None


@pytest.mark.asyncio
async def test_update_agent_status(db_session: AsyncSession):
    service = TicketAssignmentService(db_session)

    agent = await service.create_agent(
        name="测试工程师",
        email="test@example.com",
        skills={"python": 4.0},
        max_concurrent_tickets=10,
        tenant_id="tnt_001",
    )

    updated = await service.update_agent_status(
        agent.agent_id, AgentStatus.OFFLINE, "tnt_001"
    )

    assert updated.status == AgentStatus.OFFLINE


@pytest.mark.asyncio
async def test_get_assignment_history(db_session: AsyncSession):
    service = TicketAssignmentService(db_session)

    agent = await service.create_agent(
        name="测试工程师",
        email="test@example.com",
        skills={"python": 4.0},
        max_concurrent_tickets=10,
        tenant_id="tnt_001",
    )

    for i in range(3):
        ticket_data = TicketCreate(
            title=f"工单{i}",
            description="测试",
            priority=TicketPriority.MEDIUM,
            required_skills={"python": 3.0},
            tenant_id="tnt_001",
            created_by="user_001",
        )
        ticket = await service.create_ticket(ticket_data)
        await service.assign_ticket(ticket.ticket_id, AssignmentStrategy.ROUND_ROBIN, "tnt_001")

    history = await service.get_assignment_history(agent_id=agent.agent_id, tenant_id="tnt_001")

    assert len(history) >= 3
