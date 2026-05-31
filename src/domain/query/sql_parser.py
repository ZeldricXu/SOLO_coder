import re
import logging
from dataclasses import dataclass, field
from enum import Enum
from typing import Any, Dict, List, Optional, Tuple

import sqlparse
from sqlparse.sql import Identifier, IdentifierList, Where, Parenthesis, Function
from sqlparse.tokens import Keyword, DML, Punctuation

logger = logging.getLogger(__name__)


class ParserState(Enum):
    INITIAL = "initial"
    SELECT_CLAUSE = "select_clause"
    FROM_CLAUSE = "from_clause"
    JOIN_CLAUSE = "join_clause"
    WHERE_CLAUSE = "where_clause"
    GROUP_BY_CLAUSE = "group_by_clause"
    HAVING_CLAUSE = "having_clause"
    ORDER_BY_CLAUSE = "order_by_clause"
    LIMIT_CLAUSE = "limit_clause"
    WINDOW_CLAUSE = "window_clause"
    FINISHED = "finished"


class StateTransitionError(Exception):
    def __init__(self, from_state: ParserState, to_state: ParserState, token_value: str):
        super().__init__(f"Invalid state transition: {from_state.value} -> {to_state.value} for token '{token_value}'")
        self.from_state = from_state
        self.to_state = to_state
        self.token_value = token_value


class StreamSQLType(Enum):
    SELECT = "SELECT"
    INSERT = "INSERT"
    CREATE_STREAM = "CREATE_STREAM"
    DROP_STREAM = "DROP_STREAM"
    CREATE_TABLE = "CREATE_TABLE"
    UNKNOWN = "UNKNOWN"


class WindowType(Enum):
    TUMBLING = "TUMBLING"
    HOPPING = "HOPPING"
    SLIDING = "SLIDING"
    SESSION = "SESSION"
    NONE = "NONE"


class JoinType(Enum):
    INNER = "INNER"
    LEFT = "LEFT"
    RIGHT = "RIGHT"
    FULL = "FULL"
    CROSS = "CROSS"
    NONE = "NONE"


@dataclass
class StreamWindow:
    window_type: WindowType = WindowType.NONE
    size: Optional[str] = None
    slide: Optional[str] = None
    gap: Optional[str] = None
    time_field: Optional[str] = None


@dataclass
class StreamJoin:
    join_type: JoinType = JoinType.NONE
    left_source: Optional[str] = None
    right_source: Optional[str] = None
    condition: Optional[str] = None
    window: Optional[StreamWindow] = None


@dataclass
class StreamColumn:
    name: str
    alias: Optional[str] = None
    data_type: Optional[str] = None
    expression: Optional[str] = None
    aggregation: Optional[str] = None


@dataclass
class StreamSource:
    name: str
    alias: Optional[str] = None
    is_stream: bool = False


@dataclass
class ParsedStreamSQL:
    sql_type: StreamSQLType = StreamSQLType.UNKNOWN
    original_sql: str = ""
    columns: List[StreamColumn] = field(default_factory=list)
    sources: List[StreamSource] = field(default_factory=list)
    where_clause: Optional[str] = None
    group_by: List[str] = field(default_factory=list)
    having_clause: Optional[str] = None
    order_by: List[str] = field(default_factory=list)
    window: StreamWindow = field(default_factory=StreamWindow)
    join: StreamJoin = field(default_factory=StreamJoin)
    target_table: Optional[str] = None
    stream_name: Optional[str] = None
    stream_definition: Optional[Dict[str, Any]] = None
    limit: Optional[int] = None


