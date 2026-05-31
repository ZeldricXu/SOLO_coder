import pytest
from fastapi.testclient import TestClient

from src.main import app
from src.models import ServiceMetadata, Task, TaskGraph


class TestCoreAPI:
    def test_health_check(self, client):
        response = client.get("/api/v1/health")
        assert response.status_code == 200
        data = response.json()
        assert data["code"] == 200
        assert data["data"]["status"] == "healthy"

    def test_root_endpoint(self, client):
        response = client.get("/")
        assert response.status_code == 200
        data = response.json()
        assert "name" in data
        assert data["docs"] == "/docs"

    def test_create_resource(self, client, sample_entity_data):
        response = client.post("/api/v1/resources", json=sample_entity_data)
        assert response.status_code == 201
        data = response.json()
        assert data["code"] == 201
        assert "id" in data["data"]
        assert data["data"]["status"] == "pending"

    def test_create_resource_validation_error(self, client):
        response = client.post("/api/v1/resources", json={})
        assert response.status_code == 422

    def test_get_resource_status(self, client, sample_entity_data):
        create_resp = client.post("/api/v1/resources", json=sample_entity_data)
        resource_id = create_resp.json()["data"]["id"]

        status_resp = client.get(f"/api/v1/resources/{resource_id}/status")
        assert status_resp.status_code == 200
        data = status_resp.json()
        assert data["data"]["id"] == resource_id
        assert data["data"]["status"] == "pending"

    def test_get_nonexistent_resource_status(self, client):
        response = client.get("/api/v1/resources/nonexistent/status")
        assert response.status_code == 404

    def test_batch_operations(self, client, sample_entity_data):
        r1 = client.post("/api/v1/resources", json=sample_entity_data).json()["data"]["id"]
        r2 = client.post("/api/v1/resources", json=sample_entity_data).json()["data"]["id"]

        batch_request = {
            "operations": [
                {"action": "stop", "id": r1, "parameters": {}},
                {"action": "start", "id": r2, "parameters": {}},
            ]
        }

        response = client.post("/api/v1/resources/batch", json=batch_request)
        assert response.status_code == 200
        data = response.json()
        assert len(data["data"]["results"]) == 2
        assert data["data"]["results"][0]["success"] is True
        assert data["data"]["results"][1]["success"] is True

    def test_get_statistics(self, client, sample_entity_data):
        for _ in range(3):
            client.post("/api/v1/resources", json=sample_entity_data)

        response = client.get("/api/v1/statistics")
        assert response.status_code == 200
        data = response.json()
        assert data["data"]["entities"] >= 3

    def test_get_metrics(self, client):
        response = client.get("/api/v1/metrics?limit=10")
        assert response.status_code == 200
        data = response.json()
        assert "snapshots" in data["data"]


class TestSchedulerAPI:
    def test_register_task(self, client, sample_task):
        task_data = sample_task.model_dump()
        response = client.post("/api/v1/scheduler/tasks", json=task_data)
        assert response.status_code == 201
        data = response.json()
        assert data["data"]["task_id"] == sample_task.task_id

    def test_list_tasks(self, client, sample_task):
        client.post("/api/v1/scheduler/tasks", json=sample_task.model_dump())

        response = client.get("/api/v1/scheduler/tasks")
        assert response.status_code == 200
        data = response.json()
        assert len(data["data"]["tasks"]) >= 1

    def test_register_graph(self, client, sample_task_graph):
        graph_data = sample_task_graph.model_dump()
        response = client.post("/api/v1/scheduler/graphs", json=graph_data)
        assert response.status_code == 201
        data = response.json()
        assert data["data"]["graph_id"] == sample_task_graph.graph_id

    def test_list_graphs(self, client, sample_task_graph):
        client.post("/api/v1/scheduler/graphs", json=sample_task_graph.model_dump())

        response = client.get("/api/v1/scheduler/graphs")
        assert response.status_code == 200
        data = response.json()
        assert len(data["data"]["graphs"]) >= 1

    def test_get_scheduler_progress(self, client):
        response = client.get("/api/v1/scheduler/progress")
        assert response.status_code == 200
        data = response.json()
        assert "progress" in data["data"]


