import logging
from typing import Any, Dict, List, Optional

from src.domain.query.sql_parser import StreamSQLParser, ParsedStreamSQL
from src.domain.query.logical_plan import LogicalPlanBuilder, LogicalPlan
from src.domain.query.physical_plan import PhysicalPlanTranslator, PhysicalPlan
from src.domain.query.optimizer import PlanOptimizer

logger = logging.getLogger(__name__)


class QueryService:
    def __init__(self):
        self._parser = StreamSQLParser()
        self._plan_builder = LogicalPlanBuilder()
        self._translator = PhysicalPlanTranslator()
        self._optimizer = PlanOptimizer()
        self._query_cache: Dict[str, PhysicalPlan] = {}

    def parse_sql(self, sql: str) -> ParsedStreamSQL:
        parsed = self._parser.parse(sql)
        errors = self._parser.validate(parsed)
        if errors:
            logger.warning(f"SQL validation warnings: {errors}")
        return parsed

    def build_logical_plan(self, sql: str) -> LogicalPlan:
        parsed = self.parse_sql(sql)
        logical_plan = self._plan_builder.build(parsed)
        return logical_plan

    def optimize_plan(self, logical_plan: LogicalPlan) -> LogicalPlan:
        return self._optimizer.optimize(logical_plan)

    def translate_to_physical(self, logical_plan: LogicalPlan) -> PhysicalPlan:
        return self._translator.translate(logical_plan)

    def execute_query(self, sql: str, optimize: bool = True) -> Dict[str, Any]:
        if sql in self._query_cache:
            return {"physical_plan": self._query_cache[sql].to_dict(), "cached": True}

        parsed = self.parse_sql(sql)
        logical_plan = self._plan_builder.build(parsed)

        if optimize:
            logical_plan = self._optimizer.optimize(logical_plan)

        physical_plan = self._translator.translate(logical_plan)

        self._query_cache[sql] = physical_plan

        return {
            "sql": sql,
            "parsed": {
                "sql_type": parsed.sql_type.value,
                "sources": [s.name for s in parsed.sources],
                "columns": [c.name for c in parsed.columns],
                "window_type": parsed.window.window_type.value,
            },
            "logical_plan_cost": logical_plan.estimate_cost(),
            "physical_plan": physical_plan.to_dict(),
            "total_cost": physical_plan.total_cost(),
            "operators": [op.operator_type.value for op in physical_plan.get_operators()],
            "cached": False,
        }

    def explain_query(self, sql: str) -> str:
        parsed = self.parse_sql(sql)
        logical_plan = self._plan_builder.build(parsed)
        optimized = self._optimizer.optimize(logical_plan)
        return self._optimizer.explain(optimized)

    def validate_sql(self, sql: str) -> Dict[str, Any]:
        parsed = self._parser.parse(sql)
        errors = self._parser.validate(parsed)
        return {
            "valid": len(errors) == 0,
            "errors": errors,
            "sql_type": parsed.sql_type.value,
        }
