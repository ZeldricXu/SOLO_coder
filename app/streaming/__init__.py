"""
Streaming Query Parser Module.
Implements streaming SQL parsing, logical plan optimization, and physical plan translation.
"""

import re
from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from enum import Enum
from typing import Any, Dict, List, Optional, Set, Callable

from app.logging import get_logger


class JoinType(str, Enum):
    INNER = "inner"
    LEFT = "left"
    RIGHT = "right"
    FULL = "full"


class AggregationType(str, Enum):
    COUNT = "count"
    SUM = "sum"
    AVG = "avg"
    MIN = "min"
    MAX = "max"


class WindowType(str, Enum):
    TUMBLING = "tumbling"
    SLIDING = "sliding"
    SESSION = "session"


@dataclass
class StreamSource:
    name: str
    alias: str
    columns: List[str] = field(default_factory=list)


@dataclass
class WindowSpec:
    window_type: WindowType
    duration_seconds: int
    slide_seconds: Optional[int] = None


@dataclass
class LogicalPlanNode:
    node_id: str
    node_type: str
    children: List["LogicalPlanNode"] = field(default_factory=list)
    attributes: Dict[str, Any] = field(default_factory=dict)


@dataclass
class PhysicalPlanNode:
    node_id: str
    operator_type: str
    children: List["PhysicalPlanNode"] = field(default_factory=list)
    config: Dict[str, Any] = field(default_factory=dict)


@dataclass
class ParsedQuery:
    raw_sql: str
    sources: List[StreamSource]
    projection_columns: List[str]
    filter_expression: Optional[str] = None
    join_clause: Optional["JoinClause"] = None
    aggregation: Optional["AggregationSpec"] = None
    window_spec: Optional[WindowSpec] = None
    group_by_columns: List[str] = field(default_factory=list)
    order_by_columns: List[str] = field(default_factory=list)
    limit: Optional[int] = None


@dataclass
class JoinClause:
    join_type: JoinType
    left_source: StreamSource
    right_source: StreamSource
    condition: str


@dataclass
class AggregationSpec:
    functions: List["AggregationFunction"]
    group_by: List[str] = field(default_factory=list)


@dataclass
class AggregationFunction:
    function_type: AggregationType
    column: str
    alias: str


class StreamSQLLexer:
    KEYWORDS = {
        "SELECT", "FROM", "WHERE", "GROUP", "BY", "JOIN", "INNER", "LEFT",
        "RIGHT", "FULL", "OUTER", "ON", "HAVING", "ORDER", "LIMIT",
        "WINDOW", "TUMBLING", "SLIDING", "SESSION", "EMIT", "STREAM",
        "COUNT", "SUM", "AVG", "MIN", "MAX", "AS", "AND", "OR", "NOT",
        "IN", "LIKE", "BETWEEN", "IS", "NULL", "TRUE", "FALSE"
    }
    
    def __init__(self):
        self._logger = get_logger("stream_lexer")
    
    def tokenize(self, sql: str) -> List[str]:
        sql_upper = sql.upper()
        tokens: List[str] = []
        i = 0
        n = len(sql)
        
        while i < n:
            c = sql[i]
            
            if c.isspace():
                i += 1
                continue
            
            if c == '"' or c == "'":
                quote_char = c
                i += 1
                start = i
                while i < n and sql[i] != quote_char:
                    i += 1
                tokens.append(sql[start:i])
                i += 1
                continue
            
            if c.isalpha() or c == "_":
                start = i
                while i < n and (sql[i].isalnum() or sql[i] == "_"):
                    i += 1
                word = sql[start:i]
                tokens.append(word)
                continue
            
            if c.isdigit():
                start = i
                while i < n and (sql[i].isdigit() or sql[i] == "."):
                    i += 1
                tokens.append(sql[start:i])
                continue
            
            if c in "(),;=<>+-*/":
                if i + 1 < n and sql[i:i+2] in ["<=", ">=", "<>", "!="]:
                    tokens.append(sql[i:i+2])
                    i += 2
                else:
                    tokens.append(c)
                    i += 1
                continue
            
            i += 1
        
        return tokens


