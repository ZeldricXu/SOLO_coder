import json
import re
import sqlite3
import time
from collections import defaultdict
from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path
from typing import Any, Dict, Generator, List, Optional, Set, Tuple

from src.config import get_settings
from src.logging_ import get_logger
from src.models import ServiceMetadata as ServiceMetadataModel
from src.utils.errors import ResourceNotFoundError, ValidationError
from src.utils.helpers import generate_id

logger = get_logger(__name__)


@dataclass
class ServiceNode:
    service_id: str
    name: str
    version: str
    type: str
    language: str
    description: Optional[str] = None
    tags: List[str] = field(default_factory=list)
    metadata: Dict[str, Any] = field(default_factory=dict)
    endpoints: List[Dict[str, Any]] = field(default_factory=list)
    registered_at: datetime = field(default_factory=datetime.utcnow)

    def to_dict(self) -> Dict[str, Any]:
        return {
            "service_id": self.service_id,
            "name": self.name,
            "version": self.version,
            "type": self.type,
            "language": self.language,
            "description": self.description,
            "tags": self.tags,
            "metadata": self.metadata,
            "endpoints": self.endpoints,
            "registered_at": self.registered_at.isoformat(),
        }


@dataclass
class DependencyEdge:
    source_service_id: str
    target_service_id: str
    dependency_type: str = "runtime"
    version_constraint: Optional[str] = None
    description: Optional[str] = None
    created_at: datetime = field(default_factory=datetime.utcnow)

    def to_dict(self) -> Dict[str, Any]:
        return {
            "source_service_id": self.source_service_id,
            "target_service_id": self.target_service_id,
            "dependency_type": self.dependency_type,
            "version_constraint": self.version_constraint,
            "description": self.description,
            "created_at": self.created_at.isoformat(),
        }


@dataclass
class RegistrySearchResult:
    services: List[ServiceNode]
    total: int
    page: int
    page_size: int
    facets: Dict[str, Dict[str, int]] = field(default_factory=dict)


