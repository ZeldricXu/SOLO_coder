from typing import Any, Dict, List, Optional
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from core import BaseRepository, NotFoundError, emit_event, EventTypes
from .models import Rule
from .schemas import RuleCreate, RuleUpdate
from .engine import EdgeRuleEngine


class RuleRepository(BaseRepository):
    async def create(self, rule_data: Dict[str, Any]) -> Rule:
        rule = Rule(**rule_data)
        self.db.add(rule)
        await self.db.flush()
        return rule

    async def get_by_id(self, rule_id: str) -> Optional[Rule]:
        result = await self.db.execute(select(Rule).where(Rule.id == rule_id))
        return result.scalar_one_or_none()

    async def get_by_rule_id(self, rule_id: str) -> Optional[Rule]:
        result = await self.db.execute(select(Rule).where(Rule.rule_id == rule_id))
        return result.scalar_one_or_none()

    async def list(
        self,
        skip: int = 0,
        limit: int = 100,
        edge_node_id: Optional[str] = None,
        enabled: Optional[bool] = None,
    ) -> List[Rule]:
        query = select(Rule)
        if edge_node_id:
            query = query.where(Rule.edge_node_id == edge_node_id)
        if enabled is not None:
            query = query.where(Rule.enabled == enabled)
        query = query.offset(skip).limit(limit).order_by(Rule.priority.desc())
        result = await self.db.execute(query)
        return list(result.scalars().all())

    async def update(self, rule: Rule, update_data: Dict[str, Any]) -> Rule:
        for key, value in update_data.items():
            if value is not None:
                setattr(rule, key, value)
        await self.db.flush()
        return rule

    async def delete(self, rule: Rule) -> None:
        await self.db.delete(rule)


class RuleService:
    def __init__(self, db: AsyncSession):
        self.repository = RuleRepository(db)
        self.engine = EdgeRuleEngine()
        self._engine_initialized = False

    async def _ensure_engine_initialized(self) -> None:
        if not self._engine_initialized:
            await self.engine.initialize()
            self._engine_initialized = True

    async def create_rule(self, data: RuleCreate) -> Rule:
        await self._ensure_engine_initialized()
        rule_dict = data.model_dump()
        rule_dict["type"] = "edge_rule"
        rule_dict["status"] = "active" if data.enabled else "inactive"
        rule = await self.repository.create(rule_dict)
        await self.engine.register_rule(rule.to_dict())

        emit_event(
            EventTypes.RULE_TRIGGERED,
            "rule_service",
            {"rule_id": rule.id, "action": "created"},
        )
        return rule

    async def get_rule(self, rule_id: str) -> Rule:
        rule = await self.repository.get_by_id(rule_id)
        if not rule:
            raise NotFoundError("Rule", rule_id)
        return rule

    async def list_rules(
        self,
        skip: int = 0,
        limit: int = 100,
        edge_node_id: Optional[str] = None,
        enabled: Optional[bool] = None,
    ) -> List[Rule]:
        return await self.repository.list(skip, limit, edge_node_id, enabled)

    async def update_rule(self, rule_id: str, data: RuleUpdate) -> Rule:
        await self._ensure_engine_initialized()
        rule = await self.get_rule(rule_id)
        update_dict = data.model_dump(exclude_unset=True)
        updated_rule = await self.repository.update(rule, update_dict)
        await self.engine.register_rule(updated_rule.to_dict())
        return updated_rule

    async def delete_rule(self, rule_id: str) -> None:
        await self._ensure_engine_initialized()
        rule = await self.get_rule(rule_id)
        await self.repository.delete(rule)
        await self.engine.unregister_rule(rule_id)

    async def execute_rule(
        self,
        rule_id: str,
        input_data: Dict[str, Any],
        context: Optional[Dict[str, Any]] = None,
    ) -> Dict[str, Any]:
        await self._ensure_engine_initialized()
        return await self.engine.execute_rule(rule_id, input_data, context)

    async def process_data(
        self,
        input_data: Dict[str, Any],
        context: Optional[Dict[str, Any]] = None,
    ) -> List[Dict[str, Any]]:
        await self._ensure_engine_initialized()
        return await self.engine.process_data(input_data, context)

    async def load_rules_to_engine(self) -> None:
        await self._ensure_engine_initialized()
        rules = await self.repository.list(limit=1000, enabled=True)
        for rule in rules:
            await self.engine.register_rule(rule.to_dict())