class StreamSQLParser:
    def __init__(self):
        self._lexer = StreamSQLLexer()
        self._logger = get_logger("stream_parser")
    
    def parse(self, sql: str) -> ParsedQuery:
        self._logger.info("Parsing streaming SQL", sql_length=len(sql))
        
        tokens = self._lexer.tokenize(sql)
        upper_tokens = [t.upper() for t in tokens]
        
        parsed = ParsedQuery(
            raw_sql=sql,
            sources=[],
            projection_columns=[],
            group_by_columns=[],
            order_by_columns=[]
        )
        
        i = 0
        n = len(tokens)
        
        while i < n:
            token = upper_tokens[i]
            
            if token == "SELECT":
                i += 1
                cols = []
                while i < n and upper_tokens[i] not in ["FROM", "WINDOW"]:
                    if tokens[i] not in [","]:
                        cols.append(tokens[i])
                    i += 1
                parsed.projection_columns = self._parse_projection(cols)
                continue
            
            if token == "FROM":
                i += 1
                while i < n and upper_tokens[i] not in ["WHERE", "GROUP", "WINDOW", "ORDER", "LIMIT", "JOIN"]:
                    if tokens[i] not in [","]:
                        source = StreamSource(name=tokens[i], alias=tokens[i])
                        if i + 1 < n and upper_tokens[i+1] == "AS":
                            i += 2
                            source.alias = tokens[i]
                        parsed.sources.append(source)
                    i += 1
                continue
            
            if token == "JOIN" or (token in ["INNER", "LEFT", "RIGHT", "FULL"] and i + 1 < n and upper_tokens[i+1] == "JOIN"):
                join_type = JoinType.INNER
                if token == "LEFT":
                    join_type = JoinType.LEFT
                elif token == "RIGHT":
                    join_type = JoinType.RIGHT
                elif token == "FULL":
                    join_type = JoinType.FULL
                
                if token in ["INNER", "LEFT", "RIGHT", "FULL"]:
                    i += 2
                else:
                    i += 1
                
                right_source = StreamSource(name=tokens[i], alias=tokens[i])
                if i + 1 < n and upper_tokens[i+1] == "AS":
                    i += 2
                    right_source.alias = tokens[i]
                i += 1
                
                condition = ""
                if upper_tokens[i] == "ON":
                    i += 1
                    start = i
                    while i < n and upper_tokens[i] not in ["WHERE", "GROUP", "WINDOW", "ORDER", "LIMIT"]:
                        i += 1
                    condition = " ".join(tokens[start:i])
                
                if parsed.sources:
                    parsed.join_clause = JoinClause(
                        join_type=join_type,
                        left_source=parsed.sources[0],
                        right_source=right_source,
                        condition=condition
                    )
                continue
            
            if token == "WHERE":
                i += 1
                start = i
                while i < n and upper_tokens[i] not in ["GROUP", "WINDOW", "ORDER", "LIMIT"]:
                    i += 1
                parsed.filter_expression = " ".join(tokens[start:i])
                continue
            
            if token == "GROUP":
                i += 2
                while i < n and upper_tokens[i] not in ["WINDOW", "ORDER", "LIMIT", "HAVING"]:
                    if tokens[i] not in [","]:
                        parsed.group_by_columns.append(tokens[i])
                    i += 1
                continue
            
            if token == "WINDOW":
                i += 1
                window_type = WindowType.TUMBLING
                duration = 60
                slide = None
                
                if upper_tokens[i] == "TUMBLING":
                    window_type = WindowType.TUMBLING
                    i += 1
                elif upper_tokens[i] == "SLIDING":
                    window_type = WindowType.SLIDING
                    i += 1
                elif upper_tokens[i] == "SESSION":
                    window_type = WindowType.SESSION
                    i += 1
                
                if i < n and tokens[i].isdigit():
                    duration = int(tokens[i])
                    i += 1
                
                parsed.window_spec = WindowSpec(
                    window_type=window_type,
                    duration_seconds=duration,
                    slide_seconds=slide
                )
                continue
            
            if token == "ORDER":
                i += 2
                while i < n and upper_tokens[i] not in ["LIMIT"]:
                    if tokens[i] not in [",", "ASC", "DESC"]:
                        parsed.order_by_columns.append(tokens[i])
                    i += 1
                continue
            
            if token == "LIMIT":
                i += 1
                if i < n and tokens[i].isdigit():
                    parsed.limit = int(tokens[i])
                i += 1
                continue
            
            i += 1
        
        return parsed
    
    def _parse_projection(self, tokens: List[str]) -> List[str]:
        columns = []
        for t in tokens:
            if t.upper() == "AS":
                continue
            if t == ",":
                continue
            columns.append(t)
        return columns


