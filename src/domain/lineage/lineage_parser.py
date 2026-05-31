import re
import logging
import signal
from concurrent.futures import ThreadPoolExecutor, TimeoutError as FutureTimeoutError
from typing import Dict, List, Optional, Set, Tuple

import sqlparse
from sqlparse.sql import Identifier, IdentifierList, Parenthesis, Where
from sqlparse.tokens import Keyword, DML

from src.domain.lineage.models import (
    LineageNode,
    LineageEdge,
    ColumnLineage,
    LineageGraph,
    NodeType,
    EdgeType,
)

logger = logging.getLogger(__name__)


class LineageParseTimeoutException(Exception):
    def __init__(self, sql: str, timeout_seconds: float):
        super().__init__(f"Lineage parsing timed out after {timeout_seconds} seconds for SQL: {sql[:100]}...")
        self.sql = sql
        self.timeout_seconds = timeout_seconds


class LineageParseError(Exception):
    def __init__(self, message: str, sql: str = None):
        super().__init__(message)
        self.sql = sql


class LineageParser:
    TABLE_ALIAS_PATTERN = re.compile(r"(\w+)\s+(?:AS\s+)?(\w+)", re.IGNORECASE)
    COLUMN_REF_PATTERN = re.compile(r"(?:(\w+)\.)?(\w+)")
    AGG_PATTERN = re.compile(r"\b(COUNT|SUM|AVG|MIN|MAX|STDDEV|VARIANCE)\s*\(\s*(?:(\w+)\.)?(\w+|\*)\s*\)", re.IGNORECASE)
    CASE_PATTERN = re.compile(r"CASE\s+WHEN\s+.+\s+END", re.IGNORECASE | re.DOTALL)
    DEFAULT_TIMEOUT = 5.0

    def __init__(self, default_timeout: float = DEFAULT_TIMEOUT):
        self._default_timeout = default_timeout
        self._thread_pool = ThreadPoolExecutor(max_workers=1, thread_name_prefix="lineage-parser")

    def __del__(self):
        try:
            self._thread_pool.shutdown(wait=False)
        except Exception:
            pass

    def parse_sql(
        self,
        sql: str,
        default_database: str = "default",
        timeout: Optional[float] = None,
    ) -> LineageGraph:
        timeout_seconds = timeout or self._default_timeout

        def do_parse():
            graph = LineageGraph()
            sql_clean = sql.strip().rstrip(";")
            normalized = sql_clean.upper().strip()

            if normalized.startswith("INSERT") or normalized.startswith("CREATE"):
                self._parse_write_sql(sql_clean, default_database, graph)
            elif normalized.startswith("SELECT"):
                self._parse_select_sql(sql_clean, default_database, graph)
            elif normalized.startswith("MERGE"):
                self._parse_merge_sql(sql_clean, default_database, graph)

            return graph

        future = self._thread_pool.submit(do_parse)
        try:
            result = future.result(timeout=timeout_seconds)
            return result
        except FutureTimeoutError:
            future.cancel()
            logger.warning(f"Lineage parsing timed out after {timeout_seconds}s: {sql[:80]}...")
            raise LineageParseTimeoutException(sql, timeout_seconds)
        except LineageParseError:
            raise
        except Exception as e:
            logger.error(f"Unexpected error during lineage parsing: {e}")
            raise LineageParseError(f"Failed to parse SQL: {str(e)}", sql)

    def _parse_write_sql(self, sql: str, default_database: str, graph: LineageGraph) -> None:
        target_table = self._extract_target_table(sql, default_database)
        if target_table:
            target_node = self._ensure_table_node(graph, target_table[0], target_table[1])
        else:
            target_node = None

        select_match = re.search(r"(SELECT\s+.+)", sql, re.IGNORECASE | re.DOTALL)
        if select_match:
            select_sql = select_match.group(1)
            select_graph = self._parse_select_sql(select_sql, default_database, graph)

        if target_node:
            for node in list(graph.nodes.values()):
                if node.node_type == NodeType.TABLE and node.node_id != target_node.node_id:
                    edge = LineageEdge(
                        source_id=node.node_id,
                        target_id=target_node.node_id,
                        edge_type=EdgeType.DERIVES_FROM,
                        sql_text=sql,
                    )
                    graph.add_edge(edge)

        self._extract_column_lineage(sql, default_database, target_table, graph)

    def _parse_select_sql(self, sql: str, default_database: str, graph: LineageGraph) -> LineageGraph:
        sources = self._extract_source_tables(sql, default_database)
        for db, tbl in sources:
            self._ensure_table_node(graph, db, tbl)

        columns = self._extract_select_columns(sql)
        for col_info in columns:
            if col_info.get("source_table") and col_info.get("source_column"):
                src_db = default_database
                src_tbl = col_info["source_table"]
                for db, tbl in sources:
                    if tbl == src_tbl or self._match_alias(sql, src_tbl, tbl):
                        src_db = db
                        src_tbl = tbl
                        break
                source_node = self._ensure_table_node(graph, src_db, src_tbl)
                if col_info.get("source_column") != "*":
                    col_node = self._ensure_column_node(
                        graph, src_db, src_tbl, col_info["source_column"]
                    )

        return graph

    def _parse_merge_sql(self, sql: str, default_database: str, graph: LineageGraph) -> None:
        target_match = re.search(r"MERGE\s+INTO\s+(?:(\w+)\.)?(\w+)", sql, re.IGNORECASE)
        source_match = re.search(r"USING\s+(?:(\w+)\.)?(\w+)", sql, re.IGNORECASE)

        if target_match:
            tdb = target_match.group(1) or default_database
            ttbl = target_match.group(2)
            target_node = self._ensure_table_node(graph, tdb, ttbl)

        if source_match:
            sdb = source_match.group(1) or default_database
            stbl = source_match.group(2)
            source_node = self._ensure_table_node(graph, sdb, stbl)

            if target_match:
                edge = LineageEdge(
                    source_id=source_node.node_id,
                    target_id=target_node.node_id,
                    edge_type=EdgeType.DERIVES_FROM,
                    sql_text=sql,
                )
                graph.add_edge(edge)

    def _extract_target_table(self, sql: str, default_database: str) -> Optional[Tuple[str, str]]:
        insert_match = re.search(r"INSERT\s+INTO\s+(?:(\w+)\.)?(\w+)", sql, re.IGNORECASE)
        if insert_match:
            db = insert_match.group(1) or default_database
            tbl = insert_match.group(2)
            return (db, tbl)

        create_match = re.search(r"CREATE\s+(?:TEMPORARY\s+)?(?:TABLE|VIEW)\s+(?:IF\s+NOT\s+EXISTS\s+)?(?:(\w+)\.)?(\w+)", sql, re.IGNORECASE)
        if create_match:
            db = create_match.group(1) or default_database
            tbl = create_match.group(2)
            return (db, tbl)

        return None

    def _extract_source_tables(self, sql: str, default_database: str) -> List[Tuple[str, str]]:
        sources = []
        from_match = re.search(r"\bFROM\s+(.+?)(?:\s+WHERE|\s+GROUP|\s+ORDER|\s+HAVING|\s+LIMIT|\s+UNION|\s+WINDOW|\s+EMIT|\s*$)", sql, re.IGNORECASE | re.DOTALL)
        if from_match:
            from_clause = from_match.group(1).strip()
            sources.extend(self._parse_table_list(from_clause, default_database))

        join_matches = re.finditer(r"\bJOIN\s+(?:(\w+)\.)?(\w+)", sql, re.IGNORECASE)
        for jm in join_matches:
            db = jm.group(1) or default_database
            tbl = jm.group(2)
            if (db, tbl) not in sources:
                sources.append((db, tbl))

        return sources

    def _parse_table_list(self, clause: str, default_database: str) -> List[Tuple[str, str]]:
        tables = []
        parts = re.split(r",\s*", clause)
        for part in parts:
            part = part.strip()
            paren_depth = 0
            clean = []
            for ch in part:
                if ch == "(":
                    paren_depth += 1
                elif ch == ")":
                    paren_depth -= 1
                elif paren_depth == 0:
                    clean.append(ch)
            clean_str = "".join(clean).strip()
            match = re.match(r"(?:(\w+)\.)?(\w+)", clean_str)
            if match:
                db = match.group(1) or default_database
                tbl = match.group(2)
                if tbl.upper() not in ("SELECT", "AS", "ON", "AND", "OR", "WHERE"):
                    tables.append((db, tbl))
        return tables

    def _extract_select_columns(self, sql: str) -> List[Dict[str, str]]:
        columns = []
        select_match = re.search(r"SELECT\s+(DISTINCT\s+)?(.+?)\s+FROM", sql, re.IGNORECASE | re.DOTALL)
        if not select_match:
            return columns
        col_clause = select_match.group(2).strip()
        col_parts = self._split_column_list(col_clause)
        for part in col_parts:
            part = part.strip()
            if part == "*":
                columns.append({"source_column": "*", "source_table": None, "alias": None})
                continue
            dot_match = re.match(r"(?:(\w+)\.)?(\w+|\*)\s*(?:AS\s+(\w+))?$", part, re.IGNORECASE)
            if dot_match:
                columns.append({
                    "source_table": dot_match.group(1),
                    "source_column": dot_match.group(2),
                    "alias": dot_match.group(3),
                })
            else:
                agg_match = self.AGG_PATTERN.search(part)
                if agg_match:
                    columns.append({
                        "source_table": agg_match.group(2),
                        "source_column": agg_match.group(3),
                        "alias": None,
                        "transformation": "AGGREGATION",
                        "function": agg_match.group(1).upper(),
                    })
                else:
                    columns.append({
                        "source_table": None,
                        "source_column": part,
                        "alias": None,
                        "transformation": "EXPRESSION",
                    })
        return columns

    def _split_column_list(self, col_clause: str) -> List[str]:
        parts = []
        depth = 0
        current = []
        for ch in col_clause:
            if ch == "(":
                depth += 1
                current.append(ch)
            elif ch == ")":
                depth -= 1
                current.append(ch)
            elif ch == "," and depth == 0:
                parts.append("".join(current).strip())
                current = []
            else:
                current.append(ch)
        if current:
            parts.append("".join(current).strip())
        return parts

    def _extract_column_lineage(
        self,
        sql: str,
        default_database: str,
        target_table: Optional[Tuple[str, str]],
        graph: LineageGraph,
    ) -> None:
        if not target_table:
            return
        tdb, ttbl = target_table
        select_columns = self._extract_select_columns(sql)
        source_tables = self._extract_source_tables(sql, default_database)
        alias_map = self._build_alias_map(sql, source_tables, default_database)

        for idx, col_info in enumerate(select_columns):
            target_col_name = col_info.get("alias") or col_info.get("source_column", f"col_{idx}")
            src_tbl_name = col_info.get("source_table")
            src_col_name = col_info.get("source_column")

            if src_tbl_name and src_col_name and src_col_name != "*":
                resolved_tbl = alias_map.get(src_tbl_name, src_tbl_name)
                src_db = default_database
                for db, tbl in source_tables:
                    if tbl == resolved_tbl:
                        src_db = db
                        break
                transformation = col_info.get("transformation")
                function = col_info.get("function")
                edge_type = EdgeType.DERIVES_FROM
                if transformation == "AGGREGATION":
                    edge_type = EdgeType.AGGREGATES
                elif transformation == "EXPRESSION":
                    edge_type = EdgeType.TRANSFORMS

                cl = ColumnLineage(
                    source_db=src_db,
                    source_table=resolved_tbl,
                    source_column=src_col_name,
                    target_db=tdb,
                    target_table=ttbl,
                    target_column=target_col_name,
                    transformation=function or transformation,
                    transformation_type=edge_type,
                )
                src_col_node = self._ensure_column_node(graph, src_db, resolved_tbl, src_col_name)
                tgt_col_node = self._ensure_column_node(graph, tdb, ttbl, target_col_name)
                edge = LineageEdge(
                    source_id=src_col_node.node_id,
                    target_id=tgt_col_node.node_id,
                    edge_type=edge_type,
                    transformation=function or transformation,
                    sql_text=sql,
                )
                graph.add_edge(edge)

    def _build_alias_map(self, sql: str, sources: List[Tuple[str, str]], default_database: str) -> Dict[str, str]:
        alias_map = {}
        for db, tbl in sources:
            pattern = re.compile(rf"{re.escape(tbl)}\s+(?:AS\s+)?(\w+)", re.IGNORECASE)
            match = pattern.search(sql)
            if match:
                alias_map[match.group(1)] = tbl
        return alias_map

    def _ensure_table_node(self, graph: LineageGraph, database: str, table_name: str) -> LineageNode:
        node_id = f"table:{database}.{table_name}"
        if node_id not in graph.nodes:
            node = LineageNode(
                node_id=node_id,
                node_type=NodeType.TABLE,
                name=table_name,
                database=database,
            )
            graph.add_node(node)
        return graph.nodes[node_id]

    def _ensure_column_node(self, graph: LineageGraph, database: str, table_name: str, column_name: str) -> LineageNode:
        node_id = f"column:{database}.{table_name}.{column_name}"
        if node_id not in graph.nodes:
            node = LineageNode(
                node_id=node_id,
                node_type=NodeType.COLUMN,
                name=column_name,
                database=database,
                schema_name=table_name,
            )
            graph.add_node(node)
            table_node_id = f"table:{database}.{table_name}"
            if table_node_id in graph.nodes:
                edge = LineageEdge(
                    source_id=table_node_id,
                    target_id=node_id,
                    edge_type=EdgeType.DERIVES_FROM,
                )
                graph.add_edge(edge)
        return graph.nodes[node_id]

    def _match_alias(self, sql: str, alias_or_name: str, table_name: str) -> bool:
        pattern = re.compile(rf"{re.escape(table_name)}\s+(?:AS\s+)?{re.escape(alias_or_name)}\b", re.IGNORECASE)
        return bool(pattern.search(sql))
