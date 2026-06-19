import uuid
from datetime import datetime, timedelta

import pytest
import pytest_asyncio
from httpx import ASGITransport, AsyncClient
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine

from etl_engine.db.session import get_session
from etl_engine.main import app
from etl_engine.models import Base, DataSource, Pipeline, PipelineExecution, TaskExecution


SAMPLE_DAG_DEFINITION = {
    "nodes": [
        {"id": "extract_mysql", "type": "extract", "config": {"source_type": "mysql"}, "dependencies": []},
        {"id": "transform", "type": "transform", "config": {"sql": "SELECT * FROM input"}, "dependencies": ["extract_mysql"]},
        {"id": "quality_check", "type": "quality_check", "config": {"rules": []}, "dependencies": ["transform"]},
        {"id": "load_clickhouse", "type": "load", "config": {"target_type": "clickhouse"}, "dependencies": ["quality_check"]},
    ],
    "edges": [
        {"source": "extract_mysql", "target": "transform", "data_mapping": {}},
        {"source": "transform", "target": "quality_check", "data_mapping": {}},
        {"source": "quality_check", "target": "load_clickhouse", "data_mapping": {}},
    ],
    "schedule": "0 * * * *",
    "sla_seconds": 3600,
}

SAMPLE_QUALITY_RULES = [
    {"rule_type": "null_rate", "column": "name", "params": {"max_null_rate": 0.05}, "threshold": 1.0, "strategy": "alert"},
    {"rule_type": "uniqueness", "column": "id", "params": {"expect_unique": True}, "threshold": 1.0, "strategy": "alert"},
]


@pytest_asyncio.fixture
async def async_client():
    engine = create_async_engine("sqlite+aiosqlite:///:memory:")
    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)
    session_factory = async_sessionmaker(engine, class_=AsyncSession, expire_on_commit=False)

    async def override_get_session():
        async with session_factory() as session:
            yield session

    from etl_engine.api.alerts_api import _stored_rules, _channel_configs
    from etl_engine.api.quality import _pipeline_rules, _quality_reports

    _stored_rules.clear()
    _channel_configs.clear()
    _pipeline_rules.clear()
    _quality_reports.clear()

    app.dependency_overrides[get_session] = override_get_session
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        yield client
    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.drop_all)
    await engine.dispose()
    app.dependency_overrides.clear()
    _stored_rules.clear()
    _channel_configs.clear()
    _pipeline_rules.clear()
    _quality_reports.clear()


@pytest.mark.asyncio
class TestHealthAndMetricsEndpoints:

    async def test_health_endpoint(self, async_client: AsyncClient):
        response = await async_client.get("/health")
        assert response.status_code == 200
        data = response.json()
        assert data["status"] == "ok"
        assert "version" in data

    async def test_metrics_endpoint(self, async_client: AsyncClient):
        response = await async_client.get("/metrics")
        assert response.status_code == 200
        assert isinstance(response.text, str)


