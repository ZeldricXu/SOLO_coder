"""
软件目录与发现实现
核心功能：
1. 服务/库元数据注册
2. 服务检索
3. 依赖关系分析与可视化
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional, Set
from collections import defaultdict

from src.core import ServiceMetadata, LoggerProtocol


class ServiceRegistry:
    """服务注册表 - 元数据存储"""

    def __init__(self, logger: Optional[LoggerProtocol] = None) -> None:
        self._services: Dict[str, ServiceMetadata] = {}
        self._name_index: Dict[str, str] = {}
        self._tag_index: Dict[str, Set[str]] = defaultdict(set)
        self._language_index: Dict[str, Set[str]] = defaultdict(set)
        self._type_index: Dict[str, Set[str]] = defaultdict(set)
        self._logger = logger

    def register(self, service: ServiceMetadata) -> None:
        """注册服务元数据"""
        self._services[service.id] = service
        self._name_index[service.name] = service.id

        for tag in service.tags:
            self._tag_index[tag].add(service.id)
        if service.language:
            self._language_index[service.language].add(service.id)
        if service.type:
            self._type_index[service.type].add(service.id)

        if self._logger:
            self._logger.info(
                "Service registered",
                service_id=service.id,
                name=service.name,
                type=service.type,
            )

    def unregister(self, service_id: str) -> None:
        """注销服务"""
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

        if self._logger:
            self._logger.info(
                "Service unregistered",
                service_id=service_id,
                name=service.name,
            )

    def get_by_id(self, service_id: str) -> Optional[ServiceMetadata]:
        """根据ID获取服务"""
        return self._services.get(service_id)

    def get_by_name(self, name: str) -> Optional[ServiceMetadata]:
        """根据名称获取服务"""
        service_id = self._name_index.get(name)
        return self._services.get(service_id) if service_id else None

    def update(self, service: ServiceMetadata) -> None:
        """更新服务元数据"""
        if service.id in self._services:
            self.unregister(service.id)
        self.register(service)

    def list_all(self) -> List[ServiceMetadata]:
        """列出所有服务"""
        return list(self._services.values())


class ServiceCatalog:
    """服务目录 - 提供检索功能"""

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
        """
        多条件搜索服务

        Args:
            query: 关键词搜索（名称、描述）
            service_type: 服务类型过滤
            language: 编程语言过滤
            tags: 标签过滤
            owner: 负责人过滤

        Returns:
            匹配的服务列表
        """
        services = self._registry.list_all()
        results = []

        for service in services:
            match = True

            if query:
                query_lower = query.lower()
                if (
                    query_lower not in service.name.lower()
                    and query_lower not in service.description.lower()
                ):
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

    def get_statistics(self) -> Dict[str, Any]:
        """获取目录统计信息"""
        services = self._registry.list_all()

        stats: Dict[str, Any] = {
            "total_services": len(services),
            "by_type": defaultdict(int),
            "by_language": defaultdict(int),
            "by_owner": defaultdict(int),
            "total_tags": 0,
        }

        all_tags: Set[str] = set()
        for service in services:
            stats["by_type"][service.type] += 1
            stats["by_language"][service.language] += 1
            stats["by_owner"][service.owner] += 1
            all_tags.update(service.tags)

        stats["total_tags"] = len(all_tags)
        stats["all_tags"] = sorted(list(all_tags))
        return stats


class DependencyAnalyzer:
    """依赖分析器 - 分析服务间依赖关系"""

    def __init__(self, registry: ServiceRegistry) -> None:
        self._registry = registry

    def _build_dependency_graph(self) -> Dict[str, List[str]]:
        """构建依赖图"""
        graph: Dict[str, List[str]] = {}
        services = self._registry.list_all()

        for service in services:
            graph[service.id] = service.dependencies or []

        return graph

    def _build_reverse_dependency_graph(self) -> Dict[str, List[str]]:
        """构建反向依赖图（被哪些服务依赖）"""
        reverse_graph: Dict[str, List[str]] = defaultdict(list)
        services = self._registry.list_all()

        for service in services:
            for dep_id in service.dependencies or []:
                reverse_graph[dep_id].append(service.id)

        return reverse_graph

    def get_dependencies(self, service_id: str) -> List[ServiceMetadata]:
        """获取服务的直接依赖"""
        service = self._registry.get_by_id(service_id)
        if not service:
            return []

        dependencies = []
        for dep_id in service.dependencies or []:
            dep = self._registry.get_by_id(dep_id)
            if dep:
                dependencies.append(dep)

        return dependencies

    def get_dependents(self, service_id: str) -> List[ServiceMetadata]:
        """获取依赖此服务的所有服务"""
        reverse_graph = self._build_reverse_dependency_graph()
        dependent_ids = reverse_graph.get(service_id, [])

        dependents = []
        for dep_id in dependent_ids:
            dep = self._registry.get_by_id(dep_id)
            if dep:
                dependents.append(dep)

        return dependents

    def get_all_dependencies(self, service_id: str) -> List[ServiceMetadata]:
        """递归获取所有依赖（包括传递依赖）"""
        graph = self._build_dependency_graph()
        visited: Set[str] = set()
        all_deps: List[ServiceMetadata] = []

        def dfs(current_id: str) -> None:
            if current_id in visited:
                return
            visited.add(current_id)

            for dep_id in graph.get(current_id, []):
                dep = self._registry.get_by_id(dep_id)
                if dep and dep.id not in visited:
                    all_deps.append(dep)
                    dfs(dep.id)

        dfs(service_id)
        return all_deps

    def get_dependency_chain(
        self, from_service_id: str, to_service_id: str
    ) -> List[ServiceMetadata]:
        """获取两个服务之间的依赖链"""
        graph = self._build_dependency_graph()
        visited: Set[str] = set()
        path: List[str] = []

        def dfs(current_id: str) -> Optional[List[str]]:
            if current_id == to_service_id:
                return [current_id]

            if current_id in visited:
                return None
            visited.add(current_id)

            for dep_id in graph.get(current_id, []):
                result = dfs(dep_id)
                if result:
                    return [current_id] + result

            return None

        chain_ids = dfs(from_service_id)
        if not chain_ids:
            return []

        chain = []
        for sid in chain_ids:
            service = self._registry.get_by_id(sid)
            if service:
                chain.append(service)

        return chain

    def detect_circular_dependencies(self) -> List[List[str]]:
        """检测循环依赖"""
        graph = self._build_dependency_graph()
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
                    cycle_start = path.index(neighbor)
                    cycles.append(path[cycle_start:] + [neighbor])
                elif neighbor not in visited:
                    dfs(neighbor)

            path.pop()
            recursion_stack.remove(node)

        for node in graph:
            if node not in visited:
                dfs(node)

        return cycles

    def generate_mermaid_diagram(self) -> str:
        """生成Mermaid依赖图"""
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