class DependencyGraph:
    def __init__(self):
        self._nodes: Dict[str, ServiceNode] = {}
        self._edges: List[DependencyEdge] = []
        self._outgoing: Dict[str, List[DependencyEdge]] = defaultdict(list)
        self._incoming: Dict[str, List[DependencyEdge]] = defaultdict(list)

    def add_node(self, node: ServiceNode) -> None:
        self._nodes[node.service_id] = node

    def remove_node(self, service_id: str) -> bool:
        if service_id in self._nodes:
            del self._nodes[service_id]
            self._edges = [
                e for e in self._edges
                if e.source_service_id != service_id and e.target_service_id != service_id
            ]
            self._outgoing.pop(service_id, None)
            self._incoming.pop(service_id, None)
            return True
        return False

    def add_edge(self, edge: DependencyEdge) -> None:
        if edge.source_service_id not in self._nodes:
            raise ValidationError(f"Source service not found: {edge.source_service_id}")
        if edge.target_service_id not in self._nodes:
            raise ValidationError(f"Target service not found: {edge.target_service_id}")

        self._edges.append(edge)
        self._outgoing[edge.source_service_id].append(edge)
        self._incoming[edge.target_service_id].append(edge)

    def get_dependencies(self, service_id: str) -> List[ServiceNode]:
        if service_id not in self._nodes:
            raise ResourceNotFoundError(f"Service not found: {service_id}")

        return [
            self._nodes[edge.target_service_id]
            for edge in self._outgoing.get(service_id, [])
        ]

    def get_dependents(self, service_id: str) -> List[ServiceNode]:
        if service_id not in self._nodes:
            raise ResourceNotFoundError(f"Service not found: {service_id}")

        return [
            self._nodes[edge.source_service_id]
            for edge in self._incoming.get(service_id, [])
        ]

    def get_all_dependencies(self, service_id: str) -> List[ServiceNode]:
        visited: Set[str] = set()
        result: List[ServiceNode] = []

        def visit(sid: str) -> None:
            if sid in visited:
                return
            visited.add(sid)
            for edge in self._outgoing.get(sid, []):
                if edge.target_service_id not in visited:
                    node = self._nodes.get(edge.target_service_id)
                    if node:
                        result.append(node)
                        visit(edge.target_service_id)

        visit(service_id)
        return result

    def get_all_dependents(self, service_id: str) -> List[ServiceNode]:
        visited: Set[str] = set()
        result: List[ServiceNode] = []

        def visit(sid: str) -> None:
            if sid in visited:
                return
            visited.add(sid)
            for edge in self._incoming.get(sid, []):
                if edge.source_service_id not in visited:
                    node = self._nodes.get(edge.source_service_id)
                    if node:
                        result.append(node)
                        visit(edge.source_service_id)

        visit(service_id)
        return result

    def detect_cycles(self) -> List[List[str]]:
        cycles: List[List[str]] = []
        visited: Set[str] = set()
        rec_stack: Set[str] = set()
        path: List[str] = []

        def dfs(sid: str) -> None:
            visited.add(sid)
            rec_stack.add(sid)
            path.append(sid)

            for edge in self._outgoing.get(sid, []):
                target = edge.target_service_id
                if target in rec_stack:
                    cycle_start = path.index(target)
                    cycles.append(path[cycle_start:] + [target])
                elif target not in visited:
                    dfs(target)

            path.pop()
            rec_stack.remove(sid)

        for node_id in self._nodes:
            if node_id not in visited:
                dfs(node_id)

        return cycles

    def topological_sort(self) -> List[str]:
        in_degree: Dict[str, int] = {
            sid: len(self._incoming.get(sid, [])) for sid in self._nodes
        }
        queue = [sid for sid, deg in in_degree.items() if deg == 0]
        result: List[str] = []

        while queue:
            sid = queue.pop(0)
            result.append(sid)
            for edge in self._outgoing.get(sid, []):
                target = edge.target_service_id
                in_degree[target] -= 1
                if in_degree[target] == 0:
                    queue.append(target)

        if len(result) != len(self._nodes):
            raise ValidationError("Dependency graph contains cycles")

        return result

    def get_edges(self) -> List[DependencyEdge]:
        return self._edges.copy()

    def get_nodes(self) -> List[ServiceNode]:
        return list(self._nodes.values())

    def to_dict(self) -> Dict[str, Any]:
        return {
            "nodes": [n.to_dict() for n in self._nodes.values()],
            "edges": [e.to_dict() for e in self._edges],
        }

    def generate_mermaid_diagram(self) -> str:
        lines = ["graph TD"]

        for node in self._nodes.values():
            label = f"{node.name}\\n{node.version}"
            shape = "[]" if node.type == "service" else "()"
            lines.append(f'    {node.service_id}{shape[0]}"{label}"{shape[1]}')

        for edge in self._edges:
            source = self._nodes[edge.source_service_id]
            target = self._nodes[edge.target_service_id]
            style = "-->" if edge.dependency_type == "runtime" else "-.->"
            lines.append(f"    {source.service_id} {style} {target.service_id}")

        return "\n".join(lines)

    def generate_dot_diagram(self) -> str:
        lines = ["digraph dependencies {", '    rankdir=LR;']

        for node in self._nodes.values():
            shape = "box" if node.type == "service" else "ellipse"
            color = "#4CAF50" if node.type == "service" else "#2196F3"
            lines.append(
                f'    {node.service_id} [label="{node.name}\\n{node.version}", '
                f'shape={shape}, style=filled, fillcolor="{color}"];'
            )

        for edge in self._edges:
            style = "solid" if edge.dependency_type == "runtime" else "dashed"
            lines.append(
                f'    {edge.source_service_id} -> {edge.target_service_id} '
                f'[style={style}, label="{edge.dependency_type}"];'
            )

        lines.append("}")
        return "\n".join(lines)