class LogicalPlanOptimizer:
    def __init__(self):
        self._logger = get_logger("logical_optimizer")
        self._optimizations_applied: List[str] = []
    
    def optimize(self, plan: LogicalPlanNode) -> LogicalPlanNode:
        self._logger.info("Starting logical plan optimization")
        self._optimizations_applied = []
        
        plan = self._push_down_filters(plan)
        plan = self._push_down_projections(plan)
        plan = self._combine_adjacent_projections(plan)
        plan = self._eliminate_redundant_nodes(plan)
        
        self._logger.info(
            "Logical plan optimization complete",
            optimizations=self._optimizations_applied
        )
        return plan
    
    def _push_down_filters(self, plan: LogicalPlanNode) -> LogicalPlanNode:
        if not plan.children:
            return plan
        
        if plan.node_type == "filter":
            filter_expr = plan.attributes.get("expression")
            for i, child in enumerate(plan.children):
                if child.node_type == "scan":
                    new_scan = LogicalPlanNode(
                        node_id=f"scan_filtered_{child.node_id}",
                        node_type="scan",
                        attributes={
                            **child.attributes,
                            "filter_expression": filter_expr
                        }
                    )
                    plan.children[i] = new_scan
                    self._optimizations_applied.append("filter_pushdown")
                    return plan
        
        for i, child in enumerate(plan.children):
            plan.children[i] = self._push_down_filters(child)
        
        return plan
    
    def _push_down_projections(self, plan: LogicalPlanNode) -> LogicalPlanNode:
        if plan.node_type == "project":
            cols = plan.attributes.get("columns", [])
            for i, child in enumerate(plan.children):
                if child.node_type == "scan":
                    new_scan = LogicalPlanNode(
                        node_id=f"scan_projected_{child.node_id}",
                        node_type="scan",
                        attributes={
                            **child.attributes,
                            "projection_columns": cols
                        }
                    )
                    plan.children[i] = new_scan
                    self._optimizations_applied.append("projection_pushdown")
        
        for i, child in enumerate(plan.children):
            plan.children[i] = self._push_down_projections(child)
        
        return plan
    
    def _combine_adjacent_projections(self, plan: LogicalPlanNode) -> LogicalPlanNode:
        if len(plan.children) == 1:
            child = plan.children[0]
            if plan.node_type == "project" and child.node_type == "project":
                combined_cols = list(set(
                    plan.attributes.get("columns", []) +
                    child.attributes.get("columns", [])
                ))
                combined = LogicalPlanNode(
                    node_id=f"combined_{plan.node_id}",
                    node_type="project",
                    children=child.children,
                    attributes={"columns": combined_cols}
                )
                self._optimizations_applied.append("projection_combine")
                return combined
        
        for i, child in enumerate(plan.children):
            plan.children[i] = self._combine_adjacent_projections(child)
        
        return plan
    
    def _eliminate_redundant_nodes(self, plan: LogicalPlanNode) -> LogicalPlanNode:
        if plan.node_type == "project":
            cols = plan.attributes.get("columns", [])
            if len(cols) == 1 and cols[0] == "*":
                if plan.children:
                    self._optimizations_applied.append("redundant_project_elimination")
                    return plan.children[0]
        
        for i, child in enumerate(plan.children):
            plan.children[i] = self._eliminate_redundant_nodes(child)
        
        return plan


class PhysicalPlanTranslator:
    def __init__(self):
        self._logger = get_logger("physical_translator")
        self._node_counter = 0
    
    def translate(self, logical_plan: LogicalPlanNode) -> PhysicalPlanNode:
        self._logger.info("Translating logical plan to physical plan")
        self._node_counter = 0
        return self._translate_node(logical_plan)
    
    def _translate_node(self, logical: LogicalPlanNode) -> PhysicalPlanNode:
        self._node_counter += 1
        physical_children = [
            self._translate_node(child)
            for child in logical.children
        ]
        
        if logical.node_type == "scan":
            return PhysicalPlanNode(
                node_id=f"source_{self._node_counter}",
                operator_type="source_reader",
                children=physical_children,
                config={
                    "table": logical.attributes.get("table"),
                    "columns": logical.attributes.get("columns", []),
                    "filter": logical.attributes.get("filter_expression")
                }
            )
        
        elif logical.node_type == "filter":
            return PhysicalPlanNode(
                node_id=f"filter_{self._node_counter}",
                operator_type="where_filter",
                children=physical_children,
                config={"expression": logical.attributes.get("expression")}
            )
        
        elif logical.node_type == "project":
            return PhysicalPlanNode(
                node_id=f"project_{self._node_counter}",
                operator_type="column_projection",
                children=physical_children,
                config={"columns": logical.attributes.get("columns", [])}
            )
        
        elif logical.node_type == "join":
            return PhysicalPlanNode(
                node_id=f"join_{self._node_counter}",
                operator_type="stream_join",
                children=physical_children,
                config={
                    "join_type": logical.attributes.get("join_type"),
                    "condition": logical.attributes.get("condition")
                }
            )
        
        elif logical.node_type == "aggregate":
            return PhysicalPlanNode(
                node_id=f"agg_{self._node_counter}",
                operator_type="group_aggregate",
                children=physical_children,
                config={
                    "group_by": logical.attributes.get("group_by", []),
                    "functions": logical.attributes.get("functions", [])
                }
            )
        
        elif logical.node_type == "window":
            return PhysicalPlanNode(
                node_id=f"window_{self._node_counter}",
                operator_type="time_window",
                children=physical_children,
                config={
                    "window_type": logical.attributes.get("window_type"),
                    "duration": logical.attributes.get("duration"),
                    "slide": logical.attributes.get("slide")
                }
            )
        
        else:
            return PhysicalPlanNode(
                node_id=f"node_{self._node_counter}",
                operator_type=logical.node_type,
                children=physical_children,
                config=logical.attributes
            )