class TestRegistryAPI:
    def test_register_service(self, client, sample_service_metadata):
        metadata = sample_service_metadata.model_dump()
        response = client.post("/api/v1/registry/services", json=metadata)
        assert response.status_code == 201
        data = response.json()
        assert data["data"]["name"] == "test-service"

    def test_get_service(self, client, sample_service_metadata):
        metadata = sample_service_metadata.model_dump()
        create_resp = client.post("/api/v1/registry/services", json=metadata)
        service_id = create_resp.json()["data"]["service_id"]

        get_resp = client.get(f"/api/v1/registry/services/{service_id}")
        assert get_resp.status_code == 200
        assert get_resp.json()["data"]["service_id"] == service_id

    def test_get_nonexistent_service(self, client):
        response = client.get("/api/v1/registry/services/nonexistent")
        assert response.status_code == 404

    def test_unregister_service(self, client, sample_service_metadata):
        metadata = sample_service_metadata.model_dump()
        create_resp = client.post("/api/v1/registry/services", json=metadata)
        service_id = create_resp.json()["data"]["service_id"]

        delete_resp = client.delete(f"/api/v1/registry/services/{service_id}")
        assert delete_resp.status_code == 200

        get_resp = client.get(f"/api/v1/registry/services/{service_id}")
        assert get_resp.status_code == 404

    def test_search_services(self, client, clean_registry):
        services = [
            ServiceMetadata(name="order-api", type="service", language="python", tags=["api"]),
            ServiceMetadata(name="payment-api", type="service", language="go", tags=["api"]),
            ServiceMetadata(name="common-utils", type="library", language="python", tags=["utils"]),
        ]

        for svc in services:
            client.post("/api/v1/registry/services", json=svc.model_dump())

        response = client.get("/api/v1/registry/services?q=api")
        assert response.status_code == 200
        data = response.json()
        assert data["data"]["total"] == 2

        response = client.get("/api/v1/registry/services?type=library")
        assert response.json()["data"]["total"] == 1

        response = client.get("/api/v1/registry/services?tags=api")
        assert response.json()["data"]["total"] == 2

    def test_add_dependency(self, client):
        svc_a = ServiceMetadata(name="svc-a", type="service", language="python").model_dump()
        svc_b = ServiceMetadata(name="svc-b", type="service", language="python").model_dump()

        r1 = client.post("/api/v1/registry/services", json=svc_a).json()["data"]
        r2 = client.post("/api/v1/registry/services", json=svc_b).json()["data"]

        response = client.post(
            f"/api/v1/registry/services/{r1['service_id']}/dependencies/{r2['service_id']}"
        )
        assert response.status_code == 201
        assert response.json()["data"]["source_service_id"] == r1["service_id"]

    def test_get_dependencies(self, client):
        svc_a = ServiceMetadata(name="dep-a", type="service", language="python").model_dump()
        svc_b = ServiceMetadata(name="dep-b", type="service", language="python").model_dump()
        svc_c = ServiceMetadata(name="dep-c", type="service", language="python").model_dump()

        r1 = client.post("/api/v1/registry/services", json=svc_a).json()["data"]
        r2 = client.post("/api/v1/registry/services", json=svc_b).json()["data"]
        r3 = client.post("/api/v1/registry/services", json=svc_c).json()["data"]

        client.post(f"/api/v1/registry/services/{r1['service_id']}/dependencies/{r2['service_id']}")
        client.post(f"/api/v1/registry/services/{r2['service_id']}/dependencies/{r3['service_id']}")

        response = client.get(f"/api/v1/registry/services/{r1['service_id']}/dependencies")
        assert len(response.json()["data"]["dependencies"]) == 1

        response = client.get(f"/api/v1/registry/services/{r1['service_id']}/dependencies?transitive=true")
        assert len(response.json()["data"]["dependencies"]) == 2

    def test_get_dependents(self, client):
        svc_a = ServiceMetadata(name="dpt-a", type="service", language="python").model_dump()
        svc_b = ServiceMetadata(name="dpt-b", type="service", language="python").model_dump()

        r1 = client.post("/api/v1/registry/services", json=svc_a).json()["data"]
        r2 = client.post("/api/v1/registry/services", json=svc_b).json()["data"]

        client.post(f"/api/v1/registry/services/{r1['service_id']}/dependencies/{r2['service_id']}")

        response = client.get(f"/api/v1/registry/services/{r2['service_id']}/dependents")
        assert len(response.json()["data"]["dependents"]) == 1
        assert response.json()["data"]["dependents"][0]["service_id"] == r1["service_id"]

    def test_get_mermaid_diagram(self, client):
        svc_a = ServiceMetadata(name="dia-a", type="service", language="python").model_dump()
        svc_b = ServiceMetadata(name="dia-b", type="library", language="python").model_dump()

        r1 = client.post("/api/v1/registry/services", json=svc_a).json()["data"]
        r2 = client.post("/api/v1/registry/services", json=svc_b).json()["data"]

        client.post(f"/api/v1/registry/services/{r1['service_id']}/dependencies/{r2['service_id']}")

        response = client.get("/api/v1/registry/graph/diagram?format=mermaid")
        assert response.status_code == 200
        assert "graph TD" in response.json()["data"]["diagram"]

    def test_get_dot_diagram(self, client):
        response = client.get("/api/v1/registry/graph/diagram?format=dot")
        assert response.status_code == 200
        assert "digraph" in response.json()["data"]["diagram"]

    def test_invalid_diagram_format(self, client):
        response = client.get("/api/v1/registry/graph/diagram?format=invalid")
        assert response.status_code == 400

    def test_get_topological_order(self, client):
        svc_a = ServiceMetadata(name="topo-a", type="service", language="python").model_dump()
        svc_b = ServiceMetadata(name="topo-b", type="service", language="python").model_dump()

        r1 = client.post("/api/v1/registry/services", json=svc_a).json()["data"]
        r2 = client.post("/api/v1/registry/services", json=svc_b).json()["data"]

        client.post(f"/api/v1/registry/services/{r1['service_id']}/dependencies/{r2['service_id']}")

        response = client.get("/api/v1/registry/graph/topological")
        assert response.status_code == 200
        order = response.json()["data"]["order"]
        assert order.index(r1["service_id"]) < order.index(r2["service_id"])

    def test_detect_cycles(self, client):
        response = client.get("/api/v1/registry/graph/cycles")
        assert response.status_code == 200
        data = response.json()["data"]
        assert "has_cycles" in data

    def test_get_registry_statistics(self, client):
        for i in range(3):
            svc = ServiceMetadata(
                name=f"stat-{i}",
                type="service" if i < 2 else "library",
                language="python" if i < 2 else "go",
            ).model_dump()
            client.post("/api/v1/registry/services", json=svc)

        response = client.get("/api/v1/registry/statistics")
        assert response.status_code == 200
        data = response.json()["data"]
        assert data["total_services"] >= 3
        assert "by_type" in data
        assert "by_language" in data

    def test_export_registry(self, client, temp_dir):
        export_path = f"{temp_dir}/export.json"
        response = client.post(f"/api/v1/registry/export?path={export_path}")
        assert response.status_code == 200
        assert response.json()["data"]["export_path"] == export_path
