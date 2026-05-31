from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional, Set, Tuple
import hashlib

from .sql_parser import SQLParser, ParsedSQL, TableReference, ColumnReference
from .lineage_graph import LineageGraph, LineageNode, LineageEdge, NodeType, EdgeType


@dataclass
class ExtractionConfig:
    include_column_lineage: bool = True
    include_table_lineage: bool = True
    include_cte_lineage: bool = True
    include_subquery_lineage: bool = True
    include_window_functions: bool = True
    include_where_conditions: bool = True
    include_join_conditions: bool = True
    track_expression_lineage: bool = True
    resolve_wildcards: bool = True
    dialect: str = "duckdb"


class LineageExtractor:
    def __init__(self, config: Optional[ExtractionConfig] = None):
        self.config = config or ExtractionConfig()
        self.parser = SQLParser(dialect=self.config.dialect)

    def extract(self, sql: str) -> LineageGraph:
        parsed = self.parser.parse(sql)
        return self.extract_from_parsed(parsed)

    def extract_many(self, sql: str) -> LineageGraph:
        parsed_list = self.parser.parse_many(sql)
        graph = LineageGraph()
        for parsed in parsed_list:
            subgraph = self.extract_from_parsed(parsed)
            graph.merge(subgraph)
        return graph

    def extract_from_parsed(self, parsed: ParsedSQL) -> LineageGraph:
        graph = LineageGraph()

        if parsed.statement_type == "CREATE_TABLE_AS":
            self._extract_create_table_as(parsed, graph)
        elif parsed.statement_type == "INSERT":
            self._extract_insert(parsed, graph)
        elif parsed.statement_type == "SELECT":
            self._extract_select(parsed, graph)
        elif parsed.statement_type == "UPDATE":
            self._extract_update(parsed, graph)
        elif parsed.statement_type == "DELETE":
            self._extract_delete(parsed, graph)

        return graph

    def _extract_create_table_as(self, parsed: ParsedSQL, graph: LineageGraph) -> None:
        target_table = parsed.target_table
        if not target_table:
            return

        target_node = self._create_table_node(target_table)
        graph.add_node(target_node)

        self._extract_ctes(parsed, graph)
        self._extract_subqueries(parsed, graph)
        self._extract_source_tables(parsed, graph)

        if self.config.include_column_lineage:
            self._extract_column_lineage(parsed, graph, target_node)

        for src_table in parsed.tables:
            src_node_id = self._get_table_node_id(src_table)
            if graph.has_node(src_node_id):
                edge = LineageEdge(
                    source_id=src_node_id,
                    target_id=target_node.id,
                    edge_type=EdgeType.CREATE_AS,
                    expression=parsed.sql,
                    metadata={"statement_type": "CREATE_TABLE_AS"},
                )
                graph.add_edge(edge)

    def _extract_insert(self, parsed: ParsedSQL, graph: LineageGraph) -> None:
        target_table = parsed.target_table
        if not target_table:
            return

        target_node = self._create_table_node(target_table)
        if not graph.has_node(target_node.id):
            graph.add_node(target_node)

        self._extract_ctes(parsed, graph)
        self._extract_subqueries(parsed, graph)
        self._extract_source_tables(parsed, graph)

        if self.config.include_column_lineage:
            self._extract_column_lineage(parsed, graph, target_node)

        for src_table in parsed.tables:
            src_node_id = self._get_table_node_id(src_table)
            if graph.has_node(src_node_id):
                edge = LineageEdge(
                    source_id=src_node_id,
                    target_id=target_node.id,
                    edge_type=EdgeType.INSERT_INTO,
                    expression=parsed.sql,
                    metadata={"statement_type": "INSERT"},
                )
                graph.add_edge(edge)

    def _extract_select(self, parsed: ParsedSQL, graph: LineageGraph) -> None:
        self._extract_ctes(parsed, graph)
        self._extract_subqueries(parsed, graph)
        self._extract_source_tables(parsed, graph)
        self._extract_joins(parsed, graph)
        self._extract_where_conditions(parsed, graph)
        self._extract_window_functions(parsed, graph)

        if self.config.include_column_lineage:
            for col in parsed.output_columns:
                col_node = self._create_column_node(col, None)
                graph.add_node(col_node)

                for src_table in parsed.tables:
                    if col.table_alias and col.table_alias == src_table.alias:
                        src_col_id = self._get_column_node_id(src_table, col.name)
                        if graph.has_node(src_col_id):
                            edge = LineageEdge(
                                source_id=src_col_id,
                                target_id=col_node.id,
                                edge_type=EdgeType.SELECT_FROM,
                                expression=col.expression,
                                metadata={"alias": col.alias if hasattr(col, "alias") else None},
                            )
                            graph.add_edge(edge)

    def _extract_update(self, parsed: ParsedSQL, graph: LineageGraph) -> None:
        target_table = parsed.target_table
        if not target_table:
            return

        target_node = self._create_table_node(target_table)
        if not graph.has_node(target_node.id):
            graph.add_node(target_node)

        self._extract_source_tables(parsed, graph)

        for src_table in parsed.tables:
            if src_table.full_name != target_table.full_name:
                src_node_id = self._get_table_node_id(src_table)
                if graph.has_node(src_node_id):
                    edge = LineageEdge(
                        source_id=src_node_id,
                        target_id=target_node.id,
                        edge_type=EdgeType.TRANSFORM,
                        expression=parsed.sql,
                        metadata={"statement_type": "UPDATE"},
                    )
                    graph.add_edge(edge)

    def _extract_delete(self, parsed: ParsedSQL, graph: LineageGraph) -> None:
        target_table = parsed.target_table
        if not target_table:
            return

        target_node = self._create_table_node(target_table)
        if not graph.has_node(target_node.id):
            graph.add_node(target_node)

        self._extract_source_tables(parsed, graph)

    def _extract_ctes(self, parsed: ParsedSQL, graph: LineageGraph) -> None:
        if not self.config.include_cte_lineage:
            return

        for cte_alias, cte_parsed in parsed.ctes.items():
            cte_node = LineageNode(
                id=self._get_cte_node_id(cte_alias),
                name=cte_alias,
                node_type=NodeType.CTE,
                expression=cte_parsed.sql,
                metadata={"statement_type": cte_parsed.statement_type},
            )
            graph.add_node(cte_node)

            self._extract_source_tables(cte_parsed, graph)

            for src_table in cte_parsed.tables:
                src_node_id = self._get_table_node_id(src_table)
                if graph.has_node(src_node_id):
                    edge = LineageEdge(
                        source_id=src_node_id,
                        target_id=cte_node.id,
                        edge_type=EdgeType.SELECT_FROM,
                        metadata={"cte": cte_alias},
                    )
                    graph.add_edge(edge)

            if self.config.include_column_lineage:
                for i, col in enumerate(cte_parsed.output_columns):
                    col_node = self._create_column_node(col, cte_alias)
                    graph.add_node(col_node)

                    for src_table in cte_parsed.tables:
                        if col.table_alias and col.table_alias == src_table.alias:
                            src_col_id = self._get_column_node_id(src_table, col.name)
                            if graph.has_node(src_col_id):
                                edge = LineageEdge(
                                    source_id=src_col_id,
                                    target_id=col_node.id,
                                    edge_type=EdgeType.SELECT_FROM,
                                    expression=col.expression,
                                )
                                graph.add_edge(edge)

                    cte_col_edge = LineageEdge(
                        source_id=col_node.id,
                        target_id=cte_node.id,
                        edge_type=EdgeType.SELECT_FROM,
                        metadata={"column_position": i},
                    )
                    graph.add_edge(cte_col_edge)

    def _extract_subqueries(self, parsed: ParsedSQL, graph: LineageGraph) -> None:
        if not self.config.include_subquery_lineage:
            return

        for i, subquery in enumerate(parsed.subqueries):
            subquery_node = LineageNode(
                id=self._get_subquery_node_id(subquery, i),
                name=f"subquery_{i}",
                node_type=NodeType.SUBQUERY,
                expression=subquery.sql,
                metadata={"statement_type": subquery.statement_type, "index": i},
            )
            graph.add_node(subquery_node)

            self._extract_source_tables(subquery, graph)

            for src_table in subquery.tables:
                src_node_id = self._get_table_node_id(src_table)
                if graph.has_node(src_node_id):
                    edge = LineageEdge(
                        source_id=src_node_id,
                        target_id=subquery_node.id,
                        edge_type=EdgeType.SELECT_FROM,
                        metadata={"subquery_index": i},
                    )
                    graph.add_edge(edge)

    def _extract_source_tables(self, parsed: ParsedSQL, graph: LineageGraph) -> None:
        if not self.config.include_table_lineage:
            return

        alias_mapping = self.parser.resolve_table_alias_mapping(parsed)

        for table_ref in parsed.tables:
            table_node = self._create_table_node(table_ref)
            graph.add_node(table_node)

        for cte_alias in parsed.ctes.keys():
            cte_node_id = self._get_cte_node_id(cte_alias)
            if not graph.has_node(cte_node_id):
                cte_node = LineageNode(
                    id=cte_node_id,
                    name=cte_alias,
                    node_type=NodeType.CTE,
                )
                graph.add_node(cte_node)

    def _extract_joins(self, parsed: ParsedSQL, graph: LineageGraph) -> None:
        if not self.config.include_join_conditions:
            return

        for join in parsed.joins:
            join_table = join.get("table")
            condition = join.get("condition")

            if join_table:
                target_id = self._get_table_node_id(join_table)

                for src_table in parsed.tables:
                    if src_table.full_name != join_table.full_name:
                        source_id = self._get_table_node_id(src_table)
                        if graph.has_node(source_id) and graph.has_node(target_id):
                            edge = LineageEdge(
                                source_id=source_id,
                                target_id=target_id,
                                edge_type=EdgeType.JOIN_ON,
                                expression=condition,
                                metadata={"join_type": join.get("join_type")},
                            )
                            graph.add_edge(edge)

    def _extract_where_conditions(self, parsed: ParsedSQL, graph: LineageGraph) -> None:
        if not self.config.include_where_conditions:
            return

        for condition in parsed.where_conditions:
            for table_ref in parsed.tables:
                table_node_id = self._get_table_node_id(table_ref)
                if graph.has_node(table_node_id):
                    for col in parsed.columns:
                        if col.table_alias == table_ref.alias:
                            col_node_id = self._get_column_node_id(table_ref, col.name)
                            if graph.has_node(col_node_id):
                                edge = LineageEdge(
                                    source_id=col_node_id,
                                    target_id=table_node_id,
                                    edge_type=EdgeType.WHERE_FILTER,
                                    expression=condition,
                                )
                                graph.add_edge(edge)

    def _extract_window_functions(self, parsed: ParsedSQL, graph: LineageGraph) -> None:
        if not self.config.include_window_functions:
            return

        for window in parsed.window_functions:
            for partition_expr in window.get("partition_by", []):
                for table_ref in parsed.tables:
                    table_node_id = self._get_table_node_id(table_ref)
                    if graph.has_node(table_node_id):
                        edge = LineageEdge(
                            source_id=table_node_id,
                            target_id=table_node_id,
                            edge_type=EdgeType.WINDOW_PARTITION,
                            expression=partition_expr,
                            metadata={"window_function": window.get("function")},
                        )
                        graph.add_edge(edge)

            for order_expr in window.get("order_by", []):
                for table_ref in parsed.tables:
                    table_node_id = self._get_table_node_id(table_ref)
                    if graph.has_node(table_node_id):
                        edge = LineageEdge(
                            source_id=table_node_id,
                            target_id=table_node_id,
                            edge_type=EdgeType.WINDOW_ORDER,
                            expression=order_expr,
                            metadata={"window_function": window.get("function")},
                        )
                        graph.add_edge(edge)

    def _extract_column_lineage(self, parsed: ParsedSQL, graph: LineageGraph, target_table_node: LineageNode) -> None:
        alias_mapping = self.parser.resolve_table_alias_mapping(parsed)

        for col in parsed.output_columns:
            target_col_node = self._create_column_node(col, target_table_node.name)
            target_col_node.schema = target_table_node.schema
            target_col_node.database = target_table_node.database
            graph.add_node(target_col_node)

            table_edge = LineageEdge(
                source_id=target_col_node.id,
                target_id=target_table_node.id,
                edge_type=EdgeType.SELECT_FROM,
                metadata={"is_output_column": True},
            )
            graph.add_edge(table_edge)

            source_columns = self._resolve_source_columns(col, parsed, alias_mapping)
            for src_col_id in source_columns:
                if graph.has_node(src_col_id) or self._node_exists_in_subgraph(src_col_id, parsed, graph):
                    edge = LineageEdge(
                        source_id=src_col_id,
                        target_id=target_col_node.id,
                        edge_type=EdgeType.COMPUTED if col.expression and not col.expression == col.name else EdgeType.SELECT_FROM,
                        expression=col.expression,
                        metadata={
                            "alias": col.alias if hasattr(col, "alias") else None,
                            "is_computed": col.expression and col.expression != col.name,
                        },
                    )
                    graph.add_edge(edge)

    def _resolve_source_columns(self, col: ColumnReference, parsed: ParsedSQL, alias_mapping: Dict[str, str]) -> List[str]:
        source_cols = []

        if col.table_alias:
            if col.table_alias in alias_mapping:
                resolved_table = alias_mapping[col.table_alias]
                for table_ref in parsed.tables:
                    if table_ref.alias == col.table_alias or table_ref.full_name == resolved_table:
                        source_cols.append(self._get_column_node_id(table_ref, col.name))
                        break

                for cte_alias, cte_parsed in parsed.ctes.items():
                    if cte_alias == col.table_alias or cte_alias == resolved_table:
                        for i, cte_col in enumerate(cte_parsed.output_columns):
                            if cte_col.name == col.name or (hasattr(cte_col, "alias") and cte_col.alias == col.name):
                                source_cols.append(self._get_column_node_id(
                                    TableReference(name=cte_alias, alias=cte_alias),
                                    cte_col.name
                                ))
                                break

            for i, subquery in enumerate(parsed.subqueries):
                for j, sub_col in enumerate(subquery.output_columns):
                    if col.table_alias and col.table_alias == f"subquery_{i}":
                        if sub_col.name == col.name or (hasattr(sub_col, "alias") and sub_col.alias == col.name):
                            source_cols.append(self._get_column_node_id(
                                TableReference(name=f"subquery_{i}", alias=f"subquery_{i}"),
                                sub_col.name
                            ))
                            break
        else:
            for table_ref in parsed.tables:
                source_cols.append(self._get_column_node_id(table_ref, col.name))

            for cte_alias, cte_parsed in parsed.ctes.items():
                for cte_col in cte_parsed.output_columns:
                    if cte_col.name == col.name or (hasattr(cte_col, "alias") and cte_col.alias == col.name):
                        source_cols.append(self._get_column_node_id(
                            TableReference(name=cte_alias, alias=cte_alias),
                            cte_col.name
                        ))

        return source_cols

    def _node_exists_in_subgraph(self, node_id: str, parsed: ParsedSQL, graph: LineageGraph) -> bool:
        if graph.has_node(node_id):
            return True

        for cte_parsed in parsed.ctes.values():
            for table_ref in cte_parsed.tables:
                if self._get_table_node_id(table_ref) == node_id:
                    return True

        for subquery in parsed.subqueries:
            for table_ref in subquery.tables:
                if self._get_table_node_id(table_ref) == node_id:
                    return True

        return False

    def _create_table_node(self, table_ref: TableReference) -> LineageNode:
        return LineageNode(
            id=self._get_table_node_id(table_ref),
            name=table_ref.name,
            node_type=NodeType.TABLE,
            schema=table_ref.schema,
            database=table_ref.database,
            alias=table_ref.alias,
            metadata={"full_name": table_ref.full_name},
        )

    def _create_column_node(self, col: ColumnReference, table_name: Optional[str]) -> LineageNode:
        table_alias = col.table_alias or table_name
        table_ref = TableReference(name=table_alias or "unknown", alias=table_alias)
        return LineageNode(
            id=self._get_column_node_id(table_ref, col.name),
            name=col.name,
            node_type=NodeType.COLUMN,
            expression=col.expression,
            alias=col.alias if hasattr(col, "alias") else None,
            metadata={
                "table_alias": col.table_alias,
                "is_wildcard": col.is_wildcard,
                "full_name": f"{table_alias}.{col.name}" if table_alias else col.name,
            },
        )

    def _get_table_node_id(self, table_ref: TableReference) -> str:
        return f"table:{table_ref.full_name}"

    def _get_column_node_id(self, table_ref: TableReference, col_name: str) -> str:
        return f"column:{table_ref.full_name}.{col_name}"

    def _get_cte_node_id(self, cte_alias: str) -> str:
        return f"cte:{cte_alias}"

    def _get_subquery_node_id(self, subquery: ParsedSQL, index: int) -> str:
        return f"subquery:{index}:{hashlib.md5(subquery.sql.encode()).hexdigest()[:8]}"

    def extract_table_lineage(self, sql: str) -> List[Dict[str, Any]]:
        parsed = self.parser.parse(sql)
        lineage = []

        if parsed.target_table:
            target = parsed.target_table.full_name
            for src in parsed.tables:
                lineage.append({
                    "source": src.full_name,
                    "target": target,
                    "type": parsed.statement_type,
                })

        for cte_alias, cte_parsed in parsed.ctes.items():
            for src in cte_parsed.tables:
                lineage.append({
                    "source": src.full_name,
                    "target": cte_alias,
                    "type": "CTE",
                })

        return lineage

    def extract_column_lineage(self, sql: str) -> List[Dict[str, Any]]:
        parsed = self.parser.parse(sql)
        graph = self.extract_from_parsed(parsed)
        lineage = []

        col_nodes = graph.get_nodes_by_type(NodeType.COLUMN)
        for col_node in col_nodes:
            in_edges = graph.get_in_edges(col_node.id)
            for edge in in_edges:
                src_node = graph.get_node(edge.source_id)
                if src_node and src_node.node_type == NodeType.COLUMN:
                    lineage.append({
                        "source_column": src_node.full_name,
                        "target_column": col_node.full_name,
                        "edge_type": edge.edge_type.value,
                        "expression": edge.expression,
                    })

        return lineage

    def get_all_source_tables(self, sql: str) -> Set[str]:
        parsed = self.parser.parse(sql)
        return self.parser.get_all_tables(parsed)

    def get_all_source_columns(self, sql: str) -> Set[str]:
        parsed = self.parser.parse(sql)
        return self.parser.get_all_columns(parsed)
