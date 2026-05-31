"""Streaming query module for SQL parsing, logical plan optimization, and physical plan translation."""
from __future__ import annotations

import asyncio
from dataclasses import dataclass, field
from datetime import datetime
from typing import Any, Dict, List, Optional
from uuid import UUID, uuid4

from ...domain.errors.common import ValidationError
from ...domain.models.common import EventMessage, ProcessingResult, ProcessingStatus
from ...infrastructure.logging.structured_logger import LogManager
from ...infrastructure.config.settings import Settings
from ...infrastructure.config.default_config import get_default_settings
from .sql_parser import SQLParser, ASTNode
from .logical_plan import (
    LogicalPlan,
    LogicalPlanBuilder,
    LogicalPlanOptimizer,
)
from .physical_plan import (
    PhysicalPlan,
    PhysicalPlanTranslator,
    ExecutionConfig,
    ExecutionMode,
)


class StreamingQueryModule:
    def __init__(self, settings: Optional[Settings] = None) -> None:
        self._settings = settings or get_default_settings()
        self._parser = SQLParser()
        self._logical_builder = LogicalPlanBuilder()
        self._logical_optimizer = LogicalPlanOptimizer()
        self._physical_translator = PhysicalPlanTranslator()
        self._logger = LogManager().get_logger(__name__)
        self._query_history: List[Dict[str, Any]] = []
        self._query_cache: Dict[str, Dict[str, Any]] = {}

    @property
    def parser(self) -> SQLParser:
        return self._parser

    @property
    def logical_builder(self) -> LogicalPlanBuilder:
        return self._logical_builder

    @property
    def logical_optimizer(self) -> LogicalPlanOptimizer:
        return self._logical_optimizer

    @property
    def physical_translator(self) -> PhysicalPlanTranslator:
        return self._physical_translator

    async def process_event(self, event: EventMessage) -> ProcessingResult:
        result = ProcessingResult(
            started_at=datetime.utcnow(),
            status=ProcessingStatus.PROCESSING,
        )

        try:
            event_type = event.event_type
            payload = event.payload

            if event_type == "query.parse":
                parse_result = self._handle_parse(payload)
                result.results = [parse_result]
                result.status = ProcessingStatus.SUCCESS
                result.message = "Query parsed successfully"

            elif event_type == "query.plan.logical":
                plan_result = self._handle_logical_plan(payload)
                result.results = [plan_result]
                result.status = ProcessingStatus.SUCCESS
                result.message = "Logical plan generated successfully"

            elif event_type == "query.plan.optimize":
                optimize_result = self._handle_optimize(payload)
                result.results = [optimize_result]
                result.status = ProcessingStatus.SUCCESS
                result.message = "Logical plan optimized successfully"

            elif event_type == "query.plan.physical":
                physical_result = self._handle_physical_plan(payload)
                result.results = [physical_result]
                result.status = ProcessingStatus.SUCCESS
                result.message = "Physical plan generated successfully"

            elif event_type == "query.compile":
                compile_result = self._handle_compile(payload)
                result.results = [compile_result]
                result.status = ProcessingStatus.SUCCESS
                result.message = "Query compiled successfully"

            elif event_type == "query.validate":
                validate_result = self._handle_validate(payload)
                result.results = [validate_result]
                result.status = ProcessingStatus.SUCCESS
                result.message = "Query validation completed"

            elif event_type == "query.metadata":
                metadata_result = self._handle_get_metadata(payload)
                result.results = [metadata_result]
                result.status = ProcessingStatus.SUCCESS
                result.message = "Query metadata retrieved"

            elif event_type == "query.history":
                history_result = self._handle_get_history(payload)
                result.results = [history_result]
                result.status = ProcessingStatus.SUCCESS
                result.message = "Query history retrieved"

            elif event_type == "query.cache.clear":
                clear_result = self._handle_clear_cache(payload)
                result.results = [clear_result]
                result.status = ProcessingStatus.SUCCESS
                result.message = "Query cache cleared"

            else:
                raise ValidationError(
                    message=f"Unknown event type: {event_type}",
                    suggestion="Check the event type and try again.",
                )

        except Exception as e:
            result.status = ProcessingStatus.FAILED
            result.message = f"Streaming query event processing failed: {str(e)}"
            result.errors.append({"error": str(e)})

            self._logger.error(
                "Streaming query event processing failed",
                event_type=event.event_type,
                error=str(e),
            )

        result.completed_at = datetime.utcnow()
        result.calculate_duration()

        return result

    def _handle_parse(self, payload: Dict[str, Any]) -> Dict[str, Any]:
        sql = payload.get("sql")
        if not sql:
            raise ValidationError(
                message="SQL query is required",
                suggestion="Provide 'sql' in the payload.",
            )

        cache_key = f"parse:{sql}"
        if cache_key in self._query_cache:
            return self._query_cache[cache_key]

        ast = self._parser.parse(sql)
        metadata = self._parser.get_query_metadata(ast)

        parse_result = {
            "ast": ast.to_dict(),
            "metadata": metadata,
        }

        self._query_cache[cache_key] = parse_result
        self._record_query(sql, "parse", True)

        return parse_result

    def _handle_logical_plan(self, payload: Dict[str, Any]) -> Dict[str, Any]:
        sql = payload.get("sql")
        ast_data = payload.get("ast")

        ast = None
        if ast_data:
            ast = self._reconstruct_ast(ast_data)
        elif sql:
            cache_key = f"parse:{sql}"
            if cache_key in self._query_cache:
                ast = self._query_cache[cache_key]["ast"]
            else:
                ast = self._parser.parse(sql)
        else:
            raise ValidationError(
                message="Either SQL or AST is required",
                suggestion="Provide 'sql' or 'ast' in the payload.",
            )

        logical_plan = self._logical_builder.build(ast)

        cache_key = f"logical:{sql}" if sql else f"logical:{ast.id}"
        result = {
            "logical_plan": logical_plan.to_dict(),
            "operator_count": logical_plan.get_operator_count(),
        }

        self._query_cache[cache_key] = result
        self._record_query(sql or "custom_ast", "logical_plan", True)

        return result

    def _handle_optimize(self, payload: Dict[str, Any]) -> Dict[str, Any]:
        sql = payload.get("sql")
        plan_data = payload.get("logical_plan")
        enable_optimizations = payload.get("enable_optimizations", True)

        logical_plan = None
        if plan_data:
            logical_plan = self._reconstruct_logical_plan(plan_data)
        elif sql:
            cache_key = f"logical:{sql}"
            if cache_key in self._query_cache:
                logical_plan = self._reconstruct_logical_plan(self._query_cache[cache_key]["logical_plan"])
            else:
                ast = self._parser.parse(sql)
                logical_plan = self._logical_builder.build(ast)
        else:
            raise ValidationError(
                message="Either SQL or logical plan is required",
                suggestion="Provide 'sql' or 'logical_plan' in the payload.",
            )

        if enable_optimizations:
            optimized_plan = self._logical_optimizer.optimize(logical_plan)
            optimization_summary = self._logical_optimizer.get_optimization_summary()
        else:
            optimized_plan = logical_plan
            optimization_summary = {"optimizations_applied": [], "total_optimizations": 0}

        validation_result = self._logical_optimizer.validate_plan(optimized_plan)

        result = {
            "original_plan": logical_plan.to_dict(),
            "optimized_plan": optimized_plan.to_dict(),
            "optimization_summary": optimization_summary,
            "validation": validation_result,
            "operator_count": optimized_plan.get_operator_count(),
        }

        cache_key = f"optimized:{sql}" if sql else f"optimized:{optimized_plan.id}"
        self._query_cache[cache_key] = result
        self._record_query(sql or "custom_plan", "optimize", True)

        return result

    def _handle_physical_plan(self, payload: Dict[str, Any]) -> Dict[str, Any]:
        sql = payload.get("sql")
        plan_data = payload.get("logical_plan")
        config_data = payload.get("execution_config")

        logical_plan = None
        if plan_data:
            logical_plan = self._reconstruct_logical_plan(plan_data)
        elif sql:
            cache_key = f"optimized:{sql}"
            if cache_key in self._query_cache:
                logical_plan = self._reconstruct_logical_plan(self._query_cache[cache_key]["optimized_plan"])
            else:
                ast = self._parser.parse(sql)
                logical_plan = self._logical_builder.build(ast)
                logical_plan = self._logical_optimizer.optimize(logical_plan)
        else:
            raise ValidationError(
                message="Either SQL or logical plan is required",
                suggestion="Provide 'sql' or 'logical_plan' in the payload.",
            )

        config = None
        if config_data:
            config = self._reconstruct_execution_config(config_data)

        physical_plan = self._physical_translator.translate(logical_plan, config)
        translation_summary = self._physical_translator.get_translation_summary()
        validation_result = self._physical_translator.validate_physical_plan(physical_plan)
        cost_estimate = physical_plan.estimate_cost()
        execution_graph = self._physical_translator.generate_execution_graph(physical_plan)

        result = {
            "physical_plan": physical_plan.to_dict(),
            "execution_graph": execution_graph,
            "translation_summary": translation_summary,
            "validation": validation_result,
            "cost_estimate": cost_estimate,
            "operator_count": physical_plan.get_operator_count(),
        }

        cache_key = f"physical:{sql}" if sql else f"physical:{physical_plan.id}"
        self._query_cache[cache_key] = result
        self._record_query(sql or "custom_plan", "physical_plan", True)

        return result

    def _handle_compile(self, payload: Dict[str, Any]) -> Dict[str, Any]:
        sql = payload.get("sql")
        if not sql:
            raise ValidationError(
                message="SQL query is required",
                suggestion="Provide 'sql' in the payload.",
            )

        cache_key = f"compile:{sql}"
        if cache_key in self._query_cache:
            return self._query_cache[cache_key]

        ast = self._parser.parse(sql)
        logical_plan = self._logical_builder.build(ast)
        optimized_plan = self._logical_optimizer.optimize(logical_plan)
        physical_plan = self._physical_translator.translate(optimized_plan)

        result = {
            "ast": ast.to_dict(),
            "logical_plan": optimized_plan.to_dict(),
            "physical_plan": physical_plan.to_dict(),
            "optimization_summary": self._logical_optimizer.get_optimization_summary(),
            "translation_summary": self._physical_translator.get_translation_summary(),
            "cost_estimate": physical_plan.estimate_cost(),
        }

        self._query_cache[cache_key] = result
        self._record_query(sql, "compile", True)

        return result

    def _handle_validate(self, payload: Dict[str, Any]) -> Dict[str, Any]:
        sql = payload.get("sql")
        if not sql:
            raise ValidationError(
                message="SQL query is required",
                suggestion="Provide 'sql' in the payload.",
            )

        syntax_result = self._parser.validate_syntax(sql)

        if not syntax_result["valid"]:
            return {
                "valid": False,
                "syntax": syntax_result,
                "semantic": None,
                "logical": None,
            }

        try:
            ast = self._parser.parse(sql)
            logical_plan = self._logical_builder.build(ast)
            logical_validation = self._logical_optimizer.validate_plan(logical_plan)

            physical_plan = self._physical_translator.translate(logical_plan)
            physical_validation = self._physical_translator.validate_physical_plan(physical_plan)

            return {
                "valid": syntax_result["valid"] and logical_validation["valid"] and physical_validation["valid"],
                "syntax": syntax_result,
                "logical": logical_validation,
                "physical": physical_validation,
            }
        except Exception as e:
            return {
                "valid": False,
                "syntax": syntax_result,
                "error": str(e),
            }

    def _handle_get_metadata(self, payload: Dict[str, Any]) -> Dict[str, Any]:
        sql = payload.get("sql")
        if not sql:
            raise ValidationError(
                message="SQL query is required",
                suggestion="Provide 'sql' in the payload.",
            )

        cache_key = f"parse:{sql}"
        if cache_key in self._query_cache:
            ast = self._reconstruct_ast(self._query_cache[cache_key]["ast"])
        else:
            ast = self._parser.parse(sql)

        metadata = self._parser.get_query_metadata(ast)

        return {
            "query": sql,
            "metadata": metadata,
        }

    def _handle_get_history(self, payload: Dict[str, Any]) -> Dict[str, Any]:
        limit = payload.get("limit", 100)
        operation_type = payload.get("operation_type")
        success_only = payload.get("success_only", False)

        history = self._query_history

        if operation_type:
            history = [h for h in history if h["operation_type"] == operation_type]

        if success_only:
            history = [h for h in history if h["success"]]

        history.sort(key=lambda h: h["timestamp"], reverse=True)
        history = history[:limit]

        return {
            "total": len(self._query_history),
            "returned": len(history),
            "history": history,
        }

    def _handle_clear_cache(self, payload: Dict[str, Any]) -> Dict[str, Any]:
        cache_type = payload.get("cache_type")
        cleared_count = 0

        if cache_type:
            keys_to_clear = [k for k in self._query_cache.keys() if k.startswith(f"{cache_type}:")]
            for key in keys_to_clear:
                del self._query_cache[key]
                cleared_count += 1
        else:
            cleared_count = len(self._query_cache)
            self._query_cache.clear()

        return {
            "cleared_count": cleared_count,
            "remaining_count": len(self._query_cache),
        }

    def _record_query(self, query: str, operation_type: str, success: bool) -> None:
        self._query_history.append({
            "id": str(uuid4()),
            "query": query[:200] + "..." if len(query) > 200 else query,
            "operation_type": operation_type,
            "success": success,
            "timestamp": datetime.utcnow().isoformat(),
        })

        if len(self._query_history) > 1000:
            self._query_history = self._query_history[-1000:]

    def _reconstruct_ast(self, data: Dict[str, Any]) -> ASTNode:
        from .sql_parser import NodeType

        node_type = NodeType(data["node_type"])
        properties = data.get("properties", {})
        children = [self._reconstruct_ast(child) for child in data.get("children", [])]

        return ASTNode(
            node_type=node_type,
            properties=properties,
            children=children,
        )

    def _reconstruct_logical_plan(self, data: Dict[str, Any]) -> LogicalPlan:
        from .logical_plan import LogicalOperatorType

        operator = LogicalOperatorType(data["operator"])
        properties = data.get("properties", {})
        children = [self._reconstruct_logical_plan(child) for child in data.get("children", [])]

        return LogicalPlan(
            operator=operator,
            properties=properties,
            children=children,
        )

    def _reconstruct_execution_config(self, data: Dict[str, Any]) -> ExecutionConfig:
        from .physical_plan import ExecutionMode, PartitionStrategy

        mode = ExecutionMode(data.get("mode", "streaming"))
        partition_strategy = PartitionStrategy(data.get("partition_strategy", "hash"))

        return ExecutionConfig(
            mode=mode,
            parallelism=data.get("parallelism", 1),
            checkpoint_interval=data.get("checkpoint_interval"),
            checkpoint_dir=data.get("checkpoint_dir"),
            idle_timeout=data.get("idle_timeout"),
            watermark_interval=data.get("watermark_interval"),
            allowed_lateness=data.get("allowed_lateness"),
            partition_strategy=partition_strategy,
            buffer_size=data.get("buffer_size", 1000),
            batch_size=data.get("batch_size", 100),
            retry_attempts=data.get("retry_attempts", 3),
            retry_delay=data.get("retry_delay", "1s"),
        )

    def parse_query(self, sql: str) -> Dict[str, Any]:
        event = EventMessage(
            event_type="query.parse",
            payload={"sql": sql},
            source="streaming_query",
        )
        result = asyncio.run(self.process_event(event))
        return result.results[0] if result.results else {}

    def compile_query(self, sql: str, **kwargs: Any) -> Dict[str, Any]:
        event = EventMessage(
            event_type="query.compile",
            payload={"sql": sql, **kwargs},
            source="streaming_query",
        )
        result = asyncio.run(self.process_event(event))
        return result.results[0] if result.results else {}

    def validate_query(self, sql: str) -> Dict[str, Any]:
        event = EventMessage(
            event_type="query.validate",
            payload={"sql": sql},
            source="streaming_query",
        )
        result = asyncio.run(self.process_event(event))
        return result.results[0] if result.results else {}

    def get_query_metadata(self, sql: str) -> Dict[str, Any]:
        event = EventMessage(
            event_type="query.metadata",
            payload={"sql": sql},
            source="streaming_query",
        )
        result = asyncio.run(self.process_event(event))
        return result.results[0] if result.results else {}

    def generate_logical_plan(self, sql: str, **kwargs: Any) -> Dict[str, Any]:
        event = EventMessage(
            event_type="query.plan.logical",
            payload={"sql": sql, **kwargs},
            source="streaming_query",
        )
        result = asyncio.run(self.process_event(event))
        return result.results[0] if result.results else {}

    def optimize_plan(self, sql: str, **kwargs: Any) -> Dict[str, Any]:
        event = EventMessage(
            event_type="query.plan.optimize",
            payload={"sql": sql, **kwargs},
            source="streaming_query",
        )
        result = asyncio.run(self.process_event(event))
        return result.results[0] if result.results else {}

    def generate_physical_plan(self, sql: str, **kwargs: Any) -> Dict[str, Any]:
        event = EventMessage(
            event_type="query.plan.physical",
            payload={"sql": sql, **kwargs},
            source="streaming_query",
        )
        result = asyncio.run(self.process_event(event))
        return result.results[0] if result.results else {}

    def get_query_history(self, **kwargs: Any) -> Dict[str, Any]:
        event = EventMessage(
            event_type="query.history",
            payload=kwargs,
            source="streaming_query",
        )
        result = asyncio.run(self.process_event(event))
        return result.results[0] if result.results else {}

    def clear_query_cache(self, **kwargs: Any) -> Dict[str, Any]:
        event = EventMessage(
            event_type="query.cache.clear",
            payload=kwargs,
            source="streaming_query",
        )
        result = asyncio.run(self.process_event(event))
        return result.results[0] if result.results else {}
