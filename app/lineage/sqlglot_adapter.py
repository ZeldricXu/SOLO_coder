"""
SQLGlot adapter for SQL parsing.
"""

from typing import Dict, List, Optional, Set, Tuple

import sqlglot
from sqlglot import exp, parse_one

from app.lineage.base import SQLParser
from app.lineage.models import (
    ColumnLineage, ColumnReference, TableLineage
)


class SqlglotAdapter(SQLParser):
    def __init__(self, dialect: str = "mysql"):
        self._dialect = dialect
    
    @property
    def dialect(self) -> str:
        return self._dialect
    
    def parse(self, sql: str) -> Tuple[TableLineage, List[ColumnLineage]]:
        parsed = parse_one(sql, read=self._dialect)
        table_lineage = TableLineage()
        column_lineages: List[ColumnLineage] = []
        
        cte_map = self._extract_ctes(parsed)
        table_lineage.cte_tables = cte_map
        
        source_tables = self._extract_source_tables(parsed)
        table_lineage.source_tables = list(source_tables)
        
        target_tables = self._extract_target_tables(parsed)
        table_lineage.target_tables = list(target_tables)
        
        column_lineages = self._extract_column_lineage(parsed, cte_map)
        
        return table_lineage, column_lineages
    
    def _extract_ctes(self, parsed: exp.Expression) -> Dict[str, str]:
        cte_map: Dict[str, str] = {}
        
        with_clause = parsed.find(exp.With)
        if with_clause:
            for cte in with_clause.expressions:
                cte_alias = cte.alias_or_name
                cte_map[cte_alias] = cte.sql()
        
        return cte_map
    
    def _extract_source_tables(self, parsed: exp.Expression) -> Set[str]:
        sources: Set[str] = set()
        
        for table in parsed.find_all(exp.Table):
            name_parts = []
            if table.db:
                name_parts.append(table.db)
            name_parts.append(table.name)
            table_name = ".".join(name_parts)
            sources.add(table_name)
        
        return sources
    
    def _extract_target_tables(self, parsed: exp.Expression) -> Set[str]:
        targets: Set[str] = set()
        
        insert = parsed.find(exp.Insert)
        if insert:
            table = insert.this
            if table:
                name_parts = []
                if table.db:
                    name_parts.append(table.db)
                name_parts.append(table.name)
                targets.add(".".join(name_parts))
        
        update = parsed.find(exp.Update)
        if update:
            table = update.this
            if table:
                name_parts = []
                if table.db:
                    name_parts.append(table.db)
                name_parts.append(table.name)
                targets.add(".".join(name_parts))
        
        create = parsed.find(exp.Create)
        if create and isinstance(create.this, exp.Table):
            table = create.this
            name_parts = []
            if table.db:
                name_parts.append(table.db)
            name_parts.append(table.name)
            targets.add(".".join(name_parts))
        
        return targets
    
    def _extract_column_lineage(
        self,
        parsed: exp.Expression,
        cte_map: Dict[str, str]
    ) -> List[ColumnLineage]:
        lineages: List[ColumnLineage] = []
        
        select = parsed.find(exp.Select)
        if select:
            aliases = self._extract_table_aliases(parsed)
            
            for expr in select.expressions:
                target_col = self._get_target_column(expr)
                if target_col:
                    sources = self._extract_source_columns(expr, aliases)
                    transformation = self._get_transformation(expr)
                    
                    lineages.append(ColumnLineage(
                        target=target_col,
                        sources=sources,
                        transformation=transformation
                    ))
        
        return lineages
    
    def _extract_table_aliases(self, parsed: exp.Expression) -> Dict[str, str]:
        aliases: Dict[str, str] = {}
        
        for table in parsed.find_all(exp.Table):
            alias = table.alias
            if alias:
                name_parts = []
                if table.db:
                    name_parts.append(table.db)
                name_parts.append(table.name)
                aliases[alias] = ".".join(name_parts)
        
        return aliases
    
    def _get_target_column(self, expr: exp.Expression) -> Optional[ColumnReference]:
        if isinstance(expr, exp.Alias):
            return ColumnReference(
                table=None,
                column=expr.alias,
                alias=expr.alias
            )
        elif isinstance(expr, exp.Column):
            return ColumnReference(
                table=expr.table,
                column=expr.name
            )
        return None
    
    def _extract_source_columns(
        self,
        expr: exp.Expression,
        aliases: Dict[str, str]
    ) -> List[ColumnReference]:
        sources: List[ColumnReference] = []
        
        for col in expr.find_all(exp.Column):
            table = col.table
            if table and table in aliases:
                table = aliases[table]
            sources.append(ColumnReference(
                table=table,
                column=col.name
            ))
        
        return sources
    
    def _get_transformation(self, expr: exp.Expression) -> Optional[str]:
        if isinstance(expr, exp.Alias):
            inner = expr.this
            if not isinstance(inner, exp.Column):
                return inner.sql()
        return None


SQLLineageParser = SqlglotAdapter