@pytest.mark.asyncio
class TestDataSourceEndpoints:

    async def test_create_source(self, async_client: AsyncClient):
        payload = {
            "name": "test-mysql-src",
            "type": "mysql",
            "connection_config": {"host": "localhost", "port": 3306, "user": "etl"},
        }
        response = await async_client.post("/api/sources", json=payload)
        assert response.status_code == 201
        data = response.json()
        assert "id" in data
        assert data["name"] == "test-mysql-src"
        assert data["type"] == "mysql"
        assert data["is_active"] is True
        assert data["pool_size"] == 5

    async def test_get_source(self, async_client: AsyncClient):
        create_payload = {
            "name": "get-test-src",
            "type": "postgresql",
            "connection_config": {"host": "db.example.com", "database": "analytics"},
        }
        create_resp = await async_client.post("/api/sources", json=create_payload)
        assert create_resp.status_code == 201
        source_id = create_resp.json()["id"]

        response = await async_client.get(f"/api/sources/{source_id}")
        assert response.status_code == 200
        data = response.json()
        assert data["id"] == source_id
        assert data["name"] == "get-test-src"
        assert data["type"] == "postgresql"
        assert "created_at" in data
        assert "updated_at" in data

    async def test_list_sources(self, async_client: AsyncClient):
        for i in range(3):
            payload = {
                "name": f"list-src-{i}",
                "type": "mysql" if i % 2 == 0 else "postgresql",
                "connection_config": {},
            }
            await async_client.post("/api/sources", json=payload)

        response = await async_client.get("/api/sources")
        assert response.status_code == 200
        data = response.json()
        assert isinstance(data, list)
        assert len(data) == 3

    async def test_list_sources_with_type_filter(self, async_client: AsyncClient):
        for i in range(4):
            payload = {
                "name": f"filter-src-{i}",
                "type": "mysql" if i < 2 else "mongodb",
                "connection_config": {},
            }
            await async_client.post("/api/sources", json=payload)

        response = await async_client.get("/api/sources", params={"type": "mysql"})
        assert response.status_code == 200
        data = response.json()
        assert len(data) == 2
        for item in data:
            assert item["type"] == "mysql"

    async def test_update_source(self, async_client: AsyncClient):
        create_payload = {
            "name": "update-src-original",
            "type": "s3",
            "connection_config": {"bucket": "old-bucket"},
            "pool_size": 3,
        }
        create_resp = await async_client.post("/api/sources", json=create_payload)
        source_id = create_resp.json()["id"]

        update_payload = {
            "name": "update-src-modified",
            "connection_config": {"bucket": "new-bucket", "region": "us-east-1"},
            "pool_size": 10,
        }
        response = await async_client.put(f"/api/sources/{source_id}", json=update_payload)
        assert response.status_code == 200
        data = response.json()
        assert data["id"] == source_id
        assert data["name"] == "update-src-modified"
        assert data["connection_config"]["bucket"] == "new-bucket"
        assert data["connection_config"]["region"] == "us-east-1"
        assert data["pool_size"] == 10

    async def test_delete_source(self, async_client: AsyncClient):
        create_payload = {
            "name": "delete-src",
            "type": "kafka",
            "connection_config": {"brokers": "localhost:9092"},
        }
        create_resp = await async_client.post("/api/sources", json=create_payload)
        source_id = create_resp.json()["id"]

        delete_resp = await async_client.delete(f"/api/sources/{source_id}")
        assert delete_resp.status_code == 204

        get_resp = await async_client.get(f"/api/sources/{source_id}")
        assert get_resp.status_code == 404

    async def test_delete_nonexistent_source(self, async_client: AsyncClient):
        fake_id = str(uuid.uuid4())
        response = await async_client.delete(f"/api/sources/{fake_id}")
        assert response.status_code == 404

    async def test_test_source_connection(self, async_client: AsyncClient):
        create_payload = {
            "name": "test-conn-src",
            "type": "mysql",
            "connection_config": {"host": "localhost", "port": 3306},
        }
        create_resp = await async_client.post("/api/sources", json=create_payload)
        source_id = create_resp.json()["id"]

        response = await async_client.post(f"/api/sources/{source_id}/test")
        assert response.status_code == 200
        data = response.json()
        assert "success" in data
        assert "message" in data
        assert isinstance(data["success"], bool)

    async def test_get_source_not_found(self, async_client: AsyncClient):
        fake_id = str(uuid.uuid4())
        response = await async_client.get(f"/api/sources/{fake_id}")
        assert response.status_code == 404
        assert "not found" in response.json()["detail"].lower()

    async def test_create_source_duplicate_name(self, async_client: AsyncClient):
        payload = {
            "name": "duplicate-src",
            "type": "mysql",
            "connection_config": {},
        }
        await async_client.post("/api/sources", json=payload)
        response = await async_client.post("/api/sources", json=payload)
        assert response.status_code == 409