class LogicalPlanBuilder:
    def __init__(self):
        self._logger = get_logger("logical_builder")
        self._node_counter = 0
    
    def build(self, parsed: ParsedQuery) -> LogicalPlanNode:
        self._node_counter = 0
        self._logger.info("Building logical plan from parsed query")
        
        current = None
        
        for source in parsed.sources:
            scan_node = LogicalPlanNode(
                node_id=f"scan_{self._next_id()}",
                node_type="scan",
                attributes={
                    "table": source.name,
                    "alias": source.alias,
                    "columns": source.columns
                }
            )
            if current is None:
                current = scan_node
            elif parsed.join_clause:
                current = LogicalPlanNode(
                    node_id=f"join_{self._next_id()}",
                    node_type="join",
                    children=[current, scan_node],
                    attributes={
                        "join_type": parsed.join_clause.join_type.value,
                        "condition": parsed.join_clause.condition
                    }
                )
        
        if parsed.filter_expression and current:
            current = LogicalPlanNode(
                node_id=f"filter_{self._next_id()}",
                node_type="filter",
                children=[current],
                attributes={"expression": parsed.filter_expression}
            )
        
        if parsed.aggregation and current:
            current = LogicalPlanNode(
                node_id=f"agg_{self._next_id()}",
                node_type="aggregate",
                children=[current],
                attributes={
                    "group_by": parsed.group_by_columns,
                    "functions": [
                        {
                            "type": f.function_type.value,
                            "column": f.column,
                            "alias": f.alias
                        }
                        for f in parsed.aggregation.functions
                    ]
                }
            )
        
        if parsed.window_spec and current:
            current = LogicalPlanNode(
                node_id=f"window_{self._next_id()}",
                node_type="window",
                children=[current],
                attributes={
                    "window_type": parsed.window_spec.window_type.value,
                    "duration": parsed.window_spec.duration_seconds,
                    "slide": parsed.window_spec.slide_seconds
                }
            )
        
        if parsed.projection_columns and current:
            current = LogicalPlanNode(
                node_id=f"project_{self._next_id()}",
                node_type="project",
                children=[current],
                attributes={"columns": parsed.projection_columns}
            )
        
        return current or LogicalPlanNode(
            node_id=f"empty_{self._next_id()}",
            node_type="empty"
        )
    
    def _next_id(self) -> int:
        self._node_counter += 1
        return self._node_counter


class StreamingQueryPipeline:
    def __init__(self):
        self._parser = StreamSQLParser()
        self._builder = LogicalPlanBuilder()
        self._optimizer = LogicalPlanOptimizer()
        self._translator = PhysicalPlanTranslator()
        self._logger = get_logger("streaming_pipeline")
    
    def process(self, sql: str) -> Dict[str, Any]:
        self._logger.info("Processing streaming query")
        
        parsed = self._parser.parse(sql)
        logical_plan = self._builder.build(parsed)
        optimized_plan = self._optimizer.optimize(logical_plan)
        physical_plan = self._translator.translate(optimized_plan)
        
        return {
            "parsed": parsed,
            "logical_plan": logical_plan,
            "optimized_logical_plan": optimized_plan,
            "physical_plan": physical_plan,
            "optimizations": self._optimizer._optimizations_applied
        }
