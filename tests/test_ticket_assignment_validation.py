import pytest
from typing import AsyncGenerator, Dict, Any

from sqlalchemy.ext.asyncio import AsyncSession

from pydantic import ValidationError as PydanticValidationError

from core.exceptions import ValidationError, ConflictError, NotFoundError
from modules.ticket_assignment.models import (
    TicketCreate,
    AgentCreate,
    TicketStatus,
    AssignmentStrategy,
)
from modules.ticket_assignment.service import (
    TicketAssignmentService,
    AssignmentScorer,
)
from tests.fixtures.data_factory import TicketAssignmentDataFactory


pytestmark = pytest.mark.asyncio


class TestTicketParameterValidation:
    async def test_create_ticket_with_valid_data(
        self,
        db_session: AsyncSession,
    ) -> None:
        service = TicketAssignmentService(db_session)
        ticket_data = TicketAssignmentDataFactory.create_ticket_data()
        ticket_data_obj = TicketCreate(**ticket_data)

        response = await service.create_ticket(ticket_data_obj)

        assert response.ticket_id is not None
        assert response.title == ticket_data["title"]
        assert response.status == TicketStatus.UNASSIGNED
        assert response.tenant_id == ticket_data["tenant_id"]
        assert response.requester_id == ticket_data["requester_id"]

    @pytest.mark.parametrize(
        "scenario,expected_exception",
        [
            ("empty_title", ValidationError),
            ("whitespace_title", ValidationError),
            ("null_title", PydanticValidationError),
            ("empty_requester", ValidationError),
            ("null_requester", ValidationError),
        ],
    )
    async def test_create_ticket_with_invalid_parameters(
        self,
        db_session: AsyncSession,
        scenario: str,
        expected_exception: type,
    ) -> None:
        service = TicketAssignmentService(db_session)
        invalid_data = TicketAssignmentDataFactory.create_invalid_ticket_data(scenario)

        with pytest.raises(expected_exception):
            ticket_data_obj = TicketCreate(**invalid_data)
            await service.create_ticket(ticket_data_obj)

    async def test_create_ticket_with_missing_required_fields(
        self,
        db_session: AsyncSession,
    ) -> None:
        service = TicketAssignmentService(db_session)
        invalid_data = TicketAssignmentDataFactory.create_invalid_ticket_data("missing_title")

        with pytest.raises(PydanticValidationError):
            ticket_data_obj = TicketCreate(**invalid_data)
            await service.create_ticket(ticket_data_obj)

    async def test_create_ticket_with_duplicate_email(
        self,
        db_session: AsyncSession,
    ) -> None:
        service = TicketAssignmentService(db_session)
        agent_data = TicketAssignmentDataFactory.create_agent_data()

        await service.create_agent(**agent_data)
        await db_session.commit()

        with pytest.raises(ConflictError) as exc_info:
            await service.create_agent(**agent_data)

        assert "已被使用" in str(exc_info.value)


class TestAgentParameterValidation:
    async def test_create_agent_with_valid_data(
        self,
        db_session: AsyncSession,
    ) -> None:
        service = TicketAssignmentService(db_session)
        agent_data = TicketAssignmentDataFactory.create_agent_data()

        response = await service.create_agent(**agent_data)

        assert response.agent_id is not None
        assert response.name == agent_data["name"]
        assert response.email == agent_data["email"]
        assert response.status == agent_data["status"]
        assert response.max_concurrent_tickets == agent_data["max_concurrent_tickets"]

    @pytest.mark.parametrize(
        "scenario",
        [
            "empty_name",
            "whitespace_name",
            "null_name",
            "empty_email",
            "null_email",
        ],
    )
    async def test_create_agent_with_invalid_parameters(
        self,
        db_session: AsyncSession,
        scenario: str,
    ) -> None:
        service = TicketAssignmentService(db_session)
        invalid_data = TicketAssignmentDataFactory.create_invalid_agent_data(scenario)

        with pytest.raises(ValidationError) as exc_info:
            await service.create_agent(**invalid_data)

        assert "参数校验失败" in str(exc_info.value)

    async def test_create_agent_with_missing_required_fields(
        self,
        db_session: AsyncSession,
    ) -> None:
        service = TicketAssignmentService(db_session)

        with pytest.raises(TypeError):
            await service.create_agent(email="agent@example.com")

    @pytest.mark.parametrize(
        "scenario",
        [
            "invalid_email_no_at",
        ],
    )
    async def test_create_agent_with_invalid_email_format(
        self,
        db_session: AsyncSession,
        scenario: str,
    ) -> None:
        service = TicketAssignmentService(db_session)
        invalid_data = TicketAssignmentDataFactory.create_invalid_agent_data(scenario)

        with pytest.raises(ValidationError) as exc_info:
            await service.create_agent(**invalid_data)

        assert "参数校验失败" in str(exc_info.value)

    async def test_create_agent_without_tenant_id(
        self,
        db_session: AsyncSession,
    ) -> None:
        service = TicketAssignmentService(db_session)
        agent_data = TicketAssignmentDataFactory.create_agent_data()
        agent_data["tenant_id"] = None

        response = await service.create_agent(**agent_data)

        assert response.agent_id is not None
        assert response.tenant_id is None