@pytest.mark.asyncio
class TestPipelineEndpoints:

    async def test_create_pipeline(self, async_client: AsyncClient):
        payload = {
            "name": "test-create-pipeline",
            "description": "Test pipeline for creation",
            "dag_definition": SAMPLE_DAG_DEFINITION,
            "schedule": "0 * * * *",
            "is_active": True,
            "max_retries": 2,
            "retry_delay_seconds": 120,
            "timeout_seconds": 7200,
            "sla_seconds": 1800,
        }
        response = await async_client.post("/api/pipelines", json=payload)
        assert response.status_code == 201
        data = response.json()
        assert "id" in data
        assert data["name"] == "test-create-pipeline"
        assert data["description"] == "Test pipeline for creation"
        assert data["is_active"] is True
        assert data["max_retries"] == 2
        assert data["dag_definition"]["nodes"][0]["id"] == "extract_mysql"

    async def test_list_pipelines(self, async_client: AsyncClient):
        for i in range(2):
            payload = {
                "name": f"list-pipeline-{i}",
                "dag_definition": SAMPLE_DAG_DEFINITION,
            }
            await async_client.post("/api/pipelines", json=payload)

        response = await async_client.get("/api/pipelines")
        assert response.status_code == 200
        data = response.json()
        assert isinstance(data, list)
        assert len(data) == 2

    async def test_get_pipeline_detail(self, async_client: AsyncClient):
        create_payload = {
            "name": "detail-pipeline",
            "description": "Pipeline for detail test",
            "dag_definition": SAMPLE_DAG_DEFINITION,
        }
        create_resp = await async_client.post("/api/pipelines", json=create_payload)
        pipeline_id = create_resp.json()["id"]

        response = await async_client.get(f"/api/pipelines/{pipeline_id}")
        assert response.status_code == 200
        data = response.json()
        assert data["id"] == pipeline_id
        assert data["name"] == "detail-pipeline"
        assert data["dag_definition"] is not None
        assert len(data["dag_definition"]["nodes"]) == 4
        assert len(data["dag_definition"]["edges"]) == 3
        assert "created_at" in data

    async def test_trigger_pipeline(self, async_client: AsyncClient):
        create_payload = {
            "name": "trigger-pipeline",
            "dag_definition": SAMPLE_DAG_DEFINITION,
            "is_active": True,
        }
        create_resp = await async_client.post("/api/pipelines", json=create_payload)
        pipeline_id = create_resp.json()["id"]

        response = await async_client.post(f"/api/pipelines/{pipeline_id}/trigger")
        assert response.status_code == 200
        data = response.json()
        assert "pipeline_id" in data
        assert data["pipeline_id"] == pipeline_id
        assert "message" in data

    async def test_trigger_inactive_pipeline(self, async_client: AsyncClient):
        create_payload = {
            "name": "inactive-trigger-pipeline",
            "dag_definition": SAMPLE_DAG_DEFINITION,
            "is_active": False,
        }
        create_resp = await async_client.post("/api/pipelines", json=create_payload)
        pipeline_id = create_resp.json()["id"]

        response = await async_client.post(f"/api/pipelines/{pipeline_id}/trigger")
        assert response.status_code == 400

    async def test_get_pipeline_dependencies(self, async_client: AsyncClient):
        create_payload = {
            "name": "deps-pipeline",
            "dag_definition": SAMPLE_DAG_DEFINITION,
        }
        create_resp = await async_client.post("/api/pipelines", json=create_payload)
        pipeline_id = create_resp.json()["id"]

        response = await async_client.get(f"/api/pipelines/{pipeline_id}/dependencies")
        assert response.status_code == 200
        data = response.json()
        assert "nodes" in data
        assert "edges" in data
        assert "execution_order" in data
        assert isinstance(data["nodes"], list)
        assert isinstance(data["edges"], list)
        assert isinstance(data["execution_order"], list)
        assert len(data["nodes"]) == 4
        assert len(data["edges"]) == 3

    async def test_update_pipeline(self, async_client: AsyncClient):
        create_payload = {
            "name": "update-pipeline-orig",
            "description": "Original description",
            "dag_definition": SAMPLE_DAG_DEFINITION,
            "schedule": "0 * * * *",
        }
        create_resp = await async_client.post("/api/pipelines", json=create_payload)
        pipeline_id = create_resp.json()["id"]

        update_payload = {
            "description": "Updated description",
            "schedule": "0 0 * * *",
            "max_retries": 5,
            "timeout_seconds": 14400,
        }
        response = await async_client.put(f"/api/pipelines/{pipeline_id}", json=update_payload)
        assert response.status_code == 200
        data = response.json()
        assert data["id"] == pipeline_id
        assert data["description"] == "Updated description"
        assert data["schedule"] == "0 0 * * *"
        assert data["max_retries"] == 5
        assert data["timeout_seconds"] == 14400

    async def test_delete_pipeline(self, async_client: AsyncClient):
        create_payload = {
            "name": "delete-pipeline",
            "dag_definition": SAMPLE_DAG_DEFINITION,
        }
        create_resp = await async_client.post("/api/pipelines", json=create_payload)
        pipeline_id = create_resp.json()["id"]

        delete_resp = await async_client.delete(f"/api/pipelines/{pipeline_id}")
        assert delete_resp.status_code == 204

        get_resp = await async_client.get(f"/api/pipelines/{pipeline_id}")
        assert get_resp.status_code == 404

    async def test_get_pipeline_not_found(self, async_client: AsyncClient):
        fake_id = str(uuid.uuid4())
        response = await async_client.get(f"/api/pipelines/{fake_id}")
        assert response.status_code == 404

    async def test_create_pipeline_duplicate_name(self, async_client: AsyncClient):
        payload = {
            "name": "duplicate-pipeline",
            "dag_definition": SAMPLE_DAG_DEFINITION,
        }
        await async_client.post("/api/pipelines", json=payload)
        response = await async_client.post("/api/pipelines", json=payload)
        assert response.status_code == 409


