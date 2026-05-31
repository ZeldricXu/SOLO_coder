from __future__ import annotations

import copy
import uuid
from datetime import datetime, timezone
from typing import Any, Dict, List, Optional


class ServiceBuilder:
    _counter = 0

    def __init__(self):
        ServiceBuilder._counter += 1
        self._id = f"svc_{uuid.uuid4().hex[:8]}"
        self._name = f"test-service-{ServiceBuilder._counter}"
        self._description = f"Test service {ServiceBuilder._counter} description"
        self._type = "microservice"
        self._version = "1.0.0"
        self._owner = "test-owner"
        self._labels: Dict[str, str] = {"environment": "test", "team": "qa"}
        self._endpoints: List[str] = [f"http://service-{ServiceBuilder._counter}.example.com"]
        self._dependencies: List[str] = []
        self._created_at = datetime.now(timezone.utc)
        self._updated_at = datetime.now(timezone.utc)

    def with_id(self, service_id: str) -> "ServiceBuilder":
        self._id = service_id
        return self

    def with_name(self, name: str) -> "ServiceBuilder":
        self._name = name
        return self

    def with_description(self, description: str) -> "ServiceBuilder":
        self._description = description
        return self

    def with_type(self, service_type: str) -> "ServiceBuilder":
        self._type = service_type
        return self

    def with_version(self, version: str) -> "ServiceBuilder":
        self._version = version
        return self

    def with_owner(self, owner: str) -> "ServiceBuilder":
        self._owner = owner
        return self

    def with_labels(self, labels: Dict[str, str]) -> "ServiceBuilder":
        self._labels = copy.deepcopy(labels)
        return self

    def with_endpoints(self, endpoints: List[str]) -> "ServiceBuilder":
        self._endpoints = copy.deepcopy(endpoints)
        return self

    def with_dependencies(self, dependencies: List[str]) -> "ServiceBuilder":
        self._dependencies = copy.deepcopy(dependencies)
        return self

    def add_label(self, key: str, value: str) -> "ServiceBuilder":
        self._labels[key] = value
        return self

    def add_endpoint(self, endpoint: str) -> "ServiceBuilder":
        self._endpoints.append(endpoint)
        return self

    def add_dependency(self, dependency_id: str) -> "ServiceBuilder":
        self._dependencies.append(dependency_id)
        return self

    def build(self) -> Dict[str, Any]:
        return {
            "id": self._id,
            "name": self._name,
            "description": self._description,
            "type": self._type,
            "version": self._version,
            "owner": self._owner,
            "labels": copy.deepcopy(self._labels),
            "endpoints": copy.deepcopy(self._endpoints),
            "dependencies": copy.deepcopy(self._dependencies),
            "created_at": self._created_at.isoformat(),
            "updated_at": self._updated_at.isoformat(),
        }

    def build_request(self) -> Dict[str, Any]:
        return {
            "name": self._name,
            "description": self._description,
            "type": self._type,
            "version": self._version,
            "owner": self._owner,
            "labels": copy.deepcopy(self._labels),
            "endpoints": copy.deepcopy(self._endpoints),
            "dependencies": copy.deepcopy(self._dependencies),
        }

    @staticmethod
    def create_default() -> Dict[str, Any]:
        return ServiceBuilder().build()

    @staticmethod
    def create_default_request() -> Dict[str, Any]:
        return ServiceBuilder().build_request()

    @staticmethod
    def create_many(count: int) -> List[Dict[str, Any]]:
        return [ServiceBuilder().build() for _ in range(count)]

    @staticmethod
    def create_with_dependencies(depth: int = 3) -> List[Dict[str, Any]]:
        services = []
        prev_id = None

        for i in range(depth):
            builder = ServiceBuilder().with_name(f"service-level-{i}")
            if prev_id:
                builder.with_dependencies([prev_id])
            service = builder.build()
            services.append(service)
            prev_id = service["id"]

        return services

    @staticmethod
    def create_circular_dependency() -> List[Dict[str, Any]]:
        builder1 = ServiceBuilder().with_name("service-a")
        builder2 = ServiceBuilder().with_name("service-b")

        service1 = builder1.build()
        service2 = builder2.build()

        service1["dependencies"] = [service2["id"]]
        service2["dependencies"] = [service1["id"]]

        return [service1, service2]