class StreamSQLParser:
    WINDOW_PATTERN = re.compile(
        r"(TUMBLING|HOPPING|SLIDING|SESSION)\s*\(\s*SIZE\s+(\d+\s+\w+)"
        r"(?:\s*,\s*SLIDE\s+(\d+\s+\w+))?"
        r"(?:\s*,\s*GAP\s+(\d+\s+\w+))?"
        r"(?:\s*,\s*ON\s+(\w+(?:\.\w+)*))?"
        r"\s*\)",
        re.IGNORECASE | re.DOTALL,
    )
    STREAM_KEYWORD = re.compile(r"\bSTREAM\b", re.IGNORECASE)
    EMIT_PATTERN = re.compile(r"EMIT\s+(?:RESULTS\s+)?(?:WITH\s+)?(\w+)", re.IGNORECASE)

    def parse(self, sql: str) -> ParsedStreamSQL:
        sql = sql.strip().rstrip(";")
        result = ParsedStreamSQL(original_sql=sql)

        normalized = sql.upper().strip()
        if normalized.startswith("SELECT"):
            result.sql_type = StreamSQLType.SELECT
            self._parse_select(sql, result)
        elif normalized.startswith("INSERT"):
            result.sql_type = StreamSQLType.INSERT
            self._parse_insert(sql, result)
        elif "CREATE" in normalized and "STREAM" in normalized:
            result.sql_type = StreamSQLType.CREATE_STREAM
            self._parse_create_stream(sql, result)
        elif normalized.startswith("CREATE TABLE"):
            result.sql_type = StreamSQLType.CREATE_TABLE
            result.stream_name = self._extract_table_name(sql, "CREATE TABLE")
        elif normalized.startswith("DROP"):
            result.sql_type = StreamSQLType.DROP_STREAM
            result.stream_name = self._extract_table_name(sql, "DROP")

        self._extract_window(sql, result)
        return result

    def _parse_select(self, sql: str, result: ParsedStreamSQL) -> None:
        parsed = sqlparse.parse(sql)
        if not parsed:
            return
        stmt = parsed[0]

        VALID_TRANSITIONS: Dict[ParserState, set] = {
            ParserState.INITIAL: {ParserState.SELECT_CLAUSE},
            ParserState.SELECT_CLAUSE: {ParserState.FROM_CLAUSE, ParserState.FINISHED},
            ParserState.FROM_CLAUSE: {
                ParserState.JOIN_CLAUSE,
                ParserState.WHERE_CLAUSE,
                ParserState.GROUP_BY_CLAUSE,
                ParserState.ORDER_BY_CLAUSE,
                ParserState.WINDOW_CLAUSE,
                ParserState.LIMIT_CLAUSE,
                ParserState.FINISHED,
            },
            ParserState.JOIN_CLAUSE: {
                ParserState.JOIN_CLAUSE,
                ParserState.WHERE_CLAUSE,
                ParserState.GROUP_BY_CLAUSE,
                ParserState.ORDER_BY_CLAUSE,
                ParserState.WINDOW_CLAUSE,
                ParserState.LIMIT_CLAUSE,
                ParserState.FINISHED,
            },
            ParserState.WHERE_CLAUSE: {
                ParserState.GROUP_BY_CLAUSE,
                ParserState.ORDER_BY_CLAUSE,
                ParserState.WINDOW_CLAUSE,
                ParserState.LIMIT_CLAUSE,
                ParserState.FINISHED,
            },
            ParserState.GROUP_BY_CLAUSE: {
                ParserState.HAVING_CLAUSE,
                ParserState.ORDER_BY_CLAUSE,
                ParserState.WINDOW_CLAUSE,
                ParserState.LIMIT_CLAUSE,
                ParserState.FINISHED,
            },
            ParserState.HAVING_CLAUSE: {
                ParserState.ORDER_BY_CLAUSE,
                ParserState.WINDOW_CLAUSE,
                ParserState.LIMIT_CLAUSE,
                ParserState.FINISHED,
            },
            ParserState.ORDER_BY_CLAUSE: {
                ParserState.WINDOW_CLAUSE,
                ParserState.LIMIT_CLAUSE,
                ParserState.FINISHED,
            },
            ParserState.WINDOW_CLAUSE: {
                ParserState.LIMIT_CLAUSE,
                ParserState.FINISHED,
            },
            ParserState.LIMIT_CLAUSE: {ParserState.FINISHED},
            ParserState.FINISHED: set(),
        }

        current_state = ParserState.INITIAL
        join_seen = False

        def transition_to(new_state: ParserState, token_value: str) -> bool:
            nonlocal current_state
            if new_state in VALID_TRANSITIONS.get(current_state, set()):
                current_state = new_state
                return True
            else:
                logger.warning(
                    f"Invalid state transition attempt: {current_state.value} -> {new_state.value} "
                    f"for token '{token_value}'. Attempting graceful recovery."
                )
                current_state = new_state
                return False

        for token in stmt.tokens:
            token_value = token.value.upper().strip()

            if token.ttype is DML and token_value == "SELECT":
                transition_to(ParserState.SELECT_CLAUSE, token_value)
                continue

            if token.ttype is Keyword and token_value == "FROM":
                transition_to(ParserState.FROM_CLAUSE, token_value)
                continue

            if token.ttype is Keyword and token_value == "JOIN":
                transition_to(ParserState.JOIN_CLAUSE, token_value)
                join_seen = True
                continue

            if token.ttype is Keyword and "JOIN" in token_value and len(token_value) > 4:
                transition_to(ParserState.JOIN_CLAUSE, token_value)
                join_seen = True
                continue

            if token.ttype is Keyword and token_value == "WHERE":
                transition_to(ParserState.WHERE_CLAUSE, token_value)
                continue

            if token.ttype is Keyword and "GROUP BY" in token_value:
                transition_to(ParserState.GROUP_BY_CLAUSE, token_value)
                continue

            if token.ttype is Keyword and token_value == "HAVING":
                transition_to(ParserState.HAVING_CLAUSE, token_value)
                continue

            if token.ttype is Keyword and "ORDER BY" in token_value:
                transition_to(ParserState.ORDER_BY_CLAUSE, token_value)
                continue

            if token.ttype is Keyword and token_value == "LIMIT":
                transition_to(ParserState.LIMIT_CLAUSE, token_value)
                continue

            if token.ttype is Keyword and token_value == "WINDOW":
                transition_to(ParserState.WINDOW_CLAUSE, token_value)
                continue

            if token.ttype is Keyword:
                continue

            if token.ttype is Punctuation:
                continue

            if isinstance(token, Where):
                result.where_clause = token.value
                continue

            if current_state == ParserState.SELECT_CLAUSE:
                if isinstance(token, (Identifier, IdentifierList)):
                    self._extract_columns(token, result)

            elif current_state == ParserState.FROM_CLAUSE:
                if isinstance(token, (Identifier, IdentifierList)):
                    self._extract_sources(token, result)

            elif current_state == ParserState.JOIN_CLAUSE:
                if isinstance(token, (Identifier, IdentifierList)):
                    self._extract_sources(token, result)

        self._extract_join(sql, result)
        self._extract_group_by(sql, result)
        self._extract_order_by(sql, result)
        self._extract_limit(sql, result)
        self._extract_window(sql, result)

    def _extract_columns(self, token, result: ParsedStreamSQL) -> None:
        if isinstance(token, IdentifierList):
            for ident in token.get_identifiers():
                col = self._parse_column_identifier(ident)
                if col:
                    result.columns.append(col)
        elif isinstance(token, Identifier):
            col = self._parse_column_identifier(token)
            if col:
                result.columns.append(col)

    def _parse_column_identifier(self, ident) -> Optional[StreamColumn]:
        name = ident.get_real_name()
        alias = ident.get_alias()
        agg = self._extract_aggregation(str(ident))
        return StreamColumn(
            name=name or str(ident).strip(),
            alias=alias,
            expression=str(ident).strip(),
            aggregation=agg,
        )

    def _extract_aggregation(self, expr: str) -> Optional[str]:
        agg_pattern = re.compile(r"\b(COUNT|SUM|AVG|MIN|MAX|STDDEV|VARIANCE)\s*\(", re.IGNORECASE)
        match = agg_pattern.search(expr)
        return match.group(1).upper() if match else None

    def _extract_sources(self, token, result: ParsedStreamSQL) -> None:
        if isinstance(token, IdentifierList):
            for ident in token.get_identifiers():
                src = self._parse_source_identifier(ident)
                if src:
                    result.sources.append(src)
        elif isinstance(token, Identifier):
            src = self._parse_source_identifier(token)
            if src:
                result.sources.append(src)

    def _parse_source_identifier(self, ident) -> Optional[StreamSource]:
        name = ident.get_real_name()
        alias = ident.get_alias()
        is_stream = bool(self.STREAM_KEYWORD.search(str(ident)))
        return StreamSource(name=name, alias=alias, is_stream=is_stream)

    def _extract_window(self, sql: str, result: ParsedStreamSQL) -> None:
        match = self.WINDOW_PATTERN.search(sql)
        if match:
            wtype_str = match.group(1).upper()
            wtype = WindowType[wtype_str]
            result.window = StreamWindow(
                window_type=wtype,
                size=match.group(2),
                slide=match.group(3),
                gap=match.group(4),
                time_field=match.group(5),
            )
        window_keywords = re.search(r"WINDOW\s+(.+?)(?:\s+EMIT|\s*$)", sql, re.IGNORECASE)
        if window_keywords and result.window.window_type == WindowType.NONE:
            result.window.time_field = window_keywords.group(1).strip()

    def _extract_join(self, sql: str, result: ParsedStreamSQL) -> None:
        join_pattern = re.compile(
            r"(CROSS)\s+JOIN\s+(\w+)(?:\s+(?:AS\s+)?(\w+))?(?:\s+WHERE|\s+WINDOW|\s+EMIT|\s+GROUP|\s+ORDER|\s*$)|"
            r"(INNER|LEFT|RIGHT|FULL)?\s*JOIN\s+(\w+)(?:\s+(?:AS\s+)?(\w+))?\s+ON\s+(.+?)(?:\s+WHERE|\s+WINDOW|\s+EMIT|\s+GROUP|\s+ORDER|\s*$)",
            re.IGNORECASE | re.DOTALL,
        )
        match = join_pattern.search(sql)
        if match:
            groups = match.groups()
            if groups[0] is not None:
                jtype = groups[0]
                right_source = groups[1]
                condition = None
            else:
                jtype = groups[3]
                if jtype is None:
                    jtype = "INNER"
                right_source = groups[4]
                condition = groups[6].strip()

            result.join = StreamJoin(
                join_type=JoinType[jtype.upper()],
                right_source=right_source,
                condition=condition,
            )
            if len(result.sources) > 0:
                result.join.left_source = result.sources[0].name

    def _extract_group_by(self, sql: str, result: ParsedStreamSQL) -> None:
        match = re.search(r"GROUP\s+BY\s+(.+?)(?:\s+HAVING|\s+ORDER|\s+WINDOW|\s+EMIT|\s+LIMIT|\s*$)", sql, re.IGNORECASE)
        if match:
            group_str = match.group(1).strip()
            result.group_by = [g.strip() for g in group_str.split(",")]

    def _extract_order_by(self, sql: str, result: ParsedStreamSQL) -> None:
        match = re.search(r"ORDER\s+BY\s+(.+?)(?:\s+LIMIT|\s+WINDOW|\s+EMIT|\s*$)", sql, re.IGNORECASE)
        if match:
            order_str = match.group(1).strip()
            result.order_by = [o.strip() for o in order_str.split(",")]

    def _extract_limit(self, sql: str, result: ParsedStreamSQL) -> None:
        match = re.search(r"LIMIT\s+(\d+)", sql, re.IGNORECASE)
        if match:
            result.limit = int(match.group(1))

    def _extract_table_name(self, sql: str, prefix: str) -> Optional[str]:
        match = re.search(rf"{prefix}\s+(?:IF\s+NOT\s+EXISTS\s+)?(\w+)", sql, re.IGNORECASE)
        return match.group(1) if match else None

    def _parse_insert(self, sql: str, result: ParsedStreamSQL) -> None:
        match = re.search(r"INSERT\s+INTO\s+(\w+)", sql, re.IGNORECASE)
        if match:
            result.target_table = match.group(1)
        select_match = re.search(r"SELECT\s+.+", sql, re.IGNORECASE)
        if select_match:
            select_sql = sql[select_match.start():]
            inner = self.parse(select_sql)
            result.columns = inner.columns
            result.sources = inner.sources
            result.where_clause = inner.where_clause
            result.group_by = inner.group_by
            result.window = inner.window
            result.join = inner.join

    def _parse_create_stream(self, sql: str, result: ParsedStreamSQL) -> None:
        match = re.search(r"CREATE\s+STREAM\s+(?:IF\s+NOT\s+EXISTS\s+)?(\w+)", sql, re.IGNORECASE)
        if match:
            result.stream_name = match.group(1)
        as_match = re.search(r"AS\s+(SELECT\s+.+)", sql, re.IGNORECASE | re.DOTALL)
        if as_match:
            inner_sql = as_match.group(1)
            inner = self.parse(inner_sql)
            result.columns = inner.columns
            result.sources = inner.sources
            result.where_clause = inner.where_clause
            result.group_by = inner.group_by
            result.window = inner.window
            result.join = inner.join

    def validate(self, parsed: ParsedStreamSQL) -> List[str]:
        errors = []
        if parsed.sql_type == StreamSQLType.UNKNOWN:
            errors.append("Unrecognized SQL statement type")
        if parsed.sql_type == StreamSQLType.SELECT and not parsed.sources:
            errors.append("SELECT statement must have at least one source")
        if parsed.sql_type == StreamSQLType.CREATE_STREAM and not parsed.stream_name:
            errors.append("CREATE STREAM statement must specify a stream name")
        if parsed.window.window_type != WindowType.NONE and not parsed.window.size:
            errors.append("Window definition requires a SIZE parameter")
        if parsed.join.join_type != JoinType.NONE and not parsed.join.condition:
            errors.append("JOIN operation requires an ON condition")
        return errors