class TestTicketAssignmentValidation:
    async def test_assign_ticket_with_valid_data(
        self,
        db_session: AsyncSession,
    ) -> None:
        service = TicketAssignmentService(db_session)

        agent_data = TicketAssignmentDataFactory.create_agent_data(
            skills={"python": 0.9, "java": 0.7}
        )
        await service.create_agent(**agent_data)
        await db_session.commit()

        ticket_data = TicketAssignmentDataFactory.create_ticket_data(
            required_skills={"python": 0.8}
        )
        ticket_data_obj = TicketCreate(**ticket_data)
        ticket = await service.create_ticket(ticket_data_obj)
        await db_session.commit()

        assignment = await service.assign_ticket(
            ticket_id=ticket.ticket_id,
            tenant_id=ticket.tenant_id,
        )

        assert assignment is not None
        assert assignment.ticket_id == ticket.ticket_id
        assert assignment.agent_id is not None
        assert assignment.final_score > 0.0

    async def test_assign_nonexistent_ticket(
        self,
        db_session: AsyncSession,
    ) -> None:
        service = TicketAssignmentService(db_session)

        with pytest.raises(NotFoundError) as exc_info:
            await service.assign_ticket(
                ticket_id="non_existent_ticket",
                tenant_id="tnt_001",
            )

        assert "不存在" in str(exc_info.value)

    async def test_assign_ticket_with_invalid_status(
        self,
        db_session: AsyncSession,
    ) -> None:
        service = TicketAssignmentService(db_session)

        agent_data = TicketAssignmentDataFactory.create_agent_data()
        await service.create_agent(**agent_data)
        await db_session.commit()

        ticket_data = TicketAssignmentDataFactory.create_ticket_data()
        ticket_data_obj = TicketCreate(**ticket_data)
        ticket = await service.create_ticket(ticket_data_obj)
        await db_session.commit()

        await service.update_ticket_status(
            ticket_id=ticket.ticket_id,
            new_status=TicketStatus.IN_PROGRESS,
            tenant_id=ticket.tenant_id,
        )
        await db_session.commit()

        with pytest.raises(ConflictError) as exc_info:
            await service.assign_ticket(
                ticket_id=ticket.ticket_id,
                tenant_id=ticket.tenant_id,
            )

        assert "状态不允许分配" in str(exc_info.value)

    async def test_assign_ticket_without_available_agents(
        self,
        db_session: AsyncSession,
    ) -> None:
        service = TicketAssignmentService(db_session)

        ticket_data = TicketAssignmentDataFactory.create_ticket_data()
        ticket_data_obj = TicketCreate(**ticket_data)
        ticket = await service.create_ticket(ticket_data_obj)
        await db_session.commit()

        assignment = await service.assign_ticket(
            ticket_id=ticket.ticket_id,
            tenant_id=ticket.tenant_id,
        )

        assert assignment is None


