"""
Lineage data models.
"""

from dataclasses import dataclass, field
from enum import Enum
from typing import Any, Dict, List, Optional, Set, Tuple


class LineageType(str, Enum):
    TABLE = "table"
    COLUMN = "column"
    CTE = "cte"


class EdgeType(str, Enum):
    DEPENDS_ON = "depends_on"
    TRANSFORMS = "transforms"
    JOINS_WITH = "joins_with"
    FILTERS = "filters"
    AGGREGATES = "aggregates"


@dataclass
class ColumnReference:
    table: Optional[str]
    column: str
    alias: Optional[str] = None


@dataclass
class TableLineage:
    source_tables: List[str] = field(default_factory=list)
    target_tables: List[str] = field(default_factory=list)
    cte_tables: Dict[str, str] = field(default_factory=dict)


@dataclass
class ColumnLineage:
    target: ColumnReference
    sources: List[ColumnReference] = field(default_factory=list)
    transformation: Optional[str] = None
