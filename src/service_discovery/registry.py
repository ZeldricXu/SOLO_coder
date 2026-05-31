from __future__ import annotations

import logging
import time
from typing import Any, Dict, List, Optional

from src.service_discovery.models import (
    DependencyGraph,
    HealthStatus,
    ServiceContact,
    ServiceDependency,
    ServiceEndpoint,
    ServiceHealth,
    ServiceMetadata,
    ServiceQuery,
    ServiceStatus,
)

logger = logging.getLogger(__name__)


class ServiceRegistry:
    def __init__(self) -> None:
        self._services: Dict[str, ServiceMetadata] = {}
        self._health: Dict[str, ServiceHealth] = {}
        self._index: Dict[str, List[str]] = {}
        self._register_example_services()

    def _register_example_services(self) -> None:
        examples = [
            ServiceMetadata(
                name="user-service",
                description="User authentication and profile management",
                type="service",
                status="active",
                version="1.2.0",
                owner="team-auth",
                tags=["auth", "user", "rest"],
                endpoints=[ServiceEndpoint(name="api", url="http://user-service:8000", version="v1")],
                dependencies=[
                    ServiceDependency(service_id="svc_database", relationship="uses"),
                    ServiceDependency(service_id="svc_cache", relationship="uses"),
                ],
                contacts=[ServiceContact(name="Alice", email="alice@example.com", role="owner")],
                labels={"env": "production", "region": "cn-east"},
                documentation_url="https://docs.example.com/user-service",
                repository_url="https://github.com/example/user-service",
            ),
            ServiceMetadata(
                name="order-service",
                description="Order processing and management",
                type="service",
                status="active",
                version="2.0.1",
                owner="team-commerce",
                tags=["order", "commerce", "rest"],
                endpoints=[ServiceEndpoint(name="api", url="http://order-service:8000", version="v2")],
                dependencies=[
                    ServiceDependency(service_id="svc_database", relationship="uses"),
                    ServiceDependency(service_id="svc_queue", relationship="uses"),
                    ServiceDependency(service_id="svc_user", relationship="calls"),
                ],
                contacts=[ServiceContact(name="Bob", email="bob@example.com", role="owner")],
                labels={"env": "production", "region": "cn-east"},
            ),
            ServiceMetadata(
                name="database",
                description="Primary PostgreSQL database cluster",
                type="database",
                status="active",
                version="14.5",
                owner="team-infra",
                tags=["database", "postgresql"],
                endpoints=[ServiceEndpoint(name="primary", url="postgres://db:5432", protocol="postgres")],
                contacts=[ServiceContact(name="Charlie", email="charlie@example.com", role="dba")],
                labels={"env": "production", "replication": "true"},
            ),
            ServiceMetadata(
                name="cache",
                description="Redis cache cluster",
                type="cache",
                status="active",
                version="7.0",
                owner="team-infra",
                tags=["cache", "redis"],
                endpoints=[ServiceEndpoint(name="cluster", url="redis://cache:6379", protocol="redis")],
                contacts=[ServiceContact(name="Charlie", email="charlie@example.com", role="dba")],
                labels={"env": "production", "nodes": "3"},
            ),
            ServiceMetadata(
                name="queue",
                description="RabbitMQ message queue",
                type="queue",
                status="active",
                version="3.12",
                owner="team-infra",
                tags=["queue", "rabbitmq", "messaging"],
                endpoints=[
                    ServiceEndpoint(name="amqp", url="amqp://queue:5672", protocol="amqp"),
                    ServiceEndpoint(name="management", url="http://queue:15672", protocol="http"),
                ],
                contacts=[ServiceContact(name="Dave", email="dave@example.com", role="sre")],
                labels={"env": "production", "vhost": "/"},
            ),
        ]
        for svc in examples:
            svc.service_id = f"svc_{svc.name.lower().replace('-', '_')}"
            self._services[svc.service_id] = svc
            self._health[svc.service_id] = ServiceHealth(
                service_id=svc.service_id,
                health_status=HealthStatus.HEALTHY,
                message="All checks passing",
                metrics={"latency_p99": 45, "error_rate": 0.001, "throughput": 1200},
            )
            self._update_index(svc)

    def register(self, metadata: ServiceMetadata) -> ServiceMetadata:
        self._services[metadata.service_id] = metadata
        self._update_index(metadata)
        if metadata.service_id not in self._health:
            self._health[metadata.service_id] = ServiceHealth(
                service_id=metadata.service_id,
                health_status=HealthStatus.UNKNOWN,
                message="No health check yet",
            )
        logger.info(f"Registered service: {metadata.name} ({metadata.service_id})")
        return metadata

    def _update_index(self, svc: ServiceMetadata) -> None:
        self._index.setdefault("name", []).append(svc.name.lower())
        for tag in svc.tags:
            self._index.setdefault(f"tag:{tag}", []).append(svc.service_id)
        self._index.setdefault(f"type:{svc.type}", []).append(svc.service_id)
        self._index.setdefault(f"owner:{svc.owner}", []).append(svc.service_id)
        for k, v in svc.labels.items():
            self._index.setdefault(f"label:{k}={v}", []).append(svc.service_id)

    def unregister(self, service_id: str) -> bool:
        if service_id in self._services:
            del self._services[service_id]
            if service_id in self._health:
                del self._health[service_id]
            logger.info(f"Unregistered service: {service_id}")
            return True
        return False

    def get(self, service_id: str) -> Optional[ServiceMetadata]:
        return self._services.get(service_id)

    def list_all(self) -> List[ServiceMetadata]:
        return list(self._services.values())

    def query(self, query: ServiceQuery) -> List[ServiceMetadata]:
        results = list(self._services.values())

        if query.name:
            results = [s for s in results if query.name.lower() in s.name.lower()]
        if query.type:
            results = [s for s in results if s.type == query.type]
        if query.status:
            results = [s for s in results if s.status == query.status]
        if query.owner:
            results = [s for s in results if query.owner.lower() in s.owner.lower()]
        if query.tags:
            results = [s for s in results if any(t in s.tags for t in query.tags)]
        if query.labels:
            for k, v in query.labels.items():
                results = [s for s in results if s.labels.get(k) == v]

        return results

    def search(self, keyword: str) -> List[ServiceMetadata]:
        keyword = keyword.lower()
        results: List[ServiceMetadata] = []
        seen = set()

        for svc in self._services.values():
            if (
                keyword in svc.name.lower()
                or keyword in svc.description.lower()
                or any(keyword in t.lower() for t in svc.tags)
                or any(keyword in v.lower() for v in svc.labels.values())
            ):
                if svc.service_id not in seen:
                    seen.add(svc.service_id)
                    results.append(svc)

        return results

    def update_health(self, health: ServiceHealth) -> None:
        self._health[health.service_id] = health
        logger.debug(f"Updated health for {health.service_id}: {health.health_status}")

    def get_health(self, service_id: str) -> Optional[ServiceHealth]:
        return self._health.get(service_id)

    def get_dependents(self, service_id: str) -> List[ServiceMetadata]:
        return [
            svc
            for svc in self._services.values()
            if any(dep.service_id == service_id for dep in svc.dependencies)
        ]

    def get_dependency_graph(self) -> DependencyGraph:
        nodes = []
        edges = []

        status_colors = {
            "active": "#22c55e",
            "inactive": "#6b7280",
            "deprecated": "#f59e0b",
            "maintenance": "#3b82f6",
        }

        type_shapes = {
            "service": "circle",
            "library": "square",
            "database": "cylinder",
            "queue": "hexagon",
            "cache": "diamond",
            "storage": "box",
            "gateway": "parallelogram",
        }

        for svc in self._services.values():
            health = self._health.get(svc.service_id)
            health_color = {
                "healthy": "#22c55e",
                "degraded": "#f59e0b",
                "unhealthy": "#ef4444",
                "unknown": "#6b7280",
            }.get(health.health_status if health else "unknown", "#6b7280")

            nodes.append({
                "id": svc.service_id,
                "name": svc.name,
                "type": svc.type.value,
                "version": svc.version,
                "status": svc.status.value,
                "owner": svc.owner,
                "color": status_colors.get(svc.status, "#6b7280"),
                "shape": type_shapes.get(svc.type, "circle"),
                "health": health.health_status.value if health else "unknown",
                "health_color": health_color,
            })

        for svc in self._services.values():
            for dep in svc.dependencies:
                if dep.service_id in self._services:
                    edges.append({
                        "source": svc.service_id,
                        "target": dep.service_id,
                        "relationship": dep.relationship,
                        "version": dep.version_constraint,
                    })

        return DependencyGraph(nodes=nodes, edges=edges)

    def get_with_details(self, service_id: str) -> Optional[Dict[str, Any]]:
        svc = self.get(service_id)
        if not svc:
            return None

        health = self.get_health(service_id)
        dependents = self.get_dependents(service_id)
        dependencies = [dep.service_id for dep in svc.dependencies]

        return {
            "service": svc,
            "health": health,
            "dependents": [d.name for d in dependents],
            "dependencies": dependencies,
        }
