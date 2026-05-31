"""
服务发现模块
"""

from __future__ import annotations

from collections import defaultdict
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional, Set

from src.domain.contracts.tracing import LoggerProtocol
from src.domain.models.common import ServiceMetadata


class ServiceRegistry:
    def __init__(self, logger: Optional[LoggerProtocol] = None) -> None:
        self._services: Dict[str, ServiceMetadata] = {}
        self._name_index: Dict[str, str] = {}
        self._tag_index: Dict[str, Set[str]] = defaultdict(set)
        self._language_index: Dict[str, Set[str]] = defaultdict(set)
        self._type_index: Dict[str, Set[str]] = defaultdict(set)
        self._logger = logger

    def register(self, service: ServiceMetadata) -> None:
        self._services[service.id] = service
        self._name_index[service.name] = service.id
        for tag in service.tags:
            self._tag_index[tag].add(service.id)
        if service.language:
            self._language_index[service.language].add(service.id)
        if service.type:
            self._type_index[service.type].add(service.id)
        if self._logger:
            self._logger.info("Service registered", service_id=service.id, name=service.name)

    def unregister(self, service_id: str) -> None:
        if service_id not in self._services:
            return
        service = self._services[service_id]
        del self._services[service_id]
        del self._name_index[service.name]
        for tag in service.tags:
            self._tag_index[tag].discard(service_id)
        if service.language:
            self._language_index[service.language].discard(service_id)
        if service.type:
            self._type_index[service.type].discard(service_id)

    def get_by_id(self, service_id: str) -> Optional[ServiceMetadata]:
        return self._services.get(service_id)

    def get_by_name(self, name: str) -> Optional[ServiceMetadata]:
        sid = self._name_index.get(name)
        return self._services.get(sid) if sid else None

    def list_all(self) -> List[ServiceMetadata]:
        return list(self._services.values())


class ServiceCatalog:
    def __init__(self, registry: ServiceRegistry) -> None:
        self._registry = registry

    def search(
        self,
        query: Optional[str] = None,
        service_type: Optional[str] = None,
        language: Optional[str] = None,
        tags: Optional[List[str]] = None,
        owner: Optional[str] = None,
    ) -> List[ServiceMetadata]:
        services = self._registry.list_all()
        results = []
        for service in services:
            match = True
            if query:
                q = query.lower()
                if q not in service.name.lower() and q not in service.description.lower():
                    match = False
            if service_type and service.type != service_type:
                match = False
            if language and service.language != language:
                match = False
            if tags and not all(tag in service.tags for tag in tags):
                match = False
            if owner and service.owner != owner:
                match = False
            if match:
                results.append(service)
        return results


class DependencyAnalyzer:
    def __init__(self, registry: ServiceRegistry) -> None:
        self._registry = registry

    def detect_circular_dependencies(self) -> List[List[str]]:
        graph = {s.id: s.dependencies or [] for s in self._registry.list_all()}
        visited: Set[str] = set()
        recursion_stack: Set[str] = set()
        cycles: List[List[str]] = []
        path: List[str] = []

        def dfs(node: str) -> None:
            visited.add(node)
            recursion_stack.add(node)
            path.append(node)
            for neighbor in graph.get(node, []):
                if neighbor in recursion_stack:
                    cycles.append(path[path.index(neighbor):] + [neighbor])
                elif neighbor not in visited:
                    dfs(neighbor)
            path.pop()
            recursion_stack.remove(node)

        for node in graph:
            if node not in visited:
                dfs(node)
        return cycles

    def generate_mermaid_diagram(self) -> str:
        services = self._registry.list_all()
        name_map = {s.id: s.name for s in services}
        lines = ["graph TD"]
        for service in services:
            safe_name = service.name.replace("-", "_").replace(" ", "_")
            lines.append(f'    {safe_name}["{service.name}<br/><small>{service.type}</small>"]')
            for dep_id in service.dependencies or []:
                dep_name = name_map.get(dep_id, dep_id)
                safe_dep = dep_name.replace("-", "_").replace(" ", "_")
                lines.append(f"    {safe_name} --> {safe_dep}")
        return "\n".join(lines)