class TestSkillMatchingValidation:
    @pytest.mark.parametrize(
        "scenario",
        TicketAssignmentDataFactory.create_skill_matching_scenarios(),
    )
    def test_skill_matching_scenarios(
        self,
        scenario: Dict[str, Any],
    ) -> None:
        scorer = AssignmentScorer()
        score = scorer.calculate_skill_match(scenario["required"], scenario["agent"])

        assert abs(score - scenario["expected_score"]) < 0.01, (
            f"场景 '{scenario['name']}' 失败: "
            f"期望 {scenario['expected_score']}, 实际 {score}"
        )

    def test_skill_match_with_zero_required_level(
        self,
    ) -> None:
        scorer = AssignmentScorer()
        required = {"python": 0.0}
        agent = {"python": 0.5}

        score = scorer.calculate_skill_match(required, agent)

        assert score == 0.0

    def test_load_score_with_zero_max_concurrent(
        self,
    ) -> None:
        scorer = AssignmentScorer()

        score = scorer.calculate_load_score(current_tickets=5, max_concurrent_tickets=0)

        assert score == 0.0

    @pytest.mark.parametrize(
        "strategy,expected_skill_weight,expected_load_weight,expected_efficiency_weight",
        [
            (AssignmentStrategy.SKILL_MATCH, 1.0, 0.0, 0.0),
            (AssignmentStrategy.LEAST_LOADED, 0.0, 1.0, 0.0),
            (AssignmentStrategy.ROUND_ROBIN, 0.0, 0.0, 0.0),
            (AssignmentStrategy.HYBRID, 0.6, 0.3, 0.1),
        ],
    )
    def test_assignment_strategy_weights(
        self,
        strategy: AssignmentStrategy,
        expected_skill_weight: float,
        expected_load_weight: float,
        expected_efficiency_weight: float,
    ) -> None:
        scorer = AssignmentScorer()
        skill_match = 0.8
        load_score = 0.7
        efficiency = 0.9

        final_score = scorer.calculate_final_score(
            skill_match, load_score, efficiency, strategy
        )

        expected = (
            skill_match * expected_skill_weight
            + load_score * expected_load_weight
            + efficiency * expected_efficiency_weight
        )

        if strategy == AssignmentStrategy.ROUND_ROBIN:
            assert final_score == 1.0
        else:
            assert abs(final_score - expected) < 0.01


class TestTicketStatusTransitionValidation:
    async def test_close_closed_ticket_should_succeed(
        self,
        db_session: AsyncSession,
    ) -> None:
        service = TicketAssignmentService(db_session)
        ticket_data = TicketAssignmentDataFactory.create_ticket_data()
        ticket_data_obj = TicketCreate(**ticket_data)
        ticket = await service.create_ticket(ticket_data_obj)
        await db_session.commit()

        await service.update_ticket_status(
            ticket_id=ticket.ticket_id,
            new_status=TicketStatus.CLOSED,
            tenant_id=ticket.tenant_id,
        )
        await db_session.commit()

        result = await service.update_ticket_status(
            ticket_id=ticket.ticket_id,
            new_status=TicketStatus.CLOSED,
            tenant_id=ticket.tenant_id,
        )

        assert result.status == TicketStatus.CLOSED

    async def test_reopen_closed_ticket_should_fail(
        self,
        db_session: AsyncSession,
    ) -> None:
        service = TicketAssignmentService(db_session)
        ticket_data = TicketAssignmentDataFactory.create_ticket_data()
        ticket_data_obj = TicketCreate(**ticket_data)
        ticket = await service.create_ticket(ticket_data_obj)
        await db_session.commit()

        await service.update_ticket_status(
            ticket_id=ticket.ticket_id,
            new_status=TicketStatus.CLOSED,
            tenant_id=ticket.tenant_id,
        )
        await db_session.commit()

        with pytest.raises(ConflictError) as exc_info:
            await service.update_ticket_status(
                ticket_id=ticket.ticket_id,
                new_status=TicketStatus.IN_PROGRESS,
                tenant_id=ticket.tenant_id,
            )

        assert "无法重新打开" in str(exc_info.value)

    async def test_complete_ticket_releases_agent_load(
        self,
        db_session: AsyncSession,
    ) -> None:
        service = TicketAssignmentService(db_session)

        agent_data = TicketAssignmentDataFactory.create_agent_data()
        agent = await service.create_agent(**agent_data)
        await db_session.commit()

        ticket_data = TicketAssignmentDataFactory.create_ticket_data(
            required_skills={"python": 0.8}
        )
        ticket_data_obj = TicketCreate(**ticket_data)
        ticket = await service.create_ticket(ticket_data_obj)
        await db_session.commit()

        await service.assign_ticket(
            ticket_id=ticket.ticket_id,
            tenant_id=ticket.tenant_id,
        )
        await db_session.commit()

        agents = await service.list_agents(tenant_id=ticket.tenant_id)
        assert agents[0].current_tickets == 1

        await service.update_ticket_status(
            ticket_id=ticket.ticket_id,
            new_status=TicketStatus.RESOLVED,
            tenant_id=ticket.tenant_id,
        )
        await db_session.commit()

        agents = await service.list_agents(tenant_id=ticket.tenant_id)
        assert agents[0].current_tickets == 0
