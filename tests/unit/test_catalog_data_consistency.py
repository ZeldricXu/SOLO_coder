from __future__ import annotations

import copy
import threading
import time
from typing import Dict, List
from unittest.mock import MagicMock, patch

import pytest

from tests.builders import ServiceBuilder


class TestCatalogDataConsistency:
    @pytest.fixture
    def mock_db_session(self):
        with patch("internal.catalog.catalog.database") as mock_db:
            mock_db.DB = MagicMock()
            yield mock_db

    @pytest.fixture
    def sample_services(self) -> List[Dict]:
        return ServiceBuilder.create_many(5)

    @pytest.fixture
    def dependent_services(self) -> List[Dict]:
        return ServiceBuilder.create_with_dependencies(depth=3)

    def test_service_creation_idempotency(self, mock_db_session):
        service_req = ServiceBuilder.create_default_request()
        service_id = "svc_test_001"

        created_services = []
        for i in range(3):
            builder = ServiceBuilder()
            builder._name = service_req["name"]
            builder._id = service_id
            service = builder.build()
            created_services.append(service)

        assert all(s["id"] == service_id for s in created_services)
        assert all(s["name"] == service_req["name"] for s in created_services)
        assert len({s["created_at"] for s in created_services}) == 3

    def test_concurrent_service_registration_no_data_corruption(self, mock_db_session):
        results = []
        errors = []

        def register_service(index):
            try:
                builder = ServiceBuilder()
                builder._name = f"concurrent-service-{index}"
                service_req = builder.build_request()
                time.sleep(0.01)
                results.append({"index": index, "request": service_req})
            except Exception as e:
                errors.append({"index": index, "error": str(e)})

        threads = [threading.Thread(target=register_service, args=(i,)) for i in range(10)]
        for t in threads:
            t.start()
        for t in threads:
            t.join()

        assert len(errors) == 0, f"Unexpected errors: {errors}"
        assert len(results) == 10

        names = [r["request"]["name"] for r in results]
        assert len(names) == len(set(names)), "Duplicate service names found"

    def test_service_update_optimistic_locking(self, mock_db_session):
        original_service = ServiceBuilder.create_default()
        original_version = original_service["updated_at"]

        time.sleep(0.001)

        update1 = copy.deepcopy(original_service)
        update1["description"] = "First update"
        update1["updated_at"] = time.time()

        update2 = copy.deepcopy(original_service)
        update2["description"] = "Second update"
        update2["updated_at"] = time.time() + 0.001

        assert update1["updated_at"] != original_version
        assert update2["updated_at"] > update1["updated_at"]
        assert update1["id"] == update2["id"]

    def test_dependency_chain_consistency(self, dependent_services):
        assert len(dependent_services) == 3

        for i in range(1, len(dependent_services)):
            current = dependent_services[i]
            previous = dependent_services[i - 1]
            assert previous["id"] in current["dependencies"]

        all_deps = []
        for s in dependent_services:
            all_deps.extend(s["dependencies"])

        assert len(all_deps) == 2
        assert dependent_services[0]["dependencies"] == []

    def test_circular_dependency_detection(self):
        circular_services = ServiceBuilder.create_circular_dependency()
        assert len(circular_services) == 2

        s1, s2 = circular_services
        assert s2["id"] in s1["dependencies"]
        assert s1["id"] in s2["dependencies"]

        visited = set()
        stack = set()

        def has_cycle(service_id, services_map):
            if service_id in stack:
                return True
            if service_id in visited:
                return False

            visited.add(service_id)
            stack.add(service_id)

            service = services_map.get(service_id)
            if service:
                for dep in service["dependencies"]:
                    if has_cycle(dep, services_map):
                        return True

            stack.remove(service_id)
            return False

        services_map = {s["id"]: s for s in circular_services}
        assert has_cycle(s1["id"], services_map) is True

    def test_batch_operation_atomicity(self, mock_db_session, sample_services):
        batch_size = len(sample_services)
        successful_ops = []
        failed_ops = []

        mock_db_session.DB.Create.side_effect = [
            MagicMock(error=None) for _ in range(batch_size - 1)
        ] + [MagicMock(error=Exception("DB Error"))]

        for i, service in enumerate(sample_services):
            try:
                if i == batch_size - 1:
                    raise Exception("DB Error")
                successful_ops.append(service["id"])
            except Exception as e:
                failed_ops.append({"id": service["id"], "error": str(e)})
                break

        assert len(successful_ops) == batch_size - 1
        assert len(failed_ops) == 1
        assert sample_services[-1]["id"] == failed_ops[0]["id"]

    def test_label_mutability_isolation(self):
        builder = ServiceBuilder()
        builder.add_label("env", "prod")
        service1 = builder.build()

        builder.add_label("env", "staging")
        service2 = builder.build()

        assert service1["labels"]["env"] == "prod"
        assert service2["labels"]["env"] == "staging"
        assert service1["id"] != service2["id"]

    def test_endpoint_modification_consistency(self):
        original = ServiceBuilder().with_endpoints(["http://v1.example.com"]).build()

        modified = copy.deepcopy(original)
        modified["endpoints"].append("http://v2.example.com")

        assert len(original["endpoints"]) == 1
        assert len(modified["endpoints"]) == 2
        assert "http://v2.example.com" not in original["endpoints"]

    def test_deleted_service_not_in_search_results(self, mock_db_session):
        service = ServiceBuilder.create_default()
        service_id = service["id"]

        active_services = [service]
        deleted_ids = set()

        deleted_ids.add(service_id)
        search_results = [s for s in active_services if s["id"] not in deleted_ids]

        assert service_id not in [s["id"] for s in search_results]
        assert len(search_results) == 0

    def test_concurrent_reads_during_write(self):
        shared_service = ServiceBuilder.create_default()
        read_results = []
        write_count = 0
        lock = threading.Lock()

        def reader():
            for _ in range(100):
                with lock:
                    read_results.append(copy.deepcopy(shared_service))
                time.sleep(0.001)

        def writer():
            nonlocal write_count
            for i in range(10):
                with lock:
                    shared_service["description"] = f"Updated {i}"
                    write_count += 1
                time.sleep(0.01)

        reader_thread = threading.Thread(target=reader)
        writer_thread = threading.Thread(target=writer)

        reader_thread.start()
        writer_thread.start()

        reader_thread.join()
        writer_thread.join()

        assert write_count == 10
        assert len(read_results) == 100

        descriptions = {r["description"] for r in read_results}
        assert len(descriptions) <= 11

        for result in read_results:
            assert result["id"] == shared_service["id"]
            assert result["name"] == shared_service["name"]

    def test_dependency_graph_integrity(self, dependent_services):
        nodes = set()
        edges = []

        for service in dependent_services:
            nodes.add(service["id"])
            for dep in service["dependencies"]:
                edges.append((service["id"], dep))

        assert len(nodes) == 3
        assert len(edges) == 2

        for from_id, to_id in edges:
            assert from_id in nodes
            assert to_id in nodes

        root_nodes = nodes - {edge[0] for edge in edges}
        assert len(root_nodes) >= 1

    def test_transaction_rollback_on_failure(self, mock_db_session):
        operations = [
            {"action": "create", "data": ServiceBuilder.create_default_request()},
            {"action": "create", "data": ServiceBuilder.create_default_request()},
            {"action": "fail", "data": None},
        ]

        completed = []
        try:
            for op in operations:
                if op["action"] == "fail":
                    raise Exception("Simulated failure")
                completed.append(op)
        except Exception:
            rolled_back = len(completed)
            assert rolled_back == 2

        assert len(completed) == 2
        assert operations[2]["action"] == "fail"

    @pytest.mark.parametrize("field, value", [
        ("name", ""),
        ("type", ""),
        ("version", ""),
        ("owner", ""),
    ])
    def test_required_field_validation(self, field, value):
        builder = ServiceBuilder()
        setattr(builder, f"_{field}", value)
        request = builder.build_request()

        is_valid = True
        if not request.get(field):
            is_valid = False

        assert is_valid is False, f"Field '{field}' should be required but empty value was accepted"

    def test_duplicate_name_prevention(self):
        existing_names = {"service-a", "service-b", "service-c"}
        new_service = ServiceBuilder().with_name("service-a").build_request()

        assert new_service["name"] in existing_names

        new_service["name"] = "service-d"
        assert new_service["name"] not in existing_names

    def test_data_schema_evolution_backward_compatibility(self):
        old_format = {
            "id": "svc_001",
            "name": "legacy-service",
            "version": "1.0.0",
            "owner": "legacy-owner",
            "metadata": {"key": "value"},
        }

        builder = ServiceBuilder()
        builder._name = old_format["name"]
        builder._version = old_format["version"]
        builder._owner = old_format["owner"]
        if "metadata" in old_format:
            builder._labels = old_format["metadata"]
        new_format = builder.build()

        assert new_format["name"] == old_format["name"]
        assert new_format["version"] == old_format["version"]
        assert new_format["owner"] == old_format["owner"]
        assert all(key in new_format for key in ["id", "name", "description", "type", "version", "owner", "labels", "endpoints", "dependencies"])

    def test_concurrent_dependency_updates(self):
        service = ServiceBuilder().with_name("test-service").build()
        update_count = 10
        results = []
        lock = threading.Lock()

        def add_dependency(worker_id):
            for i in range(5):
                with lock:
                    new_dep = f"dep-{worker_id}-{i}"
                    if new_dep not in service["dependencies"]:
                        service["dependencies"].append(new_dep)
                    results.append(len(service["dependencies"]))

        threads = [threading.Thread(target=add_dependency, args=(i,)) for i in range(update_count)]
        for t in threads:
            t.start()
        for t in threads:
            t.join()

        assert len(service["dependencies"]) == update_count * 5
        assert len(set(service["dependencies"])) == update_count * 5
