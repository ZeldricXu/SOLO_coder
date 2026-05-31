"""
Lineage registry for managing multiple lineage graphs.
"""

from typing import List, Optional

from app.lineage.builder import LineageDAGBuilder
from app.lineage.models import EdgeType
from app.lineage.networkx_provider import NetworkXProvider


class LineageRegistry:
    def __init__(self):
        self._builders: dict = {}
    
    def register(self, name: str, builder: LineageDAGBuilder):
        self._builders[name] = builder
    
    def get(self, name: str) -> Optional[LineageDAGBuilder]:
        return self._builders.get(name)
    
    def list_all(self) -> List[str]:
        return list(self._builders.keys())
    
    def merge_all(self) -> LineageDAGBuilder:
        merged_provider = NetworkXProvider()
        for builder in self._builders.values():
            provider = builder._access_provider_for_merge()
            merged_provider.merge_from(provider)
        
        from app.lineage.builder import LineageDAGBuilder
        return LineageDAGBuilder(dag_provider=merged_provider)
