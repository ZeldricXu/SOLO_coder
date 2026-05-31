from __future__ import annotations

import re
from dataclasses import dataclass, field
from typing import Any

from streamsql.core.exceptions import LineageExtractionError
from streamsql.core.models import generate_id


@dataclass
class ColumnLineage:
    lineage_id: str = field(default_factory=lambda: generate_id("col"))
    target_table: str = ""
    target_column: str = ""
    source_tables: list[str] = field(default_factory=list)
    source_columns: list[tuple[str, str]] = field(default_factory=list)
    transform_type: str = "direct"
    expression: str = ""
    is_aggregation: bool = False
    is_join: bool = False


@dataclass
class TableLineage:
    lineage_id: str = field(default_factory=lambda: generate_id("tbl"))
    target_table: str = ""
    source_tables: list[str] = field(default_factory=list)
    operation_type: str = ""
    column_lineages: list[ColumnLineage] = field(default_factory=list)


class SQLColumnLineageExtractor:
    def __init__(self):
        self._sqlglot_available = False
        try:
            import sqlglot
            self._sqlglot_available = True
        except ImportError:
            self._sqlglot_available = False

    def extract(self, sql: str) -> list[ColumnLineage]:
        try:
            if self._sqlglot_available:
                return self._extract_with_sqlglot(sql)
            return self._extract_regex(sql)
        except Exception as e:
            raise LineageExtractionError(sql, str(e)) from e

    def _extract_with_sqlglot(self, sql: str) -> list[ColumnLineage]:
        import sqlglot
        from sqlglot import exp

        lineages: list[ColumnLineage] = []

        try:
            parsed = sqlglot.parse_one(sql)
        except Exception:
            return self._extract_regex(sql)

        target_table = self._extract_target_table(parsed)
        source_tables = self._extract_source_tables(parsed)

        selects = parsed.find_all(exp.Select)
        for select in selects:
            for col_expr in select.expressions:
                col_name = col_expr.alias_or_name
                source_cols = self._find_source_columns(col_expr)

                lineage = ColumnLineage(
                    target_table=target_table,
                    target_column=col_name,
                    source_tables=list(source_tables),
                    source_columns=source_cols,
                    expression=col_expr.sql(),
                    is_aggregation=self._has_aggregation(col_expr),
                    is_join=len(source_tables) > 1,
                )

                if len(source_cols) > 1:
                    lineage.transform_type = "expression"
                elif source_cols:
                    lineage.transform_type = "direct"
                else:
                    lineage.transform_type = "literal"

                lineages.append(lineage)

        return lineages

    def _extract_target_table(self, parsed: Any) -> str:
        for node in parsed.find_all(exp.Insert):
            return node.this.name
        for node in parsed.find_all(exp.Create):
            if isinstance(node.this, exp.Table):
                return node.this.name
        return "unknown"

    def _extract_source_tables(self, parsed: Any) -> list[str]:
        tables = []
        for table in parsed.find_all(exp.Table):
            table_name = table.name
            if table_name not in tables:
                tables.append(table_name)
        return tables

    def _find_source_columns(self, expr: Any) -> list[tuple[str, str]]:
        columns: list[tuple[str, str]] = []
        for col in expr.find_all(exp.Column):
            table = col.table or "unknown"
            name = col.name
            columns.append((table, name))
        return list(set(columns))

    def _has_aggregation(self, expr: Any) -> bool:
        agg_funcs = [exp.Sum, exp.Avg, exp.Count, exp.Min, exp.Max, exp.GroupConcat]
        for agg in agg_funcs:
            if expr.find(agg):
                return True
        return False

    def _extract_regex(self, sql: str) -> list[ColumnLineage]:
        lineages: list[ColumnLineage] = []

        target_table = self._regex_extract_target(sql)
        source_tables = self._regex_extract_sources(sql)

        select_match = re.search(
            r"SELECT\s+(.+?)\s+FROM", sql, re.IGNORECASE | re.DOTALL
        )
        if select_match:
            cols_str = select_match.group(1).strip()
            cols = [c.strip() for c in cols_str.split(",") if c.strip()]

            for col in cols:
                target_col = col.split()[-1].strip()
                if target_col.upper() == "AS":
                    target_col = col.split()[-2].strip()

                source_cols = self._regex_find_source_columns(col)

                lineage = ColumnLineage(
                    target_table=target_table,
                    target_column=target_col,
                    source_tables=list(source_tables),
                    source_columns=source_cols,
                    expression=col,
                    is_aggregation=self._regex_has_aggregation(col),
                    is_join=len(source_tables) > 1,
                )

                if len(source_cols) > 1:
                    lineage.transform_type = "expression"
                elif source_cols:
                    lineage.transform_type = "direct"
                else:
                    lineage.transform_type = "literal"

                lineages.append(lineage)

        return lineages

    def _regex_extract_target(self, sql: str) -> str:
        insert_match = re.search(r"INSERT INTO\s+([\w.]+)", sql, re.IGNORECASE)
        if insert_match:
            return insert_match.group(1)

        create_match = re.search(r"CREATE TABLE\s+([\w.]+)", sql, re.IGNORECASE)
        if create_match:
            return create_match.group(1)

        return "result"

    def _regex_extract_sources(self, sql: str) -> list[str]:
        tables: list[str] = []

        from_match = re.search(
            r"FROM\s+([\w,.\s]+?)(?:\s+WHERE|\s+GROUP|\s+ORDER|\s+HAVING|\s+LIMIT|\s*$)",
            sql,
            re.IGNORECASE | re.DOTALL,
        )
        if from_match:
            from_str = from_match.group(1).strip()
            from_str = re.sub(r"\s+JOIN\s+[\w.]+\s+ON\s+.+?(\s+|$)", " ", from_str, flags=re.IGNORECASE)
            for part in re.split(r"[,\s]+", from_str):
                part = part.strip()
                if part and not part.upper() in ["JOIN", "INNER", "LEFT", "RIGHT", "OUTER", "ON", ""]:
                    tables.append(part.split()[0])

        join_matches = re.findall(r"JOIN\s+([\w.]+)", sql, re.IGNORECASE)
        tables.extend(join_matches)

        return list(set(tables))

    def _regex_find_source_columns(self, expr: str) -> list[tuple[str, str]]:
        columns: list[tuple[str, str]] = []

        col_pattern = re.compile(r"(\w+)\.(\w+)")
        matches = col_pattern.findall(expr)
        for table, col in matches:
            columns.append((table, col))

        if not columns:
            simple_col = re.search(r"^(\w+)$", expr)
            if simple_col and not self._is_function(expr):
                columns.append(("unknown", simple_col.group(1)))

        return list(set(columns))

    def _regex_has_aggregation(self, expr: str) -> bool:
        agg_funcs = ["SUM(", "AVG(", "COUNT(", "MIN(", "MAX(", "GROUP_CONCAT("]
        return any(f in expr.upper() for f in agg_funcs)

    def _is_function(self, expr: str) -> bool:
        return bool(re.search(r"^\w+\(", expr))

    def extract_table_lineage(self, sql: str) -> TableLineage:
        column_lineages = self.extract(sql)

        target_table = column_lineages[0].target_table if column_lineages else "unknown"
        source_tables: list[str] = []
        for cl in column_lineages:
            for t in cl.source_tables:
                if t not in source_tables:
                    source_tables.append(t)

        operation = "SELECT"
        if "INSERT" in sql.upper():
            operation = "INSERT"
        elif "UPDATE" in sql.upper():
            operation = "UPDATE"
        elif "DELETE" in sql.upper():
            operation = "DELETE"
        elif "CREATE" in sql.upper():
            operation = "CREATE"

        return TableLineage(
            target_table=target_table,
            source_tables=source_tables,
            operation_type=operation,
            column_lineages=column_lineages,
        )

    def extract_batch(self, sqls: list[str]) -> list[TableLineage]:
        return [self.extract_table_lineage(sql) for sql in sqls]