@pytest.mark.asyncio
class TestExecutionEndpoints:

    async def _create_pipeline_and_execution(
        self, async_client: AsyncClient, status: str = "pending", trigger_type: str = "manual"
    ) -> tuple[str, str]:
        create_payload = {
            "name": f"exec-pipeline-{uuid.uuid4().hex[:8]}",
            "dag_definition": SAMPLE_DAG_DEFINITION,
        }
        create_resp = await async_client.post("/api/pipelines", json=create_payload)
        pipeline_id = create_resp.json()["id"]

        engine = create_async_engine("sqlite+aiosqlite:///:memory:")
        session_factory = async_sessionmaker(engine, class_=AsyncSession, expire_on_commit=False)
        async with session_factory() as session:
            pass
        await engine.dispose()

        for dep_key, dep_value in app.dependency_overrides.items():
            if dep_key == get_session:
                gen = dep_value()
                session = await anext(gen)
                execution = PipelineExecution(
                    pipeline_id=uuid.UUID(pipeline_id),
                    status=status,
                    trigger_type=trigger_type,
                )
                session.add(execution)
                await session.commit()
                await session.refresh(execution)
                execution_id = str(execution.id)
                try:
                    await anext(gen)
                except StopAsyncIteration:
                    pass
                return pipeline_id, execution_id

    async def test_list_executions(self, async_client: AsyncClient):
        pipeline_ids = []
        for i in range(2):
            create_payload = {
                "name": f"list-exec-pipeline-{i}",
                "dag_definition": SAMPLE_DAG_DEFINITION,
            }
            create_resp = await async_client.post("/api/pipelines", json=create_payload)
            pipeline_ids.append(create_resp.json()["id"])

        for dep_key, dep_value in app.dependency_overrides.items():
            if dep_key == get_session:
                gen = dep_value()
                session = await anext(gen)
                for pid in pipeline_ids:
                    for status in ["running", "success"]:
                        exec_obj = PipelineExecution(
                            pipeline_id=uuid.UUID(pid),
                            status=status,
                            trigger_type="manual",
                        )
                        session.add(exec_obj)
                await session.commit()
                try:
                    await anext(gen)
                except StopAsyncIteration:
                    pass

        response = await async_client.get("/api/executions")
        assert response.status_code == 200
        data = response.json()
        assert isinstance(data, list)
        assert len(data) >= 2

    async def test_get_execution_detail(self, async_client: AsyncClient):
        create_payload = {
            "name": "detail-exec-pipeline",
            "dag_definition": SAMPLE_DAG_DEFINITION,
        }
        create_resp = await async_client.post("/api/pipelines", json=create_payload)
        pipeline_id = create_resp.json()["id"]

        execution_id = None
        for dep_key, dep_value in app.dependency_overrides.items():
            if dep_key == get_session:
                gen = dep_value()
                session = await anext(gen)
                exec_obj = PipelineExecution(
                    pipeline_id=uuid.UUID(pipeline_id),
                    status="running",
                    trigger_type="scheduled",
                    started_at=datetime.utcnow(),
                )
                session.add(exec_obj)
                await session.commit()
                await session.refresh(exec_obj)
                execution_id = str(exec_obj.id)
                try:
                    await anext(gen)
                except StopAsyncIteration:
                    pass

        assert execution_id is not None
        response = await async_client.get(f"/api/executions/{execution_id}")
        assert response.status_code == 200
        data = response.json()
        assert data["id"] == execution_id
        assert data["pipeline_id"] == pipeline_id
        assert data["status"] == "running"
        assert data["trigger_type"] == "scheduled"
        assert "started_at" in data
        assert "created_at" in data

    async def test_get_execution_tasks(self, async_client: AsyncClient):
        create_payload = {
            "name": "tasks-exec-pipeline",
            "dag_definition": SAMPLE_DAG_DEFINITION,
        }
        create_resp = await async_client.post("/api/pipelines", json=create_payload)
        pipeline_id = create_resp.json()["id"]

        execution_id = None
        for dep_key, dep_value in app.dependency_overrides.items():
            if dep_key == get_session:
                gen = dep_value()
                session = await anext(gen)
                exec_obj = PipelineExecution(
                    pipeline_id=uuid.UUID(pipeline_id),
                    status="running",
                    trigger_type="manual",
                )
                session.add(exec_obj)
                await session.commit()
                await session.refresh(exec_obj)
                execution_id = str(exec_obj.id)

                task_names = ["extract_mysql", "transform", "quality_check", "load_clickhouse"]
                task_types = ["extract", "transform", "quality_check", "load"]
                for idx, (tname, ttype) in enumerate(zip(task_names, task_types)):
                    task = TaskExecution(
                        pipeline_id=uuid.UUID(pipeline_id),
                        task_name=tname,
                        task_type=ttype,
                        status="success" if idx < 2 else "running",
                        input_rows=1000 if idx > 0 else None,
                        output_rows=1000 if idx > 0 and idx < 3 else None,
                        retry_count=0,
                    )
                    session.add(task)
                await session.commit()
                try:
                    await anext(gen)
                except StopAsyncIteration:
                    pass

        assert execution_id is not None
        response = await async_client.get(f"/api/executions/{execution_id}/tasks")
        assert response.status_code == 200
        data = response.json()
        assert isinstance(data, list)
        assert len(data) == 4
        task_names_found = [t["task_name"] for t in data]
        assert "extract_mysql" in task_names_found
        assert "transform" in task_names_found

    async def test_cancel_execution(self, async_client: AsyncClient):
        create_payload = {
            "name": "cancel-exec-pipeline",
            "dag_definition": SAMPLE_DAG_DEFINITION,
        }
        create_resp = await async_client.post("/api/pipelines", json=create_payload)
        pipeline_id = create_resp.json()["id"]

        execution_id = None
        for dep_key, dep_value in app.dependency_overrides.items():
            if dep_key == get_session:
                gen = dep_value()
                session = await anext(gen)
                exec_obj = PipelineExecution(
                    pipeline_id=uuid.UUID(pipeline_id),
                    status="running",
                    trigger_type="manual",
                )
                session.add(exec_obj)
                await session.commit()
                await session.refresh(exec_obj)
                execution_id = str(exec_obj.id)
                try:
                    await anext(gen)
                except StopAsyncIteration:
                    pass

        assert execution_id is not None
        response = await async_client.post(f"/api/executions/{execution_id}/cancel")
        assert response.status_code == 200
        data = response.json()
        assert data["execution_id"] == execution_id
        assert data["status"] == "cancelled"
        assert "message" in data

        verify_resp = await async_client.get(f"/api/executions/{execution_id}")
        assert verify_resp.status_code == 200
        assert verify_resp.json()["status"] == "cancelled"

    async def test_cancel_completed_execution_fails(self, async_client: AsyncClient):
        create_payload = {
            "name": "cancel-completed-pipeline",
            "dag_definition": SAMPLE_DAG_DEFINITION,
        }
        create_resp = await async_client.post("/api/pipelines", json=create_payload)
        pipeline_id = create_resp.json()["id"]

        execution_id = None
        for dep_key, dep_value in app.dependency_overrides.items():
            if dep_key == get_session:
                gen = dep_value()
                session = await anext(gen)
                exec_obj = PipelineExecution(
                    pipeline_id=uuid.UUID(pipeline_id),
                    status="success",
                    trigger_type="manual",
                )
                session.add(exec_obj)
                await session.commit()
                await session.refresh(exec_obj)
                execution_id = str(exec_obj.id)
                try:
                    await anext(gen)
                except StopAsyncIteration:
                    pass

        assert execution_id is not None
        response = await async_client.post(f"/api/executions/{execution_id}/cancel")
        assert response.status_code == 400

    async def test_retry_execution(self, async_client: AsyncClient):
        create_payload = {
            "name": "retry-exec-pipeline",
            "dag_definition": SAMPLE_DAG_DEFINITION,
        }
        create_resp = await async_client.post("/api/pipelines", json=create_payload)
        pipeline_id = create_resp.json()["id"]

        execution_id = None
        for dep_key, dep_value in app.dependency_overrides.items():
            if dep_key == get_session:
                gen = dep_value()
                session = await anext(gen)
                exec_obj = PipelineExecution(
                    pipeline_id=uuid.UUID(pipeline_id),
                    status="failed",
                    trigger_type="manual",
                    error_message="Original error",
                )
                session.add(exec_obj)
                await session.commit()
                await session.refresh(exec_obj)
                execution_id = str(exec_obj.id)
                try:
                    await anext(gen)
                except StopAsyncIteration:
                    pass

        assert execution_id is not None
        response = await async_client.post(f"/api/executions/{execution_id}/retry")
        assert response.status_code == 200
        data = response.json()
        assert data["execution_id"] == execution_id
        assert "new_execution_id" in data
        assert data["new_execution_id"] is not None
        assert "message" in data

        new_exec_resp = await async_client.get(f"/api/executions/{data['new_execution_id']}")
        assert new_exec_resp.status_code == 200
        new_exec_data = new_exec_resp.json()
        assert new_exec_data["status"] == "pending"
        assert new_exec_data["trigger_type"] == "retry"

    async def test_retry_running_execution_fails(self, async_client: AsyncClient):
        create_payload = {
            "name": "retry-running-pipeline",
            "dag_definition": SAMPLE_DAG_DEFINITION,
        }
        create_resp = await async_client.post("/api/pipelines", json=create_payload)
        pipeline_id = create_resp.json()["id"]

        execution_id = None
        for dep_key, dep_value in app.dependency_overrides.items():
            if dep_key == get_session:
                gen = dep_value()
                session = await anext(gen)
                exec_obj = PipelineExecution(
                    pipeline_id=uuid.UUID(pipeline_id),
                    status="running",
                    trigger_type="manual",
                )
                session.add(exec_obj)
                await session.commit()
                await session.refresh(exec_obj)
                execution_id = str(exec_obj.id)
                try:
                    await anext(gen)
                except StopAsyncIteration:
                    pass

        assert execution_id is not None
        response = await async_client.post(f"/api/executions/{execution_id}/retry")
        assert response.status_code == 400

    async def test_get_execution_not_found(self, async_client: AsyncClient):
        fake_id = str(uuid.uuid4())
        response = await async_client.get(f"/api/executions/{fake_id}")
        assert response.status_code == 404

    async def test_list_executions_filter_by_status(self, async_client: AsyncClient):
        create_payload = {
            "name": "filter-exec-pipeline",
            "dag_definition": SAMPLE_DAG_DEFINITION,
        }
        create_resp = await async_client.post("/api/pipelines", json=create_payload)
        pipeline_id = create_resp.json()["id"]

        for dep_key, dep_value in app.dependency_overrides.items():
            if dep_key == get_session:
                gen = dep_value()
                session = await anext(gen)
                for status in ["pending", "running", "success", "success"]:
                    exec_obj = PipelineExecution(
                        pipeline_id=uuid.UUID(pipeline_id),
                        status=status,
                        trigger_type="manual",
                    )
                    session.add(exec_obj)
                await session.commit()
                try:
                    await anext(gen)
                except StopAsyncIteration:
                    pass

        response = await async_client.get("/api/executions", params={"status": "success"})
        assert response.status_code == 200
        data = response.json()
        assert len(data) == 2
        for item in data:
            assert item["status"] == "success"


