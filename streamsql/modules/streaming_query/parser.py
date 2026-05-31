from __future__ import annotations

import asyncio
import re
from dataclasses import dataclass, field
from enum import Enum
from typing import Any, Callable, Optional

from streamsql.core.exceptions import SQLParseError
from streamsql.core.models import generate_id

from streamsql.modules.streaming_query.async_pipeline import (
    AsyncParsePipeline,
    AsyncQueryResult,
    ParsePipelineOptions,
    QueryStatus,
)


class QueryType(str, Enum):
    SELECT = "SELECT"
    INSERT = "INSERT"
    UPDATE = "UPDATE"
    DELETE = "DELETE"
    CREATE = "CREATE"
    DROP = "DROP"
    ALTER = "ALTER"
    MERGE = "MERGE"


class WindowType(str, Enum):
    TUMBLING = "tumbling"
    HOPPING = "hopping"
    SLIDING = "sliding"
    SESSION = "session"


@dataclass
class ParsedQuery:
    query_id: str = field(default_factory=lambda: generate_id("sql"))
    query_type: Optional[QueryType] = None
    raw_sql: str = ""
    tables: list[str] = field(default_factory=list)
    columns: list[dict[str, Any]] = field(default_factory=list)
    where_clause: Optional[str] = None
    group_by: list[str] = field(default_factory=list)
    order_by: list[tuple[str, str]] = field(default_factory=list)
    having_clause: Optional[str] = None
    limit: Optional[int] = None
    offset: Optional[int] = None
    joins: list[dict[str, Any]] = field(default_factory=list)
    window_spec: Optional[dict[str, Any]] = None
    is_streaming: bool = False
    ctes: dict[str, str] = field(default_factory=dict)
    set_operations: list[dict[str, Any]] = field(default_factory=list)


@dataclass
class TimeWindow:
    type: WindowType
    duration: int
    slide: Optional[int] = None
    grace_period: Optional[int] = None
    timeout: Optional[int] = None


