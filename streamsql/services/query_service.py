from __future__ import annotations

from typing import Any, Optional

from streamsql.core.config import ConfigManager
from streamsql.core.context import ProcessingContext
from streamsql.core.events import EventBus
from streamsql.modules.streaming_query.parser import StreamingQueryParser
from streamsql.modules.streaming_query.optimizer import LogicalPlanOptimizer
from streamsql.modules.streaming_query.physical_plan import PhysicalPlanTranslator


class QueryService:
    def __init__(self, config_manager: Optional[ConfigManager] = None):
        self.config_manager = config_manager or ConfigManager()
        self.event_bus = EventBus()
        self.parser = StreamingQueryParser()
        self.optimizer = LogicalPlanOptimizer()
        self.translator = PhysicalPlanTranslator()

    def parse_sql(self, sql: str, optimize: bool = True) -> dict[str, Any]:
        context = ProcessingContext(trace_id="parse_sql")

        parsed = self.parser.parse(sql)

        if optimize:
            optimized = self.optimizer.optimize(parsed)
        else:
            optimized = parsed

        physical = self.translator.translate(optimized)

        return {
            "original_sql": sql,
            "parsed": parsed.to_dict(),
            "optimized": optimized.to_dict() if optimize else None,
            "physical_plan": physical.to_dict(),
            "parse_time_ms": context.get_elapsed_ms(),
            "is_streaming": self.parser.is_streaming_query(sql),
        }

    def validate_sql(self, sql: str) -> dict[str, Any]:
        try:
            self.parser.parse(sql)
            return {"valid": True, "sql": sql}
        except Exception as e:
            return {"valid": False, "sql": sql, "error": str(e)}

    def get_query_ast(self, sql: str) -> dict[str, Any]:
        parsed = self.parser.parse(sql)
        return parsed.to_dict()

    def optimize_query(self, sql: str) -> dict[str, Any]:
        parsed = self.parser.parse(sql)
        optimized = self.optimizer.optimize(parsed)
        return {
            "original": parsed.to_dict(),
            "optimized": optimized.to_dict(),
            "optimizations_applied": self.optimizer.get_last_optimizations(),
        }

    def generate_physical_plan(self, sql: str) -> dict[str, Any]:
        parsed = self.parser.parse(sql)
        optimized = self.optimizer.optimize(parsed)
        physical = self.translator.translate(optimized)
        return physical.to_dict()

    def estimate_resources(self, sql: str) -> dict[str, Any]:
        parsed = self.parser.parse(sql)
        optimized = self.optimizer.optimize(parsed)
        physical = self.translator.translate(optimized)

        return {
            "estimated_memory_mb": physical.estimated_memory_bytes / (1024 * 1024),
            "estimated_cpu_cores": physical.estimated_cpu_cores,
            "estimated_duration_ms": physical.estimated_duration_ms,
            "parallelism": physical.parallelism,
        }

    def detect_window_functions(self, sql: str) -> list[dict[str, Any]]:
        return self.parser.extract_window_functions(sql)