@pytest.mark.asyncio
class TestQualityEndpoints:

    async def _create_pipeline(self, async_client: AsyncClient) -> str:
        payload = {
            "name": f"quality-pipeline-{uuid.uuid4().hex[:8]}",
            "dag_definition": SAMPLE_DAG_DEFINITION,
        }
        resp = await async_client.post("/api/pipelines", json=payload)
        return resp.json()["id"]

    async def test_create_quality_rules(self, async_client: AsyncClient):
        pipeline_id = await self._create_pipeline(async_client)
        payload = {
            "pipeline_id": pipeline_id,
            "rules": SAMPLE_QUALITY_RULES,
        }
        response = await async_client.post("/api/quality/rules", json=payload)
        assert response.status_code == 200
        data = response.json()
        assert data["pipeline_id"] == pipeline_id
        assert isinstance(data["rules"], list)
        assert len(data["rules"]) == 2
        assert data["rules"][0]["rule_type"] == "null_rate"

    async def test_get_quality_rules(self, async_client: AsyncClient):
        pipeline_id = await self._create_pipeline(async_client)
        create_payload = {
            "pipeline_id": pipeline_id,
            "rules": SAMPLE_QUALITY_RULES,
        }
        await async_client.post("/api/quality/rules", json=create_payload)

        response = await async_client.get("/api/quality/rules", params={"pipeline_id": pipeline_id})
        assert response.status_code == 200
        data = response.json()
        assert data["pipeline_id"] == pipeline_id
        assert len(data["rules"]) == 2

    async def test_get_quality_rules_empty(self, async_client: AsyncClient):
        pipeline_id = await self._create_pipeline(async_client)
        response = await async_client.get("/api/quality/rules", params={"pipeline_id": pipeline_id})
        assert response.status_code == 200
        data = response.json()
        assert data["rules"] == []

    async def test_run_quality_validation(self, async_client: AsyncClient):
        pipeline_id = await self._create_pipeline(async_client)
        create_payload = {
            "pipeline_id": pipeline_id,
            "rules": SAMPLE_QUALITY_RULES,
        }
        await async_client.post("/api/quality/rules", json=create_payload)

        validate_payload = {
            "pipeline_id": pipeline_id,
        }
        response = await async_client.post("/api/quality/validate", json=validate_payload)
        assert response.status_code == 200
        data = response.json()
        assert "id" in data
        assert data["pipeline_id"] == pipeline_id
        assert "passed" in data
        assert "total_rules" in data
        assert "passed_rules" in data
        assert "failed_rules" in data
        assert "blocked" in data
        assert "summary" in data
        assert "created_at" in data

    async def test_run_quality_validation_no_rules(self, async_client: AsyncClient):
        pipeline_id = await self._create_pipeline(async_client)
        validate_payload = {
            "pipeline_id": pipeline_id,
        }
        response = await async_client.post("/api/quality/validate", json=validate_payload)
        assert response.status_code == 400

    async def test_create_quality_rules_nonexistent_pipeline(self, async_client: AsyncClient):
        fake_pipeline_id = str(uuid.uuid4())
        payload = {
            "pipeline_id": fake_pipeline_id,
            "rules": SAMPLE_QUALITY_RULES,
        }
        response = await async_client.post("/api/quality/rules", json=payload)
        assert response.status_code == 404


