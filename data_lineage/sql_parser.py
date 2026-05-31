from __future__ import annotations

import re
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional, Set, Tuple, Union

import sqlglot
from sqlglot import exp
from sqlglot.expressions import (
    Expression,
    Alias,
    Column,
    CTE,
    Join,
    Select,
    Table,
    Window,
    Subquery,
    Create,
    Insert,
    Update,
    Delete,
)


@dataclass
class TableReference:
    name: str
    schema: Optional[str] = None
    database: Optional[str] = None
    alias: Optional[str] = None

    @property
    def full_name(self) -> str:
        parts = []
        if self.database:
            parts.append(self.database)
        if self.schema:
            parts.append(self.schema)
        parts.append(self.name)
        return ".".join(parts)

    @property
    def identifier(self) -> str:
        return self.alias or self.full_name


@dataclass
class ColumnReference:
    name: str
    table_alias: Optional[str] = None
    expression: Optional[str] = None
    is_wildcard: bool = False

    @property
    def full_name(self) -> str:
        if self.table_alias:
            return f"{self.table_alias}.{self.name}"
        return self.name


@dataclass
class ParsedSQL:
    sql: str
    statement_type: str
    ast: Expression
    tables: List[TableReference] = field(default_factory=list)
    columns: List[ColumnReference] = field(default_factory=list)
    output_columns: List[ColumnReference] = field(default_factory=list)
    ctes: Dict[str, "ParsedSQL"] = field(default_factory=dict)
    subqueries: List["ParsedSQL"] = field(default_factory=list)
    joins: List[Dict[str, Any]] = field(default_factory=list)
    target_table: Optional[TableReference] = None
    where_conditions: List[str] = field(default_factory=list)
    window_functions: List[Dict[str, Any]] = field(default_factory=list)
    raw_ast: Optional[str] = None


