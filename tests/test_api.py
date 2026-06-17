import pytest
import pytest_asyncio
from httpx import ASGITransport, AsyncClient

from etl_engine.main import app
from etl_engine.db.session import get_session


@pytest_asyncio.fixture
def override_session(db_session):
    app.dependency_overrides[get_session] = lambda: db_session
    yield
    app.dependency_overrides.pop(get_session, None)


@pytest.mark.asyncio
async def test_health_endpoint(override_session):
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        response = await client.get("/health")
    assert response.status_code == 200
    data = response.json()
    assert data["status"] == "ok"


@pytest.mark.asyncio
async def test_create_source(override_session):
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        response = await client.post(
            "/api/sources",
            json={"name": "test_mysql", "type": "mysql", "connection_config": {"host": "localhost"}, "pool_size": 5},
        )
    assert response.status_code == 201
    data = response.json()
    assert data["name"] == "test_mysql"
    assert data["type"] == "mysql"


@pytest.mark.asyncio
async def test_list_sources(override_session):
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        await client.post(
            "/api/sources",
            json={"name": "src1", "type": "mysql", "connection_config": {}},
        )
        response = await client.get("/api/sources")
    assert response.status_code == 200
    data = response.json()
    assert isinstance(data, list)
    assert len(data) >= 1


@pytest.mark.asyncio
async def test_create_pipeline(override_session, sample_dag_definition):
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        response = await client.post(
            "/api/pipelines",
            json={
                "name": "test_pipeline",
                "dag_definition": sample_dag_definition,
                "schedule": "0 * * * *",
            },
        )
    assert response.status_code == 201
    data = response.json()
    assert data["name"] == "test_pipeline"


@pytest.mark.asyncio
async def test_list_pipelines(override_session, sample_dag_definition):
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        await client.post(
            "/api/pipelines",
            json={
                "name": "pipeline_list_test",
                "dag_definition": sample_dag_definition,
            },
        )
        response = await client.get("/api/pipelines")
    assert response.status_code == 200
    data = response.json()
    assert isinstance(data, list)
    assert len(data) >= 1


@pytest.mark.asyncio
async def test_list_executions(override_session):
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        response = await client.get("/api/executions")
    assert response.status_code == 200
    data = response.json()
    assert isinstance(data, list)