@pytest.mark.asyncio
class TestMetadataEndpoints:

    async def _setup_data(self, async_client: AsyncClient) -> tuple[str, str]:
        source_payload = {
            "name": f"meta-src-{uuid.uuid4().hex[:8]}",
            "type": "mysql",
            "connection_config": {"host": "localhost", "port": 3306},
        }
        source_resp = await async_client.post("/api/sources", json=source_payload)
        source_id = source_resp.json()["id"]

        pipeline_payload = {
            "name": f"meta-pipeline-{uuid.uuid4().hex[:8]}",
            "description": "Metadata test pipeline",
            "dag_definition": SAMPLE_DAG_DEFINITION,
            "schedule": "0 * * * *",
        }
        pipeline_resp = await async_client.post("/api/pipelines", json=pipeline_payload)
        pipeline_id = pipeline_resp.json()["id"]

        for dep_key, dep_value in app.dependency_overrides.items():
            if dep_key == get_session:
                gen = dep_value()
                session = await anext(gen)
                now = datetime.utcnow()
                exec_statuses = ["success", "failed", "running"]
                for i, status in enumerate(exec_statuses):
                    started = now - timedelta(hours=i + 1)
                    finished = started + timedelta(minutes=30) if status != "running" else None
                    exec_obj = PipelineExecution(
                        pipeline_id=uuid.UUID(pipeline_id),
                        status=status,
                        trigger_type="scheduled" if i == 0 else "manual",
                        started_at=started,
                        finished_at=finished,
                        total_rows_read=1000 + i * 100 if finished else None,
                        total_rows_written=900 + i * 100 if finished else None,
                        quality_passed=True if status == "success" else None,
                    )
                    session.add(exec_obj)
                await session.commit()
                try:
                    await anext(gen)
                except StopAsyncIteration:
                    pass

        return source_id, pipeline_id

    async def test_metadata_list_sources_with_schema(self, async_client: AsyncClient):
        await self._setup_data(async_client)
        response = await async_client.get("/api/metadata/sources")
        assert response.status_code == 200
        data = response.json()
        assert isinstance(data, list)
        assert len(data) >= 1
        for item in data:
            assert "id" in item
            assert "name" in item
            assert "type" in item
            assert "is_active" in item

    async def test_metadata_pipelines_graph(self, async_client: AsyncClient):
        _, pipeline_id = await self._setup_data(async_client)
        response = await async_client.get(f"/api/metadata/pipelines/{pipeline_id}/graph")
        assert response.status_code == 200
        data = response.json()
        assert data["pipeline_id"] == pipeline_id
        assert "pipeline_name" in data
        assert "nodes" in data
        assert "edges" in data
        assert "execution_order" in data
        assert len(data["nodes"]) == 4

    async def test_metadata_pipelines_graph_not_found(self, async_client: AsyncClient):
        fake_id = str(uuid.uuid4())
        response = await async_client.get(f"/api/metadata/pipelines/{fake_id}/graph")
        assert response.status_code == 404

    async def test_metadata_latest_status(self, async_client: AsyncClient):
        await self._setup_data(async_client)
        response = await async_client.get("/api/metadata/status")
        assert response.status_code == 200
        data = response.json()
        assert isinstance(data, list)
        assert len(data) >= 1
        for item in data:
            assert "pipeline_id" in item
            assert "pipeline_name" in item
            assert "latest_status" in item
            assert "latest_execution_id" in item
            assert "last_run_at" in item

    async def test_metadata_history(self, async_client: AsyncClient):
        _, pipeline_id = await self._setup_data(async_client)
        response = await async_client.get("/api/metadata/history")
        assert response.status_code == 200
        data = response.json()
        assert isinstance(data, list)
        assert len(data) >= 3
        for item in data:
            assert "id" in item
            assert "status" in item
            assert "trigger_type" in item
            assert "created_at" in item

    async def test_metadata_history_with_filters(self, async_client: AsyncClient):
        _, pipeline_id = await self._setup_data(async_client)
        response = await async_client.get(
            "/api/metadata/history",
            params={"pipeline_id": pipeline_id, "status": "success"},
        )
        assert response.status_code == 200
        data = response.json()
        assert isinstance(data, list)
        assert len(data) >= 1
        for item in data:
            assert item["status"] == "success"

    async def test_metadata_history_with_date_filters(self, async_client: AsyncClient):
        _, pipeline_id = await self._setup_data(async_client)
        now = datetime.utcnow()
        start = (now - timedelta(days=1)).isoformat()
        end = now.isoformat()
        response = await async_client.get(
            "/api/metadata/history",
            params={"start_date": start, "end_date": end},
        )
        assert response.status_code == 200
        data = response.json()
        assert isinstance(data, list)

    async def test_metadata_stats(self, async_client: AsyncClient):
        await self._setup_data(async_client)
        response = await async_client.get("/api/metadata/stats")
        assert response.status_code == 200
        data = response.json()
        assert "total_pipelines" in data
        assert "total_executions" in data
        assert "success_rate" in data
        assert "avg_duration_seconds" in data
        assert data["total_pipelines"] >= 1
        assert data["total_executions"] >= 3
        assert isinstance(data["success_rate"], float)
        assert 0.0 <= data["success_rate"] <= 1.0

    async def test_metadata_list_pipelines_with_deps(self, async_client: AsyncClient):
        await self._setup_data(async_client)
        response = await async_client.get("/api/metadata/pipelines")
        assert response.status_code == 200
        data = response.json()
        assert isinstance(data, list)
        assert len(data) >= 1
        for item in data:
            assert "id" in item
            assert "dependencies" in item
            assert "nodes" in item["dependencies"]
            assert "edges" in item["dependencies"]


