import asyncio
import os
import tempfile
from pathlib import Path
from typing import AsyncIterator, Iterator

import pytest
import pytest_asyncio
from httpx import ASGITransport, AsyncClient

from app.config.settings import get_settings
from app.main import create_app


@pytest.fixture(scope="session")
def event_loop():
    loop = asyncio.new_event_loop()
    yield loop
    loop.close()


@pytest.fixture
def app():
    os.environ["APP_ENV"] = "test"
    return create_app()


@pytest_asyncio.fixture
async def client(app) -> AsyncIterator[AsyncClient]:
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://testserver") as client:
        yield client


@pytest.fixture
def temp_dir() -> Iterator[Path]:
    with tempfile.TemporaryDirectory() as td:
        yield Path(td)


@pytest.fixture
def sample_config():
    return {
        "namespace": "production",
        "version": 3,
        "parameters": {
            "timeout": 30,
            "retries": 3,
            "pool_size": 10
        },
        "enabled": True
    }


@pytest.fixture
def sample_entity():
    return {
        "id": "ent_001",
        "type": "record",
        "status": "completed",
        "attributes": {"key": "value"}
    }


@pytest.fixture
def sample_sbom_json():
    return {
        "bomFormat": "CycloneDX",
        "specVersion": "1.5",
        "version": 1,
        "components": [
            {
                "type": "library",
                "name": "requests",
                "version": "2.28.0",
                "purl": "pkg:pypi/requests@2.28.0"
            },
            {
                "type": "library",
                "name": "flask",
                "version": "2.0.0",
                "purl": "pkg:pypi/flask@2.0.0"
            }
        ]
    }


@pytest.fixture
def sample_openapi_schema():
    return {
        "openapi": "3.0.0",
        "info": {
            "title": "Test API",
            "version": "1.0.0"
        },
        "paths": {
            "/users": {
                "get": {
                    "summary": "List users",
                    "responses": {
                        "200": {
                            "description": "List of users",
                            "content": {
                                "application/json": {
                                    "schema": {
                                        "type": "array",
                                        "items": {"type": "object"}
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