class SQLParser:
    def __init__(self, dialect: str = "duckdb"):
        self.dialect = dialect

    def parse(self, sql: str) -> ParsedSQL:
        try:
            parsed = sqlglot.parse_one(sql, dialect=self.dialect)
            return self._parse_expression(parsed, sql)
        except Exception as e:
            raise ValueError(f"SQL解析失败: {str(e)}")

    def parse_many(self, sql: str) -> List[ParsedSQL]:
        try:
            parsed_statements = sqlglot.parse(sql, dialect=self.dialect)
            return [self._parse_expression(stmt, stmt.sql(dialect=self.dialect)) for stmt in parsed_statements]
        except Exception as e:
            raise ValueError(f"SQL解析失败: {str(e)}")

    def _parse_expression(self, expr: Expression, original_sql: str) -> ParsedSQL:
        stmt_type = self._get_statement_type(expr)
        parsed = ParsedSQL(
            sql=original_sql,
            statement_type=stmt_type,
            ast=expr,
            raw_ast=expr.dump(),
        )

        if stmt_type == "CREATE_TABLE_AS":
            parsed.target_table = self._extract_target_table(expr)
            select_expr = expr.find(Select)
            if select_expr:
                self._parse_select(select_expr, parsed)
        elif stmt_type == "INSERT":
            parsed.target_table = self._extract_target_table(expr)
            select_expr = expr.find(Select)
            if select_expr:
                self._parse_select(select_expr, parsed)
        elif stmt_type == "SELECT":
            self._parse_select(expr, parsed)
        elif stmt_type == "UPDATE":
            parsed.target_table = self._extract_target_table(expr)
            self._extract_tables_and_columns(expr, parsed)
        elif stmt_type == "DELETE":
            parsed.target_table = self._extract_target_table(expr)
            self._extract_tables_and_columns(expr, parsed)

        return parsed

    def _get_statement_type(self, expr: Expression) -> str:
        if isinstance(expr, Create) and expr.kind == "TABLE":
            return "CREATE_TABLE_AS"
        elif isinstance(expr, Insert):
            return "INSERT"
        elif isinstance(expr, Select):
            return "SELECT"
        elif isinstance(expr, Update):
            return "UPDATE"
        elif isinstance(expr, Delete):
            return "DELETE"
        else:
            return "UNKNOWN"

    def _parse_select(self, select_expr: Select, parsed: ParsedSQL) -> None:
        self._extract_ctes(select_expr, parsed)
        self._extract_from_clause(select_expr, parsed)
        self._extract_joins(select_expr, parsed)
        self._extract_output_columns(select_expr, parsed)
        self._extract_where_conditions(select_expr, parsed)
        self._extract_window_functions(select_expr, parsed)
        self._extract_subqueries(select_expr, parsed)

    def _extract_ctes(self, select_expr: Select, parsed: ParsedSQL) -> None:
        for cte in select_expr.find_all(CTE):
            cte_alias = cte.alias
            cte_select = cte.this
            if isinstance(cte_select, Select):
                cte_parsed = ParsedSQL(
                    sql=cte_select.sql(dialect=self.dialect),
                    statement_type="CTE",
                    ast=cte_select,
                    raw_ast=cte_select.dump(),
                )
                self._parse_select(cte_select, cte_parsed)
                parsed.ctes[cte_alias] = cte_parsed

    def _extract_from_clause(self, select_expr: Select, parsed: ParsedSQL) -> None:
        from_expr = select_expr.find(exp.From)
        if from_expr:
            for table_expr in from_expr.find_all(Table):
                if not self._is_in_subquery(table_expr, select_expr):
                    table_ref = self._parse_table_reference(table_expr)
                    parsed.tables.append(table_ref)

    def _extract_joins(self, select_expr: Select, parsed: ParsedSQL) -> None:
        for join in select_expr.find_all(Join):
            join_info = {
                "join_type": join.args.get("side", "INNER"),
                "table": self._parse_table_reference(join.this),
                "condition": join.args.get("condition").sql(dialect=self.dialect) if join.args.get("condition") else None,
            }
            parsed.joins.append(join_info)
            if join_info["table"]:
                parsed.tables.append(join_info["table"])

    def _extract_output_columns(self, select_expr: Select, parsed: ParsedSQL) -> None:
        col_refs = self._extract_columns_from_select(select_expr)
        parsed.output_columns.extend(col_refs)

    def _extract_columns_from_select(self, select_expr: Select) -> List[ColumnReference]:
        columns: List[ColumnReference] = []
        select_expressions = select_expr.expressions
        
        for expr_item in select_expressions:
            if isinstance(expr_item, exp.Star):
                columns.append(ColumnReference(name="*", is_wildcard=True))
            elif isinstance(expr_item, Alias):
                col_ref = self._parse_column_expression(expr_item.this)
                col_ref.alias = expr_item.alias
                columns.append(col_ref)
            else:
                col_ref = self._parse_column_expression(expr_item)
                columns.append(col_ref)
        
        return columns

    def _parse_column_expression(self, expr: Expression) -> ColumnReference:
        if isinstance(expr, Column):
            table_alias = None
            if expr.table:
                table_alias = expr.table.name if hasattr(expr.table, "name") else str(expr.table)
            return ColumnReference(
                name=expr.name,
                table_alias=table_alias,
                expression=expr.sql(dialect=self.dialect),
            )
        else:
            return ColumnReference(
                name=self._generate_column_name(expr),
                expression=expr.sql(dialect=self.dialect),
            )

    def _generate_column_name(self, expr: Expression) -> str:
        if isinstance(expr, exp.Func):
            return f"{expr.this.__class__.__name__.lower()}_expr"
        return f"expr_{abs(hash(expr.sql()))[:8]}"

    def _extract_where_conditions(self, select_expr: Select, parsed: ParsedSQL) -> None:
        where_expr = select_expr.find(exp.Where)
        if where_expr:
            conditions = []
            for cond in where_expr.find_all(exp.Condition):
                conditions.append(cond.sql(dialect=self.dialect))
            parsed.where_conditions = conditions
            col_refs = self._extract_columns_from_expression(where_expr)
            parsed.columns.extend(col_refs)

    def _extract_window_functions(self, select_expr: Select, parsed: ParsedSQL) -> None:
        for window in select_expr.find_all(Window):
            window_func = window.this
            window_info = {
                "function": window_func.sql(dialect=self.dialect) if window_func else None,
                "partition_by": [p.sql(dialect=self.dialect) for p in window.args.get("partition_by", [])],
                "order_by": [o.sql(dialect=self.dialect) for o in window.args.get("order_by", [])],
                "spec": window.sql(dialect=self.dialect),
            }
            parsed.window_functions.append(window_info)

    def _extract_subqueries(self, select_expr: Select, parsed: ParsedSQL) -> None:
        for subquery in select_expr.find_all(Subquery):
            if not self._is_in_cte(subquery, select_expr):
                subquery_select = subquery.find(Select)
                if subquery_select:
                    sub_parsed = ParsedSQL(
                        sql=subquery_select.sql(dialect=self.dialect),
                        statement_type="SUBQUERY",
                        ast=subquery_select,
                        raw_ast=subquery_select.dump(),
                    )
                    self._parse_select(subquery_select, sub_parsed)
                    parsed.subqueries.append(sub_parsed)

    def _extract_tables_and_columns(self, expr: Expression, parsed: ParsedSQL) -> None:
        for table_expr in expr.find_all(Table):
            table_ref = self._parse_table_reference(table_expr)
            parsed.tables.append(table_ref)
        
        for col_expr in expr.find_all(Column):
            col_ref = self._parse_column_expression(col_expr)
            parsed.columns.append(col_ref)

    def _extract_columns_from_expression(self, expr: Expression) -> List[ColumnReference]:
        columns: List[ColumnReference] = []
        for col_expr in expr.find_all(Column):
            col_ref = self._parse_column_expression(col_expr)
            columns.append(col_ref)
        return columns

    def _parse_table_reference(self, table_expr: Table) -> TableReference:
        return TableReference(
            name=table_expr.name,
            schema=table_expr.db if hasattr(table_expr, "db") else None,
            database=table_expr.catalog if hasattr(table_expr, "catalog") else None,
            alias=table_expr.alias if hasattr(table_expr, "alias") else None,
        )

    def _extract_target_table(self, expr: Expression) -> Optional[TableReference]:
        if isinstance(expr, Create):
            table_expr = expr.this
            if isinstance(table_expr, Table):
                return self._parse_table_reference(table_expr)
        elif isinstance(expr, Insert):
            table_expr = expr.this
            if isinstance(table_expr, Table):
                return self._parse_table_reference(table_expr)
        elif isinstance(expr, (Update, Delete)):
            table_expr = expr.this
            if isinstance(table_expr, Table):
                return self._parse_table_reference(table_expr)
        return None

    def _is_in_subquery(self, expr: Expression, parent: Expression) -> bool:
        current = expr.parent
        while current is not None and current is not parent:
            if isinstance(current, Subquery):
                return True
            current = current.parent
        return False

    def _is_in_cte(self, expr: Expression, parent: Expression) -> bool:
        current = expr.parent
        while current is not None and current is not parent:
            if isinstance(current, CTE):
                return True
            current = current.parent
        return False

    def get_all_tables(self, parsed: ParsedSQL) -> Set[str]:
        tables = set()
        for table in parsed.tables:
            tables.add(table.full_name)
        for cte in parsed.ctes.values():
            tables.update(self.get_all_tables(cte))
        for subquery in parsed.subqueries:
            tables.update(self.get_all_tables(subquery))
        return tables

    def get_all_columns(self, parsed: ParsedSQL) -> Set[str]:
        columns = set()
        for col in parsed.columns:
            columns.add(col.full_name)
        for col in parsed.output_columns:
            columns.add(col.full_name)
        for cte in parsed.ctes.values():
            columns.update(self.get_all_columns(cte))
        for subquery in parsed.subqueries:
            columns.update(self.get_all_columns(subquery))
        return columns

    def resolve_table_alias_mapping(self, parsed: ParsedSQL) -> Dict[str, str]:
        mapping = {}
        for table in parsed.tables:
            if table.alias:
                mapping[table.alias] = table.full_name
        for cte_alias, cte_parsed in parsed.ctes.items():
            mapping[cte_alias] = cte_alias
        return mapping