@pytest.mark.asyncio
class TestAlertsEndpoints:

    async def test_list_alert_rules_empty(self, async_client: AsyncClient):
        response = await async_client.get("/api/alerts/rules")
        assert response.status_code == 200
        data = response.json()
        assert isinstance(data, list)
        assert len(data) == 0

    async def test_create_alert_rule(self, async_client: AsyncClient):
        payload = {
            "alert_type": "task_failure",
            "channels": ["email", "slack"],
            "min_severity": "warning",
            "cooldown_minutes": 30,
            "enabled": True,
        }
        response = await async_client.post("/api/alerts/rules", json=payload)
        assert response.status_code == 201
        data = response.json()
        assert "id" in data
        assert data["alert_type"] == "task_failure"
        assert data["channels"] == ["email", "slack"]
        assert data["min_severity"] == "warning"
        assert data["cooldown_minutes"] == 30
        assert data["enabled"] is True
        assert "created_at" in data

    async def test_create_alert_rule_quality_degradation(self, async_client: AsyncClient):
        payload = {
            "alert_type": "quality_degradation",
            "channels": ["pagerduty"],
            "min_severity": "error",
            "cooldown_minutes": 60,
            "enabled": True,
        }
        response = await async_client.post("/api/alerts/rules", json=payload)
        assert response.status_code == 201
        data = response.json()
        assert data["alert_type"] == "quality_degradation"
        assert data["channels"] == ["pagerduty"]
        assert data["min_severity"] == "error"

    async def test_list_alert_rules(self, async_client: AsyncClient):
        rule_types = ["task_failure", "quality_degradation", "sla_timeout"]
        for rtype in rule_types:
            payload = {
                "alert_type": rtype,
                "channels": ["email"],
                "min_severity": "warning",
                "cooldown_minutes": 15,
                "enabled": True,
            }
            await async_client.post("/api/alerts/rules", json=payload)

        response = await async_client.get("/api/alerts/rules")
        assert response.status_code == 200
        data = response.json()
        assert isinstance(data, list)
        assert len(data) == 3

    async def test_update_alert_rule(self, async_client: AsyncClient):
        create_payload = {
            "alert_type": "task_failure",
            "channels": ["email"],
            "min_severity": "warning",
            "cooldown_minutes": 15,
            "enabled": True,
        }
        create_resp = await async_client.post("/api/alerts/rules", json=create_payload)
        rule_id = create_resp.json()["id"]

        update_payload = {
            "channels": ["email", "slack", "pagerduty"],
            "min_severity": "error",
            "cooldown_minutes": 45,
            "enabled": False,
        }
        response = await async_client.put(f"/api/alerts/rules/{rule_id}", json=update_payload)
        assert response.status_code == 200
        data = response.json()
        assert data["id"] == rule_id
        assert data["channels"] == ["email", "slack", "pagerduty"]
        assert data["min_severity"] == "error"
        assert data["cooldown_minutes"] == 45
        assert data["enabled"] is False

    async def test_update_alert_rule_not_found(self, async_client: AsyncClient):
        fake_id = str(uuid.uuid4())
        update_payload = {
            "enabled": False,
        }
        response = await async_client.put(f"/api/alerts/rules/{fake_id}", json=update_payload)
        assert response.status_code == 404

    async def test_delete_alert_rule(self, async_client: AsyncClient):
        create_payload = {
            "alert_type": "task_failure",
            "channels": ["email"],
        }
        create_resp = await async_client.post("/api/alerts/rules", json=create_payload)
        rule_id = create_resp.json()["id"]

        delete_resp = await async_client.delete(f"/api/alerts/rules/{rule_id}")
        assert delete_resp.status_code == 204

        list_resp = await async_client.get("/api/alerts/rules")
        assert len(list_resp.json()) == 0

    async def test_delete_alert_rule_not_found(self, async_client: AsyncClient):
        fake_id = str(uuid.uuid4())
        response = await async_client.delete(f"/api/alerts/rules/{fake_id}")
        assert response.status_code == 404

    async def test_list_channels(self, async_client: AsyncClient):
        response = await async_client.get("/api/alerts/channels")
        assert response.status_code == 200
        data = response.json()
        assert isinstance(data, list)

    async def test_test_alert_no_channel_configured(self, async_client: AsyncClient):
        payload = {
            "channel": "nonexistent-email",
            "alert_type": "task_failure",
            "severity": "warning",
            "message": "Test alert message",
        }
        response = await async_client.post("/api/alerts/test", json=payload)
        assert response.status_code == 200
        data = response.json()
        assert data["channel"] == "nonexistent-email"
        assert data["success"] is False
        assert "not configured" in data["message"].lower()

    async def test_test_alert_sla_timeout_type(self, async_client: AsyncClient):
        payload = {
            "channel": "slack-channel",
            "alert_type": "sla_timeout",
            "severity": "critical",
            "message": "SLA breach detected",
        }
        response = await async_client.post("/api/alerts/test", json=payload)
        assert response.status_code == 200
        data = response.json()
        assert "success" in data
        assert "message" in data
