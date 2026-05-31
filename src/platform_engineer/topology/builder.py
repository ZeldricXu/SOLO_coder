from dataclasses import dataclass, field
from datetime import datetime, timezone
from typing import Any, Dict, List, Optional, Set
from uuid import uuid4


@dataclass
class ServiceNode:
    service_name: str
    node_id: str = field(default_factory=lambda: uuid4().hex[:12])
    service_type: str = "service"
    metadata: Dict[str, Any] = field(default_factory=dict)
    created_at: datetime = field(default_factory=lambda: datetime.now(timezone.utc))
    call_count: int = 0
    error_count: int = 0
    avg_duration_ms: float = 0.0
    p95_duration_ms: float = 0.0
    active: bool = True

    def to_dict(self) -> Dict[str, Any]:
        return {
            "node_id": self.node_id,
            "service_name": self.service_name,
            "service_type": self.service_type,
            "metadata": self.metadata,
            "created_at": self.created_at.isoformat(),
            "call_count": self.call_count,
            "error_count": self.error_count,
            "avg_duration_ms": self.avg_duration_ms,
            "p95_duration_ms": self.p95_duration_ms,
            "active": self.active,
        }


@dataclass
class ServiceEdge:
    edge_id: str = field(default_factory=lambda: uuid4().hex[:12])
    source_service: str = ""
    target_service: str = ""
    call_count: int = 0
    error_count: int = 0
    avg_duration_ms: float = 0.0
    p95_duration_ms: float = 0.0
    created_at: datetime = field(default_factory=lambda: datetime.now(timezone.utc))
    last_seen_at: Optional[datetime] = None
    metadata: Dict[str, Any] = field(default_factory=dict)

    @property
    def key(self) -> str:
        return f"{self.source_service}->{self.target_service}"

    def to_dict(self) -> Dict[str, Any]:
        return {
            "edge_id": self.edge_id,
            "source_service": self.source_service,
            "target_service": self.target_service,
            "call_count": self.call_count,
            "error_count": self.error_count,
            "avg_duration_ms": self.avg_duration_ms,
            "p95_duration_ms": self.p95_duration_ms,
            "created_at": self.created_at.isoformat(),
            "last_seen_at": self.last_seen_at.isoformat() if self.last_seen_at else None,
            "metadata": self.metadata,
        }


@dataclass
class ServiceTopology:
    nodes: Dict[str, ServiceNode] = field(default_factory=dict)
    edges: Dict[str, ServiceEdge] = field(default_factory=dict)
    created_at: datetime = field(default_factory=lambda: datetime.now(timezone.utc))
    updated_at: datetime = field(default_factory=lambda: datetime.now(timezone.utc))

    def add_node(self, node: ServiceNode) -> None:
        self.nodes[node.service_name] = node
        self.updated_at = datetime.now(timezone.utc)

    def add_edge(self, edge: ServiceEdge) -> None:
        self.edges[edge.key] = edge
        self.updated_at = datetime.now(timezone.utc)

    def get_or_create_node(self, service_name: str) -> ServiceNode:
        if service_name not in self.nodes:
            self.nodes[service_name] = ServiceNode(service_name=service_name)
        return self.nodes[service_name]

    def get_or_create_edge(self, source: str, target: str) -> ServiceEdge:
        key = f"{source}->{target}"
        if key not in self.edges:
            self.edges[key] = ServiceEdge(source_service=source, target_service=target)
        return self.edges[key]

    def get_dependencies(self, service_name: str) -> List[str]:
        deps = []
        for edge in self.edges.values():
            if edge.source_service == service_name:
                deps.append(edge.target_service)
        return deps

    def get_dependents(self, service_name: str) -> List[str]:
        deps = []
        for edge in self.edges.values():
            if edge.target_service == service_name:
                deps.append(edge.source_service)
        return deps

    def to_dict(self) -> Dict[str, Any]:
        return {
            "nodes": {name: node.to_dict() for name, node in self.nodes.items()},
            "edges": {key: edge.to_dict() for key, edge in self.edges.items()},
            "created_at": self.created_at.isoformat(),
            "updated_at": self.updated_at.isoformat(),
            "node_count": len(self.nodes),
            "edge_count": len(self.edges),
        }