class ServiceRegistry:
    _instance: Optional["ServiceRegistry"] = None

    def __new__(cls) -> "ServiceRegistry":
        if cls._instance is None:
            cls._instance = super().__new__(cls)
        return cls._instance

    def __init__(self):
        if not hasattr(self, "initialized"):
            self.settings = get_settings()
            self._services: Dict[str, ServiceNode] = {}
            self._dependency_graph = DependencyGraph()
            self._db_path = "./service_registry.db"
            self._conn: Optional[sqlite3.Connection] = None
            self._initialize_db()
            self._load_from_db()
            self.initialized = True

    def _initialize_db(self) -> None:
        Path(self._db_path).parent.mkdir(parents=True, exist_ok=True)
        self._conn = sqlite3.connect(self._db_path)

        self._conn.execute(
            """
            CREATE TABLE IF NOT EXISTS services (
                service_id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                version TEXT NOT NULL,
                type TEXT NOT NULL,
                language TEXT NOT NULL,
                description TEXT,
                tags TEXT,
                endpoints TEXT,
                metadata TEXT,
                registered_at TEXT NOT NULL
            )
            """
        )

        self._conn.execute(
            """
            CREATE TABLE IF NOT EXISTS dependencies (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                source_service_id TEXT NOT NULL,
                target_service_id TEXT NOT NULL,
                dependency_type TEXT NOT NULL,
                version_constraint TEXT,
                description TEXT,
                created_at TEXT NOT NULL,
                FOREIGN KEY (source_service_id) REFERENCES services (service_id),
                FOREIGN KEY (target_service_id) REFERENCES services (service_id)
            )
            """
        )

        self._conn.execute("CREATE INDEX IF NOT EXISTS idx_services_name ON services(name)")
        self._conn.execute("CREATE INDEX IF NOT EXISTS idx_services_type ON services(type)")
        self._conn.execute("CREATE INDEX IF NOT EXISTS idx_services_tags ON services(tags)")
        self._conn.commit()

    def _load_from_db(self) -> None:
        if not self._conn:
            return

        try:
            cursor = self._conn.execute("SELECT * FROM services")
            for row in cursor.fetchall():
                node = ServiceNode(
                    service_id=row[0],
                    name=row[1],
                    version=row[2],
                    type=row[3],
                    language=row[4],
                    description=row[5],
                    tags=json.loads(row[6]) if row[6] else [],
                    endpoints=json.loads(row[7]) if row[7] else [],
                    metadata=json.loads(row[8]) if row[8] else {},
                    registered_at=datetime.fromisoformat(row[9]),
                )
                self._services[node.service_id] = node
                self._dependency_graph.add_node(node)

            cursor = self._conn.execute("SELECT * FROM dependencies")
            for row in cursor.fetchall():
                edge = DependencyEdge(
                    source_service_id=row[1],
                    target_service_id=row[2],
                    dependency_type=row[3],
                    version_constraint=row[4],
                    description=row[5],
                    created_at=datetime.fromisoformat(row[6]),
                )
                try:
                    self._dependency_graph.add_edge(edge)
                except ValidationError:
                    pass

            logger.info("Loaded %d services and %d dependencies from registry",
                        len(self._services), len(self._dependency_graph._edges))

        except Exception as e:
            logger.error("Failed to load registry from DB: %s", e)

    def register(
        self,
        metadata: ServiceMetadataModel,
    ) -> ServiceNode:
        existing = next(
            (
                s for s in self._services.values()
                if s.name == metadata.name and s.version == metadata.version
            ),
            None,
        )

        if existing:
            logger.info("Service already registered: %s v%s", metadata.name, metadata.version)
            return existing

        node = ServiceNode(
            service_id=metadata.service_id,
            name=metadata.name,
            version=metadata.version,
            type=metadata.type,
            language=metadata.language,
            description=metadata.description,
            tags=metadata.tags,
            endpoints=metadata.endpoints,
            metadata=metadata.metadata,
            registered_at=metadata.registered_at,
        )

        self._services[node.service_id] = node
        self._dependency_graph.add_node(node)

        for dep in metadata.dependencies:
            target = next(
                (s for s in self._services.values() if s.name == dep),
                None,
            )
            if target:
                edge = DependencyEdge(
                    source_service_id=node.service_id,
                    target_service_id=target.service_id,
                    dependency_type="runtime",
                )
                try:
                    self._dependency_graph.add_edge(edge)
                except ValidationError:
                    pass

        if self._conn:
            self._conn.execute(
                """
                INSERT OR REPLACE INTO services VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    node.service_id,
                    node.name,
                    node.version,
                    node.type,
                    node.language,
                    node.description,
                    json.dumps(node.tags),
                    json.dumps(node.endpoints),
                    json.dumps(node.metadata),
                    node.registered_at.isoformat(),
                ),
            )
            self._conn.commit()

        logger.info("Registered service: %s v%s (%s)", node.name, node.version, node.service_id)
        return node

    def unregister(self, service_id: str) -> bool:
        if service_id not in self._services:
            return False

        service = self._services[service_id]
        self._dependency_graph.remove_node(service_id)
        del self._services[service_id]

        if self._conn:
            self._conn.execute("DELETE FROM services WHERE service_id = ?", (service_id,))
            self._conn.execute(
                "DELETE FROM dependencies WHERE source_service_id = ? OR target_service_id = ?",
                (service_id, service_id),
            )
            self._conn.commit()

        logger.info("Unregistered service: %s", service.name)
        return True

    def get(self, service_id: str) -> ServiceNode:
        if service_id not in self._services:
            raise ResourceNotFoundError(f"Service not found: {service_id}")
        return self._services[service_id]

    def get_by_name(self, name: str, version: Optional[str] = None) -> Optional[ServiceNode]:
        for service in self._services.values():
            if service.name == name:
                if version is None or service.version == version:
                    return service
        return None

    def search(
        self,
        query: Optional[str] = None,
        type: Optional[str] = None,
        language: Optional[str] = None,
        tags: Optional[List[str]] = None,
        page: int = 1,
        page_size: int = 50,
    ) -> RegistrySearchResult:
        services = list(self._services.values())

        if query:
            query_lower = query.lower()
            services = [
                s for s in services
                if query_lower in s.name.lower()
                or query_lower in (s.description or "").lower()
                or any(query_lower in t.lower() for t in s.tags)
            ]

        if type:
            services = [s for s in services if s.type == type]

        if language:
            services = [s for s in services if s.language.lower() == language.lower()]

        if tags:
            services = [
                s for s in services
                if any(t in s.tags for t in tags)
            ]

        facets: Dict[str, Dict[str, int]] = {
            "types": defaultdict(int),
            "languages": defaultdict(int),
            "tags": defaultdict(int),
        }

        for s in services:
            facets["types"][s.type] += 1
            facets["languages"][s.language] += 1
            for tag in s.tags:
                facets["tags"][tag] += 1

        total = len(services)
        start = (page - 1) * page_size
        end = start + page_size
        paginated = services[start:end]

        return RegistrySearchResult(
            services=paginated,
            total=total,
            page=page,
            page_size=page_size,
            facets={k: dict(v) for k, v in facets.items()},
        )

    def add_dependency(
        self,
        source_service_id: str,
        target_service_id: str,
        dependency_type: str = "runtime",
        version_constraint: Optional[str] = None,
        description: Optional[str] = None,
    ) -> DependencyEdge:
        edge = DependencyEdge(
            source_service_id=source_service_id,
            target_service_id=target_service_id,
            dependency_type=dependency_type,
            version_constraint=version_constraint,
            description=description,
        )
        self._dependency_graph.add_edge(edge)

        if self._conn:
            self._conn.execute(
                """
                INSERT INTO dependencies VALUES (NULL, ?, ?, ?, ?, ?, ?)
                """,
                (
                    edge.source_service_id,
                    edge.target_service_id,
                    edge.dependency_type,
                    edge.version_constraint,
                    edge.description,
                    edge.created_at.isoformat(),
                ),
            )
            self._conn.commit()

        logger.info(
            "Added dependency: %s -> %s (%s)",
            source_service_id,
            target_service_id,
            dependency_type,
        )
        return edge

    def get_dependencies(self, service_id: str) -> List[ServiceNode]:
        return self._dependency_graph.get_dependencies(service_id)

    def get_dependents(self, service_id: str) -> List[ServiceNode]:
        return self._dependency_graph.get_dependents(service_id)

    def get_all_dependencies(self, service_id: str) -> List[ServiceNode]:
        return self._dependency_graph.get_all_dependencies(service_id)

    def get_all_dependents(self, service_id: str) -> List[ServiceNode]:
        return self._dependency_graph.get_all_dependents(service_id)

    def get_dependency_graph(self) -> DependencyGraph:
        return self._dependency_graph

    def detect_cycles(self) -> List[List[str]]:
        return self._dependency_graph.detect_cycles()

    def get_topological_order(self) -> List[str]:
        return self._dependency_graph.topological_sort()

    def generate_dependency_diagram(
        self,
        format: str = "mermaid",
        service_id: Optional[str] = None,
    ) -> str:
        if service_id:
            subgraph = DependencyGraph()
            deps = self.get_all_dependencies(service_id)
            dependents = self.get_all_dependents(service_id)
            all_related = set([service_id] + [d.service_id for d in deps] + [d.service_id for d in dependents])

            for sid in all_related:
                if sid in self._services:
                    subgraph.add_node(self._services[sid])

            for edge in self._dependency_graph._edges:
                if edge.source_service_id in all_related and edge.target_service_id in all_related:
                    subgraph.add_edge(edge)

            graph = subgraph
        else:
            graph = self._dependency_graph

        if format == "mermaid":
            return graph.generate_mermaid_diagram()
        elif format == "dot":
            return graph.generate_dot_diagram()
        else:
            raise ValidationError(f"Unsupported diagram format: {format}")

    def list_all(self) -> List[ServiceNode]:
        return list(self._services.values())

    def get_statistics(self) -> Dict[str, Any]:
        type_counts: Dict[str, int] = defaultdict(int)
        language_counts: Dict[str, int] = defaultdict(int)

        for service in self._services.values():
            type_counts[service.type] += 1
            language_counts[service.language] += 1

        cycles = self.detect_cycles()

        return {
            "total_services": len(self._services),
            "total_dependencies": len(self._dependency_graph._edges),
            "by_type": dict(type_counts),
            "by_language": dict(language_counts),
            "has_cycles": len(cycles) > 0,
            "cycle_count": len(cycles),
            "db_path": self._db_path,
        }

    def export_registry(self, export_path: str) -> None:
        data = {
            "services": [s.to_dict() for s in self._services.values()],
            "dependencies": [e.to_dict() for e in self._dependency_graph._edges],
            "exported_at": datetime.utcnow().isoformat(),
        }

        Path(export_path).parent.mkdir(parents=True, exist_ok=True)
        with open(export_path, "w") as f:
            json.dump(data, f, indent=2)

        logger.info("Registry exported to: %s", export_path)

    def import_registry(self, import_path: str) -> Tuple[int, int]:
        with open(import_path, "r") as f:
            data = json.load(f)

        services_imported = 0
        for s in data.get("services", []):
            try:
                metadata = ServiceMetadataModel(
                    service_id=s["service_id"],
                    name=s["name"],
                    version=s["version"],
                    description=s.get("description"),
                    type=s["type"],
                    language=s["language"],
                    tags=s.get("tags", []),
                    endpoints=s.get("endpoints", []),
                    metadata=s.get("metadata", {}),
                )
                self.register(metadata)
                services_imported += 1
            except Exception as e:
                logger.warning("Failed to import service %s: %s", s.get("name"), e)

        dependencies_imported = 0
        for d in data.get("dependencies", []):
            try:
                self.add_dependency(
                    source_service_id=d["source_service_id"],
                    target_service_id=d["target_service_id"],
                    dependency_type=d.get("dependency_type", "runtime"),
                    version_constraint=d.get("version_constraint"),
                    description=d.get("description"),
                )
                dependencies_imported += 1
            except Exception as e:
                logger.warning("Failed to import dependency: %s", e)

        logger.info(
            "Imported %d services and %d dependencies",
            services_imported,
            dependencies_imported,
        )
        return services_imported, dependencies_imported

    def close(self) -> None:
        if self._conn:
            self._conn.close()
            self._conn = None
            logger.info("Service registry database connection closed")