class StreamingQueryParser:
    """
    SQL query parser with support for both synchronous and asynchronous parsing.

    Enhanced features:
    - Async parsing via AsyncParsePipeline
    - Callback notifications for async results
    - Event-driven result delivery
    - Backward compatible with existing synchronous API
    """

    def __init__(self, use_sqlglot: bool = True):
        self.use_sqlglot = use_sqlglot
        self._sqlglot_available = False
        try:
            import sqlglot
            self._sqlglot_available = True
        except ImportError:
            self._sqlglot_available = False

        self._async_pipeline: Optional[AsyncParsePipeline] = None

    @property
    def async_pipeline(self) -> AsyncParsePipeline:
        """Lazy initialization of async pipeline."""
        if self._async_pipeline is None:
            self._async_pipeline = AsyncParsePipeline(parser=self)
        return self._async_pipeline

    async def start_async(self) -> None:
        """Start the async parsing pipeline."""
        await self.async_pipeline.start()

    async def stop_async(self) -> None:
        """Stop the async parsing pipeline."""
        if self._async_pipeline:
            await self._async_pipeline.stop()
            self._async_pipeline = None

    async def parse_async(
        self,
        sql: str,
        options: Optional[ParsePipelineOptions] = None,
        callback: Optional[Callable[[AsyncQueryResult], Any]] = None,
    ) -> AsyncQueryResult:
        """
        Parse a SQL query asynchronously.

        Args:
            sql: The SQL query to parse
            options: Pipeline options
            callback: Optional callback when parsing completes

        Returns:
            AsyncQueryResult that will be updated as parsing progresses
        """
        return await self.async_pipeline.parse_async(sql, options, callback)

    async def parse_now_async(
        self,
        sql: str,
        options: Optional[ParsePipelineOptions] = None,
        callback: Optional[Callable[[AsyncQueryResult], Any]] = None,
    ) -> AsyncQueryResult:
        """
        Parse a SQL query immediately and wait for completion (async version).

        Args:
            sql: The SQL query to parse
            options: Pipeline options
            callback: Optional callback when parsing completes

        Returns:
            AsyncQueryResult with final result
        """
        return await self.async_pipeline.parse_now(sql, options, callback)

    async def parse_many_async(
        self,
        sqls: list[str],
        options: Optional[ParsePipelineOptions] = None,
        callback: Optional[Callable[[AsyncQueryResult], Any]] = None,
    ) -> list[AsyncQueryResult]:
        """Parse multiple SQL queries asynchronously (queued)."""
        return await self.async_pipeline.parse_many_async(sqls, options, callback)

    async def parse_many_concurrent(
        self,
        sqls: list[str],
        options: Optional[ParsePipelineOptions] = None,
        callback: Optional[Callable[[AsyncQueryResult], Any]] = None,
        max_concurrent: int = 5,
    ) -> list[AsyncQueryResult]:
        """Parse multiple SQL queries concurrently and wait for all to complete."""
        return await self.async_pipeline.parse_many(sqls, options, callback, max_concurrent)

    def get_async_result(self, query_id: str) -> Optional[AsyncQueryResult]:
        """Get the current result for an async query."""
        if self._async_pipeline:
            return self._async_pipeline.get_query_result(query_id)
        return None

    def get_async_stats(self) -> dict[str, Any]:
        """Get async pipeline statistics."""
        if self._async_pipeline:
            return self._async_pipeline.get_stats()
        return {"status": "not_started"}

    def parse(self, sql: str) -> ParsedQuery:
        """Parse a SQL query synchronously."""
        try:
            if self.use_sqlglot and self._sqlglot_available:
                return self._parse_with_sqlglot(sql)
            return self._parse_regex(sql)
        except SQLParseError:
            raise
        except Exception as e:
            raise SQLParseError(sql, message=str(e)) from e

    def _parse_with_sqlglot(self, sql: str) -> ParsedQuery:
        import sqlglot
        from sqlglot import exp

        parsed = ParsedQuery(raw_sql=sql)

        try:
            ast = sqlglot.parse_one(sql, dialect="spark")
        except Exception as e:
            return self._parse_regex(sql)

        if isinstance(ast, exp.Select):
            parsed.query_type = QueryType.SELECT
        elif isinstance(ast, exp.Insert):
            parsed.query_type = QueryType.INSERT
        elif isinstance(ast, exp.Update):
            parsed.query_type = QueryType.UPDATE
        elif isinstance(ast, exp.Delete):
            parsed.query_type = QueryType.DELETE
        elif isinstance(ast, exp.Create):
            parsed.query_type = QueryType.CREATE
        elif isinstance(ast, exp.Drop):
            parsed.query_type = QueryType.DROP
        elif isinstance(ast, exp.Alter):
            parsed.query_type = QueryType.ALTER

        parsed.tables = list(ast.find_all(exp.Table))
        parsed.tables = [t.name for t in ast.find_all(exp.Table)]

        parsed.columns = [
            {"name": c.alias_or_name, "expression": c.sql()}
            for c in ast.find_all(exp.Select)
            for c in c.expressions
        ] if ast.find_all(exp.Select) else []

        where = ast.find(exp.Where)
        if where:
            parsed.where_clause = where.sql()

        group_by = ast.find(exp.Group)
        if group_by:
            parsed.group_by = [e.sql() for e in group_by.expressions]

        order = ast.find(exp.Order)
        if order:
            parsed.order_by = [
                (e.sql(), "DESC" if e.args.get("desc") else "ASC")
                for e in order.expressions
            ]

        limit = ast.find(exp.Limit)
        if limit:
            parsed.limit = int(limit.expressions[0].name)

        parsed.is_streaming = "STREAM" in sql.upper() or "TUMBLE" in sql.upper() or "HOP" in sql.upper()

        window_match = re.search(r"(TUMBLE|HOP|SLIDING|SESSION)\s*\(", sql, re.IGNORECASE)
        if window_match:
            parsed.is_streaming = True
            window_type = window_match.group(1).upper()
            window_args = self._extract_window_args(sql, window_type)
            parsed.window_spec = {
                "type": window_type,
                "args": window_args,
            }

        return parsed

    def _extract_window_args(self, sql: str, window_type: str) -> dict[str, Any]:
        pattern = rf"{window_type}\s*\(([^)]+)\)"
        match = re.search(pattern, sql, re.IGNORECASE)
        if not match:
            return {}

        args_str = match.group(1)
        args = [arg.strip() for arg in args_str.split(",")]
        result: dict[str, Any] = {}

        if len(args) >= 1:
            result["time_col"] = args[0].strip()
        if len(args) >= 2:
            duration_match = re.search(r"(\d+)\s*(second|minute|hour|day)", args[1], re.IGNORECASE)
            if duration_match:
                value = int(duration_match.group(1))
                unit = duration_match.group(2).lower()
                multiplier = {"second": 1, "minute": 60, "hour": 3600, "day": 86400}[unit]
                result["duration_seconds"] = value * multiplier

        return result

    def _parse_regex(self, sql: str) -> ParsedQuery:
        parsed = ParsedQuery(raw_sql=sql)
        sql_stripped = sql.strip()
        sql_upper = sql.upper()

        if re.match(r"^\s*SELECT\s+", sql_stripped, re.IGNORECASE):
            parsed.query_type = QueryType.SELECT
        elif re.match(r"^\s*INSERT\s+", sql_stripped, re.IGNORECASE):
            parsed.query_type = QueryType.INSERT
        elif re.match(r"^\s*UPDATE\s+", sql_stripped, re.IGNORECASE):
            parsed.query_type = QueryType.UPDATE
        elif re.match(r"^\s*DELETE\s+", sql_stripped, re.IGNORECASE):
            parsed.query_type = QueryType.DELETE
        elif re.match(r"^\s*CREATE\s+", sql_stripped, re.IGNORECASE):
            parsed.query_type = QueryType.CREATE
        elif re.match(r"^\s*DROP\s+", sql_stripped, re.IGNORECASE):
            parsed.query_type = QueryType.DROP
        elif re.match(r"^\s*ALTER\s+", sql_stripped, re.IGNORECASE):
            parsed.query_type = QueryType.ALTER

        from_match = re.search(r"FROM\s+([\w,\s.]+?)(?:\s+WHERE|\s+GROUP|\s+ORDER|\s+HAVING|\s+LIMIT|\s*$)", sql, re.IGNORECASE | re.DOTALL)
        if from_match:
            tables_str = from_match.group(1).strip()
            parsed.tables = [t.strip() for t in tables_str.split(",") if t.strip()]

        select_match = re.search(r"SELECT\s+(.+?)\s+FROM", sql, re.IGNORECASE | re.DOTALL)
        if select_match:
            cols_str = select_match.group(1).strip()
            if cols_str == "*":
                parsed.columns = [{"name": "*", "expression": "*"}]
            else:
                parsed.columns = [
                    {"name": c.strip().split()[-1], "expression": c.strip()}
                    for c in cols_str.split(",") if c.strip()
                ]

        where_match = re.search(r"WHERE\s+(.+?)(?:\s+GROUP|\s+ORDER|\s+HAVING|\s+LIMIT|\s*$)", sql, re.IGNORECASE | re.DOTALL)
        if where_match:
            parsed.where_clause = where_match.group(1).strip()

        group_match = re.search(r"GROUP\s+BY\s+(.+?)(?:\s+HAVING|\s+ORDER|\s+LIMIT|\s*$)", sql, re.IGNORECASE | re.DOTALL)
        if group_match:
            parsed.group_by = [g.strip() for g in group_match.group(1).split(",") if g.strip()]

        order_match = re.search(r"ORDER\s+BY\s+(.+?)(?:\s+LIMIT|\s*$)", sql, re.IGNORECASE | re.DOTALL)
        if order_match:
            order_str = order_match.group(1)
            parsed.order_by = []
            for item in order_str.split(","):
                item = item.strip()
                if item.upper().endswith("DESC"):
                    parsed.order_by.append((item[:-5].strip(), "DESC"))
                elif item.upper().endswith("ASC"):
                    parsed.order_by.append((item[:-4].strip(), "ASC"))
                else:
                    parsed.order_by.append((item, "ASC"))

        limit_match = re.search(r"LIMIT\s+(\d+)", sql, re.IGNORECASE)
        if limit_match:
            parsed.limit = int(limit_match.group(1))

        parsed.is_streaming = "STREAM" in sql_upper or "TUMBLE" in sql_upper or "HOP" in sql_upper or "SLIDING" in sql_upper

        window_match = re.search(r"(TUMBLE|HOP|SLIDING|SESSION)\s*\(", sql, re.IGNORECASE)
        if window_match:
            window_type = window_match.group(1).upper()
            window_args = self._extract_window_args(sql, window_type)
            parsed.window_spec = {
                "type": window_type,
                "args": window_args,
            }

        return parsed

    def parse_many(self, sqls: list[str]) -> list[ParsedQuery]:
        """Parse multiple SQL queries synchronously."""
        return [self.parse(sql) for sql in sqls]

    def extract_tables(self, sql: str) -> list[str]:
        return self.parse(sql).tables

    def extract_columns(self, sql: str) -> list[dict[str, Any]]:
        return self.parse(sql).columns

    def is_streaming_query(self, sql: str) -> bool:
        return self.parse(sql).is_streaming

    def validate(self, sql: str) -> tuple[bool, list[str]]:
        errors: list[str] = []
        try:
            parsed = self.parse(sql)
            if parsed.query_type is None:
                errors.append("No valid query type found (SELECT, INSERT, UPDATE, DELETE, CREATE, DROP, ALTER)")
                return False, errors
            return True, errors
        except SQLParseError as e:
            errors.append(e.message)
            return False, errors