class TopologyBuilder:
    def __init__(self, max_nodes: int = 1000, max_edges: int = 10000, logger=None):
        self._topology = ServiceTopology()
        self._max_nodes = max_nodes
        self._max_edges = max_edges
        self._logger = logger
        self._trace_buffer: List[Dict[str, Any]] = []

    def process_span(self, span_data: Dict[str, Any]) -> None:
        source_service = span_data.get("service_name", "unknown")
        parent_name = span_data.get("parent_span_id")
        name = span_data.get("name", "unknown")
        duration_ms = span_data.get("duration_ms", 0.0)
        is_error = span_data.get("status") == "ERROR"
        self._topology.get_or_create_node(source_service)
        node = self._topology.nodes[source_service]
        node.call_count += 1
        if is_error:
            node.error_count += 1
        node.avg_duration_ms = (
            (node.avg_duration_ms * (node.call_count - 1) + duration_ms) / node.call_count
        )
        target_service = self._extract_target_service(span_data)
        if target_service and target_service != source_service:
            edge = self._topology.get_or_create_edge(source_service, target_service)
            edge.call_count += 1
            if is_error:
                edge.error_count += 1
            edge.last_seen_at = datetime.now(timezone.utc)
            edge.avg_duration_ms = (
                (edge.avg_duration_ms * (edge.call_count - 1) + duration_ms) / edge.call_count
            )
        self._cleanup_if_needed()

    def _extract_target_service(self, span_data: Dict[str, Any]) -> Optional[str]:
        attributes = span_data.get("attributes", {})
        peer_service = attributes.get("peer.service")
        if peer_service:
            return peer_service
        http_target = attributes.get("http.target") or attributes.get("url.path")
        if http_target and "/" in http_target:
            return f"http://{http_target.split('/')[1]}" if len(http_target.split('/')) > 1 else None
        db_system = attributes.get("db.system")
        if db_system:
            db_name = attributes.get("db.name", "database")
            return f"db:{db_system}:{db_name}"
        return None

    def process_trace(self, spans: List[Dict[str, Any]]) -> None:
        for span in spans:
            self.process_span(span)

    def get_topology(self) -> ServiceTopology:
        return self._topology

    def get_node(self, service_name: str) -> Optional[ServiceNode]:
        return self._topology.nodes.get(service_name)

    def get_edge(self, source: str, target: str) -> Optional[ServiceEdge]:
        key = f"{source}->{target}"
        return self._topology.edges.get(key)

    def reset(self) -> None:
        self._topology = ServiceTopology()
        self._trace_buffer.clear()

    def _cleanup_if_needed(self) -> None:
        if len(self._topology.nodes) > self._max_nodes:
            sorted_nodes = sorted(
                self._topology.nodes.values(),
                key=lambda n: n.call_count,
            )
            remove_count = len(self._topology.nodes) - self._max_nodes + int(self._max_nodes * 0.1)
            for node in sorted_nodes[:remove_count]:
                del self._topology.nodes[node.service_name]
                keys_to_remove = [
                    k for k in self._topology.edges
                    if self._topology.edges[k].source_service == node.service_name
                    or self._topology.edges[k].target_service == node.service_name
                ]
                for k in keys_to_remove:
                    del self._topology.edges[k]
        if len(self._topology.edges) > self._max_edges:
            sorted_edges = sorted(
                self._topology.edges.values(),
                key=lambda e: e.call_count,
            )
            remove_count = len(self._topology.edges) - self._max_edges + int(self._max_edges * 0.1)
            for edge in sorted_edges[:remove_count]:
                del self._topology.edges[edge.key]
