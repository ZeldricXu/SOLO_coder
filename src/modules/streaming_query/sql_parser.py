"""SQL parser for streaming queries."""
from __future__ import annotations

import re
from dataclasses import dataclass, field
from enum import Enum
from typing import Any, Dict, List, Optional, Tuple
from uuid import UUID, uuid4

from ...domain.errors.common import ValidationError
from ...infrastructure.logging.structured_logger import LogManager


class NodeType(Enum):
    SELECT = "select"
    FROM = "from"
    WHERE = "where"
    GROUP_BY = "group_by"
    HAVING = "having"
    ORDER_BY = "order_by"
    LIMIT = "limit"
    WINDOW = "window"
    JOIN = "join"
    AGGREGATE = "aggregate"
    FILTER = "filter"
    PROJECT = "project"
    SORT = "sort"
    UNION = "union"
    SUBQUERY = "subquery"


class AggregateType(Enum):
    COUNT = "count"
    SUM = "sum"
    AVG = "avg"
    MIN = "min"
    MAX = "max"
    FIRST = "first"
    LAST = "last"
    TUMBLING = "tumbling"
    HOPPING = "hopping"
    SLIDING = "sliding"


class JoinType(Enum):
    INNER = "inner"
    LEFT = "left"
    RIGHT = "right"
    FULL = "full"
    CROSS = "cross"


class WindowType(Enum):
    TUMBLING = "tumbling"
    HOPPING = "hopping"
    SLIDING = "sliding"
    SESSION = "session"


@dataclass
class ASTNode:
    node_type: NodeType
    children: List["ASTNode"] = field(default_factory=list)
    properties: Dict[str, Any] = field(default_factory=dict)
    id: UUID = field(default_factory=uuid4)

    def to_dict(self) -> Dict[str, Any]:
        return {
            "id": str(self.id),
            "node_type": self.node_type.value,
            "properties": self._serialize_properties(self.properties),
            "children": [child.to_dict() for child in self.children],
        }

    def _serialize_properties(self, props: Dict[str, Any]) -> Dict[str, Any]:
        serialized = {}
        for key, value in props.items():
            if isinstance(value, Enum):
                serialized[key] = value.value
            elif isinstance(value, list):
                serialized[key] = [
                    v.value if isinstance(v, Enum) else v for v in value
                ]
            elif isinstance(value, dict):
                serialized[key] = self._serialize_properties(value)
            else:
                serialized[key] = value
        return serialized


@dataclass
class WindowSpec:
    window_type: WindowType
    duration: str
    slide: Optional[str] = None
    session_gap: Optional[str] = None
    time_field: str = "event_time"


@dataclass
class AggregateSpec:
    function: AggregateType
    field: Optional[str] = None
    alias: Optional[str] = None
    distinct: bool = False
    window: Optional[WindowSpec] = None


@dataclass
class JoinCondition:
    left_field: str
    right_field: str
    operator: str = "="


@dataclass
class SelectField:
    field: str
    alias: Optional[str] = None
    expression: Optional[str] = None


@dataclass
class WhereCondition:
    field: str
    operator: str
    value: Any
    logical_op: Optional[str] = None


