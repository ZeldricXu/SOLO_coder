import json
import os
import tempfile
from datetime import datetime
from pathlib import Path
from typing import List

import pytest

from src.models import ServiceMetadata
from src.registry.registry import ServiceRegistry
from src.utils.errors import ResourceNotFoundError, ValidationError


@pytest.fixture
def clean_registry():
    registry = ServiceRegistry()
    for service in registry.list_all():
        registry.unregister(service.service_id)
    yield registry
    registry.close()


class TestServiceRegistry:
    def test_singleton_pattern(self):
        r1 = ServiceRegistry()
        r2 = ServiceRegistry()
        assert r1 is r2

    def test_register_service(self, clean_registry, sample_service_metadata):
        node = clean_registry.register(sample_service_metadata)

        assert node.service_id == "svc_test_001"
        assert node.name == "test-service"
        assert node.version == "1.0.0"
        assert "test" in node.tags

    def test_register_duplicate_service(self, clean_registry, sample_service_metadata):
        node1 = clean_registry.register(sample_service_metadata)
        node2 = clean_registry.register(sample_service_metadata)

        assert node1.service_id == node2.service_id

    def test_unregister_service(self, clean_registry, sample_service_metadata):
        node = clean_registry.register(sample_service_metadata)
        result = clean_registry.unregister(node.service_id)

        assert result is True
        with pytest.raises(ResourceNotFoundError):
            clean_registry.get(node.service_id)

    def test_unregister_nonexistent(self, clean_registry):
        assert clean_registry.unregister("nonexistent") is False

    def test_get_service(self, clean_registry, sample_service_metadata):
        node = clean_registry.register(sample_service_metadata)
        fetched = clean_registry.get(node.service_id)

        assert fetched.service_id == node.service_id
        assert fetched.name == node.name

    def test_get_by_name(self, clean_registry, sample_service_metadata):
        clean_registry.register(sample_service_metadata)

        found = clean_registry.get_by_name("test-service")
        assert found is not None
        assert found.version == "1.0.0"

        found_v2 = clean_registry.get_by_name("test-service", "2.0.0")
        assert found_v2 is None

    def test_search_services(self, clean_registry):
        services = [
            ServiceMetadata(
                name="order-service",
                version="1.0.0",
                type="service",
                language="python",
                tags=["orders", "api"],
            ),
            ServiceMetadata(
                name="payment-service",
                version="1.0.0",
                type="service",
                language="go",
                tags=["payments", "api"],
            ),
            ServiceMetadata(
                name="common-lib",
                version="2.0.0",
                type="library",
                language="python",
                tags=["utils"],
            ),
        ]

        for svc in services:
            clean_registry.register(svc)

        result = clean_registry.search(query="service")
        assert result.total == 2

        result = clean_registry.search(type="library")
        assert result.total == 1

        result = clean_registry.search(language="python")
        assert result.total == 2

        result = clean_registry.search(tags=["api"])
        assert result.total == 2

        result = clean_registry.search(query="order", page_size=1)
        assert result.total == 1
        assert len(result.services) == 1
        assert result.page_size == 1

    def test_search_facets(self, clean_registry):
        for i in range(3):
            clean_registry.register(ServiceMetadata(
                name=f"svc-{i}",
                type="service",
                language="python",
                tags=[f"tag{i}"],
            ))
        for i in range(2):
            clean_registry.register(ServiceMetadata(
                name=f"lib-{i}",
                type="library",
                language="go",
            ))

        result = clean_registry.search()

        assert result.facets["types"]["service"] == 3
        assert result.facets["types"]["library"] == 2
        assert result.facets["languages"]["python"] == 3
        assert result.facets["languages"]["go"] == 2

    def test_add_dependency(self, clean_registry):
        svc_a = clean_registry.register(ServiceMetadata(
            name="service-a",
            version="1.0.0",
            type="service",
            language="python",
        ))
        svc_b = clean_registry.register(ServiceMetadata(
            name="service-b",
            version="1.0.0",
            type="service",
            language="python",
        ))

        edge = clean_registry.add_dependency(
            source_service_id=svc_a.service_id,
            target_service_id=svc_b.service_id,
            dependency_type="runtime",
        )

        assert edge.source_service_id == svc_a.service_id
        assert edge.target_service_id == svc_b.service_id

        deps = clean_registry.get_dependencies(svc_a.service_id)
        assert len(deps) == 1
        assert deps[0].service_id == svc_b.service_id

        dependents = clean_registry.get_dependents(svc_b.service_id)
        assert len(dependents) == 1
        assert dependents[0].service_id == svc_a.service_id

    def test_add_dependency_invalid_service(self, clean_registry):
        with pytest.raises(ValidationError):
            clean_registry.add_dependency("nonexistent", "fake")

    def test_transitive_dependencies(self, clean_registry):
        svc_a = clean_registry.register(ServiceMetadata(name="a", type="service", language="python"))
        svc_b = clean_registry.register(ServiceMetadata(name="b", type="service", language="python"))
        svc_c = clean_registry.register(ServiceMetadata(name="c", type="service", language="python"))

        clean_registry.add_dependency(svc_a.service_id, svc_b.service_id)
        clean_registry.add_dependency(svc_b.service_id, svc_c.service_id)

        direct = clean_registry.get_dependencies(svc_a.service_id)
        assert len(direct) == 1

        transitive = clean_registry.get_all_dependencies(svc_a.service_id)
        assert len(transitive) == 2

    def test_cycle_detection(self, clean_registry):
        svc_a = clean_registry.register(ServiceMetadata(name="a", type="service", language="python"))
        svc_b = clean_registry.register(ServiceMetadata(name="b", type="service", language="python"))
        svc_c = clean_registry.register(ServiceMetadata(name="c", type="service", language="python"))

        clean_registry.add_dependency(svc_a.service_id, svc_b.service_id)
        clean_registry.add_dependency(svc_b.service_id, svc_c.service_id)
        clean_registry.add_dependency(svc_c.service_id, svc_a.service_id)

        cycles = clean_registry.detect_cycles()
        assert len(cycles) > 0

        with pytest.raises(ValidationError):
            clean_registry.get_topological_order()

    def test_topological_sort(self, clean_registry):
        svc_a = clean_registry.register(ServiceMetadata(name="a", type="service", language="python"))
        svc_b = clean_registry.register(ServiceMetadata(name="b", type="service", language="python"))
        svc_c = clean_registry.register(ServiceMetadata(name="c", type="service", language="python"))

        clean_registry.add_dependency(svc_a.service_id, svc_b.service_id)
        clean_registry.add_dependency(svc_a.service_id, svc_c.service_id)
        clean_registry.add_dependency(svc_b.service_id, svc_c.service_id)

        order = clean_registry.get_topological_order()
        assert order.index(svc_a.service_id) < order.index(svc_b.service_id)
        assert order.index(svc_b.service_id) < order.index(svc_c.service_id)

    def test_generate_mermaid_diagram(self, clean_registry):
        svc_a = clean_registry.register(ServiceMetadata(name="service-a", type="service", language="python"))
        svc_b = clean_registry.register(ServiceMetadata(name="lib-b", type="library", language="python"))
        clean_registry.add_dependency(svc_a.service_id, svc_b.service_id)

        diagram = clean_registry.generate_dependency_diagram(format="mermaid")

        assert "graph TD" in diagram
        assert svc_a.service_id in diagram
        assert svc_b.service_id in diagram
        assert "-->" in diagram

    def test_generate_dot_diagram(self, clean_registry):
        svc_a = clean_registry.register(ServiceMetadata(name="a", type="service", language="python"))
        svc_b = clean_registry.register(ServiceMetadata(name="b", type="library", language="go"))
        clean_registry.add_dependency(svc_a.service_id, svc_b.service_id, dependency_type="compile")

        diagram = clean_registry.generate_dependency_diagram(format="dot")

        assert "digraph dependencies" in diagram
        assert "rankdir=LR" in diagram
        assert "dashed" in diagram

    def test_invalid_diagram_format(self, clean_registry):
        with pytest.raises(ValidationError):
            clean_registry.generate_dependency_diagram(format="invalid")

    def test_statistics(self, clean_registry):
        for i in range(5):
            clean_registry.register(ServiceMetadata(
                name=f"svc-{i}",
                type="service",
                language="python",
            ))
        for i in range(3):
            clean_registry.register(ServiceMetadata(
                name=f"lib-{i}",
                type="library",
                language="go",
            ))

        stats = clean_registry.get_statistics()

        assert stats["total_services"] == 8
        assert stats["by_type"]["service"] == 5
        assert stats["by_type"]["library"] == 3
        assert stats["by_language"]["python"] == 5
        assert stats["by_language"]["go"] == 3

    def test_export_and_import_registry(self, clean_registry, temp_dir):
        svc_a = clean_registry.register(ServiceMetadata(
            name="export-svc",
            version="1.0.0",
            type="service",
            language="python",
            tags=["export"],
        ))
        svc_b = clean_registry.register(ServiceMetadata(
            name="export-lib",
            version="2.0.0",
            type="library",
            language="python",
        ))
        clean_registry.add_dependency(svc_a.service_id, svc_b.service_id)

        export_path = os.path.join(temp_dir, "registry.json")
        clean_registry.export_registry(export_path)

        assert os.path.exists(export_path)

        for service in clean_registry.list_all():
            clean_registry.unregister(service.service_id)

        assert len(clean_registry.list_all()) == 0

        services_imported, deps_imported = clean_registry.import_registry(export_path)

        assert services_imported == 2
        assert deps_imported == 1
        assert len(clean_registry.list_all()) == 2