class SQLParser:
    def __init__(self) -> None:
        self._logger = LogManager().get_logger(__name__)
        self._keywords = {
            "SELECT", "FROM", "WHERE", "GROUP", "BY", "HAVING",
            "ORDER", "LIMIT", "JOIN", "LEFT", "RIGHT", "INNER", "FULL",
            "ON", "AND", "OR", "NOT", "IN", "LIKE", "BETWEEN", "IS", "NULL",
            "TUMBLING", "HOPPING", "SLIDING", "SESSION", "WINDOW",
            "AS", "DISTINCT", "UNION", "ALL",
        }
        self._aggregate_functions = {
            "COUNT": AggregateType.COUNT,
            "SUM": AggregateType.SUM,
            "AVG": AggregateType.AVG,
            "MIN": AggregateType.MIN,
            "MAX": AggregateType.MAX,
            "FIRST": AggregateType.FIRST,
            "LAST": AggregateType.LAST,
        }
        self._join_types = {
            "INNER": JoinType.INNER,
            "LEFT": JoinType.LEFT,
            "RIGHT": JoinType.RIGHT,
            "FULL": JoinType.FULL,
            "CROSS": JoinType.CROSS,
        }
        self._window_types = {
            "TUMBLING": WindowType.TUMBLING,
            "HOPPING": WindowType.HOPPING,
            "SLIDING": WindowType.SLIDING,
            "SESSION": WindowType.SESSION,
        }

    def parse(self, sql: str) -> ASTNode:
        self._logger.info("Parsing SQL query")

        normalized_sql = self._normalize_sql(sql)

        try:
            ast = self._parse_query(normalized_sql)
            self._logger.info("SQL parsing completed successfully")
            return ast
        except Exception as e:
            self._logger.error(
                "SQL parsing failed",
                sql=sql,
                error=str(e),
            )
            raise ValidationError(
                message=f"SQL parsing failed: {str(e)}",
                suggestion="Check the SQL syntax and ensure it's valid for streaming queries.",
            )

    def _normalize_sql(self, sql: str) -> str:
        sql = sql.strip()
        sql = re.sub(r'\s+', ' ', sql)
        sql = self._preserve_string_literals(sql)
        return sql

    def _preserve_string_literals(self, sql: str) -> str:
        self._string_literals: List[str] = []

        def replace_literal(match: re.Match) -> str:
            self._string_literals.append(match.group(0))
            return f"__STR_{len(self._string_literals) - 1}__"

        pattern = r"'([^']|'')*'"
        return re.sub(pattern, replace_literal, sql)

    def _restore_string_literal(self, placeholder: str) -> str:
        match = re.match(r"__STR_(\d+)__", placeholder)
        if match:
            idx = int(match.group(1))
            return self._string_literals[idx]
        return placeholder

    def _parse_query(self, sql: str) -> ASTNode:
        tokens = self._tokenize(sql)
        pos = 0

        select_node, pos = self._parse_select(tokens, pos)
        from_node, pos = self._parse_from(tokens, pos)

        root = ASTNode(node_type=NodeType.SELECT)
        root.children.append(select_node)
        root.children.append(from_node)

        if pos < len(tokens) and tokens[pos].upper() == "WHERE":
            where_node, pos = self._parse_where(tokens, pos + 1)
            root.children.append(where_node)

        if pos < len(tokens) and tokens[pos].upper() == "GROUP":
            group_node, pos = self._parse_group_by(tokens, pos + 2)
            root.children.append(group_node)

        if pos < len(tokens) and tokens[pos].upper() == "HAVING":
            having_node, pos = self._parse_having(tokens, pos + 1)
            root.children.append(having_node)

        if pos < len(tokens) and tokens[pos].upper() == "ORDER":
            order_node, pos = self._parse_order_by(tokens, pos + 2)
            root.children.append(order_node)

        if pos < len(tokens) and tokens[pos].upper() == "LIMIT":
            limit_node, pos = self._parse_limit(tokens, pos + 1)
            root.children.append(limit_node)

        if pos < len(tokens):
            raise ValidationError(
                message=f"Unexpected tokens after query: {' '.join(tokens[pos:])}",
                suggestion="Check for extra tokens at the end of the query.",
            )

        return root

    def _tokenize(self, sql: str) -> List[str]:
        tokens: List[str] = []
        current_token = ""
        i = 0

        while i < len(sql):
            char = sql[i]

            if char.isspace():
                if current_token:
                    tokens.append(current_token)
                    current_token = ""
                i += 1
                continue

            if char in "(),;":
                if current_token:
                    tokens.append(current_token)
                    current_token = ""
                tokens.append(char)
                i += 1
                continue

            if char in "=<>!+-*/%":
                if current_token:
                    tokens.append(current_token)
                    current_token = ""

                if i + 1 < len(sql) and sql[i:i + 2] in ("<=", ">=", "!=", "<>"):
                    tokens.append(sql[i:i + 2])
                    i += 2
                else:
                    tokens.append(char)
                    i += 1
                continue

            current_token += char
            i += 1

        if current_token:
            tokens.append(current_token)

        return tokens

    def _parse_select(self, tokens: List[str], pos: int) -> Tuple[ASTNode, int]:
        if tokens[pos].upper() != "SELECT":
            raise ValidationError(
                message="Query must start with SELECT",
                suggestion="Add SELECT keyword at the beginning of the query.",
            )
        pos += 1

        fields: List[SelectField] = []
        aggregates: List[AggregateSpec] = []
        has_distinct = False

        if tokens[pos].upper() == "DISTINCT":
            has_distinct = True
            pos += 1

        while pos < len(tokens) and tokens[pos].upper() != "FROM":
            if tokens[pos] == ",":
                pos += 1
                continue

            field_spec, pos = self._parse_select_field(tokens, pos)

            if isinstance(field_spec, AggregateSpec):
                aggregates.append(field_spec)
                fields.append(SelectField(
                    field=field_spec.field or "*",
                    alias=field_spec.alias,
                ))
            else:
                fields.append(field_spec)

        select_node = ASTNode(
            node_type=NodeType.SELECT,
            properties={
                "fields": fields,
                "aggregates": aggregates,
                "distinct": has_distinct,
            },
        )

        return select_node, pos

    def _parse_select_field(self, tokens: List[str], pos: int) -> Tuple[Any, int]:
        token = tokens[pos]

        upper_token = token.upper()
        if upper_token in self._aggregate_functions and pos + 1 < len(tokens) and tokens[pos + 1] == "(":
            return self._parse_aggregate(tokens, pos)

        if upper_token in self._window_types and pos + 1 < len(tokens) and tokens[pos + 1] == "(":
            return self._parse_window_function(tokens, pos)

        field = self._restore_string_literal(token)
        pos += 1

        alias = None
        if pos < len(tokens) and tokens[pos].upper() == "AS":
            pos += 1
            alias = tokens[pos]
            pos += 1
        elif pos < len(tokens) and tokens[pos] not in (",", "FROM", "WHERE", "GROUP", "ORDER", "LIMIT", "JOIN"):
            alias = tokens[pos]
            pos += 1

        return SelectField(field=field, alias=alias), pos

    def _parse_aggregate(self, tokens: List[str], pos: int) -> Tuple[AggregateSpec, int]:
        func_name = tokens[pos].upper()
        agg_type = self._aggregate_functions[func_name]
        pos += 1

        if tokens[pos] != "(":
            raise ValidationError(
                message="Expected '(' after aggregate function",
                suggestion="Add parentheses around the aggregate function arguments.",
            )
        pos += 1

        distinct = False
        if tokens[pos].upper() == "DISTINCT":
            distinct = True
            pos += 1

        field = None
        if tokens[pos] != "*":
            field = self._restore_string_literal(tokens[pos])
        pos += 1

        if tokens[pos] != ")":
            raise ValidationError(
                message="Expected ')' after aggregate function arguments",
                suggestion="Close the aggregate function with ')'.",
            )
        pos += 1

        alias = None
        if pos < len(tokens) and tokens[pos].upper() == "AS":
            pos += 1
            alias = tokens[pos]
            pos += 1

        return AggregateSpec(
            function=agg_type,
            field=field,
            alias=alias,
            distinct=distinct,
        ), pos

    def _parse_window_function(self, tokens: List[str], pos: int) -> Tuple[AggregateSpec, int]:
        window_type_str = tokens[pos].upper()
        window_type = WindowType(window_type_str.lower())
        pos += 1

        if tokens[pos] != "(":
            raise ValidationError(
                message="Expected '(' after window function",
                suggestion="Add parentheses around the window function arguments.",
            )
        pos += 1

        inner_agg: Optional[AggregateSpec] = None
        if tokens[pos].upper() in self._aggregate_functions:
            inner_agg, pos = self._parse_aggregate(tokens, pos)

        if tokens[pos] == ",":
            pos += 1

        duration = self._parse_duration(tokens[pos])
        pos += 1

        slide = None
        session_gap = None
        if window_type in (WindowType.HOPPING, WindowType.SLIDING) and tokens[pos] == ",":
            pos += 1
            slide = self._parse_duration(tokens[pos])
            pos += 1
        elif window_type == WindowType.SESSION and tokens[pos] == ",":
            pos += 1
            session_gap = self._parse_duration(tokens[pos])
            pos += 1

        if tokens[pos] != ")":
            raise ValidationError(
                message="Expected ')' after window function arguments",
                suggestion="Close the window function with ')'.",
            )
        pos += 1

        alias = None
        if pos < len(tokens) and tokens[pos].upper() == "AS":
            pos += 1
            alias = tokens[pos]
            pos += 1

        window_spec = WindowSpec(
            window_type=window_type,
            duration=duration,
            slide=slide,
            session_gap=session_gap,
        )

        return AggregateSpec(
            function=inner_agg.function if inner_agg else AggregateType.TUMBLING,
            field=inner_agg.field if inner_agg else None,
            alias=alias,
            window=window_spec,
        ), pos

    def _parse_duration(self, token: str) -> str:
        token = token.strip("'\"")
        match = re.match(r"(\d+)\s*(ms|s|m|h|d|week|month|year)", token, re.IGNORECASE)
        if not match:
            raise ValidationError(
                message=f"Invalid duration format: {token}",
                suggestion="Use format like '10s', '5m', '1h', '1d'.",
            )
        return token.lower()

    def _parse_from(self, tokens: List[str], pos: int) -> Tuple[ASTNode, int]:
        if tokens[pos].upper() != "FROM":
            raise ValidationError(
                message="Expected FROM clause",
                suggestion="Add FROM clause after SELECT.",
            )
        pos += 1

        table_name = tokens[pos]
        pos += 1

        from_node = ASTNode(
            node_type=NodeType.FROM,
            properties={"table": table_name},
        )

        while pos < len(tokens) and tokens[pos].upper() in self._join_types:
            join_node, pos = self._parse_join(tokens, pos)
            from_node.children.append(join_node)

        return from_node, pos

    def _parse_join(self, tokens: List[str], pos: int) -> Tuple[ASTNode, int]:
        join_type_str = tokens[pos].upper()
        join_type = self._join_types.get(join_type_str, JoinType.INNER)
        pos += 1

        if tokens[pos].upper() == "JOIN":
            pos += 1

        table_name = tokens[pos]
        pos += 1

        alias = None
        if tokens[pos].upper() == "AS":
            pos += 1
            alias = tokens[pos]
            pos += 1
        elif tokens[pos].upper() != "ON":
            alias = tokens[pos]
            pos += 1

        if tokens[pos].upper() != "ON":
            raise ValidationError(
                message="Expected ON clause for JOIN",
                suggestion="Add ON clause to specify join conditions.",
            )
        pos += 1

        conditions: List[JoinCondition] = []
        while pos < len(tokens) and tokens[pos] not in ("WHERE", "GROUP", "ORDER", "LIMIT", "AND"):
            left_field = tokens[pos]
            pos += 1
            operator = tokens[pos]
            pos += 1
            right_field = tokens[pos]
            pos += 1

            conditions.append(JoinCondition(
                left_field=left_field,
                right_field=right_field,
                operator=operator,
            ))

            if pos < len(tokens) and tokens[pos].upper() == "AND":
                pos += 1

        join_node = ASTNode(
            node_type=NodeType.JOIN,
            properties={
                "join_type": join_type,
                "table": table_name,
                "alias": alias,
                "conditions": conditions,
            },
        )

        return join_node, pos

    def _parse_where(self, tokens: List[str], pos: int) -> Tuple[ASTNode, int]:
        conditions: List[WhereCondition] = []

        logical_op = None
        while pos < len(tokens) and tokens[pos].upper() not in ("GROUP", "ORDER", "LIMIT", "HAVING"):
            if tokens[pos].upper() in ("AND", "OR"):
                logical_op = tokens[pos].upper()
                pos += 1
                continue

            field = tokens[pos]
            pos += 1

            operator = tokens[pos]
            if operator.upper() == "IS":
                pos += 1
                if tokens[pos].upper() == "NOT":
                    operator = "IS NOT"
                    pos += 1
                else:
                    operator = "IS"
                pos += 1
                value = tokens[pos]
                pos += 1
            elif operator.upper() == "NOT" and tokens[pos + 1].upper() == "IN":
                operator = "NOT IN"
                pos += 2
                value = self._parse_in_values(tokens, pos)
                pos = value[1]
                value = value[0]
            elif operator.upper() == "IN":
                operator = "IN"
                pos += 1
                value = self._parse_in_values(tokens, pos)
                pos = value[1]
                value = value[0]
            elif operator.upper() == "NOT" and tokens[pos + 1].upper() == "LIKE":
                operator = "NOT LIKE"
                pos += 2
                value = self._restore_string_literal(tokens[pos])
                pos += 1
            elif operator.upper() == "LIKE":
                operator = "LIKE"
                pos += 1
                value = self._restore_string_literal(tokens[pos])
                pos += 1
            elif operator.upper() == "NOT" and tokens[pos + 1].upper() == "BETWEEN":
                operator = "NOT BETWEEN"
                pos += 2
                values = [tokens[pos], tokens[pos + 2]]
                pos += 3
                value = values
            elif operator.upper() == "BETWEEN":
                operator = "BETWEEN"
                pos += 1
                values = [tokens[pos], tokens[pos + 2]]
                pos += 3
                value = values
            else:
                pos += 1
                value = tokens[pos]
                if value.startswith("__STR_"):
                    value = self._restore_string_literal(value)
                pos += 1

            conditions.append(WhereCondition(
                field=field,
                operator=operator,
                value=value,
                logical_op=logical_op,
            ))
            logical_op = None

        where_node = ASTNode(
            node_type=NodeType.WHERE,
            properties={"conditions": conditions},
        )

        return where_node, pos

    def _parse_in_values(self, tokens: List[str], pos: int) -> Tuple[List[Any], int]:
        if tokens[pos] != "(":
            raise ValidationError(
                message="Expected '(' after IN",
                suggestion="Wrap IN values in parentheses.",
            )
        pos += 1

        values: List[Any] = []
        while tokens[pos] != ")":
            if tokens[pos] == ",":
                pos += 1
                continue
            value = tokens[pos]
            if value.startswith("__STR_"):
                value = self._restore_string_literal(value)
            values.append(value)
            pos += 1

        pos += 1
        return values, pos

    def _parse_group_by(self, tokens: List[str], pos: int) -> Tuple[ASTNode, int]:
        fields: List[str] = []

        while pos < len(tokens) and tokens[pos].upper() not in ("HAVING", "ORDER", "LIMIT"):
            if tokens[pos] == ",":
                pos += 1
                continue
            fields.append(tokens[pos])
            pos += 1

        group_node = ASTNode(
            node_type=NodeType.GROUP_BY,
            properties={"fields": fields},
        )

        return group_node, pos

    def _parse_having(self, tokens: List[str], pos: int) -> Tuple[ASTNode, int]:
        conditions: List[WhereCondition] = []

        logical_op = None
        while pos < len(tokens) and tokens[pos].upper() not in ("ORDER", "LIMIT"):
            if tokens[pos].upper() in ("AND", "OR"):
                logical_op = tokens[pos].upper()
                pos += 1
                continue

            field = tokens[pos]
            pos += 1

            if field.upper() in self._aggregate_functions and tokens[pos] == "(":
                agg_spec, pos = self._parse_aggregate(tokens, pos - 1)
                field = f"{agg_spec.function.value}({agg_spec.field})"

            operator = tokens[pos]
            pos += 1

            value = tokens[pos]
            if value.startswith("__STR_"):
                value = self._restore_string_literal(value)
            pos += 1

            conditions.append(WhereCondition(
                field=field,
                operator=operator,
                value=value,
                logical_op=logical_op,
            ))
            logical_op = None

        having_node = ASTNode(
            node_type=NodeType.HAVING,
            properties={"conditions": conditions},
        )

        return having_node, pos

    def _parse_order_by(self, tokens: List[str], pos: int) -> Tuple[ASTNode, int]:
        fields: List[Dict[str, Any]] = []

        while pos < len(tokens) and tokens[pos].upper() != "LIMIT":
            if tokens[pos] == ",":
                pos += 1
                continue

            field = tokens[pos]
            pos += 1

            direction = "ASC"
            if pos < len(tokens) and tokens[pos].upper() in ("ASC", "DESC"):
                direction = tokens[pos].upper()
                pos += 1

            fields.append({"field": field, "direction": direction})

        order_node = ASTNode(
            node_type=NodeType.ORDER_BY,
            properties={"fields": fields},
        )

        return order_node, pos

    def _parse_limit(self, tokens: List[str], pos: int) -> Tuple[ASTNode, int]:
        value = tokens[pos]
        pos += 1

        offset = None
        if pos < len(tokens) and tokens[pos].upper() == "OFFSET":
            pos += 1
            offset = tokens[pos]
            pos += 1

        limit_node = ASTNode(
            node_type=NodeType.LIMIT,
            properties={"limit": value, "offset": offset},
        )

        return limit_node, pos

    def validate_syntax(self, sql: str) -> Dict[str, Any]:
        try:
            self.parse(sql)
            return {
                "valid": True,
                "errors": [],
            }
        except ValidationError as e:
            return {
                "valid": False,
                "errors": [{
                    "message": e.message,
                    "suggestion": e.suggestion,
                    "error_code": e.error_code,
                }],
            }

    def get_query_metadata(self, ast: ASTNode) -> Dict[str, Any]:
        metadata = {
            "selected_fields": [],
            "source_tables": [],
            "where_conditions": [],
            "group_by_fields": [],
            "order_by_fields": [],
            "aggregates": [],
            "windows": [],
            "joins": [],
            "has_limit": False,
        }

        for child in ast.children:
            if child.node_type == NodeType.SELECT:
                fields = child.properties.get("fields", [])
                metadata["selected_fields"] = [
                    f.alias or f.field for f in fields
                ]
                aggregates = child.properties.get("aggregates", [])
                for agg in aggregates:
                    metadata["aggregates"].append({
                        "function": agg.function.value,
                        "field": agg.field,
                        "alias": agg.alias,
                    })
                    if agg.window:
                        metadata["windows"].append({
                            "type": agg.window.window_type.value,
                            "duration": agg.window.duration,
                            "slide": agg.window.slide,
                        })

            elif child.node_type == NodeType.FROM:
                metadata["source_tables"].append(child.properties.get("table"))
                for join_child in child.children:
                    if join_child.node_type == NodeType.JOIN:
                        metadata["joins"].append({
                            "type": join_child.properties["join_type"].value,
                            "table": join_child.properties["table"],
                        })

            elif child.node_type == NodeType.WHERE:
                conditions = child.properties.get("conditions", [])
                metadata["where_conditions"] = [
                    f"{c.field} {c.operator} {c.value}" for c in conditions
                ]

            elif child.node_type == NodeType.GROUP_BY:
                metadata["group_by_fields"] = child.properties.get("fields", [])

            elif child.node_type == NodeType.ORDER_BY:
                fields = child.properties.get("fields", [])
                metadata["order_by_fields"] = [
                    f"{f['field']} {f['direction']}" for f in fields
                ]

            elif child.node_type == NodeType.LIMIT:
                metadata["has_limit"] = True
                metadata["limit"] = child.properties.get("limit")
                metadata["offset"] = child.properties.get("offset")

        return metadata
